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

import com.embabel.agent.codex.responses.CodexReasoningEffort

import com.embabel.agent.codex.auth.CodexAccessTokenProvider
import com.embabel.agent.codex.auth.CodexCredentials
import com.embabel.agent.codex.responses.CodexHttpTransport
import com.embabel.agent.codex.responses.CodexResponsesClient
import com.embabel.common.ai.model.LlmOptions
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CodexReasoningEffortTest {
    @Nested
    inner class WireContract {
        @Test
        fun `maps every effort through llm options and chat model into the request`() {
            assertEquals(listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"),
                CodexReasoningEffort.entries.map { it.wireValue })
            for (effort in CodexReasoningEffort.entries) {
                assertEquals(effort, CodexReasoningEffort.fromWireValue(effort.wireValue))
                val options = CodexOptionsConverter.convertOptions(
                    LlmOptions(model = "alias").withCodexReasoningEffort(effort), "resolved-model")
                val body = requestBody(options)
                assertEquals(effort.wireValue, body.path("reasoning").path("effort").asText())
                assertEquals("resolved-model", body.path("model").asText())
            }
        }

        @Test
        fun `omits reasoning when unspecified and rejects unknown levels`() {
            assertFalse(requestBody(CodexChatOptions()).has("reasoning"))
            assertFailsWith<IllegalArgumentException> { CodexReasoningEffort.fromWireValue("ultra") }
        }

        @Test
        fun `runtime effort overrides defaults including explicit none`() {
            val defaults = CodexChatOptions(reasoningEffort = CodexReasoningEffort.HIGH)
            assertEquals("none", requestBody(CodexChatOptions(reasoningEffort = CodexReasoningEffort.NONE), defaults)
                .path("reasoning").path("effort").asText())
            assertEquals("high", requestBody(ChatOptions.builder().temperature(0.1).build(), defaults)
                .path("reasoning").path("effort").asText())
        }
    }

    @Nested
    inner class BuilderContract {
        @Test
        fun `mutation and clone retain effort without modifying the original`() {
            val original = CodexChatOptions(modelName = "original", reasoningEffort = CodexReasoningEffort.HIGH)
            val builder = original.mutate()
            val clone = builder.clone().model("clone").reasoningEffort(CodexReasoningEffort.LOW)
            assertEquals(CodexReasoningEffort.HIGH, builder.model("changed").build().reasoningEffort)
            assertEquals("original", original.model)
            assertEquals(CodexReasoningEffort.LOW, clone.build().reasoningEffort)
            assertNull(builder.reasoningEffort(null).build().reasoningEffort)
        }

        @Test
        fun `combine keeps effort for portable options and accepts codex overrides`() {
            val builder = CodexChatOptions(reasoningEffort = CodexReasoningEffort.HIGH).mutate()
            builder.combineWith(ChatOptions.builder().model("new"))
            assertEquals(CodexReasoningEffort.HIGH, builder.build().reasoningEffort)
            assertEquals("new", builder.build().model)
            builder.combineWith(CodexChatOptions(reasoningEffort = CodexReasoningEffort.NONE).mutate())
            assertEquals(CodexReasoningEffort.NONE, builder.build().reasoningEffort)
        }
    }

    private fun requestBody(runtime: ChatOptions, defaults: CodexChatOptions = CodexChatOptions()): JsonNode {
        val tokens = mockk<CodexAccessTokenProvider>()
        every { tokens.accessToken() } returns "test-token"
        var body = ""
        val transport = CodexHttpTransport { _, _, request ->
            body = request
            """{"output":[{"type":"message","text":"ok"}]}"""
        }
        val client = CodexResponsesClient(tokens, CodexCredentials("test-token", "refresh"), transport)
        CodexChatModel(client, "default-model", defaults).call(Prompt("hello", runtime))
        return jacksonObjectMapper().readTree(body)
    }
}
