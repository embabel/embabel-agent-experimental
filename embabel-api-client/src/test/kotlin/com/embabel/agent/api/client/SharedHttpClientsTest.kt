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

import com.embabel.agent.api.client.explorer.HttpProbeTool
import com.embabel.agent.api.client.openapi.OpenApiClientProperties
import com.embabel.agent.api.client.openapi.OpenApiLearner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration

/**
 * Building tools does not build threads (#51, #52). A JDK client starts its selector thread when
 * it is built and keeps it until closed or collected, and a tool keeps its client reachable, so a
 * client per tool was a thread per tool for as long as the tools lived.
 *
 * Growth is asserted as bounded by the number of distinct client keys rather than as zero, because
 * an earlier test in the same JVM may or may not already have built the shared client for a key.
 */
class SharedHttpClientsTest {

    @Test
    fun `learning an API many times adds at most one selector thread`() {
        /* A connect timeout no other test uses, so this is at most one new key. */
        val learner = OpenApiLearner(OpenApiClientProperties(connectTimeout = Duration.ofMillis(4_321)))
        val learned = learner.learn(resource("petstore-with-auth.json"))

        val before = selectorThreads()
        val tools = (1..TOOLS).map { learned.create(ApiCredentials.Token("token-$it")) }
        val grown = selectorThreads() - before

        assertEquals(TOOLS, tools.size)
        assertTrue(grown <= 1, "building $TOOLS tools added $grown selector threads")
    }

    @Test
    fun `fetching a spec over http many times adds at most one selector thread`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val spec = javaClass.classLoader.getResource("petstore-minimal.json")!!.readBytes()
        server.createContext("/spec.json") { exchange ->
            exchange.sendResponseHeaders(200, spec.size.toLong())
            exchange.responseBody.use { it.write(spec) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/spec.json"
            val before = selectorThreads()
            repeat(TOOLS) { OpenApiLearner.fetchRawSpec(url) }
            val grown = selectorThreads() - before
            assertTrue(grown <= 1, "fetching the spec $TOOLS times added $grown selector threads")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `constructing many probe tools adds at most one selector thread`() {
        val before = selectorThreads()
        val probes = (1..TOOLS).map { HttpProbeTool() }
        val grown = selectorThreads() - before

        assertEquals(TOOLS, probes.size)
        assertTrue(grown <= 1, "constructing $TOOLS probe tools added $grown selector threads")
    }

    @Test
    fun `the shared credentialed client never follows a redirect itself`() {
        assertEquals(HttpClient.Redirect.NEVER, SharedHttpClients.redirectRefusing(null).followRedirects())
        assertEquals(
            HttpClient.Redirect.NEVER,
            SharedHttpClients.redirectRefusing(Duration.ofSeconds(3)).followRedirects(),
        )
    }

    @Test
    fun `credentialed clients are shared per connect timeout and only per connect timeout`() {
        val three = SharedHttpClients.redirectRefusing(Duration.ofSeconds(3))
        assertSame(three, SharedHttpClients.redirectRefusing(Duration.ofSeconds(3)))
        assertSame(SharedHttpClients.redirectRefusing(null), SharedHttpClients.redirectRefusing(null))
        assertNotSame(three, SharedHttpClients.redirectRefusing(Duration.ofSeconds(4)))
        assertNotSame(three, SharedHttpClients.redirectRefusing(null))
        assertEquals(Duration.ofSeconds(3), three.connectTimeout().orElseThrow())
    }

    @Test
    fun `a caller's own client is refused unless it leaves redirects to the wrapper`() {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { following ->
            assertThrows<IllegalArgumentException> {
                CredentialSafeRedirects.overClient(following, null, emptySet())
            }
        }
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().use { never ->
            CredentialSafeRedirects.overClient(never, Duration.ofSeconds(1), emptySet())
        }
    }

    private fun resource(name: String): String = javaClass.classLoader.getResource(name)!!.toString()

    private fun selectorThreads(): Int =
        Thread.getAllStackTraces().keys.count {
            it.isAlive && it.name.startsWith("HttpClient-") && it.name.endsWith("-SelectorManager")
        }

    private companion object {
        const val TOOLS = 25
    }
}
