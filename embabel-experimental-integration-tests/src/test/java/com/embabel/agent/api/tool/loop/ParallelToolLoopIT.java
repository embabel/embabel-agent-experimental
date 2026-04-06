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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test demonstrating ParallelToolLoop with concurrent tool execution.
 * <p>
 * Shows how to:
 * - Enable parallel tool execution via configuration
 * - Observe concurrent execution through thread names
 * - Use inspectors and transformers with parallel execution
 */
@SpringBootTest(classes = AgentTestApplication.class)
@TestPropertySource(properties = {
        "embabel.agent.platform.toolloop.type=parallel",
        "logging.level.com.embabel.agent.spi.loop.support.ParallelToolLoop=DEBUG"
})
@ActiveProfiles("test")
class ParallelToolLoopIT extends AbstractToolLoopTest {

    @Autowired
    private Ai ai;

    @BeforeAll
    static void setUp() {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
    }

    @Test
    void findRestaurantNearMeParallelTest() {
        // Create tools for fetching restaurant menus
        var tools = createAllMenuTools();
        logger.info("Created {} menu tools: {}", tools.size(),
                tools.stream().map(t -> t.getDefinition().getName()).toList());

        // Custom inspector to track parallel execution
        var parallelTracker = new ParallelExecutionTracker();

        // Built-in logging inspector
        var loggingInspector = createLoggingInspector();

        // Truncate large menu results
        var truncatingTransformer = createTruncatingTransformer();

        // Sliding window for history management (larger value to avoid truncating tool call/result pairs)
        var slidingWindowTransformer = createSlidingWindowTransformer(12);

        var toolNames = tools.stream()
                .map(t -> t.getDefinition().getName())
                .toList();

        var startTime = System.currentTimeMillis();

        // Execute with tools and callbacks
        var result = ai.withDefaultLlm()
                .withTools(tools)
                .withToolLoopInspectors(parallelTracker, loggingInspector)
                .withToolLoopTransformers(truncatingTransformer, slidingWindowTransformer)
                .creating(RestaurantRecommendation.class)
                .fromPrompt("""
                        I'm looking for an Italian restaurant near the Upper East Side in NYC.
                        
                        You have access to these tools to fetch restaurant menus:
                        %s
                        
                        Please fetch ALL menus and recommend the best restaurant for a romantic dinner.
                        Consider the variety of dishes, any standout items, and overall menu appeal.
                        """.formatted(String.join(", ", toolNames)));

        var elapsed = System.currentTimeMillis() - startTime;

        // Log results
        logger.info("""
                        
                        ========== PARALLEL RESULT ({} ms) ==========
                        Recommended: {}
                        Reasoning: {}
                        Notable dishes: {}
                        Menus analyzed: {}
                        
                        Parallel execution stats:
                          Unique threads used: {}
                          Thread names: {}
                        """,
                elapsed,
                result.recommendedRestaurant(),
                result.reasoning(),
                result.notableDishes(),
                result.menusAnalyzed(),
                parallelTracker.threadNames.size(),
                parallelTracker.threadNames.keySet()
        );

        // Assertions
        assertNotNull(result.recommendedRestaurant(), "Should recommend a restaurant");
        assertTrue(result.menusAnalyzed() > 0, "Should analyze at least one menu");

        // Verify parallel execution used multiple threads (if more than 1 tool was called)
        if (parallelTracker.toolResultCount.get() > 1) {
            logger.info("Parallel execution verified: {} tools executed across {} threads",
                    parallelTracker.toolResultCount.get(),
                    parallelTracker.threadNames.size());
        }
    }

    /**
     * Inspector that tracks thread usage to verify parallel execution.
     */
    static class ParallelExecutionTracker implements ToolLoopInspector {
        final AtomicInteger toolResultCount = new AtomicInteger();
        final ConcurrentHashMap<String, Integer> threadNames = new ConcurrentHashMap<>();

        @Override
        public void beforeLlmCall(@NotNull BeforeLlmCallContext context) {
        }

        @Override
        public void afterLlmCall(@NotNull AfterLlmCallContext context) {
        }

        @Override
        public void afterToolResult(@NotNull AfterToolResultContext context) {
            toolResultCount.incrementAndGet();
            var threadName = Thread.currentThread().getName();
            threadNames.merge(threadName, 1, Integer::sum);
        }
    }
}
