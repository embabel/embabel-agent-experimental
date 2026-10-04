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
package com.embabel.agent.codex.live

import com.embabel.agent.codex.auth.CodexAccessTokenProvider
import com.embabel.agent.codex.auth.CodexTokenRefresher
import com.embabel.agent.codex.auth.FileCodexAuthStore
import com.embabel.agent.codex.auth.defaultEmbabelCodexPath
import com.embabel.agent.codex.chat.CodexOptionsConverter
import com.embabel.agent.codex.chat.withCodexReasoningEffort
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.agent.codex.responses.CodexReasoningEffort
import com.embabel.agent.codex.chat.CodexChatModel
import com.embabel.agent.codex.responses.CodexResponsesClient
import com.embabel.agent.codex.responses.RestClientCodexHttpTransport
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import com.embabel.chat.UserMessage
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

@EnabledIfEnvironmentVariable(named = "EMBABEL_LIVE_CODEX", matches = "1")
class CodexLiveIT {

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("cases")
    fun `uses persistent Embabel auth and completes responses calls`(model: String, effort: CodexReasoningEffort?) {
        val storePath = System.getenv("EMBABEL_CODEX_AUTH_FILE")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: defaultEmbabelCodexPath()
        assumeTrue(storePath.exists()) { "Missing persistent Embabel Codex auth file: $storePath" }

        val store = FileCodexAuthStore(storePath, jacksonObjectMapper())
        val credentials = requireNotNull(store.load()) { "Invalid Embabel Codex auth file: $storePath" }
        val tokenProvider = CodexAccessTokenProvider(store, CodexTokenRefresher())
        val client = CodexResponsesClient(tokenProvider, credentials, RestClientCodexHttpTransport())
        val service = SpringAiLlmService(model, "codex", CodexChatModel(client, model), CodexOptionsConverter)
        val options = effort?.let { LlmOptions(model = model).withCodexReasoningEffort(it) } ?: LlmOptions(model = model)
        val tools = if (System.getenv("EMBABEL_CODEX_LIVE_TOOLS") == "1") {
            listOf(Tool.create("unused_lookup", "Optional lookup not needed for this request") {
                error("Core sender must not execute tools")
            })
        } else emptyList()
        val response = service.createMessageSender(options).call(
            listOf(UserMessage("Do not call tools. Reply with exactly: EMBABEL_CODEX_OK")), tools,
        )
        val text = response.textContent
        val usage = assertNotNull(response.usage, "Live response should provide token usage")
        assertTrue((usage.promptTokens ?: 0) > 0)
        assertTrue((usage.completionTokens ?: 0) > 0)
        assertTrue(
            text.contains("EMBABEL_CODEX_OK", ignoreCase = true),
            "Unexpected model response for $model / $effort (len=${text.length}): ${text.take(200)}"
        )
        println("Codex live PASS: $model / ${effort?.wireValue ?: "default"}")
    }

    companion object {
        @JvmStatic
        fun cases(): List<Arguments> {
            val models = requireNotNull(System.getenv("EMBABEL_CODEX_MODEL")) {
                "EMBABEL_CODEX_MODEL must be set for the live test"
            }.split(",").map { it.trim().also { model -> require(model.isNotBlank()) } }
            val efforts = System.getenv("EMBABEL_CODEX_REASONING_EFFORT")
                ?.split(",")?.map { CodexReasoningEffort.fromWireValue(it.trim()) } ?: listOf(null)
            return models.flatMap { model -> efforts.map { effort -> Arguments.of(model, effort) } }
        }
    }
}
