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
package com.embabel.agent.executor

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.*
import com.embabel.agent.core.support.AbstractAction
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spec.model.ActionSpec
import com.embabel.agent.spec.model.StepSpecContext
import com.embabel.common.core.types.ZeroToOne

/**
 * Result of a fitness evaluation, combining a numeric score
 * with optional human-readable feedback explaining the rating.
 *
 * @param score quality score between 0.0 and 1.0
 * @param feedback optional explanation of the score (for logging/observability)
 */
data class FitnessEvaluation(
    val score: ZeroToOne,
    val feedback: String? = null,
) {
    companion object {
        fun pass(feedback: String? = null) = FitnessEvaluation(1.0, feedback)
        fun fail(feedback: String) = FitnessEvaluation(0.0, feedback)
        fun of(score: ZeroToOne, feedback: String? = null) = FitnessEvaluation(score, feedback)
    }
}

/**
 * A function that evaluates the quality of a value, returning a [FitnessEvaluation]
 * with a score between 0.0 and 1.0 and optional feedback.
 */
typealias FitnessFunction<T> = (T) -> FitnessEvaluation

/**
 * Combines two fitness functions using minimum (logical AND).
 * Both functions must score high for the combined score to be high.
 * Feedback from the lower-scoring evaluation is preserved.
 */
fun <T> FitnessFunction<T>.and(other: FitnessFunction<T>): FitnessFunction<T> = {
    val a = this(it)
    val b = other(it)
    if (a.score <= b.score) a else b
}

/**
 * Combines two fitness functions using maximum (logical OR).
 * Either function scoring high is sufficient.
 * Feedback from the higher-scoring evaluation is preserved.
 */
fun <T> FitnessFunction<T>.or(other: FitnessFunction<T>): FitnessFunction<T> = {
    val a = this(it)
    val b = other(it)
    if (a.score >= b.score) a else b
}

/**
 * Creates a weighted combination of fitness functions.
 * Each function's score is multiplied by its weight and the results are averaged.
 * Feedback from all evaluations is concatenated.
 */
fun <T> weightedFitness(vararg weighted: Pair<Double, FitnessFunction<T>>): FitnessFunction<T> = { value ->
    val evaluations = weighted.map { (weight, fn) -> weight to fn(value) }
    val score = evaluations.sumOf { (weight, eval) -> weight * eval.score } / weighted.sumOf { it.first }
    val feedback = evaluations.mapNotNull { (_, eval) -> eval.feedback }.joinToString("; ").ifEmpty { null }
    FitnessEvaluation(score, feedback)
}

/**
 * Combines multiple fitness functions using minimum (all must pass).
 * Feedback from the lowest-scoring evaluation is preserved.
 */
fun <T> allOf(vararg fns: FitnessFunction<T>): FitnessFunction<T> = { value ->
    val evaluations = fns.map { it(value) }
    evaluations.minByOrNull { it.score } ?: FitnessEvaluation.pass()
}

/**
 * Common built-in fitness functions.
 */
object FitnessFunctions {
    fun <T> alwaysPass(): FitnessFunction<T> = { FitnessEvaluation.pass() }
    fun <T> alwaysFail(): FitnessFunction<T> = { FitnessEvaluation.fail("Always fails") }
    fun nonBlank(): FitnessFunction<String> = {
        if (it.isBlank()) FitnessEvaluation.fail("Output is blank")
        else FitnessEvaluation.pass()
    }
    fun minLength(min: Int): FitnessFunction<String> = {
        if (it.length >= min) FitnessEvaluation.pass()
        else FitnessEvaluation(it.length.toDouble() / min, "Output too short: ${it.length} < $min")
    }
    fun <T> predicate(description: String = "Predicate failed", fn: (T) -> Boolean): FitnessFunction<T> = {
        if (fn(it)) FitnessEvaluation.pass()
        else FitnessEvaluation.fail(description)
    }
}

/**
 * Outcome of an agent execution, providing the output value
 * along with cost and attempt metadata for fitness evaluation.
 *
 * @param output the result value produced by the execution
 * @param costUsd total cost of the execution in USD
 * @param attempts number of attempts made so far
 */
data class ExecutionOutcome(
    val output: Any,
    val costUsd: Double = 0.0,
    val attempts: Int = 1,
)

/**
 * Result of a typed execution with fitness evaluation.
 */
sealed interface TypedResult<T> {

    /**
     * Successful typed execution.
     *
     * @param value the deserialized output
     * @param evaluation the fitness evaluation of the output
     * @param attempts number of attempts made
     * @param costUsd cost of the execution in USD
     * @param raw the underlying raw result
     */
    data class Success<T>(
        val value: T,
        val evaluation: FitnessEvaluation = FitnessEvaluation.pass(),
        val attempts: Int = 1,
        val costUsd: Double = 0.0,
        val raw: Any? = null,
    ) : TypedResult<T> {

        val score: ZeroToOne get() = evaluation.score

        /**
         * Convert to an [ExecutionOutcome] for fitness evaluation.
         */
        fun toOutcome(): ExecutionOutcome = ExecutionOutcome(
            output = value as Any,
            costUsd = costUsd,
            attempts = attempts,
        )
    }

    /**
     * Failed typed execution (CLI failure, parse failure, or all retries exhausted below threshold).
     *
     * @param error description of the failure
     * @param raw the underlying raw result, if available
     */
    data class Failure<T>(
        val error: String,
        val raw: Any? = null,
    ) : TypedResult<T>
}

/**
 * Interface for typed agent execution with fitness evaluation.
 *
 * Also implements [ActionSpec], so any agent executor can emit an [Action]
 * from a [StepSpecContext]. The default [emit] creates an action that
 * resolves tools from the context and delegates execution to [executeTyped].
 *
 * Implementations must provide [name], [description], [prompt],
 * [inputTypeNames], and [outputTypeName].
 */
