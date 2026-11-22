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
package com.embabel.agent.api.client.openapi

import com.embabel.agent.api.client.ApiCredentials
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A list bound to a path parameter went out as its `toString()`: `GET /things/[a]`, and the
 * server's 404 read as "no such thing". Asserted on the raw path a real server receives.
 */
class OpenApiPathListValueTest {

    private val received = CopyOnWriteArrayList<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            received += exchange.requestURI.rawPath
            val bytes = "ok".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a list of one in a path parameter is sent as that one value`() {
        val result = getThing(listOf("plan_std"))

        assertFalse(result is Tool.Result.Error, result.toString())
        assertEquals(listOf("/things/plan_std"), received)
    }

    @Test
    fun `a list of several in a path parameter is refused unsent`() {
        val result = getThing(listOf("a", "b"))

        val message = (result as Tool.Result.Error).message
        assertTrue("Path parameter 'id' was given 2 values" in message, message)
        assertTrue(received.isEmpty(), "sent anyway: $received")
    }

    @Test
    fun `a scalar is sent as it was`() {
        val result = getThing("plan_std")

        assertFalse(result is Tool.Result.Error, result.toString())
        assertEquals(listOf("/things/plan_std"), received)
    }

    private fun getThing(id: Any): Tool.Result {
        val spec = """
            {
              "openapi": "3.0.3",
              "info": { "title": "Things", "version": "1.0.0" },
              "servers": [{ "url": "http://127.0.0.1:${server.address.port}" }],
              "paths": {
                "/things/{id}": {
                  "get": {
                    "operationId": "getThing",
                    "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
                    "responses": { "200": { "description": "ok" } }
                  }
                }
              }
            }
        """.trimIndent()
        val tool = OpenApiLearner.buildTool("inline", OpenApiLearner.parseSpec("inline", spec), ApiCredentials.None)
        val input = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(mapOf("id" to id))
        return leafTools(tool).single().call(input)
    }

    private fun leafTools(tool: Tool): List<Tool> = when (tool) {
        is ProgressiveTool -> tool.innerTools(
            org.mockito.Mockito.mock(com.embabel.agent.core.AgentProcess::class.java),
        ).flatMap(::leafTools)
        else -> listOf(tool)
    }
}
