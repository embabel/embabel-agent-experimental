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

import com.embabel.agent.sandbox.AsyncExecution
import com.embabel.agent.sandbox.ExecutionResult
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * Handle for an asynchronous Claude Code execution.
 *
 * Allows monitoring progress, getting partial output, and cancelling execution.
 *
 * ## Example
 *
 * ```kotlin
 * val async = executor.executeAsync(
 *     prompt = "Refactor the authentication module",
 *     workingDirectory = projectPath,
 * )
 *
 * // Check progress periodically
 * while (async.isRunning) {
 *     println("Still running...")
 *     Thread.sleep(5000)
 * }
 *
 * // Get result
 * when (val result = async.await()) {
 *     is ClaudeCodeResult.Success -> println("Done: ${result.result}")
 *     is ClaudeCodeResult.Failure -> println("Failed: ${result.error}")
 *     is ClaudeCodeResult.Denied -> println("Denied: ${result.reason}")
 * }
 * ```
 */
class ClaudeCodeAsyncExecution internal constructor(
    private val underlying: AsyncExecution,
    private val timeout: Duration,
    private val resultConverter: (ExecutionResult) -> ClaudeCodeResult,
) {

    private val logger = LoggerFactory.getLogger(ClaudeCodeAsyncExecution::class.java)

    /**
     * Whether the execution is still running.
     */
    val isRunning: Boolean
        get() = underlying.isRunning

    /**
     * Whether the execution was cancelled.
     */
    val isCancelled: Boolean
        get() = underlying.isCancelled

    /**
     * Wait for completion and get the result.
     *
     * @return the Claude Code result
     */
    fun await(): ClaudeCodeResult {
        val result = resultConverter(underlying.await())
        logResult(result)
        return result
    }

    /**
     * Wait for completion with a timeout.
     *
     * @param timeout maximum time to wait
     * @return the result, or [ClaudeCodeResult.Failure] with timedOut=true if the wait times out
     */
    fun await(timeout: Duration): ClaudeCodeResult {
        val result = resultConverter(underlying.await(timeout))
        logResult(result)
        return result
    }

    private fun logResult(result: ClaudeCodeResult) {
        when (result) {
            is ClaudeCodeResult.Success -> logger.info(
                "Claude Code (async) completed: {} turns, cost \${}, duration {}",
                result.numTurns,
                "%.4f".format(result.costUsd),
                result.duration ?: "unknown"
            )
            is ClaudeCodeResult.Failure -> logger.warn(
                "Claude Code (async) failed: {} (timed out: {})",
                result.error.take(100),
                result.timedOut
            )
            is ClaudeCodeResult.Denied -> logger.warn(
                "Claude Code (async) denied: {}",
                result.reason
            )
        }
    }

    /**
     * Cancel the execution.
     *
     * @return true if cancellation was initiated, false if already completed
     */
    fun cancel(): Boolean = underlying.cancel()

    /**
     * Get partial stdout captured so far (if available).
     *
     * @return partial output, or null if not supported
     */
    fun getPartialStdout(): String? = underlying.getPartialStdout()

    /**
     * Get partial stderr captured so far (if available).
     *
     * @return partial stderr, or null if not supported
     */
    fun getPartialStderr(): String? = underlying.getPartialStderr()

    /**
     * Get a [CompletableFuture] that completes with the [ClaudeCodeResult].
     */
    fun toFuture(): CompletableFuture<ClaudeCodeResult> {
        return underlying.toFuture().thenApply(resultConverter)
    }

    companion object {
        /**
         * Create an immediately-denied async execution.
         */
        internal fun denied(result: ClaudeCodeResult.Denied): ClaudeCodeAsyncExecution {
            val completedExecution = object : AsyncExecution {
                override val isRunning = false
                override val isCancelled = false
                override fun await() = ExecutionResult.Denied(result.reason)
                override fun await(timeout: Duration) = ExecutionResult.Denied(result.reason)
                override fun cancel() = false
                override fun toFuture() = CompletableFuture.completedFuture(
                    ExecutionResult.Denied(result.reason) as ExecutionResult
                )
            }
            return ClaudeCodeAsyncExecution(completedExecution, Duration.ZERO) { result }
        }

        /**
         * Create an async execution for direct (non-sandboxed) execution.
         */
        internal fun direct(
            command: List<String>,
            workingDirectory: Path?,
            environment: Map<String, String>,
            timeout: Duration,
            outputParser: (stdout: String, exitCode: Int, duration: Duration?) -> ClaudeCodeResult,
        ): ClaudeCodeAsyncExecution {
            val execution = DirectAsyncExecution(command, workingDirectory, environment, timeout, outputParser)
            return ClaudeCodeAsyncExecution(
                underlying = execution,
                timeout = timeout,
                resultConverter = { executionResult ->
                    when (executionResult) {
                        is ExecutionResult.Completed -> outputParser(
                            executionResult.stdout,
                            executionResult.exitCode,
                            executionResult.duration
                        )
                        is ExecutionResult.TimedOut -> ClaudeCodeResult.Failure(
                            error = "Execution timed out after $timeout",
                            timedOut = true,
                            duration = executionResult.duration,
                        )
                        is ExecutionResult.Failed -> ClaudeCodeResult.Failure(error = executionResult.error)
                        is ExecutionResult.Denied -> ClaudeCodeResult.Denied(executionResult.reason)
                    }
                }
            )
        }
    }
}

