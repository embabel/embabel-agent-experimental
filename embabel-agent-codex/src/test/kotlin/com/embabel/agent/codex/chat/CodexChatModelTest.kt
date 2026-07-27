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

import com.embabel.agent.codex.auth.CodexAccessTokenProvider
import com.embabel.agent.codex.auth.CodexCredentials
import com.embabel.agent.codex.responses.CodexHttpTransport
import com.embabel.agent.codex.responses.CodexResponseException
import com.embabel.agent.codex.responses.CodexResponsesClient
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodexChatModelTest {

    private val tokenProvider = mockk<CodexAccessTokenProvider>()
    private val credentials = CodexCredentials(accessToken = "token", refreshToken = "refresh")

    private fun makeModel(responseJson: String, captureBody: ((String) -> Unit)? = null): CodexChatModel {
        every { tokenProvider.accessToken() } returns "token"
        val transport = CodexHttpTransport { _, _, body ->
            captureBody?.invoke(body)
            responseJson
        }
        val client = CodexResponsesClient(tokenProvider, credentials, transport)
        return CodexChatModel(client, model = "gpt-5.6-sol")
    }

    @Nested
    inner class Call {

        @Test
        fun `returns chat response with assistant text`() {
            val model = makeModel(
                """{"output":[{"type":"message","text":"The answer is 42"}]}"""
            )
            val response = model.call(Prompt(listOf(UserMessage("What is the answer?"))))
            assertNotNull(response)
            assertEquals(1, response.results.size)
            assertEquals("The answer is 42", response.results[0].output.text)
        }

        @Test
        fun `default options returns model name`() {
            val model = makeModel("""{"output":[]}""")
            assertNotNull(model.defaultOptions)
            assertNotNull(model.defaultOptions.model)
        }

        @Test
        fun `maps function calls onto assistant tool calls`() {
            val model = makeModel(
                """
                {"output":[{"type":"function_call","name":"lookup","arguments":"{\"q\":\"x\"}","call_id":"call_1"}]}
                """.trimIndent()
            )
            val response = model.call(Prompt(listOf(UserMessage("lookup x"))))
            val assistant = response.results[0].output
            assertTrue(assistant.hasToolCalls())
            assertEquals("lookup", assistant.toolCalls[0].name())
            assertEquals("call_1", assistant.toolCalls[0].id())
        }

        @Test
        fun `sends tools from ToolCallingChatOptions`() {
            var capturedBody = ""
            val model = makeModel("""{"output":[{"type":"message","text":"ok"}]}""") { body ->
                capturedBody = body
            }
            val toolCallback = object : ToolCallback {
                override fun getToolDefinition(): ToolDefinition =
                    ToolDefinition.builder()
                        .name("lookup")
                        .description("Lookup things")
                        .inputSchema("""{"type":"object","properties":{"q":{"type":"string"}}}""")
                        .build()

                override fun call(toolInput: String): String = "{}"
            }
            val options = ToolCallingChatOptions.builder()
                .toolCallbacks(listOf(toolCallback))                .build()

            model.call(Prompt(listOf(UserMessage("hi")), options))
            assertTrue(capturedBody.contains("\"tools\""))
            assertTrue(capturedBody.contains("\"lookup\""))
        }

        @Test
        fun `preserves runtime options when tools are attached`() {
            var capturedBody = ""
            val model = makeModel("""{"output":[{"type":"message","text":"ok"}]}""") { body ->
                capturedBody = body
            }
            val options = ToolCallingChatOptions.builder()
                .model("gpt-runtime")
                .temperature(0.25)
                .maxTokens(321)
                .topP(0.75)                .build()

            model.call(Prompt(listOf(UserMessage("hi")), options))

            assertTrue(capturedBody.contains("\"model\":\"gpt-runtime\""))
            assertTrue(capturedBody.contains("\"temperature\":0.25"))
            assertTrue(capturedBody.contains("\"max_output_tokens\":321"))
            assertTrue(capturedBody.contains("\"top_p\":0.75"))
        }

        @Test
        fun `rejects malformed tool schema with tool context`() {
            val model = makeModel("""{"output":[]}""")
            val invalidTool = object : ToolCallback {
                override fun getToolDefinition(): ToolDefinition =
                    ToolDefinition.builder()
                        .name("broken_lookup")
                        .description("Invalid test tool")
                        .inputSchema("{not-json}")
                        .build()

                override fun call(toolInput: String): String = "{}"
            }
            val options = ToolCallingChatOptions.builder()
                .toolCallbacks(listOf(invalidTool))                .build()

            val error = assertFailsWith<IllegalArgumentException> {
                model.call(Prompt(listOf(UserMessage("hi")), options))
            }

            assertTrue(error.message.orEmpty().contains("broken_lookup"))
        }
    }

    @Nested
    inner class RetryContract {

        /**
         * Spring Framework's core RetryTemplate wraps the final failure in a
         * RetryException, unlike spring-retry which rethrew the original. Callers
         * must keep seeing this module's typed exceptions.
         */
        @Test
        fun `surfaces the original typed exception once retries are exhausted`() {
            every { tokenProvider.accessToken() } returns "token"
            var attempts = 0
            val transport = CodexHttpTransport { _, _, _ ->
                attempts += 1
                throw CodexResponseException("codex transport unavailable")
            }
            val client = CodexResponsesClient(tokenProvider, credentials, transport)
            val model = CodexChatModel(
                client,
                model = "gpt-5.6-sol",
                retryTemplate = RetryTemplate(RetryPolicy.withMaxRetries(1)),
            )

            val error = assertFailsWith<CodexResponseException> {
                model.call(Prompt(listOf(UserMessage("hi"))))
            }

            assertEquals("codex transport unavailable", error.message)
            assertEquals(2, attempts, "expected 1 initial attempt plus 1 retry")
        }
    }

    @Nested
    inner class Options {

        /** Spring AI 2.0 replaced `ChatOptions.copy()` with `mutate()`. */
        @Test
        fun `mutate reproduces the current option values`() {
            val options = CodexChatOptions(
                modelName = "gpt-runtime",
                temperature = 0.25,
                maxTokens = 321,
                topP = 0.75,
            )

            val rebuilt = options.mutate().build()

            assertEquals("gpt-runtime", rebuilt.model)
            assertEquals(0.25, rebuilt.temperature)
            assertEquals(321, rebuilt.maxTokens)
            assertEquals(0.75, rebuilt.topP)
        }
    }
}
