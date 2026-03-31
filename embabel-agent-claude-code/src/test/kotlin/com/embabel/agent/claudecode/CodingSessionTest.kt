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

import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import com.embabel.agent.sandbox.SandboxConfig
import com.embabel.agent.sandbox.SandboxSession
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class CodingSessionTest {

    private val mockSession = object : SandboxSession {
        override val id = "test-session"
        override val label = "test"
        override val owner: String? = null
        override val createdAt: Instant = Instant.now()
        override var lastActiveAt: Instant = Instant.now()
        override val state = SandboxSession.SessionState.ACTIVE
        override val config = SandboxConfig()

        override fun execute(request: ExecutionRequest): ExecutionResult =
            ExecutionResult.Completed(0, "", "", Duration.ZERO, emptyList())

        override fun copyFrom(containerPath: String, hostPath: Path) {}
        override fun copyTo(hostPath: Path, containerPath: String) {}
        override fun pause() {}
        override fun resume() {}
        override fun close() {}
    }

    @Test
    fun `initial session has no claude session id`() {
        val session = CodingSession(sandboxSession = mockSession)
        assertNull(session.claudeSessionId)
        assertTrue(session.isFirstTurn)
        assertEquals(0, session.turnCount)
        assertEquals(0.0, session.totalCostUsd)
    }

    @Test
    fun `advance updates session from successful result`() {
        val session = CodingSession(sandboxSession = mockSession)

        val result = ClaudeCodeResult.Success(
            result = "Set up the project",
            sessionId = "claude-abc-123",
            costUsd = 0.05,
            numTurns = 3,
            filesModified = listOf("package.json"),
            filesCreated = listOf("src/index.ts"),
            filesDeleted = emptyList(),
        )

        val advanced = session.advance(result)

        assertEquals("claude-abc-123", advanced.claudeSessionId)
        assertFalse(advanced.isFirstTurn)
        assertEquals(1, advanced.turnCount)
        assertEquals(0.05, advanced.totalCostUsd)
        assertEquals(listOf("package.json", "src/index.ts"), advanced.allFilesAffected)
    }

    @Test
    fun `advance accumulates cost across turns`() {
        var session = CodingSession(sandboxSession = mockSession)

        val result1 = ClaudeCodeResult.Success(
            result = "Turn 1",
            sessionId = "session-1",
            costUsd = 0.03,
            numTurns = 2,
            filesModified = listOf("a.txt"),
            filesCreated = emptyList(),
            filesDeleted = emptyList(),
        )

        session = session.advance(result1)

        val result2 = ClaudeCodeResult.Success(
            result = "Turn 2",
            sessionId = "session-2",
            costUsd = 0.07,
            numTurns = 4,
            filesModified = listOf("b.txt"),
            filesCreated = emptyList(),
            filesDeleted = emptyList(),
        )

        session = session.advance(result2)

        assertEquals("session-2", session.claudeSessionId)
        assertEquals(2, session.turnCount)
        assertEquals(0.10, session.totalCostUsd, 0.001)
        assertEquals(listOf("a.txt", "b.txt"), session.allFilesAffected)
    }

    @Test
    fun `advance deduplicates affected files`() {
        var session = CodingSession(sandboxSession = mockSession)

        val result1 = ClaudeCodeResult.Success(
            result = "Turn 1",
            sessionId = "s1",
            costUsd = 0.01,
            numTurns = 1,
            filesModified = listOf("shared.txt", "a.txt"),
            filesCreated = emptyList(),
            filesDeleted = emptyList(),
        )
        session = session.advance(result1)

        val result2 = ClaudeCodeResult.Success(
            result = "Turn 2",
            sessionId = "s2",
            costUsd = 0.01,
            numTurns = 1,
            filesModified = listOf("shared.txt", "b.txt"),
            filesCreated = emptyList(),
            filesDeleted = emptyList(),
        )
        session = session.advance(result2)

        // shared.txt should appear only once
        assertEquals(listOf("shared.txt", "a.txt", "b.txt"), session.allFilesAffected)
    }

    @Test
    fun `advance preserves sandbox session reference`() {
        val session = CodingSession(sandboxSession = mockSession)

        val result = ClaudeCodeResult.Success(
            result = "done",
            sessionId = "s1",
            costUsd = 0.0,
            numTurns = 1,
            filesModified = emptyList(),
            filesCreated = emptyList(),
            filesDeleted = emptyList(),
        )

        val advanced = session.advance(result)
        assertTrue(advanced.sandboxSession === mockSession)
    }

    @Test
    fun `advance preserves claude session id when result has none`() {
        val session = CodingSession(
            sandboxSession = mockSession,
            claudeSessionId = "existing-session",
        )

        val result = ClaudeCodeResult.Success(
            result = "done",
            sessionId = null, // No session ID in result
            costUsd = 0.01,
            numTurns = 1,
            filesModified = emptyList(),
            filesCreated = emptyList(),
            filesDeleted = emptyList(),
        )

        val advanced = session.advance(result)
        assertEquals("existing-session", advanced.claudeSessionId)
    }
}
