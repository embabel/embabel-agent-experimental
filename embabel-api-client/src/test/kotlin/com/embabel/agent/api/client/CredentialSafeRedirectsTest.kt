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
package com.embabel.agent.api.client

import com.embabel.agent.api.client.graphql.GraphQlLearner
import com.embabel.agent.api.client.openapi.OpenApiLearner
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.AbstractBufferingClientHttpRequest
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI

/**
 * A credential goes only to the origin it was granted for (embabel/me#2414). The JDK client's own
 * redirect policy re-sent every header but `Authorization` and `Cookie` to wherever a source
 * pointed, so a declared API-key header and custom credential headers reached a third host.
 *
 * The origin and the "other host" are two local servers on different ports, which makes them
 * different origins exactly as two domains would be.
 */
class CredentialSafeRedirectsTest {

    private val servers = mutableListOf<RecordingServer>()

    @AfterEach
    fun stopServers() = servers.forEach { it.stop() }

    @Test
    fun `a redirect to another origin carries no credential header`() {
        val other = server { it.respond(200, "landed") }
        val origin = server { it.redirect(302, "${other.url}/landed") }

        val result = call(origin, "/moved")

        assertFalse(result is Tool.Result.Error, result.toString())
        val landed = other.requests.single()
        assertEquals("/landed", landed.path)
        assertNull(landed.headers["X-api-key"], "API-key header crossed: ${landed.headers}")
        assertNull(landed.headers["X-tenant-secret"], "custom header crossed: ${landed.headers}")
        assertNull(landed.headers["Authorization"], "Authorization crossed: ${landed.headers}")
    }

    @Test
    fun `a redirect within the origin keeps every credential header`() {
        val origin = server { exchange ->
            if (exchange.requestURI.path == "/moved") exchange.redirect(302, "/here") else exchange.respond(200, "here")
        }

        val result = call(origin, "/moved")

        assertFalse(result is Tool.Result.Error, result.toString())
        val landed = origin.requests.single { it.path == "/here" }
        assertEquals("realm-secret-7f3a", landed.headers["X-api-key"])
        assertEquals("tenant-secret", landed.headers["X-tenant-secret"])
        assertEquals("Bearer token-1", landed.headers["Authorization"])
    }

    @Test
    fun `a query-string API key echoed into another origin's Location is removed`() {
        val other = server { it.respond(200, "landed") }
        val origin = server { exchange ->
            exchange.redirect(302, "${other.url}/landed?${exchange.requestURI.rawQuery}&keep=1")
        }
        val spec = """
            {
              "openapi": "3.0.3",
              "info": { "title": "Moved", "version": "1.0.0" },
              "servers": [{ "url": "${origin.url}" }],
              "components": { "securitySchemes": { "key": { "type": "apiKey", "in": "query", "name": "api_key" } } },
              "paths": { "/moved": { "get": { "operationId": "moved", "responses": { "200": { "description": "ok" } } } } }
            }
        """.trimIndent()
        val tool = OpenApiLearner.buildTool("inline", OpenApiLearner.parseSpec("inline", spec), ApiCredentials.ApiKey("query-secret"))

        val result = leafTools(tool).single().call("")

        assertFalse(result is Tool.Result.Error, result.toString())
        assertEquals("api_key=query-secret", origin.requests.single().query, "the origin itself gets the key")
        assertEquals("keep=1", other.requests.single().query)
    }

    @Test
    fun `a GraphQL redirect to another origin carries no credential header`() {
        val other = server { it.respond(200, """{"data":{"__type":{"fields":[]}}}""") }
        val origin = server { it.redirect(307, "${other.url}/graphql") }

        /* GraphQL sends an API key as Authorization, so the two shapes cover every header it uses. */
        listOf(
            ApiCredentials.CustomHeaders(mapOf("X-Api-Key" to "realm-secret-7f3a", "X-Tenant-Secret" to "tenant-secret")),
            ApiCredentials.ApiKey("realm-secret-7f3a"),
        ).forEach { credentials ->
            runCatching {
                GraphQlLearner.buildTool(
                    endpoint = "${origin.url}/graphql",
                    apiName = "moved",
                    queryTypeName = "Query",
                    mutationTypeName = null,
                    credentials = credentials,
                )
            }
        }

        assertTrue(origin.requests.any { it.headers["X-tenant-secret"] == "tenant-secret" }, "${origin.requests}")
        assertTrue(origin.requests.any { it.headers["Authorization"] == "realm-secret-7f3a" }, "${origin.requests}")
        assertTrue(other.requests.isNotEmpty(), "the 307 was followed")
        other.requests.forEach { landed ->
            assertEquals("POST", landed.method, "a 307 keeps the method")
            assertTrue(landed.body.contains("__type"), "a 307 keeps the body")
            assertEquals("application/json", landed.headers["Content-type"], "content negotiation still crosses")
            assertNull(landed.headers["X-api-key"], "API-key header crossed: ${landed.headers}")
            assertNull(landed.headers["X-tenant-secret"], "custom header crossed: ${landed.headers}")
            assertNull(landed.headers["Authorization"], "Authorization crossed: ${landed.headers}")
        }
    }

