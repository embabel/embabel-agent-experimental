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
package com.embabel.agent.api.tool.loop.thinking;

import com.embabel.agent.AgentTestApplication;
import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.tool.callback.AfterLlmCallContext;
import com.embabel.agent.api.tool.callback.AfterToolResultContext;
import com.embabel.agent.api.tool.callback.BeforeLlmCallContext;
import com.embabel.agent.api.tool.callback.ToolLoopInspector;
import com.embabel.agent.api.tool.callback.ToolLoopTransformer;
import com.embabel.agent.api.tool.loop.testing.AbstractToolLoopTest;
import com.embabel.chat.Message;
import com.embabel.chat.SystemMessage;
import com.embabel.common.core.thinking.ThinkingResponse;
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
import java.util.Random;
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
class ToolCallReasoningIT extends AbstractToolLoopTest {

    @Autowired
    private Ai ai;

    @BeforeAll
    static void setUp() {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
    }

    public record ParkingRecommendation(
            Option chosenOption,          // which option was selected
            String location,              // e.g. "Midtown Manhattan"
            int estimatedTotalCost,       // total expected cost
            String summary                // short human-readable explanation
    ) {

        public enum Option {
            STREET,
            METER,
            GARAGE
        }
    }

    public class ParkingTooling {

        private final Random random = new Random();

        @LlmTool(description = "Find free street parking. Uncertain and may take time.")
        public String findStreetParking(String location, int maxMinutes) {

            boolean found = random.nextDouble() < 0.3; // low probability

            if (found) {
                return "Street parking found near " + location + " (free)";
            }
            return "No street parking found within " + maxMinutes + " minutes";
        }

        @LlmTool(description = "Find metered parking. Moderate cost and moderate availability. May have time limits.")
        public String findMeterParking(String location, int maxMinutes) {

            boolean found = random.nextDouble() < 0.6; // medium probability

            if (found) {
                return "Metered parking found near " + location + " ($5/hour, 2-hour limit)";
            }
            return "No metered parking found within " + maxMinutes + " minutes";
        }

        @LlmTool(description = "Reserve guaranteed garage parking near destination.")
        public String reserveGarage(String location) {

            return "Garage reserved near " + location + " ($30/hour, guaranteed)";
        }
    }

