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

import com.embabel.agent.api.tool.ToolCallContext;
import com.embabel.agent.api.tool.callback.AfterToolResultContext;
import com.embabel.agent.api.tool.callback.BeforeLlmCallContext;
import com.embabel.agent.api.tool.callback.ToolLoopInspector;
import com.embabel.agent.api.tool.loop.testing.AbstractToolLoopTest;
import com.embabel.agent.spi.loop.ImmediateThrowPolicy;
import com.embabel.agent.spi.loop.ToolInjectionStrategy;
import com.embabel.agent.spi.loop.support.DefaultToolLoop;
import com.embabel.agent.spi.tool.loop.LangChain4jLlmMessageSender;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test demonstrating framework independence using LangChain4j.
 * <p>
 * This test uses the same DefaultToolLoop as the Spring AI tests, but with
 * a LangChain4j-based LlmMessageSender. This proves that:
 * <ul>
 *   <li>The tool loop is truly framework-agnostic</li>
 *   <li>Inspectors and transformers work identically regardless of LLM framework</li>
 *   <li>Tools can be shared across different LLM integrations</li>
 * </ul>
 */
class LangChainToolLoopIT extends AbstractToolLoopTest {

    private static OpenAiChatModel chatModel;

    @BeforeAll
    static void setUp() {
        var apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return; // Skip setup if no API key
        }
        chatModel = OpenAiChatModel.builder()
            .apiKey(apiKey)
            .modelName("gpt-4.1-mini")
            .build();
    }

    @Test
    void findRestaurantWithLangChainTest() {
        if (chatModel == null) {
            logger.warn("Skipping test: OPENAI_API_KEY not set");
            return;
        }

        // Create tools for fetching restaurant menus
        var tools = createAllMenuTools();
        logger.info("Created {} menu tools: {}", tools.size(),
            tools.stream().map(t -> t.getDefinition().getName()).toList());

        // Create LangChain4j-based message sender
        var messageSender = new LangChain4jLlmMessageSender(chatModel);

        // Track callbacks to verify they work with LangChain4j
        var callbackTracker = new CallbackTracker();

        // Create transformers
        var truncatingTransformer = createTruncatingTransformer();
        var slidingWindowTransformer = createSlidingWindowTransformer(8);

        // Create the tool loop with LangChain4j backend
        var toolLoop = new DefaultToolLoop(
            messageSender,
            new ObjectMapper(),
            ToolInjectionStrategy.Companion.getNONE(),  // no injection strategy
            20,    // max iterations
            null,  // no tool decorator
            List.of(callbackTracker, createLoggingInspector()),
            List.of(truncatingTransformer, slidingWindowTransformer),
            List.of(),  // toolCallInspectors (empty for non-streaming)
            ToolCallContext.EMPTY,
            ImmediateThrowPolicy.INSTANCE
        );

        var toolNames = tools.stream()
            .map(t -> t.getDefinition().getName())
            .toList();

        var systemPrompt = "You are a helpful assistant that recommends restaurants.";

        var userPrompt = """
            I'm looking for an Italian restaurant near the Upper East Side in NYC.

            You have access to these tools to fetch restaurant menus:
            %s

            Please fetch the menus and recommend the best restaurant for a romantic dinner.
            Respond with JSON: {"recommendedRestaurant": "name", "reasoning": "why", "menusAnalyzed": N}
            """.formatted(String.join(", ", toolNames));

        var startTime = System.currentTimeMillis();

        // Execute with LangChain4j-powered tool loop
        var result = toolLoop.execute(
            List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
            tools,
            response -> response  // Simple pass-through parser
        );

        var elapsed = System.currentTimeMillis() - startTime;

        // Log results
        logger.info("""

            ========== LANGCHAIN4J RESULT ({} ms) ==========
            Final response: {}
            Iterations: {}

            Callback stats:
              beforeLlmCall: {}
              afterToolResult: {}
              Tools invoked: {}
            """,
            elapsed,
            result.getResult().substring(0, Math.min(1500, result.getResult().length())),
            result.getTotalIterations(),
            callbackTracker.beforeLlmCallCount.get(),
            callbackTracker.afterToolResultCount.get(),
            callbackTracker.toolsInvoked
        );

        // Assertions
        assertTrue(result.getTotalIterations() >= 1, "Should have at least 1 iteration");
        assertTrue(callbackTracker.afterToolResultCount.get() >= 1,
            "Should invoke at least one tool");
        assertFalse(callbackTracker.toolsInvoked.isEmpty(), "Should track invoked tools");
    }

    /**
     * Inspector that tracks callback invocations.
     */
    static class CallbackTracker implements ToolLoopInspector {
        final AtomicInteger beforeLlmCallCount = new AtomicInteger();
        final AtomicInteger afterToolResultCount = new AtomicInteger();
        final List<String> toolsInvoked = new ArrayList<>();

        @Override
        public void beforeLlmCall(@NotNull BeforeLlmCallContext context) {
            beforeLlmCallCount.incrementAndGet();
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
