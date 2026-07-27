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

import com.embabel.agent.codex.responses.CodexPromptConverter
import com.embabel.agent.codex.responses.CodexResponse
import com.embabel.agent.codex.responses.CodexResponsesClient
import com.embabel.agent.codex.responses.CodexToolConverter
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryTemplate

class CodexChatModel(
    private val responsesClient: CodexResponsesClient,
    private val model: String,
    private val defaultOptions: CodexChatOptions = CodexChatOptions(modelName = model),
    private val retryTemplate: RetryTemplate = RetryTemplate(),
) : ChatModel {

    override fun call(prompt: Prompt): ChatResponse {
        val conversion = CodexPromptConverter.convert(prompt.instructions)
        val tools = CodexToolConverter.fromPromptOptions(prompt.options)
        val options = resolveOptions(prompt.options)
        val effectiveModel = options.model ?: model
        val codexResponse = try {
            retryTemplate.execute<CodexResponse> {
                responsesClient.create(
                    model = effectiveModel,
                    input = conversion.inputItems,
                    tools = tools,
                    instructions = conversion.instructions,
                    maxOutputTokens = options.maxTokens,
                    temperature = options.temperature,
                    topP = options.topP,
                )
            }
        } catch (retryExhausted: RetryException) {
            // Spring Framework's core RetryTemplate wraps the final failure, unlike
            // spring-retry which rethrew it. Surface the module's typed exceptions.
            throw retryExhausted.cause ?: retryExhausted
        }
        val toolCalls = codexResponse.functionCalls.map { call ->
            AssistantMessage.ToolCall(
                call.callId ?: "call_${call.name}",
                "function",
                call.name,
                call.arguments,
            )
        }
        val assistantMessage = if (toolCalls.isEmpty()) {
            AssistantMessage(codexResponse.outputText)
        } else {
            AssistantMessage.builder()
                .content(codexResponse.outputText)
                .toolCalls(toolCalls)
                .build()
        }
        return ChatResponse(listOf(Generation(assistantMessage)))
    }

    override fun getDefaultOptions(): ChatOptions = defaultOptions

    private fun resolveOptions(options: ChatOptions?): CodexChatOptions {
        if (options == null) return defaultOptions
        return CodexChatOptions(
            modelName = options.model ?: defaultOptions.model ?: model,
            temperature = options.temperature ?: defaultOptions.temperature,
            maxTokens = options.maxTokens ?: defaultOptions.maxTokens,
            topP = options.topP ?: defaultOptions.topP,
        )
    }
}

data class CodexChatOptions(
    private val modelName: String? = null,
    private val temperature: Double? = null,
    private val maxTokens: Int? = null,
    private val topP: Double? = null,
) : ChatOptions {
    override fun getModel(): String? = modelName
    override fun getFrequencyPenalty(): Double? = null
    override fun getMaxTokens(): Int? = maxTokens
    override fun getPresencePenalty(): Double? = null
    override fun getStopSequences(): List<String>? = null
    override fun getTemperature(): Double? = temperature
    override fun getTopK(): Int? = null
    override fun getTopP(): Double? = topP

    /**
     * Spring AI 2.0 replaced `ChatOptions.copy()` with `mutate()`. These options carry
     * no Codex-specific fields beyond the portable ones, so a pre-populated portable
     * builder reproduces this instance faithfully.
     */
    override fun mutate(): ChatOptions.Builder<*> =
        ChatOptions.builder()
            .model(modelName)
            .temperature(temperature)
            .maxTokens(maxTokens)
            .topP(topP)
}
