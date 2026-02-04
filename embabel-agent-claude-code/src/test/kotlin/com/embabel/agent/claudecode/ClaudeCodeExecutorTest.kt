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

class ClaudeCodeExecutorTest {

    @Test
    fun `ClaudeCodeAllowedTool has correct CLI names`() {
        assertEquals("Read", ClaudeCodeAllowedTool.READ.cliName)
        assertEquals("Edit", ClaudeCodeAllowedTool.EDIT.cliName)
        assertEquals("Write", ClaudeCodeAllowedTool.WRITE.cliName)
        assertEquals("Bash", ClaudeCodeAllowedTool.BASH.cliName)
        assertEquals("Glob", ClaudeCodeAllowedTool.GLOB.cliName)
        assertEquals("Grep", ClaudeCodeAllowedTool.GREP.cliName)
        assertEquals("WebSearch", ClaudeCodeAllowedTool.WEB_SEARCH.cliName)
        assertEquals("WebFetch", ClaudeCodeAllowedTool.WEB_FETCH.cliName)
        assertEquals("NotebookEdit", ClaudeCodeAllowedTool.NOTEBOOK_EDIT.cliName)
        assertEquals("Task", ClaudeCodeAllowedTool.TASK.cliName)
    }

    @Test
    fun `ClaudeCodePermissionMode has correct CLI values`() {
        assertEquals("default", ClaudeCodePermissionMode.DEFAULT.cliValue)
        assertEquals("acceptEdits", ClaudeCodePermissionMode.ACCEPT_EDITS.cliValue)
        assertEquals("plan", ClaudeCodePermissionMode.PLAN.cliValue)
        assertEquals("bypassPermissions", ClaudeCodePermissionMode.BYPASS_PERMISSIONS.cliValue)
    }

    @Test
    fun `ClaudeCodeResult Success tracks affected files`() {
        val result = ClaudeCodeResult.Success(
            result = "Done",
            filesModified = listOf("a.kt", "b.kt"),
            filesCreated = listOf("c.kt"),
            filesDeleted = listOf("d.kt"),
        )

        assertEquals(4, result.allAffectedFiles.size)
        assertEquals(listOf("a.kt", "b.kt", "c.kt", "d.kt"), result.allAffectedFiles)
    }

    @Test
    fun `ClaudeCodeResult Success deduplicates affected files`() {
        val result = ClaudeCodeResult.Success(
            result = "Done",
            filesModified = listOf("a.kt"),
            filesCreated = listOf("a.kt"), // Same file
        )

        assertEquals(1, result.allAffectedFiles.size)
    }

    @Test
    fun `ClaudeCodeJsonOutput parses correctly`() {
        val output = ClaudeCodeJsonOutput(
            type = "result",
            result = "Success",
            sessionId = "session-123",
            totalCostUsd = 0.05,
            numTurns = 5,
            isError = false,
        )

        assertEquals("result", output.type)
        assertEquals("Success", output.result)
        assertEquals("session-123", output.sessionId)
        assertEquals(0.05, output.totalCostUsd)
        assertEquals(5, output.numTurns)
        assertEquals(false, output.isError)
    }
}
