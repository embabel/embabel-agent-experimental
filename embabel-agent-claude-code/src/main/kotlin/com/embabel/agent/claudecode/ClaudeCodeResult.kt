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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.time.Duration

/**
 * Result of a Claude Code execution.
 *
 * Represents the outcome of running `claude -p "prompt" --output-format json`.
 */
sealed interface ClaudeCodeResult {

    /**
     * Successful execution of Claude Code.
     *
     * @param result the final response from Claude Code
     * @param sessionId unique identifier for the session (can be used to resume)
     * @param costUsd total cost of the execution in USD
     * @param duration how long the execution took
     * @param numTurns number of agentic turns (API round-trips) taken
     * @param filesModified list of files that were modified during execution
     * @param filesCreated list of files that were created during execution
     * @param filesDeleted list of files that were deleted during execution
     */
    data class Success(
        val result: String,
        val sessionId: String? = null,
        val costUsd: Double = 0.0,
        val duration: Duration? = null,
        val numTurns: Int = 0,
        val filesModified: List<String> = emptyList(),
        val filesCreated: List<String> = emptyList(),
        val filesDeleted: List<String> = emptyList(),
    ) : ClaudeCodeResult {

        /**
         * All files affected by this execution.
         */
        val allAffectedFiles: List<String>
            get() = (filesModified + filesCreated + filesDeleted).distinct()
    }

    /**
     * Failed execution of Claude Code.
     *
     * @param error the error message
     * @param exitCode the exit code from the process, if available
     * @param stderr captured stderr output, if any
     * @param timedOut whether the execution timed out
     * @param duration how long the execution ran before failing
     */
    data class Failure(
        val error: String,
        val exitCode: Int? = null,
        val stderr: String? = null,
        val timedOut: Boolean = false,
        val duration: Duration? = null,
    ) : ClaudeCodeResult

    /**
     * Execution was denied (e.g., Claude Code not installed or permission denied).
     *
     * @param reason why execution was denied
     */
    data class Denied(
        val reason: String,
    ) : ClaudeCodeResult
}

/**
 * Raw JSON output format from Claude Code CLI.
 *
 * When running `claude -p "prompt" --output-format json`, the CLI returns
 * a JSON object with this structure.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ClaudeCodeJsonOutput(
    @JsonProperty("type")
    val type: String? = null,

    @JsonProperty("subtype")
    val subtype: String? = null,

    @JsonProperty("result")
    val result: String? = null,

    @JsonProperty("session_id")
    val sessionId: String? = null,

    @JsonProperty("cost_usd")
    val costUsd: Double? = null,

    @JsonProperty("total_cost_usd")
    val totalCostUsd: Double? = null,

    @JsonProperty("num_turns")
    val numTurns: Int? = null,

    @JsonProperty("is_error")
    val isError: Boolean? = null,

    @JsonProperty("duration_ms")
    val durationMs: Long? = null,

    @JsonProperty("duration_api_ms")
    val durationApiMs: Long? = null,
)

/**
 * Configuration for allowed tools when running Claude Code.
 *
 * By default, Claude Code has access to many tools. This allows restricting
 * which tools are available for a specific execution.
 */
enum class ClaudeCodeAllowedTool(val cliName: String) {
    /** Read files from the filesystem */
    READ("Read"),

    /** Edit existing files */
    EDIT("Edit"),

    /** Write new files */
    WRITE("Write"),

    /** Execute bash commands */
    BASH("Bash"),

    /** Search for files using glob patterns */
    GLOB("Glob"),

    /** Search file contents using grep */
    GREP("Grep"),

    /** Search the web */
    WEB_SEARCH("WebSearch"),

    /** Fetch content from URLs */
    WEB_FETCH("WebFetch"),

    /** Edit Jupyter notebooks */
    NOTEBOOK_EDIT("NotebookEdit"),

    /** Launch subagent tasks */
    TASK("Task"),
}

/**
 * Permission mode for Claude Code execution.
 *
 * Controls how Claude Code handles permission requests during execution.
 */
enum class ClaudeCodePermissionMode(val cliValue: String) {
    /** Default mode - prompts for permissions interactively (not suitable for automation) */
    DEFAULT("default"),

    /** Accept all edit operations automatically */
    ACCEPT_EDITS("acceptEdits"),

    /** Runs in plan mode only - no edits allowed */
    PLAN("plan"),

    /** Bypass all permission prompts (use with caution!) */
    BYPASS_PERMISSIONS("bypassPermissions"),
}
