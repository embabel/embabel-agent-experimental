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

import com.embabel.common.core.types.ZeroToOne
import org.slf4j.LoggerFactory

/**
 * Structured feedback from a critic evaluating an action's output.
 *
 * @param score quality score between 0.0 and 1.0
 * @param feedback human-readable critique explaining the score
 * @param accepted whether the output is acceptable (true = stop looping)
 */
data class ActionFeedback(
    val score: ZeroToOne,
    val feedback: String? = null,
    val accepted: Boolean = score >= DEFAULT_ACCEPTANCE_THRESHOLD,
) {
    companion object {
        const val DEFAULT_ACCEPTANCE_THRESHOLD = 0.8

        fun accept(feedback: String? = null) = ActionFeedback(1.0, feedback, accepted = true)
        fun reject(score: ZeroToOne = 0.0, feedback: String) = ActionFeedback(score, feedback, accepted = false)
    }
}

/**
 * A critic that evaluates the output of an [AgentExecutor].
 *
 * Implementations can be simple functions, rule-based validators,
 * or LLM-powered evaluators (themselves backed by an [AgentExecutor]).
 */
fun interface ActionCritic {

    /**
     * Evaluate the given output and return structured feedback.
     *
     * @param output the output produced by the generator
     * @return feedback with a score and optional critique
     */
    fun evaluate(output: Any): ActionFeedback
}

/**
 * An [AgentExecutor] that pairs a generator with a critic in a retry loop.
 *
 * The generator produces output, the critic evaluates it. If the critic
 * rejects the output, the generator runs again — up to [maxAttempts] times.
 * The best result (highest critic score) is returned.
 *
 * This implements the adversarial/generator-critic pattern, where the
 * critic can be anything: a rule-based validator, another LLM, or a
 * composite of both.
 *
 * ```kotlin
 * val generator = ClaudeCodeAgentExecutor(name = "writer", prompt = "Write a haiku about {{topic}}")
 * val critic = ActionCritic { output ->
 *     if (output.toString().lines().size == 3) ActionFeedback.accept()
 *     else ActionFeedback.reject(feedback = "A haiku must have exactly 3 lines")
 * }
 * val adversarial = AdversarialExecutor(generator, critic, maxAttempts = 3)
 * ```
 *
 * @param generator the executor that produces output
 * @param critic evaluates the generator's output
 * @param maxAttempts maximum number of generate-critique cycles (must be >= 1)
 * @param failOnReject if true, return [TypedResult.Failure] when the critic never accepts;
 *                     if false (default), return the best attempt even if not accepted
 */
class AdversarialExecutor(
    private val generator: AgentExecutor,
    private val critic: ActionCritic,
    private val maxAttempts: Int = 3,
    private val failOnReject: Boolean = false,
) : AgentExecutor {

    override val name: String get() = generator.name
    override val description: String get() = generator.description
    override val prompt: String get() = generator.prompt
    override val inputTypeNames: Set<String> get() = generator.inputTypeNames
    override val outputTypeName: String get() = generator.outputTypeName
    override val stepType: String get() = generator.stepType

    private val logger = LoggerFactory.getLogger(AdversarialExecutor::class.java)

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
    }

    override fun <T : Any> executeTyped(request: AgentRequest<T>): TypedResult<T> {
        var bestResult: TypedResult.Success<T>? = null

        for (attempt in 1..maxAttempts) {
            val result = generator.executeTyped(request)

            if (result is TypedResult.Failure) {
                logger.warn("Generator attempt {}/{} failed: {}", attempt, maxAttempts, result.error)
                if (bestResult == null && attempt == maxAttempts) {
                    return result
                }
                continue
            }

            val success = result as TypedResult.Success<T>
            val feedback = critic.evaluate(success.value)

            logger.info(
                "Attempt {}/{}: critic score={}, accepted={} {}",
                attempt, maxAttempts, feedback.score, feedback.accepted,
                feedback.feedback?.let { "- $it" } ?: "",
            )

            val scored = success.copy(score = feedback.score)
            if (bestResult == null || feedback.score > bestResult.score) {
                bestResult = scored
            }

            if (feedback.accepted) {
                return bestResult
            }
        }

        val best = bestResult
        if (best != null) {
            if (failOnReject) {
                logger.warn(
                    "Critic did not accept after {} attempts, rejecting (score={})",
                    maxAttempts, best.score,
                )
                return TypedResult.Failure(
                    error = "Critic did not accept after $maxAttempts attempts (best score: ${best.score})",
                    raw = best.raw,
                )
            }
            logger.warn(
                "Critic did not accept after {} attempts, returning best (score={})",
                maxAttempts, best.score,
            )
            return best
        }

        return TypedResult.Failure(error = "All $maxAttempts attempts failed")
    }

    companion object {

        /**
         * Create a critic from an [AgentExecutor] that returns [ActionFeedback].
         *
         * The critic executor receives the generator's output as its prompt
         * and must return JSON deserializable to [ActionFeedback].
         */
        fun criticFrom(executor: AgentExecutor): ActionCritic = ActionCritic { output ->
            val request = AgentRequest(
                prompt = { output.toString() },
                outputClass = ActionFeedback::class.java,
            )
            when (val result = executor.executeTyped(request)) {
                is TypedResult.Success -> result.value
                is TypedResult.Failure -> ActionFeedback.reject(feedback = "Critic failed: ${result.error}")
            }
        }
    }
}
