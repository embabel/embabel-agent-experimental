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
import com.embabel.agent.sandbox.DockerExecutor
import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import com.embabel.agent.sandbox.SandboxedExecutor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Executes Claude Code CLI commands.
 *
 * This is a thin wrapper around the `claude` CLI that provides:
 * - Structured execution with configurable options
 * - JSON output parsing into typed results
 * - Optional sandboxed execution via Docker
 * - Timeout handling and error reporting
 *
 * ## Prerequisites
 *
 * Claude Code CLI must be installed and available in the PATH (or in the Docker image).
 * Install with: `npm install -g @anthropic-ai/claude-code`
 *
 * ## Usage
 *
 * ```kotlin
 * // Direct execution (no sandbox)
 * val executor = ClaudeCodeExecutor()
 *
 * // Sandboxed execution via Docker
 * val sandboxedExecutor = ClaudeCodeExecutor.sandboxed()
 *
 * val result = executor.execute(
 *     prompt = "Add a new function to calculate fibonacci numbers",
 *     workingDirectory = Path.of("/path/to/project"),
 *     allowedTools = listOf(ClaudeCodeAllowedTool.READ, ClaudeCodeAllowedTool.EDIT),
 * )
 * ```
 *
 * ## Permission Modes
 *
 * For fully non-interactive automation (CI/CD, batch processing), use
 * [ClaudeCodePermissionMode.DANGEROUSLY_SKIP_PERMISSIONS] which passes
 * `--dangerously-skip-permissions` to the CLI:
 *
 * ```kotlin
 * val executor = ClaudeCodeExecutor(
 *     defaultPermissionMode = ClaudeCodePermissionMode.DANGEROUSLY_SKIP_PERMISSIONS,
 *     sandboxExecutor = DockerExecutor(image = "...", networkEnabled = false),
 * )
 * ```
 *
 * WARNING: Only use DANGEROUSLY_SKIP_PERMISSIONS in isolated environments with
 * appropriate safeguards (Docker with network isolation, git checkpoints, etc.).
 *
 * @param claudeCommand the command to run Claude Code (defaults to "claude")
 * @param defaultTimeout default timeout for executions
 * @param defaultPermissionMode default permission mode
 * @param environment additional environment variables
 * @param sandboxExecutor optional sandbox executor for isolated execution
 */
