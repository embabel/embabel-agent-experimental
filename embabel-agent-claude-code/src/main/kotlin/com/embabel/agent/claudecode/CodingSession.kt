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

import com.embabel.agent.sandbox.SandboxSession

/**
 * Bundles two orthogonal session concepts for multi-turn Claude Code interaction:
 *
 * - **[sandboxSession]**: The persistent Docker container (files, packages, env state).
 *   This is the environment — what's on disk.
 * - **[claudeSessionId]**: Claude's `--resume` conversation context (what it remembers).
 *   This is the conversation — what Claude knows.
 *
 * Multi-turn interaction needs both: Claude picks up where it left off conversationally,
 * and the sandbox picks up where it left off environmentally.
 *
 * ## Example
 *
 * ```kotlin
 * val sandbox = sessionManager.create("feature-42", config)
 * var session = CodingSession(sandbox, codebase = codebase)
 *
 * // Turn 1
 * val r1 = executor.execute(prompt = "Set up the project", sandboxSession = session.sandboxSession)
 * session = session.advance(r1 as ClaudeCodeResult.Success)
 *
 * // Turn 2 — same container, same Claude conversation
 * val r2 = executor.execute(
 *     prompt = "Add the auth module",
 *     sessionId = session.claudeSessionId,
 *     sandboxSession = session.sandboxSession,
 * )
 * session = session.advance(r2 as ClaudeCodeResult.Success)
 * ```
 *
 * @param sandboxSession the persistent sandbox environment
 * @param claudeSessionId Claude's conversation session ID (null on first turn)
 * @param codebase the codebase being worked on
 * @param turnCount number of completed turns in this session
 * @param totalCostUsd accumulated cost across all turns
 * @param allFilesAffected accumulated list of all files affected across turns
 */
data class CodingSession(
    val sandboxSession: SandboxSession,
    val claudeSessionId: String? = null,
    val codebase: Codebase? = null,
    val turnCount: Int = 0,
    val totalCostUsd: Double = 0.0,
    val allFilesAffected: List<String> = emptyList(),
) {

    /**
     * Create an updated session from a successful Claude Code result.
     * Carries forward the sandbox session and accumulates metadata.
     */
    fun advance(result: ClaudeCodeResult.Success): CodingSession = copy(
        claudeSessionId = result.sessionId ?: claudeSessionId,
        turnCount = turnCount + 1,
        totalCostUsd = totalCostUsd + result.costUsd,
        allFilesAffected = (allFilesAffected + result.allAffectedFiles).distinct(),
    )

    /**
     * Whether this is the first turn (no prior Claude session to resume).
     */
    val isFirstTurn: Boolean get() = claudeSessionId == null

    /**
     * Close the underlying sandbox session. This destroys all environment state.
     */
    fun close() = sandboxSession.close()
}
