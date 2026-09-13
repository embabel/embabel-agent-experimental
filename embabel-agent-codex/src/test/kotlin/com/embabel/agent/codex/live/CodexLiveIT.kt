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
import com.embabel.agent.codex.chat.CodexChatOptions
import com.embabel.agent.codex.responses.CodexReasoningEffort
import com.embabel.agent.codex.chat.CodexChatModel
import com.embabel.agent.codex.responses.CodexResponsesClient
import com.embabel.agent.codex.responses.RestClientCodexHttpTransport
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.Arguments
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertTrue

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
        val chatModel = CodexChatModel(
            client, model, CodexChatOptions(reasoningEffort = effort),
            RetryTemplate(RetryPolicy.withMaxRetries(0)),
        )
        val response = chatModel.call(
            Prompt(listOf(UserMessage("Reply with exactly: EMBABEL_CODEX_OK")))
        )
        val text = response.result?.output?.text.orEmpty()
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
