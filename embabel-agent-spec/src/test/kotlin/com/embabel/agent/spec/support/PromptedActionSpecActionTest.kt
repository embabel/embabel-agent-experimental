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
package com.embabel.agent.spec.support

import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spec.model.PromptedActionSpec
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PromptedActionSpecActionTest {

    private fun createAction(
        inputTypeNames: Set<String> = setOf("com.example.WebhookEvent"),
        outputTypeName: String = "com.example.Output",
        prompt: String = "{{ orderCreatedEvent.orderId }}",
    ): PromptedActionSpecAction {
        val spec = PromptedActionSpec(
            name = "testAction",
            description = "Test action",
            inputTypeNames = inputTypeNames,
            outputTypeName = outputTypeName,
            prompt = prompt,
        )
        val inputs = inputTypeNames.map {
            IoBinding(PromptedActionSpec.variableNameFor(it), it)
        }.toSet()
        return PromptedActionSpecAction(
            spec = spec,
            inputs = inputs,
            domainTypes = emptyList(),
        )
    }

    private fun mockProcessContext(
        namedBindings: Map<String, Any>,
        blackboardGet: Map<String, Any?> = namedBindings,
    ): ProcessContext {
        val blackboard = mockk<Blackboard>()
        every { blackboard.expressionEvaluationModel() } returns namedBindings
        blackboardGet.forEach { (key, value) ->
            every { blackboard[key] } returns value
        }
        every { blackboard.last(UserInput::class.java) } returns null

        val processContext = mockk<ProcessContext>()
        every { processContext.blackboard } returns blackboard
        return processContext
    }

    @Nested
    inner class TemplateModel {

        @Test
        fun `includes declared inputs`() {
            val action = createAction()
            val webhookEvent = mapOf("type" to "webhook")
            val processContext = mockProcessContext(
                namedBindings = mapOf("webhookEvent" to webhookEvent),
            )

            val result = action.templateModel(processContext)

            assertEquals(webhookEvent, result["webhookEvent"])
        }

        @Test
        fun `includes additional blackboard bindings beyond declared inputs`() {
            val action = createAction()
            val webhookEvent = mapOf("type" to "webhook")
            val orderPayload = mapOf("orderId" to "123", "customer" to "Alice", "total" to 99.99)
            val processContext = mockProcessContext(
                namedBindings = mapOf(
                    "webhookEvent" to webhookEvent,
                    "event" to webhookEvent,
                    "orderCreatedEvent" to orderPayload,
                ),
            )

            val result = action.templateModel(processContext)

            assertEquals(webhookEvent, result["webhookEvent"])
            assertEquals(orderPayload, result["orderCreatedEvent"])
            assertEquals(webhookEvent, result["event"])
        }

        @Test
        fun `declared inputs override blackboard bindings`() {
            val action = createAction()
            val blackboardWebhookEvent = mapOf("source" to "blackboard-model")
            val declaredWebhookEvent = mapOf("source" to "declared-input")
            val processContext = mockProcessContext(
                namedBindings = mapOf("webhookEvent" to blackboardWebhookEvent),
                blackboardGet = mapOf("webhookEvent" to declaredWebhookEvent),
            )

            val result = action.templateModel(processContext)

            // Declared input (from blackboard[name]) takes precedence
            assertEquals(declaredWebhookEvent, result["webhookEvent"])
        }

        @Test
        fun `includes UserInput when present`() {
            val action = createAction()
            val webhookEvent = mapOf("type" to "webhook")
            val blackboard = mockk<Blackboard>()
            every { blackboard.expressionEvaluationModel() } returns mapOf("webhookEvent" to webhookEvent)
            every { blackboard["webhookEvent"] } returns webhookEvent
            val userInput = UserInput("test user input")
            every { blackboard.last(UserInput::class.java) } returns userInput

            val processContext = mockk<ProcessContext>()
            every { processContext.blackboard } returns blackboard

            val result = action.templateModel(processContext)

            assertEquals(userInput, result["userInput"])
        }
    }
}
