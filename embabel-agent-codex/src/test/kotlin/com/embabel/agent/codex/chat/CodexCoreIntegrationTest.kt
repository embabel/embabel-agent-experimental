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
package com.embabel.agent.codex.chat

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.codex.auth.CodexAccessTokenProvider
import com.embabel.agent.codex.auth.CodexAuthException
import com.embabel.agent.codex.auth.CodexCredentials
import com.embabel.agent.codex.responses.CodexHttpTransport
import com.embabel.agent.codex.responses.CodexResponsesClient
import com.embabel.agent.codex.responses.CodexReasoningEffort
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import org.springframework.core.retry.RetryException

class CodexCoreIntegrationTest {
    private val tokens = mockk<CodexAccessTokenProvider>()
    private val credentials = CodexCredentials("token", "refresh")
    private val response = """{"id":"resp-1","model":"actual-model","output":[{"type":"message","text":"done"}],"usage":{"input_tokens":12,"output_tokens":5,"total_tokens":17,"input_tokens_details":{"cached_tokens":8},"output_tokens_details":{"reasoning_tokens":3}}}"""
    private val retry = object : RetryProperties {
        override val maxAttempts = 3
        override val propertyPrefix = "test.codex"
        override val backoffMillis = 1L
        override val backoffMultiplier = 1.0
        override val backoffMaxInterval = 1L
    }

    @Nested
    inner class CoreMessageSender {
        @Test
        fun `uses default tools and preserves tool context across builder mutation`() {
            every { tokens.accessToken() } returns "token"
            val callback = mockk<ToolCallback>()
            every { callback.toolDefinition } returns ToolDefinition.builder()
                .name("lookup").description("Lookup").inputSchema("""{"type":"object","properties":{}}""").build()
            val defaults = CodexChatOptions(reasoningEffort = CodexReasoningEffort.HIGH).mutate()
                .toolCallbacks(callback).toolContext("tenant", "one").build()
            val changed = defaults.mutate().clone().toolContext("tenant", "two").build()
            assertEquals("one", defaults.toolContext["tenant"])
            assertEquals("two", changed.toolContext["tenant"])
            assertEquals(listOf(callback), changed.toolCallbacks)
            var body = ""
            val client = CodexResponsesClient(tokens, credentials, CodexHttpTransport { _, _, request ->
                body = request
                response
            })
            CodexChatModel(client, "model", defaults).call(Prompt("hello"))
            assertEquals("lookup", jacksonObjectMapper().readTree(body).path("tools").single().path("name").asText())
        }

        @Test
        fun `preserves effort with tools and returns usage through the real core sender`() {
            every { tokens.accessToken() } returns "token"
            var body = ""
            val client = CodexResponsesClient(tokens, credentials, CodexHttpTransport { _, _, request ->
                body = request
                "data: {\"type\":\"response.completed\",\"response\":$response}\n\n"
            })
            val service = SpringAiLlmService("resolved-model", "codex", CodexChatModel(client, "fallback"), CodexOptionsConverter)
            val tool = Tool.create("lookup", "Look up a value") { error("Core sender must not execute tools") }
            val result = service.createMessageSender(
                LlmOptions(model = "alias").withCodexReasoningEffort(CodexReasoningEffort.HIGH)
            ).call(listOf(UserMessage("hello")), listOf(tool))
            val payload = jacksonObjectMapper().readTree(body)
            assertEquals("high", payload.path("reasoning").path("effort").asText())
            assertEquals("resolved-model", payload.path("model").asText())
            assertEquals("lookup", payload.path("tools").single().path("name").asText())
            val usage = assertNotNull(result.usage)
            assertEquals(12, usage.promptTokens)
            assertEquals(5, usage.completionTokens)
            assertEquals(17, usage.totalTokens)
        }

        @Test
        fun `maps json response identity and token metadata to spring ai`() {
            every { tokens.accessToken() } returns "token"
            val client = CodexResponsesClient(tokens, credentials, CodexHttpTransport { _, _, _ -> response })
            val metadata = CodexChatModel(client, "fallback").call(Prompt("hello")).metadata
            assertEquals("resp-1", metadata.id)
            assertEquals("actual-model", metadata.model)
            assertEquals(17, metadata.usage.totalTokens)
            assertEquals(8L, metadata.usage.cacheReadInputTokens)
        }
    }

    @Nested
    inner class CoreRetry {
        @Test
        fun `stops after a refreshed credential is still rejected`() {
            every { tokens.accessToken() } returns "token"
            every { tokens.invalidateAndRefresh() } returns "new-token"
            var attempts = 0
            val client = CodexResponsesClient(tokens, credentials, CodexHttpTransport { _, _, _ ->
                attempts++
                throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders(), ByteArray(0), null)
            })
            val failure = assertFailsWith<RetryException> {
                retry.coreRetryTemplate("codex").execute { CodexChatModel(client, "model").call(Prompt("hello")) }
            }
            assertIs<CodexAuthException>(failure.cause)
            assertEquals(2, attempts)
            verify(exactly = 1) { tokens.invalidateAndRefresh() }
        }

        @Test
        fun `core does not retry terminal auth failures`() {
            every { tokens.accessToken() } throws CodexAuthException("invalid_refresh_token")
            val transport = mockk<CodexHttpTransport>()
            val model = CodexChatModel(CodexResponsesClient(tokens, credentials, transport), "model")
            val failure = assertFailsWith<RetryException> {
                retry.coreRetryTemplate("codex").execute { model.call(Prompt("hello")) }
            }
            assertIs<CodexAuthException>(failure.cause)
            verify(exactly = 1) { tokens.accessToken() }
            verify(exactly = 0) { transport.post(any(), any(), any()) }
        }

        @Test
        fun `core retries transient transport failures without nested retries`() {
            every { tokens.accessToken() } returns "token"
            var attempts = 0
            val client = CodexResponsesClient(tokens, credentials, CodexHttpTransport { _, _, _ ->
                attempts++
                if (attempts < 3) throw HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)
                response
            })
            retry.coreRetryTemplate("codex").execute { CodexChatModel(client, "model").call(Prompt("hello")) }
            assertEquals(3, attempts)
        }

        @Test
        fun `core rejects unsupported requests after one attempt`() {
            every { tokens.accessToken() } returns "token"
            var attempts = 0
            val client = CodexResponsesClient(tokens, credentials, CodexHttpTransport { _, _, _ ->
                attempts++
                throw HttpClientErrorException(HttpStatus.BAD_REQUEST)
            })
            val failure = assertFailsWith<RetryException> {
                retry.coreRetryTemplate("codex").execute { CodexChatModel(client, "model").call(Prompt("hello")) }
            }
            assertIs<NonTransientAiException>(failure.cause)
            assertEquals(1, attempts)
        }
    }
}
