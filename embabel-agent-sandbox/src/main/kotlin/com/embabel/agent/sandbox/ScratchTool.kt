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
package com.embabel.agent.sandbox

import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [Tool] that provides a persistent Docker sandbox for running arbitrary
 * commands. The sandbox container stays alive between calls, allowing
 * the LLM to build up state (install packages, create files, run scripts)
 * across multiple tool invocations.
 *
 * Unlike [DockerExecutor] which creates a fresh container per execution,
 * ScratchTool maintains a long-lived container that the LLM can interact
 * with like a terminal session.
 *
 * ## Example usage in an agent
 *
 * ```kotlin
 * val scratch = ScratchTool(image = "python:3.11-slim")
 *
 * // Add to agent tools
 * promptRunner.withTool(scratch)
 *     .generateText("Install numpy and compute the eigenvalues of [[1,2],[3,4]]")
 *
 * // The LLM will call scratch_run multiple times:
 * // 1. pip install numpy
 * // 2. python3 -c "import numpy as np; print(np.linalg.eigvals([[1,2],[3,4]]))"
 *
 * // Clean up when done
 * scratch.close()
 * ```
 *
 * @param image Docker image to use for the sandbox
 * @param name Tool name visible to the LLM
 * @param description Tool description visible to the LLM
 * @param networkEnabled Whether the sandbox can access the network
 * @param memoryLimit Memory limit (e.g., "512m", "2g")
 * @param cpuLimit CPU limit (e.g., "1.0")
 * @param timeout Default timeout per command
 * @param environment Environment variables available in the sandbox
 */
