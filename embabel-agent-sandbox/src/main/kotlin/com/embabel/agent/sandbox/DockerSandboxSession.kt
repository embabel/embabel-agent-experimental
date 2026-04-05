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

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * Docker-backed [SandboxSession] that maintains a long-lived container.
 *
 * The container is created with `docker create` and started with `docker start`.
 * Commands are executed via `docker exec`. State (files, packages, env) persists
 * across executions. The container can be paused (`docker pause`) and resumed
 * (`docker unpause`) without losing any state including tmpfs mounts.
 *
 * @param label human-readable session label
 * @param config sandbox configuration
 * @param owner optional owner identifier
 * @param ttl time-to-live for idle eviction
 */
class DockerSandboxSession(
    override val label: String,
    override val config: SandboxConfig,
    override val owner: String? = null,
    val ttl: Duration,
) : SandboxSession {

    private val logger = LoggerFactory.getLogger(DockerSandboxSession::class.java)

    override val id: String = UUID.randomUUID().toString().take(12)

    override val createdAt: Instant = Instant.now()

    @Volatile
    override var lastActiveAt: Instant = Instant.now()
        private set

    @Volatile
    override var state: SandboxSession.SessionState = SandboxSession.SessionState.ACTIVE
        private set

    /** The Docker container ID (short form). */
    @Volatile
    var containerId: String? = null
        private set

    private val containerName = "sandbox-session-$id"

    init {
        startContainer()
    }

    private fun startContainer() {
        val cmd = mutableListOf(
            "docker", "create",
            "--name", containerName,
        )

        config.memory.let { cmd.addAll(listOf("--memory", it)) }
        config.cpus.let { cmd.addAll(listOf("--cpus", it)) }
        if (!config.network) cmd.addAll(listOf("--network", "none"))

        // Resolve and add environment variables
        for (key in config.propagateEnv) {
            System.getenv(key)?.let { cmd.addAll(listOf("-e", "$key=$it")) }
        }
        for ((containerKey, hostKey) in config.mapEnv) {
            System.getenv(hostKey)?.let { cmd.addAll(listOf("-e", "$containerKey=$it")) }
        }

        cmd.addAll(listOf(config.image, "sleep", "infinity"))

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            state = SandboxSession.SessionState.CLOSED
            throw RuntimeException("Failed to create sandbox container: $output")
        }

        containerId = output.take(12)

        // Start the container
        val startProcess = ProcessBuilder("docker", "start", containerId!!)
            .redirectErrorStream(true)
            .start()
        val startOutput = startProcess.inputStream.bufferedReader().readText().trim()
        val startExit = startProcess.waitFor()

        if (startExit != 0) {
            state = SandboxSession.SessionState.CLOSED
            throw RuntimeException("Failed to start sandbox container: $startOutput")
        }

        logger.info("Sandbox session '{}' ({}) started: container={}", label, id, containerId)
    }

    override fun execute(request: ExecutionRequest): ExecutionResult {
        check(state == SandboxSession.SessionState.ACTIVE) {
            "Cannot execute in session '$id' with state $state"
        }

        val cid = containerId ?: return ExecutionResult.Failed("No container ID")

        val execCmd = mutableListOf("docker", "exec")
        if (request.stdin != null) execCmd.add("-i")

        // Set working directory if specified
        request.workingDirectory?.let {
            execCmd.addAll(listOf("-w", it.toString()))
        }

        // Add request-specific environment variables
        for ((key, value) in request.environment) {
            execCmd.addAll(listOf("-e", "$key=$value"))
        }

        execCmd.add(cid)
        execCmd.addAll(request.command)

        logger.debug("Session '{}' exec: {}", id, request.command.joinToString(" ").take(100))

        return try {
            val processBuilder = ProcessBuilder(execCmd)
                .redirectErrorStream(false)

            val process = processBuilder.start()

            // Write stdin if provided
            if (request.stdin != null) {
                process.outputStream.bufferedWriter().use { it.write(request.stdin) }
            } else {
                process.outputStream.close()
            }

            // Read stdout with optional callback
            val outputLines = mutableListOf<String>()
            val stdoutThread = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { rawLine ->
                            val line = rawLine.trimEnd('\r')
                            outputLines.add(line)
                            request.stdoutCallback?.invoke(line)
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }

            var stderr = ""
            val stderrThread = Thread {
                stderr = process.errorStream.bufferedReader().readText()
            }.apply { isDaemon = true; start() }

            val completed: Boolean
            val duration = measureTime {
                completed = process.waitFor(
                    request.timeout.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )
            }

            if (!completed) {
                process.destroyForcibly()
                stdoutThread.join(1000)
                stderrThread.join(1000)
                lastActiveAt = Instant.now()
                return ExecutionResult.TimedOut(
                    partialStderr = stderr.takeIf { it.isNotBlank() },
                    duration = duration,
                )
            }

            stdoutThread.join()
            stderrThread.join()

            lastActiveAt = Instant.now()
            ExecutionResult.Completed(
                exitCode = process.exitValue(),
                stdout = outputLines.joinToString("\n"),
                stderr = stderr,
                duration = duration,
                artifacts = emptyList(),
            )
        } catch (e: Exception) {
            logger.error("Session '{}' execution failed: {}", id, e.message, e)
            ExecutionResult.Failed(error = "Execution failed: ${e.message}", cause = e)
        }
    }

    override fun copyFrom(containerPath: String, hostPath: Path) {
        val cid = containerId ?: throw IllegalStateException("No container")
        val process = ProcessBuilder("docker", "cp", "$cid:$containerPath", hostPath.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw RuntimeException("docker cp failed: $output")
        }
    }

    override fun copyTo(hostPath: Path, containerPath: String) {
        val cid = containerId ?: throw IllegalStateException("No container")
        // Ensure target directory exists
        ProcessBuilder("docker", "exec", cid, "mkdir", "-p", containerPath)
            .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
        // Append /. to copy directory CONTENTS, not the directory itself
        val source = if (hostPath.toFile().isDirectory) "${hostPath}/." else hostPath.toString()
        val process = ProcessBuilder("docker", "cp", source, "$cid:$containerPath")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw RuntimeException("docker cp failed: $output")
        }
    }

    /**
     * Pauses the container using `docker pause`.
     *
     * Uses `docker pause` (not `docker stop`) to freeze processes:
     *
     * ```
     * ┌──────────────────────┬──────────────────┬───────────┬────────────┬──────────────┐
     * │       Command        │    Processes     │  Memory   │ Filesystem │ /tmp (tmpfs) │
     * ├──────────────────────┼──────────────────┼───────────┼────────────┼──────────────┤
     * │ docker stop/start    │ Killed/Restarted │ Lost      │ Preserved  │ Cleared      │
     * ├──────────────────────┼──────────────────┼───────────┼────────────┼──────────────┤
     * │ docker pause/unpause │ Frozen/Unfrozen  │ Preserved │ Preserved  │ Preserved    │
     * └──────────────────────┴──────────────────┴───────────┴────────────┴──────────────┘
     * ```
     */
    override fun pause() {
        if (state != SandboxSession.SessionState.ACTIVE) return
        val cid = containerId ?: return

        val process = ProcessBuilder("docker", "pause", cid)
            .redirectErrorStream(true)
            .start()
        process.waitFor(10, TimeUnit.SECONDS)

        state = SandboxSession.SessionState.PAUSED
        logger.info("Session '{}' ({}) paused", label, id)
    }

    /**
     * Resumes the container using `docker unpause`.
     *
     * @see pause for why `unpause` is used instead of `start`
     */
    override fun resume() {
        check(state == SandboxSession.SessionState.PAUSED) {
            "Cannot resume session '$id' with state $state"
        }
        val cid = containerId ?: throw IllegalStateException("No container ID")

        val process = ProcessBuilder("docker", "unpause", cid)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) {
            throw RuntimeException("Failed to resume container: $output")
        }

        state = SandboxSession.SessionState.ACTIVE
        lastActiveAt = Instant.now()
        logger.info("Session '{}' ({}) resumed", label, id)
    }

    override fun close() {
        if (state == SandboxSession.SessionState.CLOSED) return
        val cid = containerId ?: return

        try {
            ProcessBuilder("docker", "rm", "-f", cid)
                .redirectErrorStream(true)
                .start()
                .waitFor(10, TimeUnit.SECONDS)
            logger.info("Session '{}' ({}) closed", label, id)
        } catch (e: Exception) {
            logger.warn("Failed to remove container for session '{}': {}", id, e.message)
        }

        state = SandboxSession.SessionState.CLOSED
        containerId = null
    }
}
