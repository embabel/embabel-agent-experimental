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
 * Implementations handle the full flow of executing a prompt, parsing structured output,
 * evaluating fitness, and optionally retrying.
 */
interface AgentExecutor {

    /**
     * Execute a typed request with fitness evaluation and optional retry.
     *
     * @param request the typed agent request
     * @return the typed result with fitness score
     */
    fun <T : Any> executeTyped(request: AgentRequest<T>): TypedResult<T>
}
