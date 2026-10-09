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
import com.embabel.agent.api.client.LearnedApiSpec
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress

/**
 * A templated `servers` URL is how one spec serves every tenant of a vendor
 * (`https://{pageId}.statuspage.io`). Before these tests the template was
 * called as written, so the request went to the host `%7BpageId%7D`.
 */
class OpenApiServerVariablesTest {

    @Test
    fun `a variable with no supplied value takes its default`() {
        val model = OpenApiLearner.buildModel("inline", parse(spec("https://{tenant}.example.com/v1", default = "acme")))

        assertEquals("https://acme.example.com/v1", model.baseUrl)
    }

    @Test
    fun `a supplied value overrides the default`() {
        val model = OpenApiLearner.buildModel(
            "inline",
            parse(spec("https://{tenant}.example.com/v1", default = "acme")),
            mapOf("tenant" to "globex"),
        )

        assertEquals("https://globex.example.com/v1", model.baseUrl)
    }

    @Test
    fun `a variable with neither a value nor a default is refused by name`() {
        val error = assertThrows<IllegalArgumentException> {
            OpenApiLearner.buildModel("inline", parse(spec("https://{tenant}.example.com", default = null)))
        }

        assertTrue("{tenant}" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `the call goes to the expanded host at every servers level`() {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                requests += exchange.requestURI.path
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }

        try {
            val spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Tenants", "version": "1.0.0" },
                  "servers": [{
                    "url": "http://localhost:{port}/{base}",
                    "variables": { "port": { "default": "1" }, "base": { "default": "spec" } }
                  }],
                  "paths": {
                    "/inherited": { "get": { "operationId": "inherited", "responses": { "204": { "description": "ok" } } } },
                    "/by-path": {
                      "servers": [{ "url": "http://localhost:{port}/path", "variables": { "port": { "default": "1" } } }],
                      "get": { "operationId": "byPath", "responses": { "204": { "description": "ok" } } }
                    },
                    "/by-operation": {
                      "get": {
                        "operationId": "byOperation",
                        "servers": [{ "url": "http://localhost:{port}/op", "variables": { "port": { "default": "1" } } }],
                        "responses": { "204": { "description": "ok" } }
                      }
                    }
                  }
                }
            """.trimIndent()
            val factory = LearnedApiSpec.OpenApi(source = "inline", rawSpec = spec)
                .toFactory(serverVariables = mapOf("port" to "${server.address.port}"))
            val tools = leafTools(factory(ApiCredentials.None)).associateBy { it.definition.name }

            tools.getValue("inherited").call("")
            tools.getValue("byPath").call("")
            tools.getValue("byOperation").call("")

            assertEquals(listOf("/spec/inherited", "/path/by-path", "/op/by-operation"), requests)
        } finally {
            server.stop(0)
        }
    }

    private fun parse(spec: String) = OpenApiLearner.parseSpecPreservingRefs("inline", spec)

    private fun spec(url: String, default: String?): String {
        val variable = if (default == null) "{}" else """{ "default": "$default" }"""
        return """
            {
              "openapi": "3.0.3",
              "info": { "title": "Tenants", "version": "1.0.0" },
              "servers": [{ "url": "$url", "variables": { "tenant": $variable } }],
              "paths": { "/ping": { "get": { "operationId": "ping", "responses": { "200": { "description": "ok" } } } } }
            }
        """.trimIndent()
    }

    private fun leafTools(tool: Tool): List<Tool> = when (tool) {
        is ProgressiveTool -> tool.innerTools(
            org.mockito.Mockito.mock(com.embabel.agent.core.AgentProcess::class.java),
        ).flatMap(::leafTools)
        else -> listOf(tool)
    }
}
