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
 * The argument checks of [OpenApiArgumentValidationTest] hold however the spec is shaped: through
 * a chain of `$ref`s, behind a JSON body listed after another content type or a form body listed
 * after a non-form one, and for a parameter that is itself a `$ref`. A value of the wrong shape cannot
 * slip past a closed schema, and an enum is matched by value, so a valid call is not refused.
 * Bodies under `allOf`/`oneOf`/`anyOf` are sent unvalidated.
 * Every case runs on both the fully-resolved parse and the ref-preserving one.
 */
class OpenApiArgumentShapeValidationTest {

    private val received = CopyOnWriteArrayList<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            received += "${exchange.requestMethod} ${exchange.requestURI}" + (if (body.isEmpty()) "" else " $body")
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a closed schema reached through a chain of refs is enforced`(preserveRefs: Boolean) {
        assertRefused(call(preserveRefs, "chained", """{"properties":{"secret":"x"}}"""), "properties.secret")
        assertSent(call(preserveRefs, "chained", """{"properties":{"amount":"5"}}"""))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a parameter enum reached through a chain of refs is enforced`(preserveRefs: Boolean) {
        assertRefused(call(preserveRefs, "chainedQuery", """{"q":"outside"}"""), "Query parameter 'q'")
        assertSent(call(preserveRefs, "chainedQuery", """{"q":"a"}"""))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an array where a closed object belongs is refused unsent`(preserveRefs: Boolean) {
        assertRefused(call(preserveRefs, "chained", """{"properties":[{"secret":"x"}]}"""), "properties")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an object where an array belongs is refused unsent`(preserveRefs: Boolean) {
        assertRefused(call(preserveRefs, "chained", """{"children":{"secret":"x"}}"""), "children")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a nullable closed object still takes null`(preserveRefs: Boolean) {
        assertSent(call(preserveRefs, "chained", """{"properties":null}"""))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an enum listing null accepts its other values and not the text null`(preserveRefs: Boolean) {
        assertSent(call(preserveRefs, "nullableEnum", """{"q":"a"}"""))
        assertRefused(call(preserveRefs, "nullableEnum", """{"q":"null"}"""), "Query parameter 'q'")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an enum of arrays is matched against the whole array`(preserveRefs: Boolean) {
        assertSent(call(preserveRefs, "arrayEnum", """{"q":["a","b"]}"""))
        assertTrue(received.single().endsWith("?q=a&q=b"), received.toString())
        assertRefused(call(preserveRefs, "arrayEnum", """{"q":["a","c"]}"""), "Query parameter 'q'")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a number enum is matched by numeric value`(preserveRefs: Boolean) {
        assertSent(call(preserveRefs, "numberEnum", """{"q":1.0}"""))
        assertRefused(call(preserveRefs, "numberEnum", """{"q":3}"""), "Query parameter 'q'")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a composed body is sent unvalidated`(preserveRefs: Boolean) {
        for (op in listOf("allOf", "oneOf", "anyOf")) {
            assertSent(call(preserveRefs, op, """{"body":{"amount":"5","secret":"x"}}"""))
            assertSent(call(preserveRefs, op, """{"body":{"secret":"x"}}"""))
        }
        // A wrong-shaped branch no longer excuses or blocks anything: the union is not walked.
        assertSent(call(preserveRefs, "oneOfClosedChildren", """{"body":{"payload":{"secret":"x"}}}"""))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a self-referencing allOf is sent without recursing`(preserveRefs: Boolean) {
        assertSent(call(preserveRefs, "loop", """{"payload":{"amount":"5"}}"""))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a scalar path enum sends a list of one as its value and refuses a list holding null`(preserveRefs: Boolean) {
        // A list of one is sent as that value (#60).
        assertSent(call(preserveRefs, "sharedParameter", """{"scope":["notes"]}"""))
        assertEquals(listOf("GET /scoped/notes"), received)
        assertRefused(call(preserveRefs, "sharedParameter", """{"scope":[null]}"""), "Path parameter 'scope'")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `the form schema is checked when the form body is listed after another content type`(preserveRefs: Boolean) {
        assertRefused(call(preserveRefs, "formAfterText", """{"body":{"secret":"x"}}"""), "secret")
        assertSent(call(preserveRefs, "formAfterText", """{"amount":"5"}"""))
        assertTrue(received.single().endsWith(" amount=5"), received.toString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `only a closed object refuses a value of another shape`(preserveRefs: Boolean) {
        assertSent(call(preserveRefs, "openShapes", """{"explicitOpen":["a"],"keywordOpen":["a"]}"""))
        // Closure without `type` is still closure: the array is refused rather than let past it.
        assertRefused(call(preserveRefs, "openShapes", """{"keywordClosed":["a"]}"""), "keywordClosed")
        assertRefused(call(preserveRefs, "chained", """{"properties":"secret=x"}"""), "properties")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `the JSON schema is checked when JSON is listed after another content type`(preserveRefs: Boolean) {
        assertRefused(call(preserveRefs, "mixedContent", """{"properties":{"secret":"x"}}"""), "properties.secret")
        assertSent(call(preserveRefs, "mixedContent", """{"properties":{"amount":"5"}}"""))
        assertTrue(received.single().endsWith("""{"properties":{"amount":"5"}}"""), received.toString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a path parameter that is a ref to a shared parameter keeps its enum`(preserveRefs: Boolean) {
        assertRefused(call(preserveRefs, "sharedParameter", """{"scope":"contacts"}"""), "Path parameter 'scope'")
        assertSent(call(preserveRefs, "sharedParameter", """{"scope":"notes"}"""))
        assertTrue(received.single().startsWith("GET /scoped/notes"), received.toString())
    }

    private fun assertRefused(result: Tool.Result, names: String) {
        val message = (result as? Tool.Result.Error)?.message ?: throw AssertionError("sent: $received")
        assertTrue(names in message, message)
        assertTrue("not sent" in message, message)
        assertTrue(received.isEmpty(), "sent anyway: $received")
    }

    private fun assertSent(result: Tool.Result) = assertFalse(result is Tool.Result.Error, result.toString())

    private fun call(preserveRefs: Boolean, operationId: String, input: String): Tool.Result {
        received.clear()
        val spec: OpenAPI = if (preserveRefs) {
            OpenApiLearner.parseSpecPreservingRefs("inline", spec())
        } else {
            OpenApiLearner.parseSpec("inline", spec())
        }
        val tool = leafTools(OpenApiLearner.buildTool("inline", spec, ApiCredentials.None))
            .single { it.definition.name == operationId }
        return tool.call(jacksonObjectMapper().readTree(input).toString())
    }

    private fun leafTools(tool: Tool): List<Tool> = when (tool) {
        is ProgressiveTool -> tool.innerTools(mock(AgentProcess::class.java)).flatMap(::leafTools)
        else -> listOf(tool)
    }

    private fun op(path: String, operationId: String, extra: String) = """
        "$path": { "post": { "operationId": "$operationId", $extra "responses": { "200": { "description": "ok" } } } }
    """

    private fun jsonBody(schema: String) = """"requestBody": { "content": { "application/json": { "schema": $schema } } },"""

    private fun query(schema: String) = """"parameters": [{ "name": "q", "in": "query", "schema": $schema }],"""

    private fun ref(name: String) = """{ "${'$'}ref": "#/components/schemas/$name" }"""

    private fun spec() = """
        {
          "openapi": "3.0.3",
          "info": { "title": "Shapes", "version": "1.0.0" },
          "servers": [{ "url": "http://127.0.0.1:${server.address.port}" }],
          "paths": {
            ${op("/chained", "chained", jsonBody(ref("Root")))},
            ${op("/chained-query", "chainedQuery", query(ref("AliasEnum")))},
            ${op("/nullable-enum", "nullableEnum", query("""{ "type": "string", "nullable": true, "enum": ["a", null] }"""))},
            ${op("/array-enum", "arrayEnum", query("""{ "type": "array", "enum": [["a", "b"], ["c"]], "items": { "type": "string" } }"""))},
            ${op("/number-enum", "numberEnum", query("""{ "type": "number", "enum": [1, 2] }"""))},
            ${op("/all-of", "allOf", jsonBody("""{ "allOf": [${ref("Closed")}, { "type": "object", "properties": { "name": { "type": "string" } } }] }"""))},
            ${op("/one-of", "oneOf", jsonBody("""{ "oneOf": [${ref("Closed")}, ${ref("ClosedName")}] }"""))},
            ${op("/any-of", "anyOf", jsonBody("""{ "anyOf": [${ref("Closed")}, ${ref("ClosedName")}] }"""))},
            ${op("/mixed", "mixedContent", """"requestBody": { "content": {
                "text/plain": { "schema": { "type": "string" } },
                "application/json": { "schema": ${ref("Root")} } } },""")},
            ${op("/one-of-closed-children", "oneOfClosedChildren", jsonBody("""{ "oneOf": [
                { "type": "object", "additionalProperties": false, "properties": { "payload": ${ref("Closed")} } },
                { "type": "object", "additionalProperties": false, "properties": { "payload": { "type": "string" } } }] }"""))},
            ${op("/loop", "loop", jsonBody("""{ "type": "object", "properties": { "payload": ${ref("Loop")} } }"""))},
            ${op("/form", "formAfterText", """"requestBody": { "content": {
                "text/plain": { "schema": { "type": "string" } },
                "application/x-www-form-urlencoded": { "schema": ${ref("Closed")} } } },""")},
            ${op("/open-shapes", "openShapes", jsonBody("""{ "type": "object", "properties": {
                "explicitOpen": { "type": "object", "additionalProperties": true },
                "keywordOpen": { "properties": { "amount": { "type": "string" } } },
                "keywordClosed": { "properties": { "amount": { "type": "string" } }, "additionalProperties": false } } }"""))},
            "/scoped/{scope}": { "get": { "operationId": "sharedParameter",
              "parameters": [{ "${'$'}ref": "#/components/parameters/Scope" }],
              "responses": { "200": { "description": "ok" } } } }
          },
          "components": {
            "parameters": {
              "Scope": { "name": "scope", "in": "path", "required": true, "schema": { "type": "string", "enum": ["notes", "tasks"] } }
            },
            "schemas": {
              "Root": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "properties": { "${'$'}ref": "#/components/schemas/Alias" },
                  "children": { "type": "array", "items": ${ref("Alias")} }
                }
              },
              "Alias": ${ref("NullableClosed")},
              "NullableClosed": { "type": "object", "nullable": true, "additionalProperties": false, "properties": { "amount": { "type": "string" } } },
              "Closed": { "type": "object", "additionalProperties": false, "properties": { "amount": { "type": "string" } } },
              "Loop": { "allOf": [${ref("Loop")}, ${ref("Closed")}] },
              "ClosedName": { "type": "object", "additionalProperties": false, "properties": { "name": { "type": "string" } } },
              "AliasEnum": ${ref("Enum")},
              "Enum": { "type": "string", "enum": ["a", "b"] }
            }
          }
        }
    """.trimIndent()
}
