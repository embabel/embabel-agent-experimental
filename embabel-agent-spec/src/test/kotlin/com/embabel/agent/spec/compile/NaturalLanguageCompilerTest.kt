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
package com.embabel.agent.spec.compile

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.JvmType
import com.embabel.agent.core.ToolGroupDescription
import com.embabel.agent.core.ValuePropertyDefinition
import com.embabel.agent.spec.model.StepSpecContext
import com.embabel.common.ai.model.LlmOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NaturalLanguageCompilerTest {

    private lateinit var ai: Ai
    private lateinit var promptRunner: PromptRunner
    private lateinit var rendering: PromptRunner.Rendering
    private lateinit var dataDictionary: DataDictionary
    private lateinit var context: StepSpecContext
    private lateinit var compiler: DefaultNaturalLanguageCompiler

    @BeforeEach
    fun setUp() {
        ai = mockk()
        promptRunner = mockk()
        rendering = mockk()
        dataDictionary = mockk()

        every { dataDictionary.domainTypes } returns listOf(
            JvmType(TestInput::class.java),
            JvmType(TestOutput::class.java),
        )

        context = StepSpecContext(
            name = "test-context",
            dataDictionary = dataDictionary,
            toolGroups = listOf(
                ToolGroupDescription("Search the web", "web"),
                ToolGroupDescription("Perform math calculations", "math"),
            ),
            tools = emptyList(),
        )

        compiler = DefaultNaturalLanguageCompiler(ai)
    }

    @Nested
    inner class CompileActionTests {

        @Test
        fun `compileAction returns success with valid compiled action`() {
            val spec = NaturalLanguageActionSpec(
                name = "summarize",
                description = "Take user input and create a summary",
            )

            val compiledAction = CompiledAction(
                name = "summarize",
                description = "Summarizes user input",
                inputTypeNames = setOf("TestInput"),
                outputTypeName = "TestOutput",
                prompt = "Summarize: {{testInput}}",
                toolGroups = listOf("web"),
                nullable = false,
            )

            every { ai.withLlm(any<LlmOptions>()) } returns promptRunner
            every { promptRunner.withId(any()) } returns promptRunner
            every { promptRunner.rendering("compiler/compile_action") } returns rendering
            every { rendering.createObject(CompiledAction::class.java, any()) } returns compiledAction

            val result = compiler.compileAction(spec, context)

            assertTrue(result.success)
            assertNotNull(result.result)
            assertEquals("summarize", result.result!!.name)
            assertEquals("Summarizes user input", result.result!!.description)
            assertEquals(setOf("TestInput"), result.result!!.inputTypeNames)
            assertEquals("TestOutput", result.result!!.outputTypeName)
            assertEquals("Summarize: {{testInput}}", result.result!!.prompt)
            assertEquals(listOf("web"), result.result!!.toolGroups)
        }

        @Test
        fun `compileAction returns failure when LLM throws exception`() {
            val spec = NaturalLanguageActionSpec(
                name = "failing",
                description = "This will fail",
            )

            every { ai.withLlm(any<LlmOptions>()) } returns promptRunner
            every { promptRunner.withId(any()) } returns promptRunner
            every { promptRunner.rendering("compiler/compile_action") } returns rendering
            every { rendering.createObject(CompiledAction::class.java, any()) } throws RuntimeException("LLM error")

            val result = compiler.compileAction(spec, context)

            assertFalse(result.success)
            assertNull(result.result)
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("LLM error"))
            assertEquals("failing", result.errors[0].source)
        }

        @Test
        fun `compileAction uses correct template model`() {
            val spec = NaturalLanguageActionSpec(
                name = "testAction",
                description = "Test description",
            )

            val compiledAction = CompiledAction(
                name = "testAction",
                description = "Test",
                inputTypeNames = setOf("TestInput"),
                outputTypeName = "TestOutput",
                prompt = "Test prompt",
            )

            every { ai.withLlm(any<LlmOptions>()) } returns promptRunner
            every { promptRunner.withId(any()) } returns promptRunner
            every { promptRunner.rendering("compiler/compile_action") } returns rendering
            every { rendering.createObject(CompiledAction::class.java, any()) } returns compiledAction

            compiler.compileAction(spec, context)

            verify {
                rendering.createObject(
                    CompiledAction::class.java,
                    match { model ->
                        model.containsKey("actionSpecification") &&
                        model.containsKey("bindings") &&
                        model.containsKey("toolGroups")
                    }
                )
            }
        }
    }

    @Nested
    inner class CompileGoalTests {

        @Test
        fun `compileGoal returns success with valid compiled goal`() {
            val spec = NaturalLanguageGoalSpec(
                name = "complete",
                description = "Task is complete when output exists",
            )

            val compiledGoal = CompiledGoal(
                name = "complete",
                description = "Task completion goal",
                outputTypeName = "TestOutput",
            )

            every { ai.withLlm(any<LlmOptions>()) } returns promptRunner
            every { promptRunner.withId(any()) } returns promptRunner
            every { promptRunner.rendering("compiler/compile_goal") } returns rendering
            every { rendering.createObject(CompiledGoal::class.java, any()) } returns compiledGoal

            val result = compiler.compileGoal(spec, context)

            assertTrue(result.success)
            assertNotNull(result.result)
            assertEquals("complete", result.result!!.name)
            assertEquals("Task completion goal", result.result!!.description)
            assertEquals("TestOutput", result.result!!.outputTypeName)
        }

        @Test
        fun `compileGoal returns failure when LLM throws exception`() {
            val spec = NaturalLanguageGoalSpec(
                name = "failing",
                description = "This will fail",
            )

            every { ai.withLlm(any<LlmOptions>()) } returns promptRunner
            every { promptRunner.withId(any()) } returns promptRunner
            every { promptRunner.rendering("compiler/compile_goal") } returns rendering
            every { rendering.createObject(CompiledGoal::class.java, any()) } throws RuntimeException("LLM error")

            val result = compiler.compileGoal(spec, context)

            assertFalse(result.success)
            assertNull(result.result)
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("LLM error"))
        }
    }

    @Nested
    inner class CompileAllTests {

        @Test
        fun `compileAll compiles multiple actions and goals`() {
            val actions = listOf(
                NaturalLanguageActionSpec("action1", "First action"),
                NaturalLanguageActionSpec("action2", "Second action"),
            )
            val goals = listOf(
                NaturalLanguageGoalSpec("goal1", "First goal"),
            )

            val compiledAction = CompiledAction(
                name = "action",
                description = "Action",
                inputTypeNames = setOf("TestInput"),
                outputTypeName = "TestOutput",
                prompt = "prompt",
            )
            val compiledGoal = CompiledGoal(
                name = "goal",
                description = "Goal",
                outputTypeName = "TestOutput",
            )

            every { ai.withLlm(any<LlmOptions>()) } returns promptRunner
            every { promptRunner.withId(any()) } returns promptRunner
            every { promptRunner.rendering("compiler/compile_action") } returns rendering
            every { promptRunner.rendering("compiler/compile_goal") } returns rendering
            every { rendering.createObject(CompiledAction::class.java, any()) } returns compiledAction
            every { rendering.createObject(CompiledGoal::class.java, any()) } returns compiledGoal

            val results = compiler.compileAll(actions, goals, context)

            assertEquals(3, results.results.size)
            assertTrue(results.success)
            assertEquals(3, results.compiledSpecs.size)
        }

        @Test
        fun `compileAll reports partial failures`() {
            val actions = listOf(
                NaturalLanguageActionSpec("action1", "First action"),
            )
            val goals = listOf(
                NaturalLanguageGoalSpec("goal1", "First goal"),
            )

            val compiledAction = CompiledAction(
                name = "action",
                description = "Action",
                inputTypeNames = setOf("TestInput"),
                outputTypeName = "TestOutput",
                prompt = "prompt",
            )

            every { ai.withLlm(any<LlmOptions>()) } returns promptRunner
            every { promptRunner.withId(any()) } returns promptRunner
            every { promptRunner.rendering("compiler/compile_action") } returns rendering
            every { promptRunner.rendering("compiler/compile_goal") } returns rendering
            every { rendering.createObject(CompiledAction::class.java, any()) } returns compiledAction
            every { rendering.createObject(CompiledGoal::class.java, any()) } throws RuntimeException("Goal compilation failed")

            val results = compiler.compileAll(actions, goals, context)

            assertFalse(results.success)
            assertEquals(2, results.results.size)
            assertEquals(1, results.compiledSpecs.size)
            assertEquals(1, results.errors.size)
        }
    }

    @Nested
    inner class CompilationResultTests {

        @Test
        fun `success factory creates successful result`() {
            val spec = mockk<com.embabel.agent.spec.model.PromptedActionSpec>()
            val result = CompilationResult.success(spec)

            assertTrue(result.success)
            assertEquals(spec, result.result)
            assertTrue(result.errors.isEmpty())
        }

        @Test
        fun `failure factory creates failed result`() {
            val result = CompilationResult.failure<com.embabel.agent.spec.model.PromptedActionSpec>(
                message = "Error message",
                source = "test",
                cause = RuntimeException("cause"),
            )

            assertFalse(result.success)
            assertNull(result.result)
            assertEquals(1, result.errors.size)
            assertEquals("Error message", result.errors[0].message)
            assertEquals("test", result.errors[0].source)
        }
    }

    @Nested
    inner class DomainTypeBindingTests {

        @Test
        fun `binding extracts correct varname`() {
            val domainType = JvmType(TestInput::class.java)
            val binding = DomainTypeBinding(domainType)

            assertTrue(binding.varname.first().isLowerCase())
        }
    }

    private fun agentContext(): StepSpecContext {
        val dd = mockk<DataDictionary>()
        every { dd.domainTypes } returns listOf(
            DynamicType(
                name = "TestInput",
                description = "Test input type",
                ownProperties = listOf(ValuePropertyDefinition("content", "string", description = "Content")),
            ),
            DynamicType(
                name = "TestOutput",
                description = "Test output type",
                ownProperties = listOf(ValuePropertyDefinition("result", "string", description = "Result")),
            ),
        )
        return StepSpecContext(
            name = "test-context",
            dataDictionary = dd,
            toolGroups = listOf(ToolGroupDescription("Search the web", "web")),
            tools = emptyList(),
        )
    }

    private fun mockAgentLlmChain() {
        every { ai.withLlm(any<LlmOptions>()) } returns promptRunner
        every { promptRunner.withId(any()) } returns promptRunner
        every { promptRunner.rendering("compiler/compile_agent") } returns rendering
    }

    @Nested
    inner class CompileTests {

        @Test
        fun `compile delegates to compileAgent`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = emptyList(),
                actions = listOf(
                    CompiledChainAction(
                        name = "test",
                        description = "Test",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "TestOutput",
                        prompt = "Test",
                    ),
                ),
                goalDescription = "Done",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compile("test-agent", "Test", agentContext())

            assertTrue(result.success)
            assertEquals(1, result.actions.size)
        }
    }

    @Nested
    inner class CompileAgentSingleAction {

        @Test
        fun `simple description produces single action and goal`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = emptyList(),
                actions = listOf(
                    CompiledChainAction(
                        name = "summarize",
                        description = "Summarizes input",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "TestOutput",
                        prompt = "Summarize: {{testInput}}",
                    ),
                ),
                goalDescription = "A summary has been produced",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("summarize-agent", "Summarize input", agentContext())

            assertTrue(result.success)
            assertEquals(1, result.actions.size)
            assertTrue(result.intermediateTypes.isEmpty())
            assertEquals("summarize", result.actions[0].name)
            assertEquals(setOf("TestInput"), result.actions[0].inputTypeNames)
            assertEquals("TestOutput", result.actions[0].outputTypeName)
        }

        @Test
        fun `single action preserves prompt and tool groups`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = emptyList(),
                actions = listOf(
                    CompiledChainAction(
                        name = "search",
                        description = "Searches the web",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "TestOutput",
                        prompt = "Search for: {{testInput.content}}",
                        toolGroups = listOf("web"),
                        nullable = true,
                    ),
                ),
                goalDescription = "Search results found",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("search-agent", "Search the web", agentContext())

            assertTrue(result.success)
            assertEquals("Search for: {{testInput.content}}", result.actions[0].prompt)
            assertEquals(listOf("web"), result.actions[0].toolGroups)
            assertTrue(result.actions[0].nullable)
        }
    }

    @Nested
    inner class CompileAgentMultiActionChain {

        @Test
        fun `complex description produces ordered actions with intermediate types`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = listOf(
                    CompiledTypeDefinition(
                        name = "ResearchResults",
                        description = "Intermediate research data",
                        properties = listOf(
                            CompiledTypeProperty("findings", "string", "Research findings"),
                            CompiledTypeProperty("relevanceScore", "number", "How relevant"),
                        ),
                    ),
                ),
                actions = listOf(
                    CompiledChainAction(
                        name = "research",
                        description = "Research the topic",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "ResearchResults",
                        prompt = "Research: {{testInput.content}}",
                    ),
                    CompiledChainAction(
                        name = "writeReport",
                        description = "Write a report from research",
                        inputTypeNames = setOf("ResearchResults"),
                        outputTypeName = "TestOutput",
                        prompt = "Write report from: {{researchResults.findings}}",
                    ),
                ),
                goalDescription = "A report has been produced from research",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("research-agent", "Research and write report", agentContext())

            assertTrue(result.success)
            assertEquals(2, result.actions.size)
            assertEquals(1, result.intermediateTypes.size)
            val intermediateType = result.intermediateTypes[0]
            assertEquals("ResearchResults", intermediateType.name)
            assertEquals(2, intermediateType.ownProperties.size)
            assertEquals("findings", intermediateType.ownProperties[0].name)
            assertTrue(intermediateType.ownProperties[0] is ValuePropertyDefinition)
            assertEquals("research", result.actions[0].name)
            assertEquals("writeReport", result.actions[1].name)
            assertEquals("ResearchResults", result.actions[0].outputTypeName)
            assertEquals(setOf("ResearchResults"), result.actions[1].inputTypeNames)
        }

        @Test
        fun `actions preserve preconditions and postconditions`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = emptyList(),
                actions = listOf(
                    CompiledChainAction(
                        name = "filter",
                        description = "Filter input",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "TestOutput",
                        pre = listOf("spel:testInput.content.length() > 0"),
                        post = listOf("spel:testOutput != null"),
                        prompt = "Filter: {{testInput}}",
                    ),
                ),
                goalDescription = "Filtered",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("filter-agent", "Filter input", agentContext())

            assertTrue(result.success)
            assertEquals(listOf("spel:testInput.content.length() > 0"), result.actions[0].pre)
            assertEquals(listOf("spel:testOutput != null"), result.actions[0].post)
        }
    }

    @Nested
    inner class CompileAgentGoalGeneration {

        @Test
        fun `goal always present and references last action output type`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = emptyList(),
                actions = listOf(
                    CompiledChainAction(
                        name = "process",
                        description = "Process input",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "TestOutput",
                        prompt = "Process: {{testInput}}",
                    ),
                ),
                goalDescription = "Processing is complete",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("process-agent", "Process input", agentContext())

            assertNotNull(result.goal)
            assertEquals("TestOutput", result.goal!!.outputTypeName)
            assertEquals("Processing is complete", result.goal!!.description)
        }
    }

    @Nested
    inner class CompileAgentTypeResolution {

        private fun fqnContext(): StepSpecContext {
            val dd = mockk<DataDictionary>()
            every { dd.domainTypes } returns listOf(
                DynamicType(name = "com.example.TelegramMessage", description = "Telegram message"),
                DynamicType(name = "com.example.ProcessedResult", description = "Processed result"),
            )
            return StepSpecContext(
                name = "fqn-context",
                dataDictionary = dd,
                toolGroups = emptyList(),
                tools = emptyList(),
            )
        }

        @Test
        fun `resolves short type names to FQN from existing domain types`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = emptyList(),
                actions = listOf(
                    CompiledChainAction(
                        name = "process",
                        description = "Process message",
                        inputTypeNames = setOf("TelegramMessage"),
                        outputTypeName = "ProcessedResult",
                        prompt = "Process: {{telegramMessage}}",
                    ),
                ),
                goalDescription = "Message processed",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("telegram-agent", "Process telegram", fqnContext())

            assertTrue(result.success)
            assertEquals(setOf("com.example.TelegramMessage"), result.actions[0].inputTypeNames)
            assertEquals("com.example.ProcessedResult", result.actions[0].outputTypeName)
            assertTrue(result.intermediateTypes.isEmpty())
        }

        @Test
        fun `leaves FQN type names unchanged`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = emptyList(),
                actions = listOf(
                    CompiledChainAction(
                        name = "process",
                        description = "Process message",
                        inputTypeNames = setOf("com.example.TelegramMessage"),
                        outputTypeName = "com.example.ProcessedResult",
                        prompt = "Process",
                    ),
                ),
                goalDescription = "Done",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("fqn-agent", "Process", fqnContext())

            assertTrue(result.success)
            assertEquals(setOf("com.example.TelegramMessage"), result.actions[0].inputTypeNames)
            assertEquals("com.example.ProcessedResult", result.actions[0].outputTypeName)
        }
    }

    @Nested
    inner class CompileAgentValidation {

        @Test
        fun `rejects clashing intermediate type name with existing domain type`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = listOf(
                    CompiledTypeDefinition(
                        name = "TestInput",
                        description = "Clashes with existing type",
                        properties = listOf(
                            CompiledTypeProperty("data", "string", "Some data"),
                        ),
                    ),
                ),
                actions = listOf(
                    CompiledChainAction(
                        name = "process",
                        description = "Process",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "TestOutput",
                        prompt = "Process",
                    ),
                ),
                goalDescription = "Done",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("clash-agent", "Process", agentContext())

            assertFalse(result.success)
            assertTrue(result.errors.any { it.message.contains("TestInput") })
        }

        @Test
        fun `auto-infers missing intermediate types instead of rejecting`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = emptyList(),
                actions = listOf(
                    CompiledChainAction(
                        name = "fetchMessage",
                        description = "Fetch a telegram message",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "TelegramMessage",
                        prompt = "Fetch message from: {{testInput}}",
                    ),
                    CompiledChainAction(
                        name = "processMessage",
                        description = "Process the message",
                        inputTypeNames = setOf("TelegramMessage"),
                        outputTypeName = "TestOutput",
                        prompt = "Process: {{telegramMessage}}",
                    ),
                ),
                goalDescription = "Message processed",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("telegram-agent", "Process telegram messages", agentContext())

            assertTrue(result.success)
            assertEquals(2, result.actions.size)
            assertEquals(1, result.intermediateTypes.size)
            val inferred = result.intermediateTypes[0]
            assertEquals("TelegramMessage", inferred.name)
            assertEquals("Auto-inferred type", inferred.description)
            assertTrue(inferred.ownProperties.isEmpty())
        }

        @Test
        fun `does not infer types that are already defined as intermediate types`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = listOf(
                    CompiledTypeDefinition(
                        name = "MiddleType",
                        description = "Explicitly defined",
                        properties = listOf(CompiledTypeProperty("data", "string")),
                    ),
                ),
                actions = listOf(
                    CompiledChainAction(
                        name = "step1",
                        description = "First step",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "MiddleType",
                        prompt = "Step 1",
                    ),
                    CompiledChainAction(
                        name = "step2",
                        description = "Second step",
                        inputTypeNames = setOf("MiddleType"),
                        outputTypeName = "TestOutput",
                        prompt = "Step 2",
                    ),
                ),
                goalDescription = "Done",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("no-infer-agent", "Two steps", agentContext())

            assertTrue(result.success)
            assertEquals(1, result.intermediateTypes.size)
            assertEquals("MiddleType", result.intermediateTypes[0].name)
            assertEquals("Explicitly defined", result.intermediateTypes[0].description)
        }

        @Test
        fun `does not infer types that exist in domain context`() {
            mockAgentLlmChain()
            val compiledAgent = CompiledAgent(
                intermediateTypes = emptyList(),
                actions = listOf(
                    CompiledChainAction(
                        name = "process",
                        description = "Process",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "TestOutput",
                        prompt = "Process",
                    ),
                ),
                goalDescription = "Done",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            val result = compiler.compileAgent("known-types-agent", "Process", agentContext())

            assertTrue(result.success)
            assertTrue(result.intermediateTypes.isEmpty())
        }
    }

    @Nested
    inner class CompileAgentErrorHandling {

        @Test
        fun `LLM exception produces failure result`() {
            every { ai.withLlm(any<LlmOptions>()) } returns promptRunner
            every { promptRunner.withId(any()) } returns promptRunner
            every { promptRunner.rendering("compiler/compile_agent") } returns rendering
            every { rendering.createObject(CompiledAgent::class.java, any()) } throws RuntimeException("LLM unavailable")

            val result = compiler.compileAgent("fail-agent", "This will fail", agentContext())

            assertFalse(result.success)
            assertTrue(result.actions.isEmpty())
            assertTrue(result.errors.isNotEmpty())
            assertTrue(result.errors[0].message.contains("LLM unavailable"))
        }

        @Test
        fun `uses correct template path and model keys`() {
            every { ai.withLlm(any<LlmOptions>()) } returns promptRunner
            every { promptRunner.withId(any()) } returns promptRunner
            every { promptRunner.rendering("compiler/compile_agent") } returns rendering
            val compiledAgent = CompiledAgent(
                intermediateTypes = emptyList(),
                actions = listOf(
                    CompiledChainAction(
                        name = "test",
                        description = "Test",
                        inputTypeNames = setOf("TestInput"),
                        outputTypeName = "TestOutput",
                        prompt = "Test",
                    ),
                ),
                goalDescription = "Done",
            )
            every { rendering.createObject(CompiledAgent::class.java, any()) } returns compiledAgent

            compiler.compileAgent("test-agent", "Test", agentContext())

            verify {
                promptRunner.rendering("compiler/compile_agent")
            }
            verify {
                rendering.createObject(
                    CompiledAgent::class.java,
                    match { model ->
                        model.containsKey("agentName") &&
                        model.containsKey("agentDescription") &&
                        model.containsKey("bindings") &&
                        model.containsKey("toolGroups")
                    },
                )
            }
        }
    }

    // Test domain types
    data class TestInput(val content: String)
    data class TestOutput(val result: String)
}
