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
package com.embabel.agent.claudecode

import com.embabel.agent.executor.TypedResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypedExecutionTest {

    private val executor = ClaudeCodeExecutor()

    // --- JSON extraction tests ---

    @Test
    fun `extractJson handles raw JSON object`() {
        val json = """{"name": "test", "value": 42}"""
        assertEquals(json, executor.extractJson(json))
    }

    @Test
    fun `extractJson handles raw JSON array`() {
        val json = """[1, 2, 3]"""
        assertEquals(json, executor.extractJson(json))
    }

    @Test
    fun `extractJson strips markdown code fences`() {
        val input = """
            Here is the result:
            ```json
            {"name": "test"}
            ```
            That's all.
        """.trimIndent()
        assertEquals("""{"name": "test"}""", executor.extractJson(input))
    }

    @Test
    fun `extractJson strips markdown fences without language tag`() {
        val input = """
            ```
            {"name": "test"}
            ```
        """.trimIndent()
        assertEquals("""{"name": "test"}""", executor.extractJson(input))
    }

    @Test
    fun `extractJson finds JSON embedded in text`() {
        val input = """Here is the output: {"name": "test"} and that's it."""
        assertEquals("""{"name": "test"}""", executor.extractJson(input))
    }

    @Test
    fun `extractJson returns trimmed text when no JSON found`() {
        val input = "  just plain text  "
        assertEquals("just plain text", executor.extractJson(input))
    }

    // --- JSON parsing tests ---

    data class SimpleOutput(val name: String = "", val value: Int = 0)

    @Test
    fun `extractAndParse deserializes JSON to data class`() {
        val json = """{"name": "hello", "value": 42}"""
        val result = executor.extractAndParse(json, SimpleOutput::class.java)
        assertEquals("hello", result.name)
        assertEquals(42, result.value)
    }

    @Test
    fun `extractAndParse handles JSON with extra text`() {
        val input = """Here is the result: {"name": "hello", "value": 42}"""
        val result = executor.extractAndParse(input, SimpleOutput::class.java)
        assertEquals("hello", result.name)
    }

    @Test
    fun `extractAndParse handles markdown-fenced JSON`() {
        val input = "```json\n{\"name\": \"hello\", \"value\": 42}\n```"
        val result = executor.extractAndParse(input, SimpleOutput::class.java)
        assertEquals("hello", result.name)
        assertEquals(42, result.value)
    }

    // --- JSON schema generation tests ---

    @Test
    fun `generateJsonSchema for String returns string type`() {
        val schema = executor.generateJsonSchema(String::class.java)
        assertTrue(schema.contains("string"))
    }

    @Test
    fun `generateJsonSchema for data class includes properties`() {
        val schema = executor.generateJsonSchema(SimpleOutput::class.java)
        assertTrue(schema.contains("name"))
        assertTrue(schema.contains("value"))
        assertTrue(schema.contains("object"))
    }

    // --- TypedResult tests ---

    @Test
    fun `TypedResult Success holds value and score`() {
        val raw = ClaudeCodeResult.Success(result = "raw")
        val result = TypedResult.Success(
            value = SimpleOutput("test", 1),
            score = 0.95,
            attempts = 1,
            raw = raw,
        )
        assertEquals("test", result.value.name)
        assertEquals(0.95, result.score)
        assertEquals(1, result.attempts)
    }

    @Test
    fun `TypedResult Failure holds error info`() {
        val result = TypedResult.Failure<SimpleOutput>(
            error = "parse failed",
            raw = ClaudeCodeResult.Failure(error = "bad output"),
        )
        assertEquals("parse failed", result.error)
    }

    // --- buildCommand mcpConfig tests ---

    @Test
    fun `buildCommand includes mcp-config flag when mcpConfig is provided`() {
        val command = executor.buildCommand(
            prompt = "test prompt",
            allowedTools = null,
            maxTurns = null,
            permissionMode = ClaudeCodePermissionMode.DEFAULT,
            sessionId = null,
            model = null,
            systemPrompt = null,
            mcpConfig = """{"mcpServers":{}}""",
        )

        assertTrue(command.contains("--mcp-config"), "Command should include --mcp-config flag")
        // The value after --mcp-config should be a file path (temp file)
        val mcpConfigIndex = command.indexOf("--mcp-config")
        assertTrue(mcpConfigIndex >= 0)
        val configFilePath = command[mcpConfigIndex + 1]
        assertTrue(configFilePath.endsWith(".json"), "Config should be written to a .json temp file")
    }

    @Test
    fun `buildCommand omits mcp-config when null`() {
        val command = executor.buildCommand(
            prompt = "test prompt",
            allowedTools = null,
            maxTurns = null,
            permissionMode = ClaudeCodePermissionMode.DEFAULT,
            sessionId = null,
            model = null,
            systemPrompt = null,
            mcpConfig = null,
        )

        assertTrue(!command.contains("--mcp-config"), "Command should not include --mcp-config flag when null")
    }
}