    @Test
    void parkingDecisionMakerTest() {
        // Create Parking Options Tool
        var tools = new ParkingTooling();

        // Custom inspector to track callbacks
        var callbackTracker = new CallbackTracker();

        // Built-in logging inspector with INFO level
        var loggingInspector = createLoggingInspector();


        List<String> toolNames = List.of(
                "findStreetParking",
                "findMeterParking",
                "reserveGarage"
        );

        var startTime = System.currentTimeMillis();

        String prompt = """
                    You are a parking decision agent.
                
                           You MUST:
                           1. Provide reasoning inside <parking_decision_reasoning>...</parking_decision_reasoning>
                           2. Keep reasoning concise (3-5 bullet points)
                           3. Call at most ONE tool
                
                           <parking_decision_reasoning>
                           Explain:
                           - time constraint
                           - risk of being late
                           - trade-offs (street vs meter vs garage)
                           </parking_decision_reasoning>
                
                           Scenario:
                           Advisor is driving to a client meeting in Midtown Manhattan.
                           He has 30 minutes before the meeting and must not be late.
                
                           Parking Options:
                           - Street: free, uncertain
                           - Meter: $5/hour, may expire (2h typical)
                           - Garage: $30/hour, guaranteed
                
                           Meeting duration: ~3 hours.
                
                           Decide best option and use tool if needed: %s
                """.formatted(String.join(", ", toolNames));

        // Execute with tools and callbacks
        ThinkingResponse<ParkingRecommendation> result = null;
        result = ai.withDefaultLlm()
                .withToolObject(tools)
                .withToolLoopInspectors(callbackTracker, loggingInspector)
                .thinking().createObject(prompt, ParkingRecommendation.class);
        var elapsed = System.currentTimeMillis() - startTime;

        // Log results
        logger.info("""
                        
                        ========== RESULT ({} ms) ==========
                        Recommended: {}
                        Reasoning: {}
                        Notable dishes:
                        
                        Callback stats:
                          beforeLlmCall: {}
                          afterLlmCall: {}
                          afterToolResult: {}
                        """,
                elapsed,
                result.getResult(),
                result.getThinkingBlocks(),
                callbackTracker.beforeLlmCallCount.get(),
                callbackTracker.afterLlmCallCount.get(),
                callbackTracker.afterToolResultCount.get()
        );

        // Assertions

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

    @Test
    void parkingDecisionMakerWithSystemInstructionsTest() {

        // Create Parking Options Tool
        var tools = new ParkingTooling();

        // Custom inspector to track callbacks
        var callbackTracker = new CallbackTracker();

        // Built-in logging inspector with INFO level
        var loggingInspector = createLoggingInspector();

        // Add system messages before each LLM call
        var systemMessageTransformer = new SystemMessageTransformer(
                "You are a helpful parking decision assistant. Be concise and practical.",
                """
                                You MUST always emit exactly one XML reasoning block before providing your final answer:

                                    <tool_use_reasoning>
                                    - Need: why this specific tool is necessary to improve the decision
                                    - Selected tool: which single tool to call
                                    - Alternatives: other tools or approaches considered but not selected
                                    - Expected value: what the tool result will reveal or confirm
                                    </tool_use_reasoning>

                                    Tool-use rules:
                                    - Call at most ONE tool.
                                    - Call at least one tool to probe and verify your reasoning.
                                    - Choose the single most valuable tool for the decision.
                                    - Keep reasoning concise and decision-focused.
                                    - Never copy <tool_use_reasoning> into the final structured object.
                        """
        );


        List<String> toolNames = List.of(
                "findStreetParking",
                "findMeterParking",
                "reserveGarage"
        );

        var startTime = System.currentTimeMillis();

        String prompt = """
                
                          Scenario:
                          An advisor is driving to a client meeting in Midtown Manhattan.
                
                          Constraints:
                          - 30 minutes remain before the meeting starts
                          - arriving late is not acceptable
                          - the meeting is expected to last about 3 hours
                
                          Parking options:
                          - Street parking: free, but uncertain
                          - Metered parking: $5 per hour, typically limited to 2 hours
                          - Garage parking: $30 per hour, guaranteed availability
                
                          Important decision factors:
                          - available time before the meeting
                          - risk of arriving late
                          - trade-offs between street, metered, and garage parking
                
                          Recommend the best parking option.
                          Use tools $s only if they materially improve the decision.
                
                
                """.formatted(String.join(", ", toolNames));

        // Execute with tools and callbacks
        ThinkingResponse<ParkingRecommendation> result = null;
        result = ai.withDefaultLlm()
                .withToolObject(tools)
                .withToolLoopInspectors(callbackTracker, loggingInspector)
                .withToolLoopTransformers(systemMessageTransformer)
                .thinking().createObject(prompt, ParkingRecommendation.class);
        var elapsed = System.currentTimeMillis() - startTime;

        // Log results
        logger.info("""
                        
                        ========== RESULT ({} ms) ==========
                        Recommended: {}
                        Reasoning: {}
                        Notable dishes:
                        
                        Callback stats:
                          beforeLlmCall: {}
                          afterLlmCall: {}
                          afterToolResult: {}
                        """,
                elapsed,
                result.getResult(),
                result.getThinkingBlocks(),
                callbackTracker.beforeLlmCallCount.get(),
                callbackTracker.afterLlmCallCount.get(),
                callbackTracker.afterToolResultCount.get()
        );

        // Assertions

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
     * Transformer that adds system messages after existing system messages but before user messages.
     */
    static class SystemMessageTransformer implements ToolLoopTransformer {
        private final List<String> systemMessages;
        private final Logger logger = LoggerFactory.getLogger(getClass());

        SystemMessageTransformer(List<String> systemMessages) {
            this.systemMessages = systemMessages;
        }

        SystemMessageTransformer(String... systemMessages) {
            this.systemMessages = List.of(systemMessages);
        }

        @NotNull
        @Override
        public List<Message> transformBeforeLlmCall(@NotNull BeforeLlmCallContext context) {
            logger.info("Adding {} system message(s) before LLM call (iteration {})",
                    systemMessages.size(), context.getIteration());
            var history = new ArrayList<>(context.getHistory());

            // Find the last SystemMessage index
            int lastSystemMessageIndex = -1;
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i) instanceof SystemMessage) {
                    lastSystemMessageIndex = i;
                }
            }

            // Insert after last SystemMessage, or at beginning if none exist
            int insertIndex = lastSystemMessageIndex + 1;
            for (String content : systemMessages) {
                history.add(insertIndex++, new SystemMessage(content));
            }

            return history;
        }
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
