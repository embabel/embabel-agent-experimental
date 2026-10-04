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

import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback

data class CodexChatOptions(
    private val modelName: String? = null,
    private val temperature: Double? = null,
    private val maxTokens: Int? = null,
    private val topP: Double? = null,
    val reasoningEffort: CodexReasoningEffort? = null,
    private val frequencyPenalty: Double? = null,
    private val presencePenalty: Double? = null,
    private val stopSequences: List<String>? = null,
    private val topK: Int? = null,
    private val toolCallbacks: List<ToolCallback> = emptyList(),
    private val toolContext: Map<String, Any> = emptyMap(),
) : ToolCallingChatOptions {
    override fun getToolCallbacks(): List<ToolCallback> = toolCallbacks
    override fun getToolContext(): Map<String, Any> = toolContext
    override fun getModel(): String? = modelName
    override fun getTemperature(): Double? = temperature
    override fun getMaxTokens(): Int? = maxTokens
    override fun getTopP(): Double? = topP
    override fun getFrequencyPenalty(): Double? = frequencyPenalty
    override fun getPresencePenalty(): Double? = presencePenalty
    override fun getStopSequences(): List<String>? = stopSequences
    override fun getTopK(): Int? = topK

    override fun mutate(): Builder = Builder(this)

    class Builder internal constructor(options: CodexChatOptions) : DefaultToolCallingChatOptions.Builder<Builder>() {
        private var effort: CodexReasoningEffort? = options.reasoningEffort

        init {
            model(options.model)
            temperature(options.temperature)
            maxTokens(options.maxTokens)
            topP(options.topP)
            frequencyPenalty(options.frequencyPenalty)
            presencePenalty(options.presencePenalty)
            stopSequences(options.stopSequences)
            topK(options.topK)
            toolCallbacks(options.toolCallbacks)
            toolContext(options.toolContext)
        }

        fun reasoningEffort(value: CodexReasoningEffort?): Builder = apply { effort = value }

        override fun build(): CodexChatOptions {
            val portable = super.build()
            return CodexChatOptions(
                modelName = portable.model,
                temperature = portable.temperature,
                maxTokens = portable.maxTokens,
                topP = portable.topP,
                reasoningEffort = effort,
                frequencyPenalty = portable.frequencyPenalty,
                presencePenalty = portable.presencePenalty,
                stopSequences = portable.stopSequences?.toList(),
                topK = portable.topK,
                toolCallbacks = portable.toolCallbacks.orEmpty().toList(),
                toolContext = portable.toolContext.orEmpty().toMap(),
            )
        }

        override fun combineWith(other: ChatOptions.Builder<*>): Builder = apply {
            super.combineWith(other)
            if (other is Builder) other.effort?.let { effort = it }
        }
    }
}
