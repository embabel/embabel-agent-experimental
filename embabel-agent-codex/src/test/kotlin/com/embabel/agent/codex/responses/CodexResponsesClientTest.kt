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
package com.embabel.agent.codex.responses

import com.embabel.agent.codex.auth.CodexAccessTokenProvider
import com.embabel.agent.codex.auth.CodexCredentials
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodexResponsesClientTest {

    private val tokenProvider = mockk<CodexAccessTokenProvider>()
    private val credentials = CodexCredentials(accessToken = "test-token", refreshToken = "refresh")

    @Nested
    inner class CreateResponse {

        @Test
        fun `parses output text from response`() {
            every { tokenProvider.accessToken() } returns "test-token"
            val transport = CodexHttpTransport { _, _, _ ->
                """
                {
                  "output": [
                    {"type": "message", "text": "Hello from Codex!"}
                  ]
                }
                """.trimIndent()
            }
            val client = CodexResponsesClient(tokenProvider, credentials, transport)
            val response = client.create("gpt-5.3-codex", listOf(mapOf("role" to "user")))
            assertEquals("Hello from Codex!", response.outputText)
            assertTrue(response.functionCalls.isEmpty())
        }

        @Test
        fun `parses nested output_text content parts`() {
            every { tokenProvider.accessToken() } returns "test-token"
            val transport = CodexHttpTransport { _, _, _ ->
                """
                {
                  "output": [
                    {
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {"type": "output_text", "text": "Nested hello"}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            }
            val client = CodexResponsesClient(tokenProvider, credentials, transport)
            val response = client.create("gpt-5.3-codex", listOf(mapOf("role" to "user")))
            assertEquals("Nested hello", response.outputText)
        }

        @Test
        fun `parses function calls from response`() {
            every { tokenProvider.accessToken() } returns "test-token"
            val transport = CodexHttpTransport { _, _, _ ->
                """
                {
                  "output": [
                    {"type": "function_call", "name": "search", "arguments": "{\"query\":\"test\"}", "call_id": "call-1"}
                  ]
                }
                """.trimIndent()
            }
            val client = CodexResponsesClient(tokenProvider, credentials, transport)
            val response = client.create("gpt-5.3-codex", listOf(mapOf("role" to "user")))
            assertEquals(1, response.functionCalls.size)
            assertEquals("search", response.functionCalls[0].name)
            assertEquals("call-1", response.functionCalls[0].callId)
        }

        @Test
        fun `parses SSE stream deltas`() {
            every { tokenProvider.accessToken() } returns "test-token"
            val transport = CodexHttpTransport { _, _, _ ->
                """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"Hello"}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":" world"}

                event: response.completed
                data: {"type":"response.completed","response":{"output":[]}}

                """.trimIndent()
            }
            val client = CodexResponsesClient(tokenProvider, credentials, transport)
            val response = client.create("gpt-5.5", listOf(mapOf("role" to "user")))
            assertEquals("Hello world", response.outputText)
        }

        @Test
        fun `sends stream and store flags`() {
            every { tokenProvider.accessToken() } returns "tok"
            var body = ""
            val transport = CodexHttpTransport { _, _, requestBody ->
                body = requestBody
                """{"output":[]}"""
            }
            CodexResponsesClient(tokenProvider, credentials, transport)
                .create("gpt-5.5", emptyList())
            assertTrue(body.contains("\"stream\":true"))
            assertTrue(body.contains("\"store\":false"))
        }

        @Test
        fun `sends Authorization bearer header`() {
            every { tokenProvider.accessToken() } returns "my-secret-token"
            var capturedHeaders: Map<String, String> = emptyMap()
            val transport = CodexHttpTransport { _, headers, _ ->
                capturedHeaders = headers
                """{"output":[]}"""
            }
            val client = CodexResponsesClient(tokenProvider, credentials, transport)
            client.create("gpt-5.5", emptyList())
            assertEquals("Bearer my-secret-token", capturedHeaders["Authorization"])
        }

        @Test
        fun `retries once after unauthorized by refreshing token`() {
            every { tokenProvider.accessToken() } returnsMany listOf("stale", "fresh")
            every { tokenProvider.invalidateAndRefresh() } returns "fresh"
            var attempts = 0
            val transport = CodexHttpTransport { _, headers, _ ->
                attempts += 1
                if (attempts == 1) {
                    throw HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED,
                        "Unauthorized",
                        HttpHeaders(),
                        ByteArray(0),
                        null,
                    )
                }
                assertEquals("Bearer fresh", headers["Authorization"])
                """{"output":[{"type":"message","text":"recovered"}]}"""
            }
            val client = CodexResponsesClient(tokenProvider, credentials, transport)
            val response = client.create("gpt-5.5", emptyList())
            assertEquals("recovered", response.outputText)
            assertEquals(2, attempts)
            verify(exactly = 1) { tokenProvider.invalidateAndRefresh() }
        }

        @Test
        fun `includes instructions in request body when provided`() {
            every { tokenProvider.accessToken() } returns "tok"
            var body = ""
            val transport = CodexHttpTransport { _, _, requestBody ->
                body = requestBody
                """{"output":[]}"""
            }
            CodexResponsesClient(tokenProvider, credentials, transport)
                .create("gpt-5.5", emptyList(), instructions = "Be terse")
            assertTrue(body.contains("\"instructions\":\"Be terse\""))
        }

        @Test
        fun `includes max_output_tokens and temperature when provided`() {
            every { tokenProvider.accessToken() } returns "tok"
            var body = ""
            val transport = CodexHttpTransport { _, _, requestBody ->
                body = requestBody
                """{"output":[]}"""
            }
            CodexResponsesClient(tokenProvider, credentials, transport)
                .create("gpt-5.6-sol", emptyList(), maxOutputTokens = 128, temperature = 0.5)
            assertTrue(body.contains("\"max_output_tokens\":128"))
            assertTrue(body.contains("\"temperature\":0.5"))
        }

        @Test
        fun `throws when JSON response contains an error`() {
            every { tokenProvider.accessToken() } returns "tok"
            val transport = CodexHttpTransport { _, _, _ ->
                """{"error":{"code":"access_denied","message":"Subscription access denied"}}"""
            }
            val client = CodexResponsesClient(tokenProvider, credentials, transport)

            val error = assertFailsWith<CodexResponseException> {
                client.create("gpt-runtime", emptyList())
            }

            assertTrue(error.message.orEmpty().contains("access_denied"))
        }

        @Test
        fun `throws when SSE response reports failure`() {
            every { tokenProvider.accessToken() } returns "tok"
            val transport = CodexHttpTransport { _, _, _ ->
                """
                event: response.failed
                data: {"type":"response.failed","response":{"error":{"code":"server_error","message":"Generation failed"}}}

                """.trimIndent()
            }
            val client = CodexResponsesClient(tokenProvider, credentials, transport)

            val error = assertFailsWith<CodexResponseException> {
                client.create("gpt-runtime", emptyList())
            }

            assertTrue(error.message.orEmpty().contains("Generation failed"))
        }

        @Test
        fun `throws when SSE stream is malformed or incomplete`() {
            every { tokenProvider.accessToken() } returns "tok"
            val malformed = CodexHttpTransport { _, _, _ -> "data: {not-json}\n\n" }
            val incomplete = CodexHttpTransport { _, _, _ ->
                """
                data: {"type":"response.output_text.delta","delta":"partial"}

                """.trimIndent()
            }

            assertFailsWith<CodexResponseException> {
                CodexResponsesClient(tokenProvider, credentials, malformed)
                    .create("gpt-runtime", emptyList())
            }
            assertFailsWith<CodexResponseException> {
                CodexResponsesClient(tokenProvider, credentials, incomplete)
                    .create("gpt-runtime", emptyList())
            }
        }
    }
}
