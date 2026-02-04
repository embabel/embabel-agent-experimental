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

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClaudeCodeToolTest {

    @Test
    fun `tool has correct definition`() {
        val tool = ClaudeCodeTool()

        assertEquals("claude_code", tool.definition.name)
        assertTrue(tool.definition.description.contains("Claude Code"))
        assertNotNull(tool.definition.inputSchema)
    }

    @Test
    fun `tool definition has required parameters`() {
        val tool = ClaudeCodeTool()

        val params = tool.definition.inputSchema.parameters
        assertTrue(params.any { it.name == "prompt" && it.required })
        assertTrue(params.any { it.name == "workingDirectory" && !it.required })
        assertTrue(params.any { it.name == "allowedTools" && !it.required })
        assertTrue(params.any { it.name == "maxTurns" && !it.required })
    }

    @Test
    fun `readOnly tool has restricted tools`() {
        val tool = ClaudeCodeTool.readOnly()

        assertEquals("claude_code_explore", tool.definition.name)
        assertTrue(tool.definition.description.contains("read-only"))
    }

    @Test
    fun `default allowed tools are reasonable`() {
        val defaultTools = ClaudeCodeTool.DEFAULT_ALLOWED_TOOLS

        assertTrue(defaultTools.contains(ClaudeCodeAllowedTool.READ))
        assertTrue(defaultTools.contains(ClaudeCodeAllowedTool.EDIT))
        assertTrue(defaultTools.contains(ClaudeCodeAllowedTool.WRITE))
        assertTrue(defaultTools.contains(ClaudeCodeAllowedTool.BASH))
        assertTrue(defaultTools.contains(ClaudeCodeAllowedTool.GLOB))
        assertTrue(defaultTools.contains(ClaudeCodeAllowedTool.GREP))

        // Web tools are not included by default
        assertTrue(!defaultTools.contains(ClaudeCodeAllowedTool.WEB_SEARCH))
        assertTrue(!defaultTools.contains(ClaudeCodeAllowedTool.WEB_FETCH))
    }

    @Test
    fun `default max turns is set`() {
        assertEquals(20, ClaudeCodeTool.DEFAULT_MAX_TURNS)
    }
}