class ScratchTool(
    private val image: String = DEFAULT_IMAGE,
    name: String = "scratch_run",
    description: String = """
        Run a bash command in a persistent Docker sandbox with Python 3, Node.js, Java,
        Perl, SQLite, Graphviz, ImageMagick, numpy, pandas, and common utilities.
        State persists between calls — files, packages, and environment carry over.
        For Python: python3 -c 'code' or write a script file then run it.
        For Node: node -e 'code'. For multi-line code, write to a file first.
        If a command fails, diagnose the error and try again — DO NOT give up.
        Install missing packages if needed (pip install, apt-get, npm install).
    """.trimIndent(),
    private val shell: String = "bash",
    private val networkEnabled: Boolean = true,
    private val memoryLimit: String? = "1g",
    private val cpuLimit: String? = "2.0",
    private val timeout: Duration = 60.seconds,
    private val environment: Map<String, String> = emptyMap(),
) : Tool, AutoCloseable {

    private val logger = LoggerFactory.getLogger(ScratchTool::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var containerId: String? = null

    override val definition = Tool.Definition(
        name = name,
        description = description,
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string(
                "command",
                "The bash command to run in the sandbox. Can be multi-line. " +
                    "Examples: 'pip install pandas', 'python3 script.py', 'ls -la /workspace'",
            ),
            Tool.Parameter.string(
                "stdin",
                "Optional input to pipe to the command's stdin",
                required = false,
            ),
        ),
    )

    override fun call(input: String): Tool.Result {
        return try {
            @Suppress("UNCHECKED_CAST")
            val params = objectMapper.readValue(input, Map::class.java) as Map<String, Any?>
            val rawCommand = params["command"] as? String
                ?: return Tool.Result.error("'command' parameter is required")
            val stdin = params["stdin"] as? String

            // Auto-detect raw Python/JS code and wrap it
            val command = wrapIfCode(rawCommand)

            ensureContainer()
            val cid = containerId ?: return Tool.Result.error("Failed to start sandbox container")

            val result = executeInContainer(cid, command, stdin)
            Tool.Result.text(result)
        } catch (e: Exception) {
            logger.warn("Scratch command failed: {}", e.message)
            Tool.Result.error("Command failed: ${e.message}")
        }
    }

    private fun ensureContainer() {
        if (containerId != null) return
        synchronized(this) {
            if (containerId != null) return

            logger.info("Starting scratch sandbox (image={})", image)

            val cmd = mutableListOf(
                "docker", "run", "-d",
                "--name", "scratch-${System.currentTimeMillis()}",
            )

            memoryLimit?.let { cmd.addAll(listOf("--memory", it)) }
            cpuLimit?.let { cmd.addAll(listOf("--cpus", it)) }
            if (!networkEnabled) cmd.addAll(listOf("--network", "none"))

            for ((key, value) in environment) {
                cmd.addAll(listOf("-e", "$key=$value"))
            }

            cmd.addAll(listOf(image, "sleep", "infinity"))

            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw RuntimeException("Failed to start sandbox container: $output")
            }

            containerId = output.take(12)
            logger.info("Scratch sandbox started: {}", containerId)
        }
    }

    private fun executeInContainer(cid: String, command: String, stdin: String?): String {
        val execCmd = mutableListOf("docker", "exec")
        if (stdin != null) execCmd.add("-i")
        execCmd.addAll(listOf(cid, shell, "-c", command))

        logger.debug("Scratch exec: {}", command.take(100))

        val process = ProcessBuilder(execCmd)
            .redirectErrorStream(true)
            .start()

        if (stdin != null) {
            process.outputStream.bufferedWriter().use { it.write(stdin) }
        } else {
            process.outputStream.close()
        }

        val completed = process.waitFor(timeout.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            return "Command timed out after ${timeout.inWholeSeconds}s"
        }

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.exitValue()

        return if (exitCode == 0) {
            output.ifBlank { "(no output)" }
        } else {
            "Exit code $exitCode:\n$output"
        }
    }

    override fun close() {
        val cid = containerId ?: return
        try {
            logger.info("Stopping scratch sandbox: {}", cid)
            ProcessBuilder("docker", "rm", "-f", cid)
                .redirectErrorStream(true)
                .start()
                .waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            containerId = null
        } catch (e: Exception) {
            logger.warn("Failed to stop sandbox {}: {}", cid, e.message)
        }
    }

    /**
     * Detect when the LLM passes raw Python or JavaScript code instead of
     * a bash command, and auto-wrap it so it actually runs.
     */
    private fun wrapIfCode(command: String): String {
        val trimmed = command.trim()

        // Looks like Python (import, def, print, variable assignment with no bash syntax)
        val pythonIndicators = listOf("import ", "from ", "def ", "class ", "print(", "numpy", "pandas")
        if (pythonIndicators.any { trimmed.startsWith(it) || trimmed.contains("\n$it") }) {
            if (!trimmed.startsWith("python")) {
                logger.debug("Auto-wrapping as Python script")
                val escaped = trimmed.replace("'", "'\\''")
                return "python3 -c '$escaped'"
            }
        }

        // Looks like JavaScript (const, let, var, console.log, require)
        val jsIndicators = listOf("const ", "let ", "var ", "console.log", "require(")
        if (jsIndicators.any { trimmed.startsWith(it) || trimmed.contains("\n$it") }) {
            if (!trimmed.startsWith("node")) {
                logger.debug("Auto-wrapping as Node.js script")
                val escaped = trimmed.replace("'", "'\\''")
                return "node -e '$escaped'"
            }
        }

        return command
    }

    companion object {

        /**
         * Default sandbox image with Python, Node.js, Java, Perl, SQLite,
         * Graphviz, ImageMagick, and common data science packages.
         */
        const val DEFAULT_IMAGE = "embabel/agent-sandbox:latest"

        /**
         * Create a scratch tool with the default batteries-included image.
         */
        fun default(
            networkEnabled: Boolean = true,
        ) = ScratchTool(
            image = DEFAULT_IMAGE,
            networkEnabled = networkEnabled,
        )

        /**
         * Create a scratch tool with a minimal Python image.
         */
        fun python(
            networkEnabled: Boolean = true,
        ) = ScratchTool(
            image = "python:3.11-slim",
            name = "python_scratch",
            description = "Run Python code or commands in an isolated sandbox with Python 3.11. " +
                "State persists between calls.",
            shell = "bash",
            networkEnabled = networkEnabled,
        )

        /**
         * Create a scratch tool with a minimal Node.js image.
         */
        fun node(
            networkEnabled: Boolean = true,
        ) = ScratchTool(
            image = "node:20-slim",
            name = "node_scratch",
            description = "Run JavaScript/Node.js code or commands in an isolated sandbox with Node 20. " +
                "State persists between calls.",
            shell = "bash",
            networkEnabled = networkEnabled,
        )

        /**
         * Create a scratch tool with a custom image.
         */
        fun custom(
            image: String,
            shell: String = "sh",
            networkEnabled: Boolean = true,
        ) = ScratchTool(
            image = image,
            shell = shell,
            networkEnabled = networkEnabled,
        )
    }
}