class ClaudeCodeExecutor(
    private val claudeCommand: String = "claude",
    private val defaultTimeout: Duration = 10.minutes,
    private val defaultPermissionMode: ClaudeCodePermissionMode = ClaudeCodePermissionMode.ACCEPT_EDITS,
    private val environment: Map<String, String> = emptyMap(),
    private val sandboxExecutor: SandboxedExecutor? = null,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    /**
     * Check if Claude Code CLI is available.
     *
     * @return null if available, or a [ClaudeCodeResult.Denied] with the reason if not
     */
    fun checkAvailability(): ClaudeCodeResult.Denied? {
        // If using sandbox, check sandbox availability
        sandboxExecutor?.let { executor ->
            executor.checkAvailability()?.let { reason ->
                return ClaudeCodeResult.Denied("Sandbox not available: $reason")
            }
        }

        // Check Claude CLI directly (or skip if sandboxed - the sandbox will fail if not available)
        if (sandboxExecutor == null) {
            return try {
                val process = ProcessBuilder(claudeCommand, "--version")
                    .redirectErrorStream(true)
                    .start()

                val completed = process.waitFor(5, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    return ClaudeCodeResult.Denied("Claude Code CLI check timed out")
                }

                if (process.exitValue() != 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    return ClaudeCodeResult.Denied("Claude Code CLI returned error: $output")
                }

                null
            } catch (e: Exception) {
                ClaudeCodeResult.Denied(
                    "Claude Code CLI not available: ${e.message}. " +
                        "Install with: npm install -g @anthropic-ai/claude-code"
                )
            }
        }

        return null
    }

    /**
     * Execute Claude Code with a prompt.
     *
     * @param prompt the task/prompt to execute
     * @param workingDirectory the working directory for execution
     * @param allowedTools list of tools Claude Code is allowed to use (null = all tools)
     * @param maxTurns maximum number of agentic turns before stopping
     * @param permissionMode how to handle permission requests
     * @param timeout maximum execution time
     * @param sessionId optional session ID to resume a previous session
     * @param model optional model to use (e.g., "sonnet", "opus")
     * @param systemPrompt optional system prompt to prepend
     * @param streamOutput if true, log Claude's output as it streams (uses stream-json format)
     * @param streamCallback optional callback to receive streaming events in real-time
     * @return the execution result
     */
    fun execute(
        prompt: String,
        workingDirectory: Path? = null,
        allowedTools: List<ClaudeCodeAllowedTool>? = null,
        maxTurns: Int? = null,
        permissionMode: ClaudeCodePermissionMode = defaultPermissionMode,
        timeout: Duration = defaultTimeout,
        sessionId: String? = null,
        model: String? = null,
        systemPrompt: String? = null,
        streamOutput: Boolean = false,
        streamCallback: ((ClaudeStreamEvent) -> Unit)? = null,
    ): ClaudeCodeResult {
        // Check availability first
        checkAvailability()?.let { return it }

        val command = buildCommand(
            prompt = prompt,
            allowedTools = allowedTools,
            maxTurns = maxTurns,
            permissionMode = permissionMode,
            sessionId = sessionId,
            model = model,
            systemPrompt = systemPrompt,
            streamOutput = streamOutput,
        )

        logger.info(
            "Executing Claude Code: \"{}\" (allowed tools: {}, max turns: {}{})",
            prompt.take(80),
            allowedTools?.size ?: "all",
            maxTurns ?: "unlimited",
            if (streamOutput) ", streaming" else ""
        )

        val result = if (sandboxExecutor != null) {
            executeSandboxed(command, workingDirectory, timeout, streamOutput, streamCallback)
        } else {
            executeDirect(command, workingDirectory, timeout, streamOutput, streamCallback)
        }

        // Log final result - callbacks already invoked during streaming via logStreamLine
        when (result) {
            is ClaudeCodeResult.Success -> {
                logger.info(
                    "Claude Code completed: {} turns, cost \${}, duration {}",
                    result.numTurns,
                    "%.4f".format(result.costUsd),
                    result.duration ?: "unknown"
                )
                // For non-streaming mode, invoke callback with final result
                if (!streamOutput) {
                    streamCallback?.invoke(ClaudeStreamEvent.Text(result.result))
                    streamCallback?.invoke(ClaudeStreamEvent.Complete(result.numTurns, result.costUsd))
                }
            }
            is ClaudeCodeResult.Failure -> {
                logger.warn(
                    "Claude Code failed: {} (timed out: {})",
                    result.error.take(100),
                    result.timedOut
                )
                if (!streamOutput) {
                    streamCallback?.invoke(ClaudeStreamEvent.Error(result.error))
                }
            }
            is ClaudeCodeResult.Denied -> {
                logger.warn(
                    "Claude Code denied: {}",
                    result.reason
                )
                streamCallback?.invoke(ClaudeStreamEvent.Error(result.reason))
            }
        }

        return result
    }

    /**
     * Execute Claude Code asynchronously.
     *
     * This is useful for long-running tasks where you want to:
     * - Monitor progress without blocking
     * - Cancel execution if needed
     * - Run multiple Claude Code instances concurrently
     *
     * @param prompt the task/prompt to execute
     * @param workingDirectory the working directory for execution
     * @param allowedTools list of tools Claude Code is allowed to use (null = all tools)
     * @param maxTurns maximum number of agentic turns before stopping
     * @param permissionMode how to handle permission requests
     * @param timeout maximum execution time
     * @param sessionId optional session ID to resume a previous session
     * @param model optional model to use (e.g., "sonnet", "opus")
     * @param systemPrompt optional system prompt to prepend
     * @param streamOutput if true, log Claude's output as it streams (uses stream-json format)
     * @return a [ClaudeCodeAsyncExecution] handle for monitoring and controlling the execution
     */
    fun executeAsync(
        prompt: String,
        workingDirectory: Path? = null,
        allowedTools: List<ClaudeCodeAllowedTool>? = null,
        maxTurns: Int? = null,
        permissionMode: ClaudeCodePermissionMode = defaultPermissionMode,
        timeout: Duration = defaultTimeout,
        sessionId: String? = null,
        model: String? = null,
        systemPrompt: String? = null,
        streamOutput: Boolean = false,
    ): ClaudeCodeAsyncExecution {
        // Check availability first
        checkAvailability()?.let {
            return ClaudeCodeAsyncExecution.denied(it)
        }

        val command = buildCommand(
            prompt = prompt,
            allowedTools = allowedTools,
            maxTurns = maxTurns,
            permissionMode = permissionMode,
            sessionId = sessionId,
            model = model,
            systemPrompt = systemPrompt,
            streamOutput = streamOutput,
        )

        logger.info(
            "Executing Claude Code (async): \"{}\" (allowed tools: {}, max turns: {}{})",
            prompt.take(80),
            allowedTools?.size ?: "all",
            maxTurns ?: "unlimited",
            if (streamOutput) ", streaming" else ""
        )

        return if (sandboxExecutor != null) {
            executeAsyncSandboxed(command, workingDirectory, timeout)
        } else {
            executeAsyncDirect(command, workingDirectory, timeout)
        }
    }

    private fun executeAsyncSandboxed(
        command: List<String>,
        workingDirectory: Path?,
        timeout: Duration,
    ): ClaudeCodeAsyncExecution {
        val executor = sandboxExecutor
            ?: return ClaudeCodeAsyncExecution.denied(ClaudeCodeResult.Denied("No sandbox executor configured"))

        val request = ExecutionRequest(
            command = command,
            workingDirectory = workingDirectory,
            environment = environment + mapOf("CI" to "true"),
            timeout = timeout,
        )

        val asyncExecution = executor.executeAsync(request)
        return ClaudeCodeAsyncExecution(asyncExecution, timeout) { result ->
            convertExecutionResult(result, timeout)
        }
    }

    private fun executeAsyncDirect(
        command: List<String>,
        workingDirectory: Path?,
        timeout: Duration,
    ): ClaudeCodeAsyncExecution {
        return ClaudeCodeAsyncExecution.direct(command, workingDirectory, environment, timeout) { stdout, exitCode, duration ->
            parseOutput(stdout, exitCode, duration)
        }
    }

    private fun convertExecutionResult(result: ExecutionResult, timeout: Duration): ClaudeCodeResult {
        return when (result) {
            is ExecutionResult.Completed -> parseOutput(result.stdout, result.exitCode, result.duration)
            is ExecutionResult.TimedOut -> ClaudeCodeResult.Failure(
                error = "Execution timed out after $timeout",
                timedOut = true,
                duration = result.duration,
            )
            is ExecutionResult.Failed -> ClaudeCodeResult.Failure(error = result.error)
            is ExecutionResult.Denied -> ClaudeCodeResult.Denied(result.reason)
        }
    }

    /**
     * Execute Claude Code in plan-only mode.
     *
     * This runs Claude Code without allowing any edits, useful for getting
     * a plan of what changes would be made.
     */
    fun plan(
        prompt: String,
        workingDirectory: Path? = null,
        timeout: Duration = defaultTimeout,
    ): ClaudeCodeResult {
        return execute(
            prompt = prompt,
            workingDirectory = workingDirectory,
            allowedTools = listOf(
                ClaudeCodeAllowedTool.READ,
                ClaudeCodeAllowedTool.GLOB,
                ClaudeCodeAllowedTool.GREP,
            ),
            permissionMode = ClaudeCodePermissionMode.PLAN,
            timeout = timeout,
        )
    }

    private fun executeSandboxed(
        command: List<String>,
        workingDirectory: Path?,
        timeout: Duration,
        streamOutput: Boolean,
        streamCallback: ((ClaudeStreamEvent) -> Unit)?,
    ): ClaudeCodeResult {
        val executor = sandboxExecutor ?: return ClaudeCodeResult.Denied("No sandbox executor configured")

        val request = ExecutionRequest(
            command = command,
            workingDirectory = workingDirectory,
            environment = environment + mapOf("CI" to "true"),
            timeout = timeout,
        )

        // Note: Sandboxed execution doesn't support real-time streaming yet,
        // but we still parse stream-json format if requested
        return when (val result = executor.execute(request)) {
            is ExecutionResult.Completed -> {
                if (streamOutput) {
                    // Process each line through the callback for post-hoc streaming
                    if (streamCallback != null) {
                        result.stdout.lines().forEach { line ->
                            logStreamLine(line, streamCallback)
                        }
                    }
                    parseStreamOutput(result.stdout, result.exitCode, result.duration)
                } else {
                    parseOutput(result.stdout, result.exitCode, result.duration)
                }
            }
            is ExecutionResult.TimedOut -> ClaudeCodeResult.Failure(
                error = "Execution timed out after $timeout",
                timedOut = true,
                duration = result.duration,
            )
            is ExecutionResult.Failed -> ClaudeCodeResult.Failure(
                error = result.error,
            )
            is ExecutionResult.Denied -> ClaudeCodeResult.Denied(result.reason)
        }
    }

    private fun executeDirect(
        command: List<String>,
        workingDirectory: Path?,
        timeout: Duration,
        streamOutput: Boolean,
        streamCallback: ((ClaudeStreamEvent) -> Unit)?,
    ): ClaudeCodeResult {
        return try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(false)

            workingDirectory?.let { processBuilder.directory(it.toFile()) }

            val env = processBuilder.environment()
            env.putAll(environment)
            env["CI"] = "true"

            val process = processBuilder.start()
            process.outputStream.close()

            val outputLines = mutableListOf<String>()
            var stderr = ""

            val stdoutThread = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        outputLines.add(line)
                        if (streamOutput) {
                            logStreamLine(line, streamCallback)
                        }
                    }
                }
            }.apply { start() }

            val stderrThread = Thread {
                stderr = process.errorStream.bufferedReader().readText()
            }.apply { start() }

            val completed = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                stdoutThread.join(1000)
                stderrThread.join(1000)
                return ClaudeCodeResult.Failure(
                    error = "Execution timed out after $timeout",
                    stderr = stderr,
                    timedOut = true,
                )
            }

            stdoutThread.join()
            stderrThread.join()

            val stdout = outputLines.joinToString("\n")
            if (streamOutput) {
                parseStreamOutput(stdout, process.exitValue(), null, stderr)
            } else {
                parseOutput(stdout, process.exitValue(), null, stderr)
            }
        } catch (e: Exception) {
            ClaudeCodeResult.Failure(error = e.message ?: "Unknown error")
        }
    }

    /**
     * Log a single line from stream-json output and invoke the callback if provided.
     */
    private fun logStreamLine(line: String, callback: ((ClaudeStreamEvent) -> Unit)?) {
        if (line.isBlank()) return

        try {
            val json = objectMapper.readTree(line)
            val type = json.get("type")?.asText()

            when (type) {
                "assistant" -> {
                    val message = json.get("message")?.get("content")
                    if (message != null && message.isArray && message.size() > 0) {
                        val text = message[0]?.get("text")?.asText()
                        if (!text.isNullOrBlank()) {
                            logger.info("[Claude] {}", text.take(200))
                            callback?.invoke(ClaudeStreamEvent.Text(text))
                        }
                    }
                }
                "user" -> {
                    val message = json.get("message")?.get("content")
                    if (message != null && message.isArray && message.size() > 0) {
                        val firstContent = message[0]
                        val toolResult = firstContent?.get("tool_result")
                        if (toolResult != null) {
                            val toolName = toolResult.get("tool_use_id")?.asText() ?: "tool"
                            logger.info("[Tool Result] {}", toolName)
                            callback?.invoke(ClaudeStreamEvent.ToolResult(toolName))
                        }
                    }
                }
                "result" -> {
                    val subtype = json.get("subtype")?.asText()
                    if (subtype == "success") {
                        val cost = json.get("total_cost_usd")?.asDouble() ?: 0.0
                        val turns = json.get("num_turns")?.asInt() ?: 0
                        logger.info("[Result] Completed: {} turns, cost \${}", turns, "%.4f".format(cost))
                        callback?.invoke(ClaudeStreamEvent.Complete(turns, cost))
                    } else if (subtype == "error") {
                        val error = json.get("result")?.asText() ?: "Unknown error"
                        logger.warn("[Result] Error: {}", error.take(100))
                        callback?.invoke(ClaudeStreamEvent.Error(error))
                    }
                }
                else -> {
                    // Log other message types at debug level
                    logger.debug("[Stream] type={}: {}", type, line.take(100))
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to parse stream line: {}", line.take(100))
        }
    }

    /**
     * Parse stream-json format output and extract the final result.
     */
    private fun parseStreamOutput(stdout: String, exitCode: Int, duration: Duration?, stderr: String = ""): ClaudeCodeResult {
        if (stdout.isBlank()) {
            val errorMsg = if (stderr.isNotBlank()) {
                "Empty output from Claude Code. Stderr: ${stderr.take(500)}"
            } else {
                "Empty output from Claude Code"
            }
            logger.warn("Claude Code returned empty stdout. Exit code: {}, stderr: {}", exitCode, stderr.take(200))
            return ClaudeCodeResult.Failure(
                error = errorMsg,
                exitCode = exitCode,
                duration = duration,
                stderr = stderr.takeIf { it.isNotBlank() },
            )
        }

        // Find the last "result" line which contains the final outcome
        val lines = stdout.lines().filter { it.isNotBlank() }
        var lastResult: ClaudeCodeJsonOutput? = null
        var sessionId: String? = null

        for (line in lines) {
            try {
                val json = objectMapper.readTree(line)
                val type = json.get("type")?.asText()

                if (type == "result") {
                    lastResult = objectMapper.treeToValue(json, ClaudeCodeJsonOutput::class.java)
                }

                // Capture session ID from any message that has it
                json.get("session_id")?.asText()?.let { sessionId = it }
            } catch (e: Exception) {
                // Skip malformed lines
            }
        }

        if (lastResult == null) {
            return ClaudeCodeResult.Failure(
                error = "No result message found in stream output",
                exitCode = exitCode,
                duration = duration,
            )
        }

        return if (lastResult.isError == true || exitCode != 0) {
            ClaudeCodeResult.Failure(
                error = lastResult.result ?: "Unknown error",
                exitCode = exitCode,
                duration = duration,
            )
        } else {
            ClaudeCodeResult.Success(
                result = lastResult.result ?: "",
                sessionId = sessionId ?: lastResult.sessionId,
                costUsd = lastResult.totalCostUsd ?: lastResult.costUsd ?: 0.0,
                duration = lastResult.durationMs?.milliseconds ?: duration,
                numTurns = lastResult.numTurns ?: 0,
            )
        }
    }

    private fun buildCommand(
        prompt: String,
        allowedTools: List<ClaudeCodeAllowedTool>?,
        maxTurns: Int?,
        permissionMode: ClaudeCodePermissionMode,
        sessionId: String?,
        model: String?,
        systemPrompt: String?,
        streamOutput: Boolean = false,
    ): List<String> {
        // stream-json format requires --verbose flag when used with -p (print) mode
        val outputFormat = if (streamOutput) "stream-json" else "json"
        val command = mutableListOf(
            claudeCommand,
            "-p", prompt,
            "--output-format", outputFormat,
        )

        if (streamOutput) {
            command.add("--verbose")
        }

        // Handle permission modes
        when (permissionMode) {
            ClaudeCodePermissionMode.DEFAULT -> {
                // No additional flags needed
            }
            ClaudeCodePermissionMode.DANGEROUSLY_SKIP_PERMISSIONS -> {
                // Use the dedicated flag for full YOLO mode
                // Note: This ignores --permission-mode, so we don't add it
                command.add("--dangerously-skip-permissions")
            }
            else -> {
                command.add("--permission-mode")
                command.add(permissionMode.cliValue)
            }
        }

        if (allowedTools != null && allowedTools.isNotEmpty()) {
            command.add("--allowedTools")
            command.add(allowedTools.joinToString(",") { it.cliName })
        }

        maxTurns?.let {
            command.add("--max-turns")
            command.add(it.toString())
        }

        sessionId?.let {
            command.add("--resume")
            command.add(it)
        }

        model?.let {
            command.add("--model")
            command.add(it)
        }

        systemPrompt?.let {
            command.add("--system-prompt")
            command.add(it)
        }

        return command
    }

    private fun parseOutput(stdout: String, exitCode: Int, duration: Duration?, stderr: String = ""): ClaudeCodeResult {
        if (stdout.isBlank()) {
            val errorMsg = if (stderr.isNotBlank()) {
                "Empty output from Claude Code. Stderr: ${stderr.take(500)}"
            } else {
                "Empty output from Claude Code"
            }
            logger.warn("Claude Code returned empty stdout. Exit code: {}, stderr: {}", exitCode, stderr.take(200))
            return ClaudeCodeResult.Failure(
                error = errorMsg,
                exitCode = exitCode,
                duration = duration,
                stderr = stderr.takeIf { it.isNotBlank() },
            )
        }

        return try {
            val jsonOutput = objectMapper.readValue<ClaudeCodeJsonOutput>(stdout)

            if (jsonOutput.isError == true || exitCode != 0) {
                ClaudeCodeResult.Failure(
                    error = jsonOutput.result ?: "Unknown error",
                    exitCode = exitCode,
                    duration = duration,
                )
            } else {
                ClaudeCodeResult.Success(
                    result = jsonOutput.result ?: "",
                    sessionId = jsonOutput.sessionId,
                    costUsd = jsonOutput.totalCostUsd ?: jsonOutput.costUsd ?: 0.0,
                    duration = jsonOutput.durationMs?.milliseconds ?: duration,
                    numTurns = jsonOutput.numTurns ?: 0,
                )
            }
        } catch (e: Exception) {
            logger.debug("Failed to parse JSON output, treating as plain text: {}", e.message)

            if (exitCode == 0) {
                ClaudeCodeResult.Success(
                    result = stdout,
                    duration = duration,
                )
            } else {
                ClaudeCodeResult.Failure(
                    error = "Failed to parse output: ${e.message}",
                    exitCode = exitCode,
                    stderr = stdout,
                    duration = duration,
                )
            }
        }
    }

    companion object {
        /**
         * Create a sandboxed executor using Docker.
         *
         * The Docker image must have Claude Code CLI installed.
         * You can use an image like:
         * ```dockerfile
         * FROM node:20-slim
         * RUN npm install -g @anthropic-ai/claude-code
         * ```
         *
         * @param image Docker image with Claude Code installed
         * @param timeout default timeout
         */
        fun sandboxed(
            image: String = "embabel/claude-code-sandbox:latest",
            timeout: Duration = 10.minutes,
        ): ClaudeCodeExecutor {
            return ClaudeCodeExecutor(
                defaultTimeout = timeout,
                sandboxExecutor = DockerExecutor(
                    image = image,
                    networkEnabled = true, // Needed for Anthropic API
                    memoryLimit = "2g",
                    cpuLimit = "2.0",
                ),
            )
        }

        /**
         * Create a sandboxed executor with a custom sandbox.
         */
        fun withSandbox(
            sandbox: SandboxedExecutor,
            timeout: Duration = 10.minutes,
        ): ClaudeCodeExecutor {
            return ClaudeCodeExecutor(
                defaultTimeout = timeout,
                sandboxExecutor = sandbox,
            )
        }
    }
}
