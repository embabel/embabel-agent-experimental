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

import com.embabel.agent.executor.AgentRequest
import com.embabel.agent.executor.FitnessEvaluation
import com.embabel.agent.executor.FitnessFunction
import com.embabel.agent.executor.FitnessFunctions
import com.embabel.agent.executor.allOf
import com.embabel.agent.executor.and
import com.embabel.agent.executor.or
import com.embabel.agent.executor.weightedFitness
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FitnessFunctionTest {

    @Test
    fun `and combinator returns minimum of two scores`() {
        val high: FitnessFunction<String> = { FitnessEvaluation(0.9) }
        val low: FitnessFunction<String> = { FitnessEvaluation(0.3, "too low") }
        val combined = high.and(low)
        val result = combined("test")
        assertEquals(0.3, result.score)
        assertEquals("too low", result.feedback)
    }

    @Test
    fun `or combinator returns maximum of two scores`() {
        val high: FitnessFunction<String> = { FitnessEvaluation(0.9, "good") }
        val low: FitnessFunction<String> = { FitnessEvaluation(0.3) }
        val combined = high.or(low)
        val result = combined("test")
        assertEquals(0.9, result.score)
        assertEquals("good", result.feedback)
    }

    @Test
    fun `weightedFitness computes weighted average`() {
        val always: FitnessFunction<String> = { FitnessEvaluation.pass() }
        val never: FitnessFunction<String> = { FitnessEvaluation.fail("nope") }
        // weight 3 * 1.0 + weight 1 * 0.0 = 3.0 / 4.0 = 0.75
        val weighted = weightedFitness(3.0 to always, 1.0 to never)
        assertEquals(0.75, weighted("test").score)
    }

    @Test
    fun `weightedFitness with equal weights averages scores`() {
        val half: FitnessFunction<Int> = { FitnessEvaluation(0.5) }
        val full: FitnessFunction<Int> = { FitnessEvaluation(1.0) }
        val weighted = weightedFitness(1.0 to half, 1.0 to full)
        assertEquals(0.75, weighted(42).score)
    }

    @Test
    fun `weightedFitness concatenates feedback`() {
        val a: FitnessFunction<String> = { FitnessEvaluation(0.5, "short") }
        val b: FitnessFunction<String> = { FitnessEvaluation(1.0, "fine") }
        val weighted = weightedFitness(1.0 to a, 1.0 to b)
        assertEquals("short; fine", weighted("test").feedback)
    }

    @Test
    fun `allOf returns minimum of all scores`() {
        val a: FitnessFunction<String> = { FitnessEvaluation(0.8) }
        val b: FitnessFunction<String> = { FitnessEvaluation(0.5, "lowest") }
        val c: FitnessFunction<String> = { FitnessEvaluation(0.9) }
        val combined = allOf(a, b, c)
        val result = combined("test")
        assertEquals(0.5, result.score)
        assertEquals("lowest", result.feedback)
    }

    @Test
    fun `allOf with no functions returns pass`() {
        val combined = allOf<String>()
        assertEquals(1.0, combined("test").score)
    }

    @Test
    fun `alwaysPass returns 1`() {
        val result = FitnessFunctions.alwaysPass<String>()("anything")
        assertEquals(1.0, result.score)
        assertNull(result.feedback)
    }

    @Test
    fun `alwaysFail returns 0`() {
        val result = FitnessFunctions.alwaysFail<String>()("anything")
        assertEquals(0.0, result.score)
        assertNotNull(result.feedback)
    }

    @Test
    fun `nonBlank passes for non-blank strings`() {
        assertEquals(1.0, FitnessFunctions.nonBlank()("hello").score)
    }

    @Test
    fun `nonBlank fails for blank strings`() {
        val empty = FitnessFunctions.nonBlank()("")
        assertEquals(0.0, empty.score)
        assertNotNull(empty.feedback)
        val spaces = FitnessFunctions.nonBlank()("   ")
        assertEquals(0.0, spaces.score)
    }

    @Test
    fun `minLength returns 1 when met`() {
        assertEquals(1.0, FitnessFunctions.minLength(5)("hello").score)
        assertEquals(1.0, FitnessFunctions.minLength(5)("hello world").score)
    }

    @Test
    fun `minLength returns proportional score when not met`() {
        val result = FitnessFunctions.minLength(5)("abc")
        assertEquals(0.6, result.score, 0.01)
        assertNotNull(result.feedback)
        assertEquals(0.0, FitnessFunctions.minLength(5)("").score)
    }

    @Test
    fun `predicate returns 1 for true, 0 for false`() {
        val isPositive = FitnessFunctions.predicate<Int>("must be positive") { it > 0 }
        assertEquals(1.0, isPositive(5).score)
        val negative = isPositive(-1)
        assertEquals(0.0, negative.score)
        assertEquals("must be positive", negative.feedback)
    }

    @Test
    fun `chaining and and or combinators works`() {
        val nonBlank = FitnessFunctions.nonBlank()
        val minLen = FitnessFunctions.minLength(3)
        val combined = nonBlank.and(minLen)
        assertEquals(0.0, combined("").score)
        assertEquals(1.0, combined("hello").score)
    }

    @Test
    fun `FitnessEvaluation companion methods`() {
        assertEquals(1.0, FitnessEvaluation.pass().score)
        assertEquals(0.0, FitnessEvaluation.fail("bad").score)
        assertEquals("bad", FitnessEvaluation.fail("bad").feedback)
        assertEquals(0.7, FitnessEvaluation.of(0.7, "ok").score)
    }

    @Test
    fun `AgentRequest defaults are sensible`() {
        val request = AgentRequest(
            prompt = { "test" },
            outputClass = String::class.java,
        )
        assertEquals(0, request.maxRetries)
        assertEquals(0.8, request.fitnessThreshold)
        assertEquals(emptyList(), request.tools)
        assertEquals(1.0, request.fitnessFunction("anything").score)
    }
}
