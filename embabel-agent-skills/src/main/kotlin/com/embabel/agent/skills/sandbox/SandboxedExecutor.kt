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
package com.embabel.agent.skills.sandbox

import java.nio.file.Path
import kotlin.time.Duration

/**
 * A generalized executor for running commands in sandboxed environments.
 *
 * This provides a common abstraction for:
 * - Running scripts (Python, Bash, JS, etc.)
 * - Running CLI tools (Claude Code, Aider, etc.)
 * - Running arbitrary commands with isolation
 *
 * Implementations can provide different sandboxing strategies:
 * - **ProcessExecutor**: OS process isolation (basic)
 * - **DockerExecutor**: Container isolation (strong)
 * - **NoOpExecutor**: Denies all execution (safe default)
 *
 * @see ExecutionRequest
 * @see ExecutionResult
 */
interface SandboxedExecutor {

    /**
     * Execute a command in the sandbox.
     *
     * @param request the execution request
     * @return the execution result
     */
    fun execute(request: ExecutionRequest): ExecutionResult

    /**
     * Check if the executor is available and properly configured.
     *
     * @return null if available, or a reason string if not
     */
    fun checkAvailability(): String?

    /**
     * Validate a request without executing it.
     *
     * @param request the request to validate
     * @return null if valid, or a [ExecutionResult.Denied] with reason
     */
    fun validate(request: ExecutionRequest): ExecutionResult.Denied? = null
}

/**
 * Request to execute a command in a sandbox.
 *
 * @param command the command and arguments to execute
 * @param workingDirectory the working directory for execution
 * @param environment additional environment variables
 * @param stdin input to provide via standard input
 * @param inputFiles files to make available to the command (will be in INPUT_DIR)
 * @param timeout maximum execution time
 * @param captureOutput whether to capture stdout/stderr (default true)
 */
data class ExecutionRequest(
    val command: List<String>,
    val workingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    val inputFiles: List<Path> = emptyList(),
    val timeout: Duration,
    val captureOutput: Boolean = true,
)

/**
 * Result of a sandboxed execution.
 */
sealed interface ExecutionResult {

    /**
     * Successful execution (process completed, regardless of exit code).
     *
     * @param exitCode the process exit code
     * @param stdout captured standard output
     * @param stderr captured standard error
     * @param duration how long execution took
     * @param artifacts files produced in OUTPUT_DIR
     */
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val duration: Duration,
        val artifacts: List<ExecutionArtifact> = emptyList(),
    ) : ExecutionResult {

        /** Whether the process exited successfully (exit code 0) */
        val success: Boolean get() = exitCode == 0
    }

    /**
     * Execution timed out.
     *
     * @param partialStderr any stderr captured before timeout
     * @param duration how long before timeout
     */
    data class TimedOut(
        val partialStderr: String? = null,
        val duration: Duration,
    ) : ExecutionResult

    /**
     * Execution failed to start.
     *
     * @param error the error message
     * @param cause the underlying exception, if any
     */
    data class Failed(
        val error: String,
        val cause: Throwable? = null,
    ) : ExecutionResult

    /**
     * Execution was denied (validation failed or not permitted).
     *
     * @param reason why execution was denied
     */
    data class Denied(
        val reason: String,
    ) : ExecutionResult
}

/**
 * An artifact (file) produced by execution.
 *
 * @param name the file name
 * @param path absolute path to the artifact
 * @param mimeType detected MIME type
 * @param sizeBytes file size in bytes
 */
data class ExecutionArtifact(
    val name: String,
    val path: Path,
    val mimeType: String? = null,
    val sizeBytes: Long,
) {
    companion object {
        /**
         * Infer MIME type from file name extension.
         */
        fun inferMimeType(fileName: String): String {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when (extension) {
                "pdf" -> "application/pdf"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "html", "htm" -> "text/html"
                "txt" -> "text/plain"
                "md" -> "text/markdown"
                "csv" -> "text/csv"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "zip" -> "application/zip"
                "tar" -> "application/x-tar"
                "gz" -> "application/gzip"
                "py" -> "text/x-python"
                "js" -> "text/javascript"
                "kt", "kts" -> "text/x-kotlin"
                "java" -> "text/x-java"
                "sh" -> "text/x-shellscript"
                else -> "application/octet-stream"
            }
        }
    }
}

/**
 * A no-op executor that denies all execution.
 *
 * This is the safe default when no sandbox is configured.
 */
object NoOpExecutor : SandboxedExecutor {

    override fun execute(request: ExecutionRequest): ExecutionResult =
        ExecutionResult.Denied("Execution is disabled. No sandbox executor is configured.")

    override fun checkAvailability(): String =
        "No sandbox executor is configured."

    override fun validate(request: ExecutionRequest): ExecutionResult.Denied =
        ExecutionResult.Denied("Execution is disabled. No sandbox executor is configured.")
}
