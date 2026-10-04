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

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.event.observation.InternalObservabilityApi
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.codex.auth.CodexAccessTokenProvider
import com.embabel.agent.codex.auth.CodexTokenRefresher
import com.embabel.agent.codex.auth.FileCodexAuthStore
import com.embabel.agent.codex.auth.defaultEmbabelCodexPath
import com.embabel.agent.codex.chat.CodexChatModel
import com.embabel.agent.codex.chat.CodexOptionsConverter
import com.embabel.agent.codex.chat.withCodexReasoningEffort
import com.embabel.agent.codex.responses.CodexHttpTransport
import com.embabel.agent.codex.responses.CodexReasoningEffort
import com.embabel.agent.codex.responses.CodexResponsesClient
import com.embabel.agent.codex.responses.RestClientCodexHttpTransport
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.support.DefaultToolDecorator
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.spi.support.ToolLoopLlmOperations
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.common.ai.model.ConfigurableModelProvider
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.LlmOptions
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.validation.Validation
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Runs text and tool lookup goals through the real planner and LLM operations using dedicated Codex auth. */
@EnabledIfEnvironmentVariable(named = "EMBABEL_LIVE_CODEX", matches = "1")
class CodexGoalLiveIT {
    @OptIn(InternalObservabilityApi::class)
    @ParameterizedTest(name = "tool lookup enabled: {0}")
    @ValueSource(booleans = [false, true])
    fun `luna achieves a goal through core with optional tool execution`(usesTool: Boolean) {
        val path = System.getenv("EMBABEL_CODEX_AUTH_FILE")
            ?.takeIf { it.isNotBlank() }?.let(Path::of) ?: defaultEmbabelCodexPath()
        val store = FileCodexAuthStore(path, jacksonObjectMapper())
        val credentials = requireNotNull(store.load()) { "Dedicated Embabel Codex login required: $path" }
        val requests = mutableListOf<String>()
        val transport = RestClientCodexHttpTransport()
        val client = CodexResponsesClient(CodexAccessTokenProvider(store, CodexTokenRefresher()), credentials,
            CodexHttpTransport { url, headers, body ->
                requests.add(body)
                transport.post(url, headers, body)
            })
        val secret = UUID.randomUUID().toString()
        val toolCalls = AtomicInteger()
        val tool = Tool.create("lookup_code", "Return the current verification code") {
            check(toolCalls.incrementAndGet() == 1) { "Only one lookup is needed" }
            Tool.Result.text(secret)
        }
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
                    AgentMetadataReader().createAgentMetadata(SimpleGoalAgent(model, if (usesTool) tool else null)),
                )

                val process = platform.runAgentFrom(
                    agent = agent,
                    processOptions = ProcessOptions(),
                    bindings = mapOf("it" to TranslationRequest("chat", "French")),
                )

                assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
                val result = assertIs<GoalAnswer>(process.lastResult())
                assertEquals(if (usesTool) secret else "cat", result.word.trim().lowercase())
                assertEquals(if (usesTool) 1 else 0, toolCalls.get())
                assertEquals(if (usesTool) 2 else 1, requests.size)
                if (usesTool) {
                    val mapper = jacksonObjectMapper()
                    val input = mapper.readTree(requests.last()).path("input")
                    val call = input.single { it.path("type").asText() == "function_call" }
                    val output = input.single { it.path("type").asText() == "function_call_output" }
                    assertEquals("lookup_code", call.path("name").asText())
                    assertEquals(call.path("call_id").asText(), output.path("call_id").asText())
                    assertTrue(output.path("output").asText().contains(secret))
                }
                assertEquals(if (usesTool) 2 else 1, process.llmInvocations.size)
                assertTrue((process.ownUsage().promptTokens ?: 0) > 0)
                assertTrue((process.ownUsage().completionTokens ?: 0) > 0)
                println("Codex goal PASS: $model / low; tool=$usesTool; requests=${requests.size}; ${process.ownUsage()}")
            }
        }
    }

    data class TranslationRequest(val word: String, val language: String)
    data class GoalAnswer(val word: String)

    @Agent(description = "Answer a simple text or tool lookup request")
    class SimpleGoalAgent(private val model: String, private val tool: Tool?) {
        @Action
        @AchievesGoal(description = "The requested answer is available")
        fun answer(request: TranslationRequest, context: OperationContext): GoalAnswer {
            val runner = context.ai()
                .withLlm(LlmOptions(model = model).withCodexReasoningEffort(CodexReasoningEffort.LOW))
            val text = if (tool == null) {
                runner.generateText("Translate the ${request.language} word '${request.word}' into English. Return only the lowercase translated word, without punctuation.")
            } else {
                runner.withTools(tool).generateText("Call lookup_code exactly once. Return only the exact code it returns, without punctuation or explanation.")
            }
            return GoalAnswer(text)
        }
    }
}
