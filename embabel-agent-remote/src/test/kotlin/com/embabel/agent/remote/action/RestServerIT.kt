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
package com.embabel.agent.remote.action

import com.embabel.agent.core.Action
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.Socket

/**
 * Integration tests for [RestServer] communication with external REST API server.
 *
 * **Prerequisites:** Requires an external server running on `localhost:8000`.
 * Tests are skipped automatically if the server is not available.
 *
 * Not run under CI - use `mvn -Dtest=*IT test` to run manually.
 */
class RestServerIT {

    @BeforeEach
    fun checkServerAvailable() {
        assumeTrue(
            isServerAvailable(),
            "Skipping: external server not available on localhost:8000"
        )
    }

    /**
     * Checks if the required REST server is listening on localhost:8000.
     */
    private fun isServerAvailable(): Boolean = try {
        Socket("localhost", 8000).use { true }
    } catch (e: Exception) {
        false
    }

    @Test
    fun testConnection() {
        val agentPlatform = dummyAgentPlatform()
        val registration = RestServerRegistration(
            baseUrl = "http://localhost:8000",
            name = "python",
            description = "python actions",
        )
        val objectMapper = jacksonObjectMapper()
        val restClient = RestServer.createRestClient(objectMapper)
        val restServer = RestServer(
            registration,
            restClient,
            objectMapper,
        )
        val agentScope = restServer.agentScope(agentPlatform)
        assertTrue(agentScope.actions.isNotEmpty(), "Should have had agents")
    }

    @Test
    fun testInvokeAction() {
        val agentPlatform = dummyAgentPlatform()
        val registration = RestServerRegistration(
            baseUrl = "http://localhost:8000",
            name = "python",
            description = "python actions",
        )
        val objectMapper = jacksonObjectMapper()
        val restClient = RestServer.createRestClient(objectMapper)
        val restServer = RestServer(
            registration,
            restClient,
            objectMapper,
        )
        val agentScope = restServer.agentScope(agentPlatform)
        assertTrue(agentScope.actions.isNotEmpty(), "Should have had agents")
        val greet: Action = agentScope.actions.first { it.name == "greet" }
        val pc = mockk<ProcessContext>(relaxed = true)
        every { pc.agentProcess.getValue("input", "GreetingInput") } returns mapOf(
            "name" to "Bob", "language" to "en"
        )

        greet.execute(pc)

        // Test passes if no exception is thrown
    }

}
