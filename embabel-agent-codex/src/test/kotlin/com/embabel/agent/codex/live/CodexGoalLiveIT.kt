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
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.agent.codex.responses.CodexReasoningEffort
import com.embabel.agent.codex.chat.CodexChatModel
import com.embabel.agent.codex.responses.CodexResponsesClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import java.util.concurrent.Executors
import com.embabel.agent.spi.support.ExecutorAsyncer
import kotlin.test.assertTrue


import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.api.event.observation.InternalObservabilityApi
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.support.DefaultToolDecorator
import com.embabel.agent.spi.support.ToolLoopLlmOperations
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.common.ai.model.ConfigurableModelProvider
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Runs an annotated translation goal through the real planner and LLM operations using dedicated Codex auth. */
@EnabledIfEnvironmentVariable(named = "EMBABEL_LIVE_CODEX", matches = "1")
class CodexGoalLiveIT {
    @OptIn(InternalObservabilityApi::class)
    @Test
    fun `luna achieves an English translation goal`() {
        val path = System.getenv("EMBABEL_CODEX_AUTH_FILE")
            ?.takeIf { it.isNotBlank() }?.let(Path::of) ?: defaultEmbabelCodexPath()
        val store = FileCodexAuthStore(path, jacksonObjectMapper())
        val credentials = requireNotNull(store.load()) { "Dedicated Embabel Codex login required: $path" }
        val client = CodexResponsesClient(CodexAccessTokenProvider(store, CodexTokenRefresher()), credentials)
        val model = "gpt-5.6-luna"
        val service = SpringAiLlmService(model, "codex", CodexChatModel(client, model), CodexOptionsConverter)
        val provider = ConfigurableModelProvider(
            llms = listOf(service),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(defaultLlm = model),
        )
        Validation.buildDefaultValidatorFactory().use { validation ->
            Executors.newSingleThreadExecutor().use { executor ->
                val operations = ToolLoopLlmOperations(
                    provider, DefaultToolDecorator(), validation.validator, asyncer = ExecutorAsyncer(executor),
                )
                val platform = dummyAgentPlatform(llmOperations = operations)
                val agent = assertIs<CoreAgent>(
                    AgentMetadataReader().createAgentMetadata(TranslationAgent(model)),
                )

                val process = platform.runAgentFrom(
                    agent = agent,
                    processOptions = ProcessOptions(),
                    bindings = mapOf("it" to TranslationRequest("chat", "French")),
                )

                assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
                val result = assertIs<EnglishTranslation>(process.lastResult())
                assertEquals("cat", result.word.trim().lowercase())
                assertEquals(1, process.llmInvocations.size)
                assertTrue((process.ownUsage().promptTokens ?: 0) > 0)
                assertTrue((process.ownUsage().completionTokens ?: 0) > 0)
                println("Codex goal PASS: $model / low: chat (French) -> ${result.word}; ${process.ownUsage()}")
            }
        }
    }

    data class TranslationRequest(val word: String, val language: String)
    data class EnglishTranslation(val word: String)

    @Agent(description = "Translate a word into English")
    class TranslationAgent(private val model: String) {
        @Action
        @AchievesGoal(description = "An English translation is available")
        fun translate(request: TranslationRequest, context: OperationContext): EnglishTranslation {
            val text = context.ai()
                .withLlm(LlmOptions(model = model).withCodexReasoningEffort(CodexReasoningEffort.LOW))
                .generateText("Translate the ${request.language} word '${request.word}' into English. Return only the lowercase translated word, without punctuation.")
            return EnglishTranslation(text)
        }
    }
}
