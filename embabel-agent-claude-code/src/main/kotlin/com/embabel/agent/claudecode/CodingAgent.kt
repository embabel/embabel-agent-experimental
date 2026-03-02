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

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import java.nio.file.Path

/**
 * Outcome of a Claude Code implementation action.
 *
 * @param success whether the implementation succeeded
 * @param summary summary of what was done
 * @param filesChanged list of files that were modified or created
 * @param sessionId session ID for potential follow-up
 * @param costUsd cost of the execution
 */
data class ImplementationOutcome(
    val success: Boolean,
    val summary: String,
    val filesChanged: List<String> = emptyList(),
    val sessionId: String? = null,
    val costUsd: Double = 0.0,
)

/**
 * A specification for a feature to implement.
 *
 * @param description what the feature should do
 * @param requirements specific requirements or acceptance criteria
 * @param targetFiles optional list of files to focus on
 */
data class FeatureSpecification(
    val description: String,
    val requirements: List<String> = emptyList(),
    val targetFiles: List<String> = emptyList(),
)

/**
 * A codebase context for Claude Code operations.
 *
 * @param path root path of the codebase
 * @param language primary programming language
 * @param framework optional framework being used
 */
data class Codebase(
    val path: Path,
    val language: String? = null,
    val framework: String? = null,
)

/**
 * Agent that uses Claude Code for implementation tasks.
 *
 * This agent provides actions that delegate complex coding tasks to Claude Code,
 * allowing Embabel's GOAP planner to orchestrate high-level workflows while
 * Claude Code handles the detailed implementation.
 *
 * ## Example Usage
 *
 * ```kotlin
 * @Agent(description = "Feature implementation workflow")
 * class FeatureWorkflowAgent(
 *     private val codeAgent: CodeImplementationAgent,
 * ) {
 *     @Action(
 *         description = "Implement a new feature",
 *         pre = ["hasFeatureSpec", "!featureImplemented"],
 *         post = ["featureImplemented"],
 *     )
 *     fun implementFeature(spec: FeatureSpecification, codebase: Codebase): ImplementationOutcome {
 *         return codeAgent.implementFeature(spec, codebase)
 *     }
 * }
 * ```
 *
 * @param executor the Claude Code executor to use
 * @param defaultMaxTurns default maximum turns for executions
 * @param streamOutput if true, log Claude's output as it streams
 */
