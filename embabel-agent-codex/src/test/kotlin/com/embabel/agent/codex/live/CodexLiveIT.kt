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
import com.embabel.agent.codex.chat.CodexChatModel
import com.embabel.agent.codex.responses.CodexResponsesClient
import com.embabel.agent.codex.responses.RestClientCodexHttpTransport
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "EMBABEL_LIVE_CODEX", matches = "1")
class CodexLiveIT {

    @Test
    fun `uses persistent Embabel auth and completes a responses call`() {
        val storePath = System.getenv("EMBABEL_CODEX_AUTH_FILE")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: defaultEmbabelCodexPath()
        assumeTrue(storePath.exists()) { "Missing persistent Embabel Codex auth file: $storePath" }

        val store = FileCodexAuthStore(storePath, jacksonObjectMapper())
        val credentials = requireNotNull(store.load()) { "Invalid Embabel Codex auth file: $storePath" }
        val tokenProvider = CodexAccessTokenProvider(store, CodexTokenRefresher())
        val client = CodexResponsesClient(tokenProvider, credentials, RestClientCodexHttpTransport())
        val model = requireNotNull(System.getenv("EMBABEL_CODEX_MODEL")) {
            "EMBABEL_CODEX_MODEL must be set for the live test"
        }
        val chatModel = CodexChatModel(client, model)

        val response = chatModel.call(
            Prompt(listOf(UserMessage("Reply with exactly: EMBABEL_CODEX_OK")))
        )
        val text = response.result?.output?.text.orEmpty()
        assertTrue(
            text.contains("EMBABEL_CODEX_OK", ignoreCase = true),
            "Unexpected model response for $model (len=${text.length}): ${text.take(200)}"
        )
    }
}