    @Test
    fun `an https to http redirect is not followed at all`() {
        val plain = server { it.respond(200, "landed") }
        val tlsOrigin = CannedRedirect(from = "https", redirectTo = "${plain.url}/landed", next = JdkClientHttpRequestFactory())
        val factory = CredentialSafeRedirects(tlsOrigin, CredentialSafeRedirects.MAX_HOPS, emptySet())
        val request = factory.createRequest(URI("https://api.example.com/moved"), HttpMethod.GET)
        request.headers.set("X-Api-Key", "realm-secret-7f3a")
        request.headers.set("Authorization", "Bearer token-1")

        val error = assertThrows<IOException> { request.execute() }

        assertTrue("plain http" in error.message.orEmpty(), error.message)
        assertTrue(plain.requests.isEmpty(), "the http host was called: ${plain.requests}")
    }

    @Test
    fun `a redirect loop stops after the hop limit`() {
        val origin = server { it.redirect(302, "/again") }

        val result = call(origin, "/moved")

        val message = (result as Tool.Result.Error).message
        assertTrue("Stopped after ${CredentialSafeRedirects.MAX_HOPS} redirects" in message, message)
        assertEquals(CredentialSafeRedirects.MAX_HOPS + 1, origin.requests.size)
    }

    @Test
    fun `origins compare scheme, host and port, with the default port implied`() {
        assertTrue(CredentialSafeRedirects.sameOrigin(URI("https://Api.Example.com/a"), URI("https://api.example.com:443/b")))
        assertFalse(CredentialSafeRedirects.sameOrigin(URI("https://api.example.com/a"), URI("http://api.example.com/a")))
        assertFalse(CredentialSafeRedirects.sameOrigin(URI("https://api.example.com/a"), URI("https://cdn.example.com/a")))
        assertFalse(CredentialSafeRedirects.sameOrigin(URI("http://127.0.0.1:8080/a"), URI("http://127.0.0.1:8081/a")))
    }

    /* An API key in a declared header, a custom credential header and a bearer token: one of each
     * shape that went out as a default header. */
    private fun call(origin: RecordingServer, path: String): Tool.Result {
        val spec = """
            {
              "openapi": "3.0.3",
              "info": { "title": "Moved", "version": "1.0.0" },
              "servers": [{ "url": "${origin.url}" }],
              "components": { "securitySchemes": { "key": { "type": "apiKey", "in": "header", "name": "X-Api-Key" } } },
              "paths": { "$path": { "get": { "operationId": "moved", "responses": { "200": { "description": "ok" } } } } }
            }
        """.trimIndent()
        val tool = OpenApiLearner.buildTool("inline", OpenApiLearner.parseSpec("inline", spec), CREDENTIALS)
        return leafTools(tool).single().call("")
    }

    private fun server(handler: (HttpExchange) -> Unit): RecordingServer =
        RecordingServer(handler).also { servers += it }

    private fun leafTools(tool: Tool): List<Tool> = when (tool) {
        is ProgressiveTool -> tool.innerTools(
            org.mockito.Mockito.mock(com.embabel.agent.core.AgentProcess::class.java),
        ).flatMap(::leafTools)
        else -> listOf(tool)
    }

    private class Recorded(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: String,
    ) {
        override fun toString() = "$method $path?$query $headers"
    }

    private class RecordingServer(handler: (HttpExchange) -> Unit) {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Recorded>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests += Recorded(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    query = exchange.requestURI.rawQuery,
                    headers = exchange.requestHeaders.mapValues { it.value.joinToString(",") },
                    body = exchange.requestBody.readAllBytes().decodeToString(),
                )
                handler(exchange)
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        fun stop() = server.stop(0)
    }

    /* Stands in for an https origin, which a local test server cannot be without a certificate the
     * client trusts: it answers every request to [from] with a 302, and passes the rest on. */
    private class CannedRedirect(
        private val from: String,
        private val redirectTo: String,
        private val next: ClientHttpRequestFactory,
    ) : ClientHttpRequestFactory {
        override fun createRequest(uri: URI, httpMethod: HttpMethod): ClientHttpRequest {
            if (uri.scheme != from) return next.createRequest(uri, httpMethod)
            return object : AbstractBufferingClientHttpRequest() {
                override fun getURI() = uri
                override fun getMethod() = httpMethod
                override fun executeInternal(headers: HttpHeaders, bufferedOutput: ByteArray): ClientHttpResponse =
                    object : ClientHttpResponse {
                        override fun getStatusCode() = HttpStatusCode.valueOf(302)
                        override fun getStatusText() = "Found"
                        override fun getHeaders() = HttpHeaders().apply { set(HttpHeaders.LOCATION, redirectTo) }
                        override fun getBody(): InputStream = ByteArrayInputStream(ByteArray(0))
                        override fun close() {}
                    }
            }
        }
    }

    companion object {
        private val CREDENTIALS = ApiCredentials.Multiple(
            listOf(
                ApiCredentials.ApiKey("realm-secret-7f3a"),
                ApiCredentials.CustomHeaders(mapOf("X-Tenant-Secret" to "tenant-secret")),
                ApiCredentials.Token("token-1"),
            ),
        )

        private fun HttpExchange.redirect(status: Int, location: String) {
            responseHeaders.set("Location", location)
            sendResponseHeaders(status, -1)
            close()
        }

        private fun HttpExchange.respond(status: Int, body: String) {
            val bytes = body.toByteArray()
            responseHeaders.set("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
