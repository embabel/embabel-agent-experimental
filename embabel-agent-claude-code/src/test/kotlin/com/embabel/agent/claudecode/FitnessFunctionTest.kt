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
import com.embabel.agent.executor.FitnessFunction
import com.embabel.agent.executor.FitnessFunctions
import com.embabel.agent.executor.allOf
import com.embabel.agent.executor.and
import com.embabel.agent.executor.or
import com.embabel.agent.executor.weightedFitness
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FitnessFunctionTest {

    @Test
    fun `and combinator returns minimum of two scores`() {
        val high: FitnessFunction<String> = { 0.9 }
        val low: FitnessFunction<String> = { 0.3 }
        val combined = high.and(low)
        assertEquals(0.3, combined("test"))
    }

    @Test
    fun `or combinator returns maximum of two scores`() {
        val high: FitnessFunction<String> = { 0.9 }
        val low: FitnessFunction<String> = { 0.3 }
        val combined = high.or(low)
        assertEquals(0.9, combined("test"))
    }

    @Test
    fun `weightedFitness computes weighted average`() {
        val always: FitnessFunction<String> = { 1.0 }
        val never: FitnessFunction<String> = { 0.0 }
        // weight 3 * 1.0 + weight 1 * 0.0 = 3.0 / 4.0 = 0.75
        val weighted = weightedFitness(3.0 to always, 1.0 to never)
        assertEquals(0.75, weighted("test"))
    }

    @Test
    fun `weightedFitness with equal weights averages scores`() {
        val half: FitnessFunction<Int> = { 0.5 }
        val full: FitnessFunction<Int> = { 1.0 }
        val weighted = weightedFitness(1.0 to half, 1.0 to full)
        assertEquals(0.75, weighted(42))
    }

    @Test
    fun `allOf returns minimum of all scores`() {
        val a: FitnessFunction<String> = { 0.8 }
        val b: FitnessFunction<String> = { 0.5 }
        val c: FitnessFunction<String> = { 0.9 }
        val combined = allOf(a, b, c)
        assertEquals(0.5, combined("test"))
    }

    @Test
    fun `allOf with no functions returns 1`() {
        val combined = allOf<String>()
        assertEquals(1.0, combined("test"))
    }

    @Test
    fun `alwaysPass returns 1`() {
        assertEquals(1.0, FitnessFunctions.alwaysPass<String>()("anything"))
    }

    @Test
    fun `alwaysFail returns 0`() {
        assertEquals(0.0, FitnessFunctions.alwaysFail<String>()("anything"))
    }

    @Test
    fun `nonBlank passes for non-blank strings`() {
        assertEquals(1.0, FitnessFunctions.nonBlank()("hello"))
    }

    @Test
    fun `nonBlank fails for blank strings`() {
        assertEquals(0.0, FitnessFunctions.nonBlank()(""))
        assertEquals(0.0, FitnessFunctions.nonBlank()("   "))
    }

    @Test
    fun `minLength returns 1 when met`() {
        assertEquals(1.0, FitnessFunctions.minLength(5)("hello"))
        assertEquals(1.0, FitnessFunctions.minLength(5)("hello world"))
    }

    @Test
    fun `minLength returns proportional score when not met`() {
        assertEquals(0.6, FitnessFunctions.minLength(5)("abc"), 0.01)
        assertEquals(0.0, FitnessFunctions.minLength(5)(""))
    }

    @Test
    fun `predicate returns 1 for true, 0 for false`() {
        val isPositive = FitnessFunctions.predicate<Int> { it > 0 }
        assertEquals(1.0, isPositive(5))
        assertEquals(0.0, isPositive(-1))
    }

    @Test
    fun `chaining and and or combinators works`() {
        val nonBlank = FitnessFunctions.nonBlank()
        val minLen = FitnessFunctions.minLength(3)
        val combined = nonBlank.and(minLen)
        assertEquals(0.0, combined(""))
        assertEquals(1.0, combined("hello"))
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
        assertEquals(1.0, request.fitnessFunction("anything"))
    }
}
