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
import com.embabel.agent.core.AgentProcess
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.swagger.v3.oas.models.OpenAPI
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A model could send what the spec forbids: a property a closed (`additionalProperties: false`)
 * body schema does not declare, at any depth and through either the flat or the `body`-wrapper
 * form, or a path or query value outside its `enum`. Each is refused before a request leaves,
 * asserted against a real loopback server. Every case runs on both the fully-resolved parse and
 * the ref-preserving one, so `$ref` resolution is covered too.
 */
class OpenApiArgumentValidationTest {

    private val received = CopyOnWriteArrayList<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            received += "${exchange.requestMethod} ${exchange.requestURI.rawPath}" +
                (exchange.requestURI.rawQuery?.let { "?$it" } ?: "") + (if (body.isEmpty()) "" else " $body")
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    // --- (a) closed body schemas ---

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a nested property a closed schema does not declare is refused unsent, flat form`(preserveRefs: Boolean) {
        val result = call(preserveRefs, "updateDeal", """{"dealId":"7","properties":{"amount":"5","dealstage":"won"}}""")

        assertRefused(result, "properties.dealstage")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a nested property a closed schema does not declare is refused unsent, body wrapper form`(preserveRefs: Boolean) {
        val result = call(preserveRefs, "updateDeal", """{"dealId":"7","body":{"properties":{"dealstage":"won"}}}""")

        assertRefused(result, "properties.dealstage")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a top-level property a closed schema does not declare is refused unsent, body wrapper form`(preserveRefs: Boolean) {
        val result = call(preserveRefs, "updateDeal", """{"dealId":"7","body":{"properties":{"amount":"5"},"pipeline":"x"}}""")

        assertRefused(result, "pipeline")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a wrapper and flat fields merged still cannot smuggle a nested property`(preserveRefs: Boolean) {
        val result = call(
            preserveRefs, "updateDeal",
            """{"dealId":"7","body":{"properties":{"dealstage":"won"}},"properties":{"amount":"5","dealstage":"won"}}""",
        )

        assertRefused(result, "properties.dealstage")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `declared properties of a closed schema are sent`(preserveRefs: Boolean) {
        val result = call(preserveRefs, "updateDeal", """{"dealId":"7","properties":{"amount":"5","dealname":"A"}}""")

        assertSent(result)
        assertEquals(listOf("""PATCH /deals/7 {"properties":{"amount":"5","dealname":"A"}}"""), received)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an open schema stays permissive`(preserveRefs: Boolean) {
        val result = call(preserveRefs, "updateOpen", """{"id":"7","body":{"properties":{"anything":"goes"},"extra":1}}""")

        assertSent(result)
        assertEquals(listOf("""POST /open/7 {"properties":{"anything":"goes"},"extra":1}"""), received)
    }

    // --- (b) enum on path and query parameters ---

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a path value outside its enum is refused unsent`(preserveRefs: Boolean) {
        val result = call(preserveRefs, "associate", """{"fromObjectType":"contacts","id":"1","toObjectType":"companies"}""")

        assertRefused(result, "Path parameter 'fromObjectType'")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a path value inside its enum is sent`(preserveRefs: Boolean) {
        val result = call(preserveRefs, "associate", """{"fromObjectType":"notes","id":"1","toObjectType":"companies"}""")

        assertSent(result)
        assertEquals(listOf("PUT /objects/notes/1/associations/companies"), received)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a query value outside its enum is refused unsent`(preserveRefs: Boolean) {
        val result = call(preserveRefs, "search", """{"kind":"c"}""")

        assertRefused(result, "Query parameter 'kind'")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a query value inside its enum is sent`(preserveRefs: Boolean) {
        val result = call(preserveRefs, "search", """{"kind":"a"}""")

        assertSent(result)
        assertEquals(listOf("GET /search?kind=a"), received)
    }

    private fun assertRefused(result: Tool.Result, names: String) {
        val message = (result as Tool.Result.Error).message
        assertTrue(names in message, message)
        assertTrue("not sent" in message, message)
        assertTrue(received.isEmpty(), "sent anyway: $received")
    }

    private fun assertSent(result: Tool.Result) = assertFalse(result is Tool.Result.Error, result.toString())

    private fun call(preserveRefs: Boolean, operationId: String, input: String): Tool.Result {
        val spec: OpenAPI = if (preserveRefs) {
            OpenApiLearner.parseSpecPreservingRefs("inline", spec())
        } else {
            OpenApiLearner.parseSpec("inline", spec())
        }
        val tool = leafTools(OpenApiLearner.buildTool("inline", spec, ApiCredentials.None))
            .single { it.definition.name == operationId }
        // Round-trip so the input is exactly what a model would send.
        return tool.call(jacksonObjectMapper().readTree(input).toString())
    }

    private fun leafTools(tool: Tool): List<Tool> = when (tool) {
        is ProgressiveTool -> tool.innerTools(mock(AgentProcess::class.java)).flatMap(::leafTools)
        else -> listOf(tool)
    }

    private fun spec() = """
        {
          "openapi": "3.0.3",
          "info": { "title": "Deals", "version": "1.0.0" },
          "servers": [{ "url": "http://127.0.0.1:${server.address.port}" }],
          "paths": {
            "/deals/{dealId}": {
              "patch": {
                "operationId": "updateDeal",
                "parameters": [{ "name": "dealId", "in": "path", "required": true, "schema": { "type": "string" } }],
                "requestBody": { "content": { "application/json": { "schema": { "${'$'}ref": "#/components/schemas/DealUpdate" } } } },
                "responses": { "200": { "description": "ok" } }
              }
            },
            "/open/{id}": {
              "post": {
                "operationId": "updateOpen",
                "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }],
                "requestBody": { "content": { "application/json": { "schema": {
                  "type": "object",
                  "properties": { "properties": { "type": "object", "properties": { "amount": { "type": "string" } } } }
                } } } },
                "responses": { "200": { "description": "ok" } }
              }
            },
            "/objects/{fromObjectType}/{id}/associations/{toObjectType}": {
              "put": {
                "operationId": "associate",
                "parameters": [
                  { "name": "fromObjectType", "in": "path", "required": true, "schema": { "type": "string", "enum": ["notes", "tasks"] } },
                  { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } },
                  { "name": "toObjectType", "in": "path", "required": true, "schema": { "type": "string", "enum": ["contacts", "companies"] } }
                ],
                "responses": { "200": { "description": "ok" } }
              }
            },
            "/search": {
              "get": {
                "operationId": "search",
                "parameters": [{ "name": "kind", "in": "query", "schema": { "type": "string", "enum": ["a", "b"] } }],
                "responses": { "200": { "description": "ok" } }
              }
            }
          },
          "components": {
            "schemas": {
              "DealUpdate": {
                "type": "object",
                "additionalProperties": false,
                "properties": { "properties": { "${'$'}ref": "#/components/schemas/DealProperties" } }
              },
              "DealProperties": {
                "type": "object",
                "additionalProperties": false,
                "properties": { "amount": { "type": "string" }, "dealname": { "type": "string" } }
              }
            }
          }
        }
    """.trimIndent()
}
