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
package com.embabel.agent.skills.script

import com.embabel.agent.api.tool.Tool
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths
import kotlin.time.Duration.Companion.milliseconds

class ScriptToolTest {

    private val testScript = SkillScript(
        skillName = "my-skill",
        fileName = "build.sh",
        language = ScriptLanguage.BASH,
        basePath = Paths.get("/skills/my-skill"),
    )

    @Test
    fun `definition has correct name`() {
        val tool = ScriptTool(testScript, TestEngine())
        assertEquals("my-skill_build", tool.definition.name)
    }

    @Test
    fun `definition has auto-generated description`() {
        val tool = ScriptTool(testScript, TestEngine())
        assertTrue(tool.definition.description.contains("build.sh"))
        assertTrue(tool.definition.description.contains("my-skill"))
    }

    @Test
    fun `definition can have custom description`() {
        val tool = ScriptTool(testScript, TestEngine(), description = "Custom description")
        assertEquals("Custom description", tool.definition.description)
    }

    @Test
    fun `definition has args and stdin parameters`() {
        val tool = ScriptTool(testScript, TestEngine())
        val params = tool.definition.inputSchema.parameters

        assertEquals(2, params.size)
        assertEquals("args", params[0].name)
        assertEquals(Tool.ParameterType.ARRAY, params[0].type)
        assertEquals("stdin", params[1].name)
        assertEquals(Tool.ParameterType.STRING, params[1].type)
    }

    @Test
    fun `call returns error when engine denies execution`() {
        val engine = object : SkillScriptExecutionEngine {
            override fun supportedLanguages() = emptySet<ScriptLanguage>()
            override fun execute(script: SkillScript, args: List<String>, stdin: String?) =
                ScriptExecutionResult.Denied("Not supported")
            override fun validate(script: SkillScript) =
                ScriptExecutionResult.Denied("Validation failed")
        }

        val tool = ScriptTool(testScript, engine)
        val result = tool.call("{}")

        assertTrue(result is Tool.Result.Error)
        assertTrue((result as Tool.Result.Error).message.contains("Validation failed"))
    }

    @Test
    fun `call returns success result with output`() {
        val engine = TestEngine(
            result = ScriptExecutionResult.Success(
                stdout = "Hello World",
                stderr = "",
                exitCode = 0,
                duration = 100.milliseconds,
            )
        )

        val tool = ScriptTool(testScript, engine)
        val result = tool.call("{}")

        assertTrue(result is Tool.Result.Text)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("exit code 0"))
        assertTrue(text.contains("Hello World"))
    }

    @Test
    fun `call parses arguments from JSON input`() {
        var capturedArgs: List<String> = emptyList()
        val engine = object : TestEngine() {
            override fun execute(script: SkillScript, args: List<String>, stdin: String?): ScriptExecutionResult {
                capturedArgs = args
                return ScriptExecutionResult.Success("", "", 0, 1.milliseconds)
            }
        }

        val tool = ScriptTool(testScript, engine)
        tool.call("""{"args": ["--verbose", "--output", "file.txt"]}""")

        assertEquals(listOf("--verbose", "--output", "file.txt"), capturedArgs)
    }

    @Test
    fun `call parses stdin from JSON input`() {
        var capturedStdin: String? = null
        val engine = object : TestEngine() {
            override fun execute(script: SkillScript, args: List<String>, stdin: String?): ScriptExecutionResult {
                capturedStdin = stdin
                return ScriptExecutionResult.Success("", "", 0, 1.milliseconds)
            }
        }

        val tool = ScriptTool(testScript, engine)
        tool.call("""{"stdin": "input data"}""")

        assertEquals("input data", capturedStdin)
    }

    @Test
    fun `call handles empty input`() {
        val tool = ScriptTool(testScript, TestEngine())
        val result = tool.call("")

        assertTrue(result is Tool.Result.Text)
    }

    @Test
    fun `call handles malformed JSON gracefully`() {
        val tool = ScriptTool(testScript, TestEngine())
        val result = tool.call("not valid json")

        assertTrue(result is Tool.Result.Text)
    }

    @Test
    fun `call formats failure result correctly`() {
        val engine = TestEngine(
            result = ScriptExecutionResult.Failure(
                error = "Script crashed",
                stderr = "Error: something went wrong",
                exitCode = 1,
                timedOut = false,
                duration = 50.milliseconds,
            )
        )

        val tool = ScriptTool(testScript, engine)
        val result = tool.call("{}")

        assertTrue(result is Tool.Result.Error)
        val message = (result as Tool.Result.Error).message
        assertTrue(message.contains("Script crashed"))
        assertTrue(message.contains("something went wrong"))
    }

    @Test
    fun `call indicates timeout in failure result`() {
        val engine = TestEngine(
            result = ScriptExecutionResult.Failure(
                error = "Execution exceeded time limit",
                timedOut = true,
            )
        )

        val tool = ScriptTool(testScript, engine)
        val result = tool.call("{}")

        assertTrue(result is Tool.Result.Error)
        assertTrue((result as Tool.Result.Error).message.contains("timed out"))
    }

    /**
     * Test engine that returns configurable results.
     */
    private open class TestEngine(
        private val result: ScriptExecutionResult = ScriptExecutionResult.Success(
            stdout = "ok",
            stderr = "",
            exitCode = 0,
            duration = 1.milliseconds,
        )
    ) : SkillScriptExecutionEngine {
        override fun supportedLanguages() = ScriptLanguage.entries.toSet()
        override fun execute(script: SkillScript, args: List<String>, stdin: String?) = result
    }
}
