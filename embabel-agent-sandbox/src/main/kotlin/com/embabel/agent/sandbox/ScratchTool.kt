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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A [Tool] that exposes a persistent [SandboxSession] to an LLM as a bash-like
 * REPL. The session stays alive between calls so the LLM can build up state
 * (install packages, create files, run scripts) across multiple tool invocations.
 *
 * The tool owns its session: the session is created lazily on first [call] via
 * [sessionManager] and destroyed when [close] is invoked. All container lifecycle
 * (create, exec, pause/resume, destroy) is delegated to the [SandboxSession]
 * abstraction — this class does not call `docker` directly.
 *
 * ## Example
 *
 * ```kotlin
 * val scratch = ScratchTool(
 *     sessionManager = DockerSandboxSessionManager(),
 *     config = SandboxConfig(enabled = true, image = ScratchTool.DEFAULT_IMAGE),
 *     owner = "alice",
 * )
 *
 * promptRunner.withTool(scratch)
 *     .generateText("Install numpy and compute the eigenvalues of [[1,2],[3,4]]")
 *
 * scratch.close() // destroys the underlying session
 * ```
 *
 * @param sessionManager manager that owns the underlying session
 * @param config sandbox configuration (image, resources, env)
 * @param owner optional owner identifier passed through to the session
 * @param ttl idle TTL for the session
 * @param name tool name visible to the LLM
 * @param description tool description visible to the LLM
 * @param shell shell used to interpret commands inside the container
 * @param timeout per-command execution timeout
 */
class ScratchTool(
    private val sessionManager: SandboxSessionManager,
    private val config: SandboxConfig = SandboxConfig(enabled = true, image = DEFAULT_IMAGE, memory = "1g", cpus = "2.0"),
    private val owner: String? = null,
    private val ttl: Duration = 1.hours,
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
    private val timeout: Duration = 60.seconds,
) : Tool, AutoCloseable {

    private val logger = LoggerFactory.getLogger(ScratchTool::class.java)
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var session: SandboxSession? = null

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

            val command = wrapIfCode(rawCommand)
            val active = ensureSession()
            renderResult(active.execute(ExecutionRequest(
                command = listOf(shell, "-c", command),
                stdin = stdin,
                timeout = timeout,
            )))
        } catch (e: Exception) {
            logger.warn("Scratch command failed: {}", e.message)
            Tool.Result.error("Command failed: ${e.message}")
        }
    }

    private fun ensureSession(): SandboxSession = synchronized(this) {
        val current = session
        when (current?.state) {
            SandboxSession.SessionState.ACTIVE -> current
            SandboxSession.SessionState.PAUSED -> {
                logger.info("Resuming paused scratch session {}", current.id)
                current.resume()
                current
            }
            SandboxSession.SessionState.CLOSED, null -> {
                if (current != null) {
                    logger.info("Previous scratch session {} was closed — creating a new one", current.id)
                }
                sessionManager.create(
                    label = definition.name,
                    config = config,
                    owner = owner,
                    ttl = ttl,
                ).also { session = it }
            }
        }
    }

    private fun renderResult(result: ExecutionResult): Tool.Result = when (result) {
        is ExecutionResult.Completed -> {
            val combined = buildString {
                append(result.stdout)
                if (result.stderr.isNotEmpty()) {
                    if (isNotEmpty() && !endsWith("\n")) append("\n")
                    append(result.stderr)
                }
            }
            if (result.success) {
                Tool.Result.text(combined.ifBlank { "(no output)" })
            } else {
                Tool.Result.text("Exit code ${result.exitCode}:\n$combined")
            }
        }
        is ExecutionResult.TimedOut -> Tool.Result.text("Command timed out after ${timeout.inWholeSeconds}s")
        is ExecutionResult.Failed -> Tool.Result.error("Command failed: ${result.error}")
        is ExecutionResult.Denied -> Tool.Result.error("Command denied: ${result.reason}")
    }

    override fun close() {
        val s = session ?: return
        try {
            s.close()
        } catch (e: Exception) {
            logger.warn("Failed to close scratch session {}: {}", s.id, e.message)
        }
        session = null
    }

    /**
     * Detect when the LLM passes raw Python or JavaScript code instead of
     * a bash command, and auto-wrap it so it actually runs.
     */
    private fun wrapIfCode(command: String): String {
        val trimmed = command.trim()

        val pythonIndicators = listOf("import ", "from ", "def ", "class ", "print(", "numpy", "pandas")
        if (pythonIndicators.any { trimmed.startsWith(it) || trimmed.contains("\n$it") }) {
            if (!trimmed.startsWith("python")) {
                logger.debug("Auto-wrapping as Python script")
                val escaped = trimmed.replace("'", "'\\''")
                return "python3 -c '$escaped'"
            }
        }

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
    }
}
