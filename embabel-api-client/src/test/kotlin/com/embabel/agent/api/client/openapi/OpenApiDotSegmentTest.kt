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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A path value of `.` or `..` was sent as a dot segment, `GET /things/..`, which a normalizing
 * server reads as the parent route (embabel/me#2418). Asserted on the raw path a real server
 * receives, because that is where a dot segment does its damage.
 */
class OpenApiDotSegmentTest {

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

    @ParameterizedTest
    @ValueSource(strings = ["..", ".", "%2e%2e", "%2E", ".%2E", "%252e%252e"])
    fun `a path value that is a dot segment, plain or percent-encoded, is refused unsent`(value: String) {
        val result = getThing(value)

        val message = (result as Tool.Result.Error).message
        assertTrue("Path parameter 'id'" in message, message)
        assertTrue("'$value'" in message, message)
        assertTrue(received.isEmpty(), "sent anyway: $received")
    }

    @ParameterizedTest
    @CsvSource("v1.2, /things/v1.2", "a..b, /things/a..b", "..., /things/...", ".hidden, /things/.hidden", "../x, /things/..%2Fx")
    fun `a value with dots that is not a dot segment is sent as it is`(value: String, path: String) {
        val result = getThing(value)

        assertFalse(result is Tool.Result.Error, result.toString())
        assertEquals(listOf(path), received)
    }

    private fun getThing(id: String): Tool.Result {
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
