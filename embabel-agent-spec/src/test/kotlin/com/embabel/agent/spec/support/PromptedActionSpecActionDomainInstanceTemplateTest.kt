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
import com.embabel.agent.core.DomainInstance
import com.embabel.agent.core.DomainType
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spec.model.PromptedActionSpec
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the prompt-template flattening for [DomainInstance] inputs.
 *
 * Without this, a Jinja prompt like `{{ hubSpotContactCreated.email }}`
 * resolves against the carrier object (e.g. `DynamicSignal`), whose JVM
 * class exposes no `email` getter — so Jinja silently substitutes blank.
 * The fix exposes the [DomainInstance.properties] map at the template
 * boundary so the same dotted accessor walks into the property map and
 * returns the projected payload value.
 *
 * The carrier identity stays intact everywhere else (planner type
 * matching via [DomainInstance.domainType], dedup keys, signal store
 * persistence) — only the prompt template sees the flattened map.
 */
class PromptedActionSpecActionDomainInstanceTemplateTest {

    /** Minimal carrier — what `DynamicSignal` is in production. */
    private class FakeCarrier(
        override val domainType: DomainType,
        override val properties: Map<String, Any?>,
    ) : DomainInstance

    private fun createAction(): PromptedActionSpecAction {
        val spec = PromptedActionSpec(
            name = "new-in-hubspot",
            description = "Describe the new HubSpot contact",
            inputTypeNames = setOf("HubSpotContactCreated"),
            outputTypeName = "BackgroundMessage",
            prompt = "Use the fields available in {{ hubSpotContactCreated }}",
        )
        val inputs = setOf(
            IoBinding(PromptedActionSpec.variableNameFor("HubSpotContactCreated"), "HubSpotContactCreated"),
        )
        return PromptedActionSpecAction(
            spec = spec,
            inputs = inputs,
            domainTypes = emptyList(),
        )
    }

    private fun mockProcessContext(named: Map<String, Any>): ProcessContext {
        val blackboard = mockk<Blackboard>()
        every { blackboard.expressionEvaluationModel() } returns emptyMap()
        named.forEach { (key, value) -> every { blackboard[key] } returns value }
        every { blackboard.last(UserInput::class.java) } returns null
        val processContext = mockk<ProcessContext>()
        every { processContext.blackboard } returns blackboard
        return processContext
    }

    @Test
    fun `DomainInstance input is flattened to its properties map for the template`() {
        val type = DynamicType(name = "HubSpotContactCreated")
        val payload = mapOf<String, Any?>(
            "email" to "rod@example.com",
            "firstName" to "Rod",
            "lastName" to "Johnson",
            "portalId" to 7777777,
        )
        val signal = FakeCarrier(domainType = type, properties = payload)

        val action = createAction()
        val processContext = mockProcessContext(mapOf("hubSpotContactCreated" to signal))

        val result = action.templateModel(processContext)

        // The template now sees the property map, not the carrier instance —
        // so `{{ hubSpotContactCreated.email }}` resolves cleanly.
        assertEquals(payload, result["hubSpotContactCreated"])
        assertEquals("rod@example.com", (result["hubSpotContactCreated"] as Map<*, *>)["email"])
    }

    @Test
    fun `non-DomainInstance input is passed through unchanged`() {
        val action = createAction()
        // A plain Map (no DomainInstance) — used by some pack tools that
        // emit raw JSON. Behavior must not change for these.
        val rawMap = mapOf("type" to "raw", "value" to "untyped")
        val processContext = mockProcessContext(mapOf("hubSpotContactCreated" to rawMap))

        val result = action.templateModel(processContext)

        assertEquals(rawMap, result["hubSpotContactCreated"])
    }

    @Test
    fun `DomainInstance with empty properties yields an empty map, not null`() {
        // Defensive — must not regress to throwing or substituting null
        // when the projection produced an empty payload (rare, but
        // possible for tests / smoke-event signals).
        val type = DynamicType(name = "HubSpotContactCreated")
        val signal = FakeCarrier(domainType = type, properties = emptyMap())

        val action = createAction()
        val processContext = mockProcessContext(mapOf("hubSpotContactCreated" to signal))

        val result = action.templateModel(processContext)

        val flat = result["hubSpotContactCreated"]
        assertTrue(flat is Map<*, *>, "expected flattened Map, got ${flat?.let { it::class }}")
        assertEquals(0, (flat as Map<*, *>).size)
    }
}
