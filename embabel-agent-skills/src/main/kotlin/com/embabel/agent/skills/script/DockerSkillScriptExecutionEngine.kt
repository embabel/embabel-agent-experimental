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
package com.embabel.agent.skills.script

import com.embabel.agent.sandbox.DockerExecutor
import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import com.embabel.agent.tools.file.FileTools
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Script execution engine that runs scripts inside a Docker container for sandboxed execution.
 *
 * This provides isolation from the host system while still allowing scripts to:
 * - Read input files via INPUT_DIR
 * - Write output artifacts via OUTPUT_DIR
 * - Access network (can be disabled)
 *
 * This implementation delegates to [DockerExecutor] from the sandbox module for the
 * actual container execution, while adding skill-specific logic like:
 * - Script language validation and interpreter selection
 * - Input file path resolution via [FileTools]
 *
 * @param image the Docker image to use for execution
 * @param timeout maximum execution time before killing the container
 * @param supportedLanguages which script languages this engine supports
 * @param networkEnabled whether to allow network access from the container
 * @param memoryLimit memory limit for the container (e.g., "512m", "1g")
 * @param cpuLimit CPU limit for the container (e.g., "1.0" for 1 CPU)
 * @param environment additional environment variables to pass to the container
 * @param workDir working directory inside the container
 * @param user user to run as inside the container (default: "agent" for the embabel image)
 * @param fileTools FileReadTools for resolving input file paths securely.
 *                  Input paths are resolved relative to the fileTools root with path traversal protection.
 *                  Defaults to current working directory.
 */
