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

import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A Tool that executes agentic coding tasks using Claude Code CLI.
 *
 * This tool allows LLMs to delegate complex coding tasks to Claude Code,
 * which can read, edit, and create files, run commands, and perform
 * multi-step coding operations.
 *
 * ## Example Usage
 *
 * An LLM might invoke this tool with:
 * ```json
 * {
 *   "prompt": "Add comprehensive unit tests for the UserService class",
 *   "workingDirectory": "/path/to/project"
 * }
 * ```
 *
 * ## Security Considerations
 *
 * This tool executes Claude Code with the permissions configured in the executor.
 * By default, it uses ACCEPT_EDITS mode which will automatically approve file edits.
 * Consider restricting allowed tools for sensitive environments.
 *
 * @param executor the Claude Code executor to use
 * @param defaultWorkingDirectory default working directory if not specified in the call
 * @param defaultAllowedTools default tools to allow (null = all tools)
 * @param defaultMaxTurns default maximum turns (null = unlimited)
 * @param toolName the name to expose this tool as
 * @param toolDescription the description for the tool
 */
class ClaudeCodeTool(
    private val executor: ClaudeCodeExecutor = ClaudeCodeExecutor(),
    private val defaultWorkingDirectory: Path? = null,
    private val defaultAllowedTools: List<ClaudeCodeAllowedTool>? = DEFAULT_ALLOWED_TOOLS,
    private val defaultMaxTurns: Int? = DEFAULT_MAX_TURNS,
    toolName: String = "claude_code",
    toolDescription: String = DEFAULT_DESCRIPTION,
) : Tool {

    private val objectMapper = jacksonObjectMapper()

    override val definition: Tool.Definition = Tool.Definition(
        name = toolName,
        description = toolDescription,
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string(
                name = "prompt",
                description = "The coding task to perform. Be specific about what changes to make, " +
                    "which files to modify, and what the expected outcome should be.",
                required = true,
            ),
            Tool.Parameter.string(
                name = "workingDirectory",
                description = "The directory to work in. Defaults to the current project directory.",
                required = false,
            ),
            Tool.Parameter(
                name = "allowedTools",
                type = Tool.ParameterType.ARRAY,
                description = "Tools to allow: Read, Edit, Write, Bash, Glob, Grep, WebSearch, WebFetch. " +
                    "Defaults to Read, Edit, Write, Bash, Glob, Grep.",
                required = false,
                itemType = Tool.ParameterType.STRING,
            ),
            Tool.Parameter.integer(
                name = "maxTurns",
                description = "Maximum number of agentic turns (API round-trips). " +
                    "Defaults to $DEFAULT_MAX_TURNS. Use higher values for complex tasks.",
                required = false,
            ),
        ),
    )

    override fun call(input: String): Tool.Result {
        val params = parseInput(input)

        val workingDir = params.workingDirectory?.let { Paths.get(it) } ?: defaultWorkingDirectory
        val allowedTools = params.allowedTools?.mapNotNull { parseAllowedTool(it) } ?: defaultAllowedTools
        val maxTurns = params.maxTurns ?: defaultMaxTurns

        val result = executor.execute(
            prompt = params.prompt,
            workingDirectory = workingDir,
            allowedTools = allowedTools,
            maxTurns = maxTurns,
        )

        return formatResult(result)
    }

    private fun parseInput(input: String): ClaudeCodeInput {
        if (input.isBlank()) {
            throw IllegalArgumentException("prompt is required")
        }

        return try {
            objectMapper.readValue<ClaudeCodeInput>(input)
        } catch (e: Exception) {
            // If JSON parsing fails, treat the entire input as the prompt
            ClaudeCodeInput(prompt = input)
        }
    }

    private fun parseAllowedTool(name: String): ClaudeCodeAllowedTool? {
        return ClaudeCodeAllowedTool.entries.find {
            it.cliName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true)
        }
    }

    private fun formatResult(result: ClaudeCodeResult): Tool.Result = when (result) {
        is ClaudeCodeResult.Success -> {
            val output = buildString {
                appendLine("Claude Code completed successfully")
                if (result.numTurns > 0) {
                    appendLine("Turns: ${result.numTurns}")
                }
                if (result.costUsd > 0) {
                    appendLine("Cost: $${String.format("%.4f", result.costUsd)}")
                }
                result.duration?.let { appendLine("Duration: $it") }
                appendLine()
                appendLine("=== Result ===")
                appendLine(result.result)

                if (result.allAffectedFiles.isNotEmpty()) {
                    appendLine()
                    appendLine("=== Affected Files ===")
                    result.allAffectedFiles.forEach { appendLine("- $it") }
                }
            }

            // Return with artifact for potential blackboard integration
            Tool.Result.withArtifact(output.trim(), result)
        }

        is ClaudeCodeResult.Failure -> {
            val message = buildString {
                append("Claude Code failed: ${result.error}")
                if (result.timedOut) {
                    append(" (timed out)")
                }
                result.exitCode?.let { append(" [exit code: $it]") }
                result.duration?.let { append(" [ran for: $it]") }
                if (!result.stderr.isNullOrBlank()) {
                    appendLine()
                    appendLine("=== stderr ===")
                    appendLine(result.stderr.trim())
                }
            }
            Tool.Result.error(message.trim())
        }

        is ClaudeCodeResult.Denied -> {
            Tool.Result.error("Claude Code execution denied: ${result.reason}")
        }
    }

    /**
     * Input parameters for Claude Code tool invocation.
     */
    private data class ClaudeCodeInput(
        val prompt: String,
        val workingDirectory: String? = null,
        val allowedTools: List<String>? = null,
        val maxTurns: Int? = null,
    )

    companion object {
        /** Default maximum turns to prevent runaway executions */
        const val DEFAULT_MAX_TURNS = 20

        /** Default allowed tools - a reasonable set for most coding tasks */
        val DEFAULT_ALLOWED_TOOLS = listOf(
            ClaudeCodeAllowedTool.READ,
            ClaudeCodeAllowedTool.EDIT,
            ClaudeCodeAllowedTool.WRITE,
            ClaudeCodeAllowedTool.BASH,
            ClaudeCodeAllowedTool.GLOB,
            ClaudeCodeAllowedTool.GREP,
        )

        /** Default description for the tool */
        const val DEFAULT_DESCRIPTION = """Execute agentic coding tasks using Claude Code.

Claude Code is an AI-powered coding assistant that can:
- Read and understand codebases
- Edit existing files with precise changes
- Create new files
- Run bash commands (tests, builds, etc.)
- Search for files and content

Use this tool for complex coding tasks that require multiple steps or
understanding of the codebase context. Provide specific, detailed prompts
for best results."""

        /**
         * Create a read-only tool that can explore but not modify code.
         */
        fun readOnly(
            executor: ClaudeCodeExecutor = ClaudeCodeExecutor(),
            defaultWorkingDirectory: Path? = null,
        ): ClaudeCodeTool = ClaudeCodeTool(
            executor = executor,
            defaultWorkingDirectory = defaultWorkingDirectory,
            defaultAllowedTools = listOf(
                ClaudeCodeAllowedTool.READ,
                ClaudeCodeAllowedTool.GLOB,
                ClaudeCodeAllowedTool.GREP,
            ),
            toolName = "claude_code_explore",
            toolDescription = """Explore and understand codebases using Claude Code (read-only).

This tool can read files, search for patterns, and analyze code structure,
but cannot make any modifications. Use this for research and understanding
tasks where no changes should be made.""",
        )
    }
}
