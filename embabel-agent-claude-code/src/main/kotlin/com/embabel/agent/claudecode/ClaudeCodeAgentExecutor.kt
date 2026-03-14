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

import com.embabel.agent.executor.AgentExecutor
import com.embabel.agent.mcp.EphemeralMcpToolServer
import com.embabel.agent.executor.AgentRequest
import com.embabel.agent.executor.TypedResult
import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import com.embabel.agent.sandbox.SandboxConfig
import com.embabel.agent.sandbox.SandboxedExecutor
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
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
@JsonTypeName("agent-executor")
class ClaudeCodeAgentExecutor(
    override val name: String = "claude-code",
    override val description: String = "Execute a coding task using Claude Code CLI",
    override val prompt: String = "{{userInput}}",
    override val inputTypeNames: Set<String> = emptySet(),
    override val outputTypeName: String = "String",
    private val claudeCommand: String = "claude",
    private val defaultTimeout: Duration = 10.minutes,
    private val defaultPermissionMode: ClaudeCodePermissionMode = ClaudeCodePermissionMode.ACCEPT_EDITS,
    private val environment: Map<String, String> = emptyMap(),
    /**
     * Sandbox configuration. When [SandboxConfig.enabled] is true,
     * Claude Code runs inside a Docker container with configurable
     * resource limits and environment variable propagation.
     */
    val sandbox: SandboxConfig = SandboxConfig(),
) : AgentExecutor {

    @JsonIgnore
    private val sandboxExecutor: SandboxedExecutor? = sandbox.createExecutor()

    override fun isSandboxed(): Boolean = sandboxExecutor != null

    @JsonIgnore
    private val logger = LoggerFactory.getLogger(javaClass)
    @JsonIgnore
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
        mcpConfig: String? = null,
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
            mcpConfig = mcpConfig,
        )

        logger.info(
            "Executing Claude Code: \"{}\" (allowed tools: {}, max turns: {}{}, sandbox: {})",
            prompt.take(80),
            allowedTools?.size ?: "all",
            maxTurns ?: "unlimited",
            if (streamOutput) ", streaming" else "",
            if (sandboxExecutor != null) "docker" else "none",
        )

        val result = if (sandboxExecutor != null) {
            executeSandboxed(command, workingDirectory, timeout, streamOutput, streamCallback)
        } else if (sandbox.enabled) {
            // Sandbox was requested but is not available (e.g. Docker not running).
            // NEVER fall back to unsandboxed execution — this would run Claude Code
            // directly on the host with full filesystem access.
            val msg = "Sandbox is enabled but not available. Refusing to run unsandboxed. " +
                "Ensure Docker is running and the image '${sandbox.image}' is available."
            logger.error(msg)
            return ClaudeCodeResult.Denied(msg)
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
        mcpConfig: String? = null,
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
            mcpConfig = mcpConfig,
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
        } else if (sandbox.enabled) {
            val msg = "Sandbox is enabled but not available. Refusing to run unsandboxed. " +
                "Ensure Docker is running and the image '${sandbox.image}' is available."
            logger.error(msg)
            ClaudeCodeAsyncExecution.denied(ClaudeCodeResult.Denied(msg))
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

        // When streaming, pipe each stdout line through logStreamLine in real-time
        // via the ExecutionRequest's stdoutCallback. DockerExecutor reads stdout
        // line-by-line in a thread, so the callback fires as Docker produces output.
        val stdoutLineCallback: ((String) -> Unit)? = if (streamOutput && streamCallback != null) {
            { line -> logStreamLine(line, streamCallback) }
        } else null

        val request = ExecutionRequest(
            command = command,
            workingDirectory = workingDirectory,
            environment = environment + mapOf("CI" to "true"),
            timeout = timeout,
            stdoutCallback = stdoutLineCallback,
        )

        return when (val result = executor.execute(request)) {
            is ExecutionResult.Completed -> {
                if (streamOutput) {
                    // If no real-time callback was used, process post-hoc
                    if (stdoutLineCallback == null && streamCallback != null) {
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

    internal fun buildCommand(
        prompt: String,
        allowedTools: List<ClaudeCodeAllowedTool>?,
        maxTurns: Int?,
        permissionMode: ClaudeCodePermissionMode,
        sessionId: String?,
        model: String?,
        systemPrompt: String?,
        streamOutput: Boolean = false,
        mcpConfig: String? = null,
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

        mcpConfig?.let { config ->
            // Write MCP config to a temp file since it may exceed CLI arg limits
            val configFile = Files.createTempFile("mcp-config-", ".json")
            Files.writeString(configFile, config)
            configFile.toFile().deleteOnExit()
            command.add("--mcp-config")
            command.add(configFile.toAbsolutePath().toString())
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

    override fun <T : Any> executeTyped(request: AgentRequest<T>): TypedResult<T> =
        executeTyped(request = request, workingDirectory = null)

    /**
     * Execute a typed request with fitness evaluation and optional retry.
     *
     * This method handles the full flow:
     * 1. If tools are provided, starts an [EphemeralMcpToolServer]
     * 2. Builds a system prompt instructing Claude to return JSON matching the output type
     * 3. Calls [execute] with the prompt
     * 4. Parses the result as JSON and deserializes to the output type
     * 5. Evaluates fitness; retries if below threshold and retries remain
     * 6. Returns the best attempt (highest score)
     *
     * @param request the typed agent request
     * @param workingDirectory the working directory for execution
     * @param allowedTools list of tools Claude Code is allowed to use
     * @param maxTurns maximum number of agentic turns
     * @param permissionMode how to handle permission requests
     * @param timeout maximum execution time
     * @param model optional model to use
     */
    fun <T : Any> executeTyped(
        request: AgentRequest<T>,
        workingDirectory: Path? = null,
        allowedTools: List<ClaudeCodeAllowedTool>? = null,
        maxTurns: Int? = null,
        permissionMode: ClaudeCodePermissionMode = defaultPermissionMode,
        timeout: Duration = defaultTimeout,
        model: String? = null,
    ): TypedResult<T> {
        var mcpServer: EphemeralMcpToolServer? = null

        try {
            // Start ephemeral MCP server if tools are provided
            val mcpConfig = if (request.tools.isNotEmpty()) {
                mcpServer = EphemeralMcpToolServer(request.tools)
                mcpServer.toMcpConfigJson()
            } else {
                null
            }

            // Build system prompt for structured JSON output
            val jsonSchema = generateJsonSchema(request.outputClass)
            val systemPrompt = buildString {
                appendLine("You MUST respond with valid JSON matching this schema:")
                appendLine(jsonSchema)
                appendLine()
                appendLine("Return ONLY the JSON object, no markdown fencing, no explanation.")
            }

            var bestResult: TypedResult.Success<T>? = null
            val maxAttempts = 1 + request.maxRetries

            for (attempt in 1..maxAttempts) {
                val prompt = if (attempt == 1) {
                    request.prompt()
                } else {
                    val feedback = buildString {
                        appendLine(request.prompt())
                        appendLine()
                        appendLine("Previous attempt scored ${bestResult?.score ?: 0.0}. Please improve your response.")
                    }
                    feedback
                }

                val rawCallback = request.streamCallback
                val streamCallback: ((ClaudeStreamEvent) -> Unit)? = rawCallback?.let { cb -> { event -> cb(event) } }
                val result = execute(
                    prompt = prompt,
                    workingDirectory = workingDirectory,
                    allowedTools = allowedTools,
                    maxTurns = maxTurns,
                    permissionMode = permissionMode,
                    timeout = timeout,
                    model = model,
                    systemPrompt = systemPrompt,
                    mcpConfig = mcpConfig,
                    streamOutput = rawCallback != null,
                    streamCallback = streamCallback,
                )

                if (result !is ClaudeCodeResult.Success) {
                    logger.warn("Typed execution attempt {}/{} failed: {}", attempt, maxAttempts, result)
                    if (bestResult != null) continue
                    return TypedResult.Failure(
                        error = when (result) {
                            is ClaudeCodeResult.Failure -> result.error
                            is ClaudeCodeResult.Denied -> result.reason
                            else -> "Unknown failure"
                        },
                        raw = result,
                    )
                }

                // Parse JSON from the response
                val parsed = try {
                    extractAndParse(result.result, request.outputClass)
                } catch (e: Exception) {
                    logger.warn("Typed execution attempt {}/{}: JSON parse failed: {}", attempt, maxAttempts, e.message)
                    continue
                }

                // Evaluate fitness
                val score = request.fitnessFunction(parsed)
                logger.info("Typed execution attempt {}/{}: fitness score = {}", attempt, maxAttempts, score)

                val candidate = TypedResult.Success(
                    value = parsed,
                    score = score,
                    attempts = attempt,
                    raw = result,
                )

                if (bestResult == null || score > bestResult.score) {
                    bestResult = candidate
                }

                if (score >= request.fitnessThreshold) {
                    return bestResult
                }
            }

            // Return best attempt if we have one
            return bestResult ?: TypedResult.Failure(error = "All $maxAttempts attempts failed to produce parseable output")
        } finally {
            mcpServer?.close()
        }
    }

    /**
     * Extract JSON from a Claude response that may contain markdown fencing or extra text.
     */
    internal fun <T> extractAndParse(text: String, clazz: Class<T>): T {
        val json = extractJson(text)
        return objectMapper.readValue(json, clazz)
    }

    /**
     * Extract a JSON object or array from text, stripping markdown fencing if present.
     */
    internal fun extractJson(text: String): String {
        // Try stripping markdown code fences first
        val fencePattern = Regex("```(?:json)?\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        val fenceMatch = fencePattern.find(text)
        if (fenceMatch != null) {
            return fenceMatch.groupValues[1].trim()
        }

        // Try to find raw JSON object or array
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed
        }

        // Find first { or [ and last } or ]
        val start = trimmed.indexOfFirst { it == '{' || it == '[' }
        val end = trimmed.indexOfLast { it == '}' || it == ']' }
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }

        return trimmed
    }

    internal fun generateJsonSchema(clazz: Class<*>): String {
        if (clazz == String::class.java) {
            return """{"type": "string"}"""
        }
        return try {
            val config = objectMapper.serializationConfig
            val javaType = objectMapper.constructType(clazz)
            val beanDesc = config.introspect(javaType)
            val properties = mutableMapOf<String, Any>()
            val required = mutableListOf<String>()

            for (propDef in beanDesc.findProperties()) {
                val name = propDef.name
                val propType = propDef.primaryType
                val typeStr = when {
                    propType.isTypeOrSubTypeOf(String::class.java) -> "string"
                    propType.isTypeOrSubTypeOf(Int::class.java) ||
                        propType.isTypeOrSubTypeOf(Long::class.java) ||
                        propType.isTypeOrSubTypeOf(java.lang.Integer::class.java) ||
                        propType.isTypeOrSubTypeOf(java.lang.Long::class.java) -> "integer"
                    propType.isTypeOrSubTypeOf(Double::class.java) ||
                        propType.isTypeOrSubTypeOf(Float::class.java) ||
                        propType.isTypeOrSubTypeOf(java.lang.Double::class.java) ||
                        propType.isTypeOrSubTypeOf(java.lang.Float::class.java) -> "number"
                    propType.isTypeOrSubTypeOf(Boolean::class.java) ||
                        propType.isTypeOrSubTypeOf(java.lang.Boolean::class.java) -> "boolean"
                    propType.isCollectionLikeType || propType.isArrayType -> "array"
                    else -> "object"
                }
                properties[name] = mapOf("type" to typeStr)
                required.add(name)
            }

            val schema = mutableMapOf<String, Any>("type" to "object", "properties" to properties)
            if (required.isNotEmpty()) {
                schema["required"] = required
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema)
        } catch (_: Exception) {
            """{"type": "object"}"""
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
        ): ClaudeCodeAgentExecutor {
            return ClaudeCodeAgentExecutor(
                defaultTimeout = timeout,
                sandbox = SandboxConfig(
                    enabled = true,
                    image = image,
                ),
            )
        }

        /**
         * Create a sandboxed executor with a custom [SandboxConfig].
         */
        fun withSandbox(
            config: SandboxConfig,
            timeout: Duration = 10.minutes,
        ): ClaudeCodeAgentExecutor {
            return ClaudeCodeAgentExecutor(
                defaultTimeout = timeout,
                sandbox = config,
            )
        }
    }
}
