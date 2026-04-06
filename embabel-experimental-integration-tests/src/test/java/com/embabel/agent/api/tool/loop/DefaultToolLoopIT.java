/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.api.tool.loop;

import com.embabel.agent.AgentTestApplication;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.tool.callback.AfterLlmCallContext;
import com.embabel.agent.api.tool.callback.AfterToolResultContext;
import com.embabel.agent.api.tool.callback.BeforeLlmCallContext;
import com.embabel.agent.api.tool.callback.ToolLoopInspector;
import com.embabel.agent.api.tool.loop.testing.AbstractToolLoopTest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating DefaultToolLoop with inspectors and transformers.
 * <p>
 * Shows how to:
 * - Create tools that fetch real data (restaurant menus via Jsoup)
 * - Use ToolLoopInspector for observability
 * - Use ToolLoopTransformer for result truncation and sliding window
 * - Have LLM use tools and summarize results
 */
@SpringBootTest(classes = AgentTestApplication.class)
@ActiveProfiles("test")
class DefaultToolLoopIT extends AbstractToolLoopTest {

    @Autowired
    private Ai ai;

    @BeforeAll
    static void setUp() {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
    }

    @Test
    void findRestaurantNearMeTest() {
        // Create tools for fetching restaurant menus
        var tools = createAllMenuTools();
        logger.info("Created {} menu tools: {}", tools.size(),
            tools.stream().map(t -> t.getDefinition().getName()).toList());

        // Custom inspector to track callbacks
        var callbackTracker = new CallbackTracker();

        // Built-in logging inspector with INFO level
        var loggingInspector = createLoggingInspector();

        // Truncate large menu results to save tokens
        var truncatingTransformer = createTruncatingTransformer();

        // Sliding window to manage conversation history size
        var slidingWindowTransformer = createSlidingWindowTransformer(10);

        var toolNames = tools.stream()
            .map(t -> t.getDefinition().getName())
            .toList();

        var startTime = System.currentTimeMillis();

        // Execute with tools and callbacks
        var result = ai.withDefaultLlm()
            .withTools(tools)
            .withToolLoopInspectors(callbackTracker, loggingInspector)
            .withToolLoopTransformers(truncatingTransformer, slidingWindowTransformer)
            .creating(RestaurantRecommendation.class)
            .fromPrompt("""
                I'm looking for an Italian restaurant near the Upper East Side in NYC.

                You have access to these tools to fetch restaurant menus:
                %s

                Please fetch the menus and recommend the best restaurant for a romantic dinner.
                Consider the variety of dishes, any standout items, and overall menu appeal.
                """.formatted(String.join(", ", toolNames)));

        var elapsed = System.currentTimeMillis() - startTime;

        // Log results
        logger.info("""

            ========== RESULT ({} ms) ==========
            Recommended: {}
            Reasoning: {}
            Notable dishes: {}
            Menus analyzed: {}

            Callback stats:
              beforeLlmCall: {}
              afterLlmCall: {}
              afterToolResult: {}
            """,
            elapsed,
            result.recommendedRestaurant(),
            result.reasoning(),
            result.notableDishes(),
            result.menusAnalyzed(),
            callbackTracker.beforeLlmCallCount.get(),
            callbackTracker.afterLlmCallCount.get(),
            callbackTracker.afterToolResultCount.get()
        );

        // Assertions
        assertNotNull(result.recommendedRestaurant(), "Should recommend a restaurant");
        assertFalse(result.recommendedRestaurant().isBlank(), "Restaurant name should not be blank");
        assertNotNull(result.reasoning(), "Should provide reasoning");
        assertTrue(result.menusAnalyzed() > 0, "Should analyze at least one menu");

        // Verify callbacks were invoked
        assertTrue(callbackTracker.beforeLlmCallCount.get() >= 1,
            "beforeLlmCall should be called at least once");
        assertTrue(callbackTracker.afterLlmCallCount.get() >= 1,
            "afterLlmCall should be called at least once");
        // afterToolResult should be called for each tool the LLM invokes
        assertTrue(callbackTracker.afterToolResultCount.get() >= 1,
            "afterToolResult should be called at least once");

        // Verify tool names were captured
        assertFalse(callbackTracker.toolsInvoked.isEmpty(), "Should track invoked tools");
        logger.info("Tools invoked: {}", callbackTracker.toolsInvoked);
    }

    /**
     * Custom inspector that tracks callback invocations for testing.
     */
    static class CallbackTracker implements ToolLoopInspector {
        final AtomicInteger beforeLlmCallCount = new AtomicInteger();
        final AtomicInteger afterLlmCallCount = new AtomicInteger();
        final AtomicInteger afterToolResultCount = new AtomicInteger();
        final List<String> toolsInvoked = new ArrayList<>();

        protected final Logger logger = LoggerFactory.getLogger(getClass());

        @Override
        public void beforeLlmCall(@NotNull BeforeLlmCallContext context) {
            beforeLlmCallCount.incrementAndGet();
            var threadName = Thread.currentThread().getName();
            logger.info("Before LLM Call Thread {}", threadName);
        }

        @Override
        public void afterLlmCall(@NotNull AfterLlmCallContext context) {
            afterLlmCallCount.incrementAndGet();
        }

        @Override
        public void afterToolResult(@NotNull AfterToolResultContext context) {
            afterToolResultCount.incrementAndGet();
            synchronized (toolsInvoked) {
                toolsInvoked.add(context.getToolCall().getName());
            }
        }
    }
}
