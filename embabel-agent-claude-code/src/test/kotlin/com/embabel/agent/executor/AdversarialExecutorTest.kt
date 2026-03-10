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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdversarialExecutorTest {

    /**
     * Stub generator that returns a sequence of values.
     */
    private class StubGenerator(
        private val values: List<String>,
    ) : AgentExecutor {
        override val name = "stub"
        override val description = "Stub generator"
        override val prompt = "test"
        override val inputTypeNames = emptySet<String>()
        override val outputTypeName = "String"
        var callCount = 0; private set

        override fun <T : Any> executeTyped(request: AgentRequest<T>): TypedResult<T> {
            val index = callCount++
            val value = values.getOrElse(index) { values.last() }
            @Suppress("UNCHECKED_CAST")
            return TypedResult.Success(
                value = value as T,
                score = 1.0,
                attempts = index + 1,
                raw = null,
            )
        }
    }

    /**
     * Stub generator that always fails.
     */
    private class FailingGenerator : AgentExecutor {
        override val name = "failing"
        override val description = "Always fails"
        override val prompt = "test"
        override val inputTypeNames = emptySet<String>()
        override val outputTypeName = "String"

        override fun <T : Any> executeTyped(request: AgentRequest<T>): TypedResult<T> =
            TypedResult.Failure(error = "always fails")
    }

    private val request = AgentRequest(
        prompt = { "test prompt" },
        outputClass = String::class.java,
    )

    @Test
    fun `accepts on first attempt when critic approves`() {
        val generator = StubGenerator(listOf("good output"))
        val critic = ActionCritic { ActionFeedback.accept("looks great") }
        val executor = AdversarialExecutor(generator, critic)

        val result = executor.executeTyped(request)

        assertTrue(result is TypedResult.Success)
        assertEquals("good output", (result as TypedResult.Success).value)
        assertEquals(1.0, result.score)
        assertEquals(1, generator.callCount)
    }

    @Test
    fun `retries when critic rejects then accepts`() {
        val generator = StubGenerator(listOf("bad", "better", "good"))
        var callCount = 0
        val critic = ActionCritic {
            callCount++
            when (callCount) {
                1 -> ActionFeedback.reject(score = 0.2, feedback = "too short")
                2 -> ActionFeedback.reject(score = 0.6, feedback = "getting there")
                else -> ActionFeedback.accept("perfect")
            }
        }
        val executor = AdversarialExecutor(generator, critic, maxAttempts = 3)

        val result = executor.executeTyped(request)

        assertTrue(result is TypedResult.Success)
        assertEquals("good", (result as TypedResult.Success).value)
        assertEquals(1.0, result.score)
        assertEquals(3, generator.callCount)
    }

    @Test
    fun `returns best result when all attempts rejected`() {
        val generator = StubGenerator(listOf("v1", "v2", "v3"))
        var callCount = 0
        val critic = ActionCritic {
            callCount++
            when (callCount) {
                1 -> ActionFeedback.reject(score = 0.3, feedback = "poor")
                2 -> ActionFeedback.reject(score = 0.7, feedback = "ok but not great")
                else -> ActionFeedback.reject(score = 0.5, feedback = "meh")
            }
        }
        val executor = AdversarialExecutor(generator, critic, maxAttempts = 3)

        val result = executor.executeTyped(request)

        assertTrue(result is TypedResult.Success)
        // Best score was 0.7 from attempt 2
        assertEquals(0.7, (result as TypedResult.Success).score)
        assertEquals("v2", result.value)
    }

    @Test
    fun `returns failure when all attempts fail`() {
        val generator = FailingGenerator()
        val critic = ActionCritic { ActionFeedback.accept() }
        val executor = AdversarialExecutor(generator, critic, maxAttempts = 2)

        val result = executor.executeTyped(request)

        assertTrue(result is TypedResult.Failure)
        assertEquals("always fails", (result as TypedResult.Failure).error)
    }

    @Test
    fun `continues past failures if later attempt succeeds`() {
        var genCall = 0
        val generator = object : AgentExecutor {
            override val name = "mixed"
            override val description = "Sometimes fails"
            override val prompt = "test"
            override val inputTypeNames = emptySet<String>()
            override val outputTypeName = "String"

            override fun <T : Any> executeTyped(request: AgentRequest<T>): TypedResult<T> {
                genCall++
                return if (genCall == 1) {
                    TypedResult.Failure(error = "transient error")
                } else {
                    @Suppress("UNCHECKED_CAST")
                    TypedResult.Success(value = "recovered" as T, score = 1.0, attempts = genCall, raw = null)
                }
            }
        }
        val critic = ActionCritic { ActionFeedback.accept() }
        val executor = AdversarialExecutor(generator, critic, maxAttempts = 3)

        val result = executor.executeTyped(request)

        assertTrue(result is TypedResult.Success)
        assertEquals("recovered", (result as TypedResult.Success).value)
    }

    @Test
    fun `maxAttempts must be at least 1`() {
        val generator = StubGenerator(listOf("x"))
        val critic = ActionCritic { ActionFeedback.accept() }
        assertThrows<IllegalArgumentException> {
            AdversarialExecutor(generator, critic, maxAttempts = 0)
        }
    }

    @Test
    fun `single attempt mode works`() {
        val generator = StubGenerator(listOf("only one"))
        val critic = ActionCritic { ActionFeedback.reject(score = 0.5, feedback = "not great") }
        val executor = AdversarialExecutor(generator, critic, maxAttempts = 1)

        val result = executor.executeTyped(request)

        assertTrue(result is TypedResult.Success)
        assertEquals("only one", (result as TypedResult.Success).value)
        assertEquals(0.5, result.score)
        assertEquals(1, generator.callCount)
    }

    // --- ActionFeedback tests ---

    @Test
    fun `ActionFeedback accept creates accepted feedback`() {
        val fb = ActionFeedback.accept("good")
        assertEquals(1.0, fb.score)
        assertTrue(fb.accepted)
        assertEquals("good", fb.feedback)
    }

    @Test
    fun `ActionFeedback reject creates rejected feedback`() {
        val fb = ActionFeedback.reject(score = 0.3, feedback = "bad")
        assertEquals(0.3, fb.score)
        assertTrue(!fb.accepted)
        assertEquals("bad", fb.feedback)
    }

    @Test
    fun `ActionFeedback default accepted based on threshold`() {
        assertTrue(ActionFeedback(score = 0.9).accepted)
        assertTrue(ActionFeedback(score = 0.8).accepted)
        assertTrue(!ActionFeedback(score = 0.79).accepted)
        assertTrue(!ActionFeedback(score = 0.0).accepted)
    }

    // --- Delegates to generator for ActionSpec ---

    @Test
    fun `delegates name and description to generator`() {
        val generator = StubGenerator(listOf("x"))
        val critic = ActionCritic { ActionFeedback.accept() }
        val executor = AdversarialExecutor(generator, critic)

        assertEquals("stub", executor.name)
        assertEquals("Stub generator", executor.description)
        assertEquals("test", executor.prompt)
        assertEquals("String", executor.outputTypeName)
    }
}