interface AgentExecutor : ActionSpec {

    override val stepType: String get() = "agent-executor"

    /** Prompt template (supports {{variable}} syntax) for the task */
    val prompt: String

    /** Input type names to bind from the blackboard */
    val inputTypeNames: Set<String>

    /** Output type name to write to the blackboard */
    val outputTypeName: String

    /**
     * Fitness function that evaluates the [ExecutionOutcome] after each attempt.
     * Has access to the output value, cost, and attempt count.
     * Defaults to always passing (score 1.0).
     */
    val resultFitness: FitnessFunction<ExecutionOutcome> get() = FitnessFunctions.alwaysPass()

    /**
     * Maximum number of retry attempts when fitness is below [fitnessThreshold].
     * 0 means no retries (single attempt only).
     */
    val maxRetries: Int get() = 0

    /**
     * Minimum acceptable fitness score. Results below this trigger a retry
     * if [maxRetries] allows.
     */
    val fitnessThreshold: ZeroToOne get() = 0.8

    /**
     * Execute a typed request with fitness evaluation and optional retry.
     */
    fun <T : Any> executeTyped(request: AgentRequest<T>): TypedResult<T>

    /**
     * Emit an [Action] that delegates to this executor.
     * Tools from the [StepSpecContext] are passed to the executor via the [AgentRequest].
     */
    override fun emit(stepContext: StepSpecContext): Action {
        val varName = outputTypeName.substringAfterLast('.').decapitalize()
        val inputs = inputTypeNames.map { IoBinding(variableNameFor(it), it) }.toSet()

        return AgentExecutorAction(
            executor = this,
            varName = varName,
            inputs = inputs,
            stepContext = stepContext,
        )
    }
}

/**
 * Action that delegates execution to an [AgentExecutor].
 * Runs a fitness-evaluated retry loop using the executor's
 * [AgentExecutor.resultFitness], [AgentExecutor.maxRetries],
 * and [AgentExecutor.fitnessThreshold].
 */
private class AgentExecutorAction(
    private val executor: AgentExecutor,
    private val varName: String,
    inputs: Set<IoBinding>,
    stepContext: StepSpecContext,
) : AbstractAction(
    name = executor.name,
    description = executor.description,
    inputs = inputs,
    outputs = setOf(IoBinding(varName, executor.outputTypeName)),
    toolGroups = emptySet(),
    canRerun = false,
) {
    private val resolvedTools = stepContext.tools

    override val domainTypes: Collection<DomainType> = stepContext.dataDictionary.domainTypes

    override fun execute(processContext: ProcessContext): ActionStatus {
        val action = this
        return ActionRunner.execute(processContext) {
            val context = OperationContext(
                processContext = processContext,
                operation = action,
                toolGroups = emptySet(),
            )
            val templateModel = buildTemplateModel(processContext, inputs)
            val renderedPrompt = context.agentPlatform()
                .platformServices.templateRenderer
                .renderLiteralTemplate(executor.prompt, templateModel)

            val request = AgentRequest(
                prompt = { renderedPrompt },
                outputClass = String::class.java,
                tools = resolvedTools,
            )

            val maxAttempts = 1 + executor.maxRetries
            var bestResult: TypedResult.Success<String>? = null

            for (attempt in 1..maxAttempts) {
                when (val result = executor.executeTyped(request)) {
                    is TypedResult.Success -> {
                        val outcome = result.toOutcome()
                        val evaluation = executor.resultFitness(outcome)

                        logger.info(
                            "Executor '{}' attempt {}/{}: fitness={} {}",
                            executor.name, attempt, maxAttempts, evaluation.score,
                            evaluation.feedback?.let { "($it)" } ?: "",
                        )

                        val evaluated = result.copy(evaluation = evaluation)
                        if (bestResult == null || evaluation.score > bestResult.score) {
                            bestResult = evaluated
                        }

                        if (evaluation.score >= executor.fitnessThreshold) {
                            break
                        }
                    }

                    is TypedResult.Failure -> {
                        logger.warn(
                            "Executor '{}' attempt {}/{} failed: {}",
                            executor.name, attempt, maxAttempts, result.error,
                        )
                        if (bestResult == null && attempt == maxAttempts) {
                            throw RuntimeException("Agent execution failed: ${result.error}")
                        }
                    }
                }
            }

            val finalResult = bestResult
                ?: throw RuntimeException("Agent execution failed: all $maxAttempts attempts failed")

            if (finalResult.score < executor.fitnessThreshold) {
                logger.warn(
                    "Executor '{}' best fitness {} below threshold {} after {} attempts. {}",
                    executor.name, finalResult.score, executor.fitnessThreshold, maxAttempts,
                    finalResult.evaluation.feedback ?: "",
                )
            }

            processContext.blackboard[varName] = finalResult.value
        }
    }

    override fun referencedInputProperties(variable: String): Set<String> = emptySet()
}

private fun variableNameFor(typeName: String): String =
    typeName.substringAfterLast('.').decapitalize()

private fun buildTemplateModel(processContext: ProcessContext, inputs: Set<IoBinding>): Map<String, Any> {
    val model = mutableMapOf<String, Any>()
    model.putAll(processContext.blackboard.expressionEvaluationModel())
    for (input in inputs) {
        val value = processContext.blackboard[input.name]
            ?: throw IllegalArgumentException("Input variable '${input.name}' not found in process context.")
        model[input.name] = value
    }
    processContext.blackboard.last<UserInput>()?.let { model["userInput"] = it }
    return model
}
