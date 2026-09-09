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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class OpenApiOperationNameCollisionTest {

    @Test
    fun `model preserves colliding operation ids with deterministic callable names`() {
        val expected = mapOf(
            "/cats" to ("find/record" to "find_record_get_cats"),
            "/dogs" to ("find_record" to "find_record_get_dogs"),
            "/birds" to ("list-birds" to "list-birds"),
            "/frogs" to ("list.frogs" to "list_frogs"),
            "/unnamed" to (null to "get_unnamed"),
            "/foo.bar" to ("look.here" to "look_here_get_foo_bar_a57ed7b9"),
            "/foo/bar" to ("look/here" to "look_here_get_foo_bar_bf7551e2"),
        )

        assertEquals(expected, model(catFirst = true))
        assertEquals(expected, model(catFirst = false))
    }

    @Test
    fun `operation with multiple tags remains one modeled operation`() {
        val model = buildModel(collisionSpec(catFirst = true))

        assertEquals(1, model.allOperations.count { it.path == "/cats" })
        assertEquals(
            setOf("animals", "search"),
            model.resources.filter { resource -> resource.operations.any { it.path == "/cats" } }.map { it.name }.toSet(),
        )
    }

    @Test
    fun `legacy sanitized allowlists and synthesized names still match`() {
        val model = buildModel(collisionSpec(catFirst = true))

        assertEquals(listOf("/frogs"), model.filterByOperationIds(setOf("list/frogs")).allOperations.map { it.path })
        assertEquals(listOf("/unnamed"), model.filterByOperationIds(setOf("get_unnamed")).allOperations.map { it.path })
        assertEquals(null, model.allOperations.single { it.path == "/unnamed" }.operationId)
    }

    @Test
    fun `colliding operations can be allowlisted independently and together`() {
        val model = buildModel(collisionSpec(catFirst = true))

        assertEquals(listOf("/cats"), model.filterByOperationIds(setOf("find/record")).allOperations.map { it.path })
        assertEquals(listOf("/dogs"), model.filterByOperationIds(setOf("find_record")).allOperations.map { it.path })
        assertEquals(
            setOf("/cats", "/dogs"),
            model.filterByOperationIds(setOf("find/record", "find_record")).allOperations.map { it.path }.toSet(),
        )
    }

    @Test
    fun `allowlists materialize each colliding operation without overwrite`() {
        assertEquals(setOf("find_record_get_cats"), materializedNames(setOf("find/record")))
        assertEquals(setOf("find_record_get_dogs"), materializedNames(setOf("find_record")))
        assertEquals(
            setOf("find_record_get_cats", "find_record_get_dogs"),
            materializedNames(setOf("find/record", "find_record")),
        )
        assertEquals(
            setOf("find_record_get_cats"),
            materializedTools(
                setOf("find/record", "find_record"),
                tags = setOf("search"),
            ).map { it.definition.name }.toSet(),
        )
    }

    @Test
    fun `materialized colliding tools call their own HTTP paths`() {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
                val response = "{}".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        try {
            val tools = materializedTools(
                setOf("find/record", "find_record"),
                "http://localhost:${server.address.port}",
            ).associateBy { it.definition.name }

            tools.getValue("find_record_get_cats").call("")
            tools.getValue("find_record_get_dogs").call("")

            assertEquals(listOf("GET /cats", "GET /dogs"), requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `method and path form the operation identity`() {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }

        try {
            val spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Methods", "version": "1.0.0" },
                  "servers": [{ "url": "http://localhost:${server.address.port}" }],
                  "paths": {
                    "/records": {
                      "get": { "operationId": "read.record", "responses": { "204": { "description": "ok" } } },
                      "post": { "operationId": "read/record", "responses": { "204": { "description": "ok" } } }
                    }
                  }
                }
            """.trimIndent()
            val openApi = OpenApiLearner.parseSpecPreservingRefs("inline", spec)
            val tools = collectAllLeafTools(
                OpenApiLearner.buildTool("inline", openApi, ApiCredentials.None),
            ).associateBy { it.definition.name }

            tools.getValue("read_record_get_records").call("")
            tools.getValue("read_record_post_records").call("")

            assertEquals(listOf("GET /records", "POST /records"), requests)
        } finally {
            server.stop(0)
        }
    }

    private fun model(catFirst: Boolean) = buildModel(collisionSpec(catFirst))
        .allOperations
        .associate { it.path to (it.operationId to it.name) }

    private fun buildModel(spec: String) = OpenApiLearner.buildModel(
        "inline",
        OpenApiLearner.parseSpecPreservingRefs("inline", spec),
    )

    private fun materializedNames(operationIds: Set<String>): Set<String> =
        materializedTools(operationIds).map { it.definition.name }.toSet()

    private fun materializedTools(
        operationIds: Set<String>,
        serverUrl: String = "https://example.com",
        tags: Set<String>? = null,
    ): List<Tool> {
        val spec = collisionSpec(catFirst = true, serverUrl)
        val openApi = OpenApiLearner.parseSpecPreservingRefs("inline", spec)
        return collectAllLeafTools(
            OpenApiLearner.buildTool("inline", openApi, ApiCredentials.None, tags, operationIds),
        )
    }

    private fun collectAllLeafTools(tool: Tool): List<Tool> = when (tool) {
        is ProgressiveTool -> tool.innerTools(
            org.mockito.Mockito.mock(com.embabel.agent.core.AgentProcess::class.java),
        ).flatMap(::collectAllLeafTools)
        else -> listOf(tool)
    }

    private fun collisionSpec(catFirst: Boolean, serverUrl: String = "https://example.com"): String {
        val cats = operation("/cats", "find/record", listOf("animals", "search"))
        val dogs = operation("/dogs", "find_record")
        val ordered = if (catFirst) listOf(cats, dogs) else listOf(dogs, cats)
        val paths = (ordered + listOf(
            operation("/birds", "list-birds"),
            operation("/frogs", "list.frogs"),
            operation("/unnamed", ""),
            operation("/foo.bar", "look.here"),
            operation("/foo/bar", "look/here"),
        )).joinToString(",")
        return """
            {
              "openapi": "3.0.3",
              "info": { "title": "Collision API", "version": "1.0.0" },
              "servers": [{ "url": "$serverUrl" }],
              "paths": { $paths }
            }
        """.trimIndent()
    }

    private fun operation(path: String, operationId: String, tags: List<String> = listOf("animals")): String =
        """"$path": { "get": { "operationId": "$operationId", "tags": ${tags.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}, "responses": { "200": { "description": "ok" } } } }"""
}
