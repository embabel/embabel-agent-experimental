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
import com.embabel.agent.core.JvmType
import com.embabel.agent.core.ToolGroupDescription
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

    // Test domain types
    data class TestInput(val content: String)
    data class TestOutput(val result: String)
}