class DockerSkillScriptExecutionEngine @JvmOverloads constructor(
    private val image: String = DEFAULT_IMAGE,
    private val timeout: Duration = 60.seconds,
    private val supportedLanguages: Set<ScriptLanguage> = ScriptLanguage.entries.toSet(),
    private val networkEnabled: Boolean = true,
    private val memoryLimit: String? = "512m",
    private val cpuLimit: String? = "1.0",
    private val environment: Map<String, String> = emptyMap(),
    private val workDir: String = "/home/agent/workspace",
    private val user: String? = "agent",
    private val fileTools: FileTools = FileTools.readWrite(System.getProperty("user.dir")),
) : SkillScriptExecutionEngine {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val dockerExecutor = DockerExecutor(
        image = image,
        networkEnabled = networkEnabled,
        memoryLimit = memoryLimit,
        cpuLimit = cpuLimit,
        baseEnvironment = environment,
        user = user,
        workDir = workDir,
    )

    override fun supportedLanguages(): Set<ScriptLanguage> = supportedLanguages

    override fun validate(script: SkillScript): ScriptExecutionResult.Denied? {
        if (script.language !in supportedLanguages) {
            return ScriptExecutionResult.Denied(
                "Script language ${script.language} is not enabled. Enabled languages: $supportedLanguages"
            )
        }

        if (!script.scriptPath.exists()) {
            return ScriptExecutionResult.Denied(
                "Script file does not exist: ${script.scriptPath}"
            )
        }

        // Check if Docker is available via the executor
        dockerExecutor.checkAvailability()?.let { reason ->
            return ScriptExecutionResult.Denied(reason)
        }

        return null
    }

    override fun execute(
        script: SkillScript,
        args: List<String>,
        stdin: String?,
        inputFiles: List<Path>,
    ): ScriptExecutionResult {
        // Validate first
        validate(script)?.let { return it }

        // Validate and resolve input files using FileTools for secure path resolution
        val resolvedInputFiles = mutableListOf<Path>()
        for (inputFile in inputFiles) {
            val pathStr = inputFile.toString()
            try {
                val resolved = fileTools.resolveAndValidateFile(pathStr)
                resolvedInputFiles.add(resolved)
            } catch (e: SecurityException) {
                return ScriptExecutionResult.Denied(
                    "Path traversal not allowed: $inputFile"
                )
            } catch (e: IllegalArgumentException) {
                return ScriptExecutionResult.Denied(
                    "Input file error: ${e.message}"
                )
            }
        }

        // Create a temporary directory for the script
        val scriptDir = Files.createTempDirectory("script-exec-")
        try {
            // Copy script to temp directory
            val scriptFileName = script.fileName
            val containerScriptPath = scriptDir.resolve(scriptFileName)
            Files.copy(script.scriptPath, containerScriptPath)

            // Build the command with interpreter
            val interpreter = getInterpreter(script.language)
            val command = interpreter + listOf("/script/$scriptFileName") + args

            // Create mount for script directory
            val scriptMount = DockerExecutor.Mount(
                hostPath = scriptDir,
                containerPath = "/script",
                readOnly = true,
            )

            // Execute via DockerExecutor with script mount
            val dockerExecutorWithMount = DockerExecutor(
                image = image,
                networkEnabled = networkEnabled,
                memoryLimit = memoryLimit,
                cpuLimit = cpuLimit,
                baseEnvironment = environment,
                user = user,
                workDir = workDir,
                mounts = listOf(scriptMount),
            )

            val request = ExecutionRequest(
                command = command,
                stdin = stdin,
                inputFiles = resolvedInputFiles,
                timeout = timeout,
            )

            val result = dockerExecutorWithMount.execute(request)

            return convertResult(result, script.fileName)
        } finally {
            // Cleanup script directory
            try {
                scriptDir.toFile().deleteRecursively()
            } catch (e: Exception) {
                logger.warn("Failed to cleanup script directory: {}", scriptDir, e)
            }
        }
    }

    private fun getInterpreter(language: ScriptLanguage): List<String> {
        return when (language) {
            ScriptLanguage.PYTHON -> listOf("python3")
            ScriptLanguage.BASH -> listOf("bash")
            ScriptLanguage.JAVASCRIPT -> listOf("node")
            ScriptLanguage.KOTLIN_SCRIPT -> listOf("kotlin")
        }
    }

    private fun convertResult(result: ExecutionResult, scriptName: String): ScriptExecutionResult {
        return when (result) {
            is ExecutionResult.Completed -> {
                val artifacts = result.artifacts.map { artifact ->
                    ScriptArtifact(
                        name = artifact.name,
                        path = artifact.path,
                        mimeType = artifact.mimeType,
                        sizeBytes = artifact.sizeBytes,
                    )
                }
                ScriptExecutionResult.Success(
                    stdout = result.stdout,
                    stderr = result.stderr,
                    exitCode = result.exitCode,
                    duration = result.duration,
                    artifacts = artifacts,
                )
            }

            is ExecutionResult.TimedOut -> {
                logger.warn("Script {} timed out after {}", scriptName, timeout)
                ScriptExecutionResult.Failure(
                    error = "Script execution timed out after $timeout",
                    stderr = result.partialStderr,
                    timedOut = true,
                    duration = result.duration,
                )
            }

            is ExecutionResult.Failed -> {
                logger.error("Script {} failed: {}", scriptName, result.error)
                ScriptExecutionResult.Failure(
                    error = result.error,
                    timedOut = false,
                )
            }

            is ExecutionResult.Denied -> {
                ScriptExecutionResult.Denied(result.reason)
            }
        }
    }

    companion object {
        /**
         * Default Docker image for script execution.
         * This should be an image with Python, Node.js, and common tools installed.
         *
         * Build from the Dockerfile in embabel-agent-skills/docker:
         * ```
         * docker build -t embabel/agent-sandbox:latest ./embabel-agent-skills/docker
         * ```
         */
        const val DEFAULT_IMAGE = "embabel/agent-sandbox:latest"

        private val logger = LoggerFactory.getLogger(DockerSkillScriptExecutionEngine::class.java)

        /**
         * Check if Docker is available on this system.
         */
        fun isDockerAvailable(): Boolean = DockerExecutor.isDockerAvailable()

        /**
         * Check if a Docker image exists locally.
         */
        fun imageExists(image: String): Boolean = DockerExecutor.imageExists(image)

        /**
         * Ensure the default sandbox image exists, logging instructions if not.
         *
         * @return true if the image exists, false if it needs to be built
         */
        fun ensureDefaultImageExists(): Boolean {
            if (!isDockerAvailable()) {
                logger.error("Docker is not available. Please install Docker to use DockerExecutionEngine.")
                return false
            }

            if (!imageExists(DEFAULT_IMAGE)) {
                logger.warn(
                    """
                    |Docker image '$DEFAULT_IMAGE' not found.
                    |
                    |Build it from the embabel-agent-skills module:
                    |  docker build -t $DEFAULT_IMAGE ./embabel-agent-skills/docker
                    |
                    |Or use a different image:
                    |  DockerExecutionEngine(image = "your-image:tag")
                    """.trimMargin()
                )
                return false
            }

            return true
        }

        /**
         * Create a DockerExecutionEngine with Python-only support.
         */
        fun pythonOnly(
            image: String = DEFAULT_IMAGE,
            timeout: Duration = 60.seconds,
        ) = DockerSkillScriptExecutionEngine(
            image = image,
            timeout = timeout,
            supportedLanguages = setOf(ScriptLanguage.PYTHON),
        )

        /**
         * Create a DockerExecutionEngine with maximum isolation.
         */
        fun isolated(
            image: String = DEFAULT_IMAGE,
            timeout: Duration = 30.seconds,
        ) = DockerSkillScriptExecutionEngine(
            image = image,
            timeout = timeout,
            networkEnabled = false,
            memoryLimit = "256m",
            cpuLimit = "0.5",
        )
    }
}
