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

import com.embabel.agent.tools.file.FileTools
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Script execution engine that runs scripts inside a Docker container for sandboxed execution.
 *
 * This provides isolation from the host system while still allowing scripts to:
 * - Read input files via INPUT_DIR
 * - Write output artifacts via OUTPUT_DIR
 * - Access network (can be disabled)
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

        // Check if Docker is available
        return try {
            val process = ProcessBuilder("docker", "version")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                ScriptExecutionResult.Denied("Docker is not available or not running")
            } else {
                null
            }
        } catch (e: Exception) {
            ScriptExecutionResult.Denied("Docker is not available: ${e.message}")
        }
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

        // Create temporary directories for I/O
        val tempBase = Files.createTempDirectory("docker-exec-${script.skillName}")
        val inputDir = tempBase.resolve("input")
        val outputDir = tempBase.resolve("output")
        val scriptDir = tempBase.resolve("script")

        Files.createDirectories(inputDir)
        Files.createDirectories(outputDir)
        Files.createDirectories(scriptDir)

        try {
            // Copy resolved input files to input directory
            for (resolvedFile in resolvedInputFiles) {
                Files.copy(resolvedFile, inputDir.resolve(resolvedFile.fileName))
            }

            // Copy script to script directory
            val scriptFileName = script.fileName
            val containerScriptPath = scriptDir.resolve(scriptFileName)
            Files.copy(script.scriptPath, containerScriptPath)

            // Build docker command
            val dockerCommand = buildDockerCommand(
                script = script,
                scriptDir = scriptDir,
                inputDir = inputDir,
                outputDir = outputDir,
                args = args,
            )

            logger.debug("Executing docker command: {}", dockerCommand.joinToString(" "))

            val duration: Duration
            val processBuilder = ProcessBuilder(dockerCommand)
                .redirectErrorStream(false)

            val process = processBuilder.start()

            // Write stdin if provided
            if (stdin != null) {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(stdin)
                }
            } else {
                process.outputStream.close()
            }

            // Wait for completion with timeout
            val completed: Boolean
            duration = measureTime {
                completed = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }

            if (!completed) {
                // Kill the container
                process.destroyForcibly()
                logger.warn("Docker execution for {} timed out after {}", script.fileName, timeout)
                return ScriptExecutionResult.Failure(
                    error = "Script execution timed out after $timeout",
                    timedOut = true,
                    duration = duration,
                )
            }

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.exitValue()

            // Collect artifacts from output directory and copy to persistent location
            val artifacts = collectAndCopyArtifacts(outputDir)

            // Cleanup temp directories (input/script are no longer needed, output is copied)
            try {
                tempBase.toFile().deleteRecursively()
            } catch (e: Exception) {
                logger.warn("Failed to cleanup temp directory: {}", tempBase, e)
            }

            return ScriptExecutionResult.Success(
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                duration = duration,
                artifacts = artifacts,
            )

        } catch (e: Exception) {
            logger.error("Docker execution failed for {}: {}", script.fileName, e.message, e)
            // Cleanup on error
            try {
                tempBase.toFile().deleteRecursively()
            } catch (cleanupError: Exception) {
                logger.warn("Failed to cleanup temp directory: {}", tempBase, cleanupError)
            }
            return ScriptExecutionResult.Failure(
                error = "Docker execution failed: ${e.message}",
                timedOut = false,
            )
        }
    }

    private fun buildDockerCommand(
        script: SkillScript,
        scriptDir: Path,
        inputDir: Path,
        outputDir: Path,
        args: List<String>,
    ): List<String> {
        val command = mutableListOf(
            "docker", "run",
            "--rm",  // Remove container after execution
        )

        // Resource limits
        memoryLimit?.let { command.addAll(listOf("--memory", it)) }
        cpuLimit?.let { command.addAll(listOf("--cpus", it)) }

        // Network
        if (!networkEnabled) {
            command.addAll(listOf("--network", "none"))
        }

        // User
        user?.let { command.addAll(listOf("--user", it)) }

        // Working directory
        command.addAll(listOf("--workdir", workDir))

        // Mount directories
        command.addAll(listOf(
            "-v", "${scriptDir.absolutePathString()}:/script:ro",
            "-v", "${inputDir.absolutePathString()}:/input:ro",
            "-v", "${outputDir.absolutePathString()}:/output:rw",
        ))

        // Environment variables
        command.addAll(listOf(
            "-e", "INPUT_DIR=/input",
            "-e", "OUTPUT_DIR=/output",
        ))
        for ((key, value) in environment) {
            command.addAll(listOf("-e", "$key=$value"))
        }

        // Image
        command.add(image)

        // Script execution command based on language
        val interpreter = getInterpreter(script.language)
        command.addAll(interpreter)
        command.add("/script/${script.fileName}")

        // Script arguments
        command.addAll(args)

        return command
    }

    private fun getInterpreter(language: ScriptLanguage): List<String> {
        return when (language) {
            ScriptLanguage.PYTHON -> listOf("python3")
            ScriptLanguage.BASH -> listOf("bash")
            ScriptLanguage.JAVASCRIPT -> listOf("node")
            ScriptLanguage.KOTLIN_SCRIPT -> listOf("kotlin")
        }
    }

    /**
     * Collect artifacts from output directory and copy them to a persistent location.
     * The artifacts need to outlive the temp directory cleanup.
     */
    private fun collectAndCopyArtifacts(outputDir: Path): List<ScriptArtifact> {
        if (!Files.isDirectory(outputDir)) {
            return emptyList()
        }

        val artifactsDir = Files.createTempDirectory("docker-artifacts")

        return Files.list(outputDir)
            .filter { Files.isRegularFile(it) }
            .map { file ->
                val persistentPath = artifactsDir.resolve(file.fileName)
                Files.copy(file, persistentPath)
                ScriptArtifact(
                    name = file.fileName.toString(),
                    path = persistentPath,
                    mimeType = inferMimeType(file.fileName.toString()),
                    sizeBytes = Files.size(persistentPath),
                )
            }
            .toList()
    }

    private fun inferMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "pdf" -> "application/pdf"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            else -> "application/octet-stream"
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
        fun isDockerAvailable(): Boolean {
            return try {
                val process = ProcessBuilder("docker", "version")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Check if a Docker image exists locally.
         */
        fun imageExists(image: String): Boolean {
            return try {
                val process = ProcessBuilder("docker", "image", "inspect", image)
                    .redirectErrorStream(true)
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }

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
