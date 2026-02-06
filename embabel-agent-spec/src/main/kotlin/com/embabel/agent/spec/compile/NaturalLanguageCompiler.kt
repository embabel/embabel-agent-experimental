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
package com.embabel.agent.spec.compile

import com.embabel.agent.spec.model.GoalSpec
import com.embabel.agent.spec.model.PromptedActionSpec
import com.embabel.agent.spec.model.StepSpec
import com.embabel.agent.spec.model.StepSpecContext

/**
 * Natural language specification for an action to be compiled.
 *
 * @param name Unique name for the action
 * @param description Natural language description of what the action should do
 */
data class NaturalLanguageActionSpec(
    val name: String,
    val description: String,
)

/**
 * Natural language specification for a goal to be compiled.
 *
 * @param name Unique name for the goal
 * @param description Natural language description of what the goal represents
 */
data class NaturalLanguageGoalSpec(
    val name: String,
    val description: String,
)

/**
 * Error that occurred during compilation.
 *
 * @param message Description of the error
 * @param source The source specification that caused the error
 * @param cause Optional underlying exception
 */
data class CompilationError(
    val message: String,
    val source: String,
    val cause: Throwable? = null,
)

/**
 * Result of compiling a single specification.
 *
 * @param result The compiled spec, or null if compilation failed
 * @param errors Any errors that occurred during compilation
 */
data class CompilationResult<S : StepSpec<*>>(
    val result: S?,
    val errors: List<CompilationError> = emptyList(),
) {
    val success: Boolean get() = result != null && errors.isEmpty()

    companion object {
        fun <S : StepSpec<*>> success(result: S) = CompilationResult(result = result)

        fun <S : StepSpec<*>> failure(error: CompilationError) =
            CompilationResult<S>(result = null, errors = listOf(error))

        fun <S : StepSpec<*>> failure(message: String, source: String, cause: Throwable? = null) =
            failure<S>(CompilationError(message, source, cause))
    }
}

/**
 * Aggregate results from compiling multiple specifications.
 *
 * @param results Individual compilation results
 */
data class CompilationResults(
    val results: List<CompilationResult<*>>,
) {
    val success: Boolean get() = results.all { it.success }
    val errors: List<CompilationError> get() = results.flatMap { it.errors }
    val compiledSpecs: List<StepSpec<*>> get() = results.mapNotNull { it.result }
}

/**
 * Compiles natural language specifications into executable step specs.
 *
 * The compiler uses an LLM to translate natural language descriptions of actions
 * and goals into structured [PromptedActionSpec] and [GoalSpec] objects that can
 * be executed by the agent framework.
 *
 * ## Example Usage
 *
 * ```kotlin
 * val compiler = DefaultNaturalLanguageCompiler(agentPlatform)
 *
 * val actionSpec = NaturalLanguageActionSpec(
 *     name = "summarize",
 *     description = "Take the user's input and create a concise summary"
 * )
 *
 * val result = compiler.compileAction(actionSpec, context)
 * if (result.success) {
 *     println("Compiled action: ${result.result}")
 * }
 * ```
 *
 * The compiler uses the [StepSpecContext] to understand available domain types
 * and tool groups, allowing it to select appropriate input/output types and
 * configure the action with relevant tools.
 */
interface NaturalLanguageCompiler {

    /**
     * Compile a natural language action specification into a [PromptedActionSpec].
     *
     * The compiler will:
     * - Analyze the description to understand the action's intent
     * - Select appropriate input types from the data dictionary
     * - Select an appropriate output type from the data dictionary
     * - Generate a prompt template for the action
     * - Identify relevant tool groups
     *
     * @param spec The natural language specification to compile
     * @param context The context providing available types and tools
     * @return Compilation result with the spec or errors
     */
    fun compileAction(
        spec: NaturalLanguageActionSpec,
        context: StepSpecContext,
    ): CompilationResult<PromptedActionSpec>

    /**
     * Compile a natural language goal specification into a [GoalSpec].
     *
     * The compiler will:
     * - Analyze the description to understand what the goal represents
     * - Select an appropriate output type from the data dictionary
     *
     * @param spec The natural language specification to compile
     * @param context The context providing available types
     * @return Compilation result with the spec or errors
     */
    fun compileGoal(
        spec: NaturalLanguageGoalSpec,
        context: StepSpecContext,
    ): CompilationResult<GoalSpec>

    /**
     * Compile multiple specifications from natural language descriptions.
     *
     * @param actions Action specifications to compile
     * @param goals Goal specifications to compile
     * @param context The context providing available types and tools
     * @return Aggregate compilation results
     */
    fun compileAll(
        actions: List<NaturalLanguageActionSpec>,
        goals: List<NaturalLanguageGoalSpec>,
        context: StepSpecContext,
    ): CompilationResults {
        val actionResults = actions.map { compileAction(it, context) }
        val goalResults = goals.map { compileGoal(it, context) }
        return CompilationResults(actionResults + goalResults)
    }
}