@Agent(
    description = "Implements features and fixes bugs using Claude Code",
    scan = false, // Typically composed into other agents rather than used standalone
)
class CodeImplementationAgent(
    private val executor: ClaudeCodeExecutor = ClaudeCodeExecutor(),
    private val defaultMaxTurns: Int = 30,
    private val streamOutput: Boolean = false,
    private val streamCallback: ((ClaudeStreamEvent) -> Unit)? = null,
) {

    /**
     * Implement a feature based on a specification.
     */
    @Action(
        description = "Implement a feature using Claude Code",
    )
    fun implementFeature(
        spec: FeatureSpecification,
        codebase: Codebase,
    ): ImplementationOutcome {
        val prompt = buildImplementationPrompt(spec, codebase)

        val result = executor.execute(
            prompt = prompt,
            workingDirectory = codebase.path,
            allowedTools = listOf(
                ClaudeCodeAllowedTool.READ,
                ClaudeCodeAllowedTool.EDIT,
                ClaudeCodeAllowedTool.WRITE,
                ClaudeCodeAllowedTool.BASH,
                ClaudeCodeAllowedTool.GLOB,
                ClaudeCodeAllowedTool.GREP,
            ),
            maxTurns = defaultMaxTurns,
            streamOutput = streamOutput,
            streamCallback = streamCallback,
        )

        return when (result) {
            is ClaudeCodeResult.Success -> ImplementationOutcome(
                success = true,
                summary = result.result,
                filesChanged = result.allAffectedFiles,
                sessionId = result.sessionId,
                costUsd = result.costUsd,
            )
            is ClaudeCodeResult.Failure -> ImplementationOutcome(
                success = false,
                summary = "Implementation failed: ${result.error}",
            )
            is ClaudeCodeResult.Denied -> ImplementationOutcome(
                success = false,
                summary = "Implementation denied: ${result.reason}",
            )
        }
    }

    /**
     * Fix a bug in the codebase.
     */
    @Action(
        description = "Fix a bug using Claude Code",
    )
    fun fixBug(
        bugDescription: String,
        codebase: Codebase,
        reproductionSteps: List<String> = emptyList(),
    ): ImplementationOutcome {
        val prompt = buildBugFixPrompt(bugDescription, reproductionSteps)

        val result = executor.execute(
            prompt = prompt,
            workingDirectory = codebase.path,
            allowedTools = listOf(
                ClaudeCodeAllowedTool.READ,
                ClaudeCodeAllowedTool.EDIT,
                ClaudeCodeAllowedTool.BASH,
                ClaudeCodeAllowedTool.GLOB,
                ClaudeCodeAllowedTool.GREP,
            ),
            maxTurns = defaultMaxTurns,
            streamOutput = streamOutput,
            streamCallback = streamCallback,
        )

        return when (result) {
            is ClaudeCodeResult.Success -> ImplementationOutcome(
                success = true,
                summary = result.result,
                filesChanged = result.allAffectedFiles,
                sessionId = result.sessionId,
                costUsd = result.costUsd,
            )
            is ClaudeCodeResult.Failure -> ImplementationOutcome(
                success = false,
                summary = "Bug fix failed: ${result.error}",
            )
            is ClaudeCodeResult.Denied -> ImplementationOutcome(
                success = false,
                summary = "Bug fix denied: ${result.reason}",
            )
        }
    }

    /**
     * Add tests for existing code.
     */
    @Action(
        description = "Add tests using Claude Code",
    )
    fun addTests(
        targetDescription: String,
        codebase: Codebase,
        testFramework: String? = null,
    ): ImplementationOutcome {
        val prompt = buildTestPrompt(targetDescription, testFramework)

        val result = executor.execute(
            prompt = prompt,
            workingDirectory = codebase.path,
            allowedTools = listOf(
                ClaudeCodeAllowedTool.READ,
                ClaudeCodeAllowedTool.EDIT,
                ClaudeCodeAllowedTool.WRITE,
                ClaudeCodeAllowedTool.BASH,
                ClaudeCodeAllowedTool.GLOB,
                ClaudeCodeAllowedTool.GREP,
            ),
            maxTurns = defaultMaxTurns,
            streamOutput = streamOutput,
            streamCallback = streamCallback,
        )

        return when (result) {
            is ClaudeCodeResult.Success -> ImplementationOutcome(
                success = true,
                summary = result.result,
                filesChanged = result.allAffectedFiles,
                sessionId = result.sessionId,
                costUsd = result.costUsd,
            )
            is ClaudeCodeResult.Failure -> ImplementationOutcome(
                success = false,
                summary = "Adding tests failed: ${result.error}",
            )
            is ClaudeCodeResult.Denied -> ImplementationOutcome(
                success = false,
                summary = "Adding tests denied: ${result.reason}",
            )
        }
    }

    /**
     * Refactor code according to a specification.
     */
    @Action(
        description = "Refactor code using Claude Code",
    )
    fun refactor(
        refactoringDescription: String,
        codebase: Codebase,
        scope: List<String> = emptyList(),
    ): ImplementationOutcome {
        val prompt = buildRefactorPrompt(refactoringDescription, scope)

        val result = executor.execute(
            prompt = prompt,
            workingDirectory = codebase.path,
            allowedTools = listOf(
                ClaudeCodeAllowedTool.READ,
                ClaudeCodeAllowedTool.EDIT,
                ClaudeCodeAllowedTool.BASH,
                ClaudeCodeAllowedTool.GLOB,
                ClaudeCodeAllowedTool.GREP,
            ),
            maxTurns = defaultMaxTurns,
            streamOutput = streamOutput,
            streamCallback = streamCallback,
        )

        return when (result) {
            is ClaudeCodeResult.Success -> ImplementationOutcome(
                success = true,
                summary = result.result,
                filesChanged = result.allAffectedFiles,
                sessionId = result.sessionId,
                costUsd = result.costUsd,
            )
            is ClaudeCodeResult.Failure -> ImplementationOutcome(
                success = false,
                summary = "Refactoring failed: ${result.error}",
            )
            is ClaudeCodeResult.Denied -> ImplementationOutcome(
                success = false,
                summary = "Refactoring denied: ${result.reason}",
            )
        }
    }

    /**
     * Continue a previous Claude Code session with a new prompt.
     * Useful for multi-step workflows like: fix bug -> create PR.
     *
     * @param sessionId the session ID from a previous execution
     * @param prompt the new instruction for Claude
     * @param codebase the codebase context
     * @param additionalTools additional tools to allow beyond the defaults
     */
    @Action(
        description = "Continue a Claude Code session with a new instruction",
    )
    fun continueSession(
        sessionId: String,
        prompt: String,
        codebase: Codebase,
        additionalTools: List<ClaudeCodeAllowedTool> = emptyList(),
    ): ImplementationOutcome {
        val allTools = listOf(
            ClaudeCodeAllowedTool.READ,
            ClaudeCodeAllowedTool.EDIT,
            ClaudeCodeAllowedTool.WRITE,
            ClaudeCodeAllowedTool.BASH,
            ClaudeCodeAllowedTool.GLOB,
            ClaudeCodeAllowedTool.GREP,
        ) + additionalTools

        val result = executor.execute(
            prompt = prompt,
            workingDirectory = codebase.path,
            allowedTools = allTools.distinct(),
            maxTurns = defaultMaxTurns,
            streamOutput = streamOutput,
            streamCallback = streamCallback,
            sessionId = sessionId,
        )

        return when (result) {
            is ClaudeCodeResult.Success -> ImplementationOutcome(
                success = true,
                summary = result.result,
                filesChanged = result.allAffectedFiles,
                sessionId = result.sessionId,
                costUsd = result.costUsd,
            )
            is ClaudeCodeResult.Failure -> ImplementationOutcome(
                success = false,
                summary = "Continuation failed: ${result.error}",
            )
            is ClaudeCodeResult.Denied -> ImplementationOutcome(
                success = false,
                summary = "Continuation denied: ${result.reason}",
            )
        }
    }

    /**
     * Create a pull request for changes made in a previous session.
     * This continues an existing session and instructs Claude to create a PR.
     *
     * @param sessionId the session ID from a previous execution (e.g., from fixBug)
     * @param codebase the codebase context
     * @param issueNumber optional issue number to reference in the PR
     * @param baseBranch the base branch for the PR (default: main)
     * @param branchName optional branch name (Claude will create one if not specified)
     */
    @Action(
        description = "Create a pull request using Claude Code",
    )
    fun createPullRequest(
        sessionId: String,
        codebase: Codebase,
        issueNumber: Int? = null,
        baseBranch: String = "main",
        branchName: String? = null,
    ): ImplementationOutcome {
        val prompt = buildString {
            appendLine("Create a pull request for the changes you just made.")
            appendLine()
            if (branchName != null) {
                appendLine("Use branch name: $branchName")
            } else {
                appendLine("Create an appropriate branch name based on the changes.")
            }
            appendLine("Base branch: $baseBranch")
            if (issueNumber != null) {
                appendLine("This fixes issue #$issueNumber - reference it in the PR.")
            }
            appendLine()
            appendLine("Steps:")
            appendLine("1. Create and checkout a new branch")
            appendLine("2. Stage and commit all changes with a descriptive message")
            appendLine("3. Push the branch to origin")
            appendLine("4. Create a PR using 'gh pr create'")
            appendLine()
            appendLine("Return the PR URL when done.")
        }

        val result = executor.execute(
            prompt = prompt,
            workingDirectory = codebase.path,
            allowedTools = listOf(
                ClaudeCodeAllowedTool.READ,
                ClaudeCodeAllowedTool.BASH, // Needed for git and gh commands
            ),
            maxTurns = defaultMaxTurns,
            streamOutput = streamOutput,
            streamCallback = streamCallback,
            sessionId = sessionId,
        )

        return when (result) {
            is ClaudeCodeResult.Success -> ImplementationOutcome(
                success = true,
                summary = result.result,
                filesChanged = result.allAffectedFiles,
                sessionId = result.sessionId,
                costUsd = result.costUsd,
            )
            is ClaudeCodeResult.Failure -> ImplementationOutcome(
                success = false,
                summary = "PR creation failed: ${result.error}",
            )
            is ClaudeCodeResult.Denied -> ImplementationOutcome(
                success = false,
                summary = "PR creation denied: ${result.reason}",
            )
        }
    }

    private fun buildImplementationPrompt(spec: FeatureSpecification, codebase: Codebase): String {
        return buildString {
            appendLine("Implement the following feature:")
            appendLine()
            appendLine(spec.description)

            if (spec.requirements.isNotEmpty()) {
                appendLine()
                appendLine("Requirements:")
                spec.requirements.forEach { appendLine("- $it") }
            }

            if (spec.targetFiles.isNotEmpty()) {
                appendLine()
                appendLine("Focus on these files:")
                spec.targetFiles.forEach { appendLine("- $it") }
            }

            codebase.language?.let {
                appendLine()
                appendLine("Primary language: $it")
            }

            codebase.framework?.let {
                appendLine()
                appendLine("Framework: $it")
            }

            appendLine()
            appendLine("Follow existing code patterns and conventions. Add tests if appropriate.")
        }
    }

    private fun buildBugFixPrompt(description: String, reproductionSteps: List<String>): String {
        return buildString {
            appendLine("Fix the following bug:")
            appendLine()
            appendLine(description)

            if (reproductionSteps.isNotEmpty()) {
                appendLine()
                appendLine("Reproduction steps:")
                reproductionSteps.forEachIndexed { i, step ->
                    appendLine("${i + 1}. $step")
                }
            }

            appendLine()
            appendLine("Investigate the root cause, implement a fix, and verify with tests if possible.")
        }
    }

    private fun buildTestPrompt(targetDescription: String, testFramework: String?): String {
        return buildString {
            appendLine("Add comprehensive tests for:")
            appendLine()
            appendLine(targetDescription)

            testFramework?.let {
                appendLine()
                appendLine("Use the $it testing framework.")
            }

            appendLine()
            appendLine("Include unit tests, edge cases, and integration tests where appropriate.")
        }
    }

    private fun buildRefactorPrompt(description: String, scope: List<String>): String {
        return buildString {
            appendLine("Refactor the code as follows:")
            appendLine()
            appendLine(description)

            if (scope.isNotEmpty()) {
                appendLine()
                appendLine("Scope (files/modules to refactor):")
                scope.forEach { appendLine("- $it") }
            }

            appendLine()
            appendLine("Ensure all tests still pass after refactoring. Do not change behavior.")
        }
    }
}
