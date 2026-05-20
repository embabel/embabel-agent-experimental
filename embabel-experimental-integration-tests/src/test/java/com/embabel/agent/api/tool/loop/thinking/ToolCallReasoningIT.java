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
                "You are a helpful decision assistant. Be concise and practical.",
                """
                                CRITICAL WORKFLOW - Two-phase decision process:

                                === PHASE 1: Tool Selection (First Response) ===

                                1. For EACH tool you plan to call, emit a SEPARATE <tool_use_reasoning> block:

                                    <tool_use_reasoning>
                                    Tool: [TOOL_NAME]
                                    Why THIS tool: [explain why this specific tool is needed]
                                    Information expected: [what this tool will reveal]
                                    Advantage over alternatives: [why this tool vs others]
                                    Confidence: [confidence=X.X]
                                    </tool_use_reasoning>

                                    If calling 2 tools, emit 2 separate blocks. If calling 3 tools, emit 3 separate blocks.

                                2. Then call the tool(s) to probe real-time conditions
                                    - You MUST call at least one tool. Do NOT skip it.
                                    - Tools are PROBES for information gathering, not final decisions.
                                    - Do NOT provide the final answer yet.

                                === PHASE 2: Final Decision (Second Response, after receiving tool results) ===

                                1. Emit final decision reasoning:
                                    <final_decision_reasoning>
                                    Explain:
                                    - What each tool probe revealed
                                    - How the probe results informed your analysis
                                    - Why you chose this option based on probe data and constraints
                                    - Confidence in final recommendation in format [confidence=CONFIDENCE-VALUE]
                                    </final_decision_reasoning>

                                2. Then provide the final structured output
                                    - Your final recommendation may differ from the options you probed.
                                    - Never copy reasoning blocks into the final structured object.

                                REMINDER: One <tool_use_reasoning> block per tool call. Multiple tools = multiple blocks. Emit reasoning in BOTH phases.
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
                
                          Available tools: %s
                
                
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
     * Test with multiple tool probes to verify thinking blocks accumulate correctly
     * across multiple tool calls (either in same iteration or across iterations).
     * <p>
     * This test investigates:
     * - Whether multiple tools are called in the same iteration or separate iterations
     * - Whether multiple tool calls in the same iteration share the same AssistantMessage
     * - Whether thinking blocks accumulate correctly in both scenarios
     */
    @Test
    void parkingDecisionMakerWithMultiProbes() {
        // Create Parking Options Tool
        var tools = new ParkingTooling();
        var loggingInspector = createLoggingInspector();
        var callbackTracker = new CallbackTracker();

        var systemMessageTransformer = new SystemMessageTransformer(
                "You are a helpful decision assistant. Be concise and practical.",
                """
                                CRITICAL WORKFLOW - Two-phase decision process:

                                === PHASE 1: Tool Selection (First Response) ===

                                1. For EACH tool you plan to call, emit a SEPARATE <tool_use_reasoning> block:

                                    <tool_use_reasoning>
                                    Tool: [TOOL_NAME]
                                    Why THIS tool: [explain why this specific tool is needed]
                                    Information expected: [what this tool will reveal]
                                    Advantage over alternatives: [why this tool vs others]
                                    Confidence: [confidence=X.X]
                                    </tool_use_reasoning>

                                    Since you must call at least 2 tools, you must emit at least 2 separate blocks.

                                2. Call AT LEAST TWO tools to gather comprehensive information
                                    - You MUST call at least 2 tools to probe different aspects.
                                    - Tools are PROBES for information gathering, not final decisions.
                                    - Multiple probes provide better decision quality.

                                === PHASE 2: Final Decision (After receiving tool results) ===

                                1. Emit final decision reasoning:
                                    <final_decision_reasoning>
                                    Explain:
                                    - What each tool probe revealed
                                    - How the probe results informed your analysis
                                    - Why you chose this option based on probe data and constraints
                                    - Confidence in final recommendation in format [confidence=CONFIDENCE-VALUE]
                                    </final_decision_reasoning>

                                2. Then provide the final structured output
                                    - Your final recommendation should synthesize insights from multiple probes.
                                    - Never copy reasoning blocks into the final structured object.

                                REMINDER: One <tool_use_reasoning> block per tool call. At least 2 tools = at least 2 blocks. Emit reasoning in BOTH phases.
                        """
        );

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
                
                          Available tools: %s
                
                
                """.formatted(String.join(", ", List.of("findStreetParking", "findMeterParking", "reserveGarage")));



        long start = System.currentTimeMillis();
        ThinkingResponse<ParkingRecommendation> result = ai.withDefaultLlm()
                .withToolObject(tools)
                .withToolLoopInspectors(callbackTracker, loggingInspector)
                .withToolLoopTransformers(systemMessageTransformer)
                .thinking().createObject(prompt, ParkingRecommendation.class);
        long elapsed = System.currentTimeMillis() - start;

        logger.info("""
                        
                        ========== RESULT ({} ms) ==========
                        Recommended: {}
                        Reasoning: {}
                        
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

        // Verify at least 2 tools were called
        assertTrue(callbackTracker.toolsInvoked.size() >= 2,
                "Should invoke at least 2 tools for comprehensive probing, invoked: " + callbackTracker.toolsInvoked);
        logger.info("Tools invoked: {}", callbackTracker.toolsInvoked);

        // Verify thinking blocks were accumulated
        assertFalse(result.getThinkingBlocks().isEmpty(),
                "Should have accumulated thinking blocks");

        // Log analysis of tool call pattern
        int totalIterations = callbackTracker.beforeLlmCallCount.get();
        int toolsCalled = callbackTracker.toolsInvoked.size();
        int toolResultCallbacks = callbackTracker.afterToolResultCount.get();

        logger.info("=== TOOL CALL PATTERN ANALYSIS ===");
        logger.info("Total LLM iterations: {}", totalIterations);
        logger.info("Total tools called: {}", toolsCalled);
        logger.info("Tool result callbacks: {}", toolResultCallbacks);

        if (toolResultCallbacks == toolsCalled && totalIterations < toolsCalled + 1) {
            logger.info("PATTERN: Multiple tools called in SAME iteration (parallel tool calls)");
        } else if (totalIterations >= toolsCalled) {
            logger.info("PATTERN: Tools called across SEPARATE iterations (sequential tool calls)");
        }

        logger.info("Thinking blocks captured: {}", result.getThinkingBlocks().size());
        for (int i = 0; i < result.getThinkingBlocks().size(); i++) {
            var block = result.getThinkingBlocks().get(i);
            logger.info("  Block {}: tagType={}, tagValue={}, contentLength={}",
                    i + 1, block.getTagType(), block.getTagValue(), block.getContent().length());
        }
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
