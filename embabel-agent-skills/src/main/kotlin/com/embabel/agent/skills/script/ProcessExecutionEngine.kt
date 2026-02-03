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

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * Process-based script execution engine.
 *
 * Executes scripts as subprocesses using the appropriate interpreter.
 * This provides basic isolation through process boundaries but does NOT
 * provide strong sandboxing - scripts have access to the filesystem and
 * network as permitted by the OS user running the JVM.
 *
 * ## Security Considerations
 *
 * This engine is suitable for:
 * - Trusted scripts from known sources
 * - Development and testing environments
 * - Scenarios where OS-level user permissions provide adequate isolation
 *
 * For untrusted scripts, consider using [DockerExecutionEngine] or
 * [GraalVMExecutionEngine] which provide stronger isolation.
 *
 * @param timeout maximum execution time before the process is killed
 * @param supportedLanguages languages this engine will execute (defaults to all)
 * @param interpreters map of language to interpreter command
 * @param environment environment variables to pass to scripts (defaults to inheriting current env)
 * @param inheritEnvironment whether to inherit the current process environment
 */
class ProcessExecutionEngine(
    private val timeout: Duration = 30.seconds,
    private val supportedLanguages: Set<ScriptLanguage> = ScriptLanguage.entries.toSet(),
    private val interpreters: Map<ScriptLanguage, List<String>> = defaultInterpreters,
    private val environment: Map<String, String> = emptyMap(),
    private val inheritEnvironment: Boolean = true,
) : SkillScriptExecutionEngine {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Default interpreters for each language.
         * The script path will be appended to this command.
         */
        val defaultInterpreters: Map<ScriptLanguage, List<String>> = mapOf(
            ScriptLanguage.PYTHON to listOf("python3"),
            ScriptLanguage.BASH to listOf("bash"),
            ScriptLanguage.JAVASCRIPT to listOf("node"),
            ScriptLanguage.KOTLIN_SCRIPT to listOf("kotlin"),
        )
    }

    override fun supportedLanguages(): Set<ScriptLanguage> = supportedLanguages

    override fun validate(script: SkillScript): ScriptExecutionResult.Denied? {
        // Check language support
        if (script.language !in supportedLanguages) {
            return ScriptExecutionResult.Denied(
                "Language ${script.language} is not enabled. " +
                    "Enabled languages: ${supportedLanguages.joinToString()}"
            )
        }

        // Check interpreter is configured
        if (script.language !in interpreters) {
            return ScriptExecutionResult.Denied(
                "No interpreter configured for ${script.language}"
            )
        }

        // Check script file exists
        val scriptFile = script.scriptPath.toFile()
        if (!scriptFile.exists()) {
            return ScriptExecutionResult.Denied(
                "Script file does not exist: ${script.scriptPath}"
            )
        }

        if (!scriptFile.isFile) {
            return ScriptExecutionResult.Denied(
                "Script path is not a file: ${script.scriptPath}"
            )
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

        // Validate input files exist
        for (inputFile in inputFiles) {
            if (!Files.exists(inputFile)) {
                return ScriptExecutionResult.Denied("Input file does not exist: $inputFile")
            }
            if (!Files.isRegularFile(inputFile)) {
                return ScriptExecutionResult.Denied("Input path is not a file: $inputFile")
            }
        }

        val interpreter = interpreters[script.language]!!
        val command = interpreter + listOf(script.scriptPath.toAbsolutePath().toString()) + args

        // Create input directory and copy input files
        val inputDir = Files.createTempDirectory("script-input-")
        logger.debug("Created input directory: {}", inputDir)

        // Create output directory for artifacts
        val outputDir = Files.createTempDirectory("script-output-")
        logger.debug("Created output directory: {}", outputDir)

        return try {
            // Copy input files to input directory
            for (inputFile in inputFiles) {
                val targetPath = inputDir.resolve(inputFile.fileName)
                Files.copy(inputFile, targetPath)
                logger.debug("Copied input file {} to {}", inputFile, targetPath)
            }

            logger.debug("Executing script: {} with args: {}, {} input files", script.fileName, args, inputFiles.size)

            val (result, duration) = measureTimedValue {
                executeProcess(
                    command = command,
                    workingDir = script.basePath.toFile(),
                    stdin = stdin,
                    inputDir = inputDir,
                    outputDir = outputDir,
                )
            }

            when (result) {
                is ProcessResult.Completed -> {
                    val artifacts = collectArtifacts(outputDir)
                    logger.debug(
                        "Script {} completed with exit code {} in {}, produced {} artifacts",
                        script.fileName,
                        result.exitCode,
                        duration,
                        artifacts.size
                    )
                    // Clean up input directory (no longer needed)
                    cleanupDirectory(inputDir)
                    ScriptExecutionResult.Success(
                        stdout = result.stdout,
                        stderr = result.stderr,
                        exitCode = result.exitCode,
                        duration = duration,
                        artifacts = artifacts,
                    )
                }

                is ProcessResult.TimedOut -> {
                    logger.warn("Script {} timed out after {}", script.fileName, timeout)
                    cleanupDirectory(inputDir)
                    cleanupDirectory(outputDir)
                    ScriptExecutionResult.Failure(
                        error = "Script execution timed out after $timeout",
                        stderr = result.stderr,
                        timedOut = true,
                        duration = duration,
                    )
                }

                is ProcessResult.Failed -> {
                    logger.error("Script {} failed to start: {}", script.fileName, result.error)
                    cleanupDirectory(inputDir)
                    cleanupDirectory(outputDir)
                    ScriptExecutionResult.Failure(
                        error = result.error,
                        duration = duration,
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error executing script {}: {}", script.fileName, e.message, e)
            cleanupDirectory(inputDir)
            cleanupDirectory(outputDir)
            ScriptExecutionResult.Failure(
                error = "Unexpected error: ${e.message}",
            )
        }
    }

    /**
     * Collect artifacts from the output directory.
     */
    private fun collectArtifacts(outputDir: Path): List<ScriptArtifact> {
        if (!Files.isDirectory(outputDir)) {
            return emptyList()
        }

        return Files.list(outputDir)
            .filter { Files.isRegularFile(it) }
            .map { file ->
                ScriptArtifact(
                    name = file.fileName.toString(),
                    path = file.toAbsolutePath(),
                    mimeType = ScriptArtifact.inferMimeType(file.fileName.toString()),
                    sizeBytes = Files.size(file),
                )
            }
            .toList()
            .sortedBy { it.name }
    }

    /**
     * Clean up a temporary directory.
     */
    private fun cleanupDirectory(dir: Path) {
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        } catch (e: Exception) {
            logger.warn("Failed to clean up directory {}: {}", dir, e.message)
        }
    }

    private fun executeProcess(
        command: List<String>,
        workingDir: File,
        stdin: String?,
        inputDir: Path,
        outputDir: Path,
    ): ProcessResult {
        return try {
            val processBuilder = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)

            // Set up environment
            val env = processBuilder.environment()
            if (!inheritEnvironment) {
                env.clear()
            }
            env.putAll(environment)

            // Add INPUT_DIR for scripts to read input files
            env["INPUT_DIR"] = inputDir.toAbsolutePath().toString()

            // Add OUTPUT_DIR for scripts to write artifacts
            env["OUTPUT_DIR"] = outputDir.toAbsolutePath().toString()

            val process = processBuilder.start()

            // Write stdin if provided
            if (stdin != null) {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(stdin)
                }
            } else {
                process.outputStream.close()
            }

            // Read stdout and stderr concurrently to avoid blocking
            var stdout = ""
            var stderr = ""

            val stdoutThread = Thread {
                stdout = process.inputStream.bufferedReader().readText()
            }.apply { start() }

            val stderrThread = Thread {
                stderr = process.errorStream.bufferedReader().readText()
            }.apply { start() }

            // Wait for process with timeout
            val completed = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                stdoutThread.join(1000)
                stderrThread.join(1000)

                return ProcessResult.TimedOut(stderr)
            }

            // Wait for output readers to complete
            stdoutThread.join()
            stderrThread.join()

            ProcessResult.Completed(
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderr,
            )
        } catch (e: Exception) {
            ProcessResult.Failed(e.message ?: "Unknown error starting process")
        }
    }

    /**
     * Internal result type for process execution.
     */
    private sealed class ProcessResult {
        data class Completed(
            val exitCode: Int,
            val stdout: String,
            val stderr: String,
        ) : ProcessResult()

        data class TimedOut(
            val stderr: String,
        ) : ProcessResult()

        data class Failed(
            val error: String,
        ) : ProcessResult()
    }
}