/**
 * Async execution implementation for direct process execution.
 */
private class DirectAsyncExecution(
    command: List<String>,
    workingDirectory: Path?,
    environment: Map<String, String>,
    private val timeout: Duration,
    private val outputParser: (stdout: String, exitCode: Int, duration: Duration?) -> ClaudeCodeResult,
) : AsyncExecution {

    private val cancelled = AtomicBoolean(false)
    private var process: Process? = null

    private val future: CompletableFuture<ExecutionResult> = CompletableFuture.supplyAsync {
        if (cancelled.get()) {
            return@supplyAsync ExecutionResult.Failed("Execution was cancelled before starting")
        }

        try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(false)

            workingDirectory?.let { processBuilder.directory(it.toFile()) }

            val env = processBuilder.environment()
            env.putAll(environment)
            env["CI"] = "true"

            val proc = processBuilder.start()
            process = proc
            proc.outputStream.close()

            var stdout = ""
            var stderr = ""

            val stdoutThread = Thread {
                stdout = proc.inputStream.bufferedReader().readText()
            }.apply { start() }

            val stderrThread = Thread {
                stderr = proc.errorStream.bufferedReader().readText()
            }.apply { start() }

            val completed = proc.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

            if (!completed || cancelled.get()) {
                proc.destroyForcibly()
                stdoutThread.join(1000)
                stderrThread.join(1000)
                return@supplyAsync ExecutionResult.TimedOut(
                    partialStderr = stderr.takeIf { it.isNotEmpty() },
                    duration = timeout,
                )
            }

            stdoutThread.join()
            stderrThread.join()

            ExecutionResult.Completed(
                exitCode = proc.exitValue(),
                stdout = stdout,
                stderr = stderr,
                duration = timeout, // Approximate - could track actual
            )
        } catch (e: Exception) {
            ExecutionResult.Failed(error = e.message ?: "Unknown error", cause = e)
        }
    }

    override val isRunning: Boolean
        get() = !future.isDone

    override val isCancelled: Boolean
        get() = cancelled.get()

    override fun await(): ExecutionResult = future.get()

    override fun await(timeout: Duration): ExecutionResult {
        return try {
            future.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            ExecutionResult.TimedOut(duration = timeout)
        }
    }

    override fun cancel(): Boolean {
        if (cancelled.compareAndSet(false, true)) {
            process?.destroyForcibly()
            future.cancel(true)
            return true
        }
        return false
    }

    override fun toFuture(): CompletableFuture<ExecutionResult> = future
}
