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
 * A function that evaluates the quality of a value, returning a score between 0.0 and 1.0.
 */
typealias FitnessFunction<T> = (T) -> ZeroToOne

/**
 * Combines two fitness functions using minimum (logical AND).
 * Both functions must score high for the combined score to be high.
 */
fun <T> FitnessFunction<T>.and(other: FitnessFunction<T>): FitnessFunction<T> =
    { minOf(this(it), other(it)) }

/**
 * Combines two fitness functions using maximum (logical OR).
 * Either function scoring high is sufficient.
 */
fun <T> FitnessFunction<T>.or(other: FitnessFunction<T>): FitnessFunction<T> =
    { maxOf(this(it), other(it)) }

/**
 * Creates a weighted combination of fitness functions.
 * Each function's score is multiplied by its weight and the results are averaged.
 */
fun <T> weightedFitness(vararg weighted: Pair<Double, FitnessFunction<T>>): FitnessFunction<T> = { value ->
    weighted.sumOf { (weight, fn) -> weight * fn(value) } / weighted.sumOf { it.first }
}

/**
 * Combines multiple fitness functions using minimum (all must pass).
 */
fun <T> allOf(vararg fns: FitnessFunction<T>): FitnessFunction<T> = { value ->
    fns.minOfOrNull { it(value) } ?: 1.0
}

/**
 * Common built-in fitness functions.
 */
object FitnessFunctions {
    fun <T> alwaysPass(): FitnessFunction<T> = { 1.0 }
    fun <T> alwaysFail(): FitnessFunction<T> = { 0.0 }
    fun nonBlank(): FitnessFunction<String> = { if (it.isBlank()) 0.0 else 1.0 }
    fun minLength(min: Int): FitnessFunction<String> = { if (it.length >= min) 1.0 else it.length.toDouble() / min }
    fun <T> predicate(fn: (T) -> Boolean): FitnessFunction<T> = { if (fn(it)) 1.0 else 0.0 }
}

/**
 * Result of a typed execution with fitness evaluation.
 */
sealed interface TypedResult<T> {

    /**
     * Successful typed execution.
     *
     * @param value the deserialized output
     * @param score the fitness score of the output
     * @param attempts number of attempts made
     * @param raw the underlying raw result
     */
    data class Success<T>(
        val value: T,
        val score: ZeroToOne,
        val attempts: Int,
        val raw: Any?,
    ) : TypedResult<T>

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
     * Execute a typed request with fitness evaluation and optional retry.
     */
    fun <T : Any> executeTyped(request: AgentRequest<T>): TypedResult<T>

    /**
     * Whether this executor runs in a sandbox (e.g., Docker container).
     * When sandboxed, workspace MCP tools are not passed since the sandbox
     * can't reach the host's ephemeral MCP server.
     */
    fun isSandboxed(): Boolean = false

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

            // When the executor runs sandboxed (e.g., Docker), skip workspace MCP tools —
            // the container can't reach the host's ephemeral MCP server, and the sandboxed
            // CLI (e.g., Claude Code) has its own built-in tools.
            val effectiveTools = if (executor.isSandboxed()) emptyList() else resolvedTools

            // Wire streaming progress into the output channel so the UI
            // can show real-time updates while the executor runs.
            val outputChannel = processContext.processOptions.outputChannel
            val processId = processContext.agentProcess.id
            val logger = org.slf4j.LoggerFactory.getLogger("AgentExecutorAction")
            logger.info("Output channel type for {}: {} (processId={})",
                executor.name, outputChannel::class.simpleName, processId)

            // Send an immediate progress event so the UI shows the action is running
            val sandboxLabel = if (executor.isSandboxed()) " (sandbox)" else ""
            outputChannel.send(
                com.embabel.agent.api.channel.ProgressOutputChannelEvent(
                    processId = processId,
                    message = "${executor.name}: executing$sandboxLabel...",
                )
            )

            val progressCallback: ((Any) -> Unit)? = if (outputChannel != null) {
                { event ->
                    when (event) {
                        is com.embabel.agent.claudecode.ClaudeStreamEvent.Text ->
                            outputChannel.send(
                                com.embabel.agent.api.channel.ProgressOutputChannelEvent(
                                    processId = processId,
                                    message = "${executor.name}: ${event.text.take(120)}",
                                )
                            )
                        is com.embabel.agent.claudecode.ClaudeStreamEvent.Error ->
                            outputChannel.send(
                                com.embabel.agent.api.channel.ProgressOutputChannelEvent(
                                    processId = processId,
                                    message = "${executor.name}: error — ${event.message.take(120)}",
                                )
                            )
                        is com.embabel.agent.claudecode.ClaudeStreamEvent.Complete ->
                            outputChannel.send(
                                com.embabel.agent.api.channel.ProgressOutputChannelEvent(
                                    processId = processId,
                                    message = "${executor.name}: completed (${event.turns} turns)",
                                )
                            )
                    }
                }
            } else null

            val request = AgentRequest(
                prompt = { renderedPrompt },
                outputClass = String::class.java,
                tools = effectiveTools,
                streamCallback = progressCallback,
            )

            when (val result = executor.executeTyped(request)) {
                is TypedResult.Success -> {
                    // If the output type is BackgroundMessage but the executor returned a String,
                    // wrap it so the planner can match the expected type.
                    val value = if (executor.outputTypeName.endsWith("BackgroundMessage") && result.value is String) {
                        com.embabel.agent.spec.model.BackgroundMessage(content = result.value as String)
                    } else {
                        result.value
                    }
                    processContext.blackboard[varName] = value
                }

                is TypedResult.Failure ->
                    throw RuntimeException("Agent execution failed: ${result.error}")
            }
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
