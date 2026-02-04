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

import com.embabel.agent.skills.sandbox.DockerExecutor
import com.embabel.agent.skills.sandbox.ExecutionRequest
import com.embabel.agent.skills.sandbox.ExecutionResult
import com.embabel.agent.skills.sandbox.ProcessExecutor
import com.embabel.agent.skills.sandbox.SandboxedExecutor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
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
        )

        logger.debug(
            "Executing Claude Code: {} with {} allowed tools, max turns: {}",
            prompt.take(100),
            allowedTools?.size ?: "all",
            maxTurns ?: "unlimited"
        )

        return if (sandboxExecutor != null) {
            executeSandboxed(command, workingDirectory, timeout)
        } else {
            executeDirect(command, workingDirectory, timeout)
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
    ): ClaudeCodeResult {
        val executor = sandboxExecutor ?: return ClaudeCodeResult.Denied("No sandbox executor configured")

        val request = ExecutionRequest(
            command = command,
            workingDirectory = workingDirectory,
            environment = environment + mapOf("CI" to "true"),
            timeout = timeout,
        )

        return when (val result = executor.execute(request)) {
            is ExecutionResult.Completed -> parseOutput(result.stdout, result.exitCode, result.duration)
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

            var stdout = ""
            var stderr = ""

            val stdoutThread = Thread {
                stdout = process.inputStream.bufferedReader().readText()
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

            parseOutput(stdout, process.exitValue(), null)
        } catch (e: Exception) {
            ClaudeCodeResult.Failure(error = e.message ?: "Unknown error")
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
    ): List<String> {
        val command = mutableListOf(
            claudeCommand,
            "-p", prompt,
            "--output-format", "json",
        )

        if (permissionMode != ClaudeCodePermissionMode.DEFAULT) {
            command.add("--permission-mode")
            command.add(permissionMode.cliValue)
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

    private fun parseOutput(stdout: String, exitCode: Int, duration: Duration?): ClaudeCodeResult {
        if (stdout.isBlank()) {
            return ClaudeCodeResult.Failure(
                error = "Empty output from Claude Code",
                exitCode = exitCode,
                duration = duration,
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
