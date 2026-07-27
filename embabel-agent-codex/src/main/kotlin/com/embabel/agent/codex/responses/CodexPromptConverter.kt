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
package com.embabel.agent.codex.responses

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

data class CodexPromptConversion(
    val instructions: String? = null,
    val inputItems: List<Map<String, Any>> = emptyList(),
)

object CodexPromptConverter {

    fun convert(messages: List<Message>): CodexPromptConversion {
        val instructions = messages
            .filterIsInstance<SystemMessage>()
            .map { it.text }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
        val inputItems = messages.flatMap { message -> toInputItems(message) }
        return CodexPromptConversion(instructions = instructions, inputItems = inputItems)
    }

    fun toInputItems(messages: List<Message>): List<Map<String, Any>> =
        convert(messages).inputItems

    private fun toInputItems(message: Message): List<Map<String, Any>> =
        when (message) {
            is SystemMessage -> emptyList()
            is UserMessage -> listOf(
                mapOf(
                    "type" to "message",
                    "role" to "user",
                    "content" to listOf(mapOf("type" to "input_text", "text" to message.text))
                )
            )
            is AssistantMessage -> assistantItems(message)
            is ToolResponseMessage -> message.responses.map { response ->
                mapOf(
                    "type" to "function_call_output",
                    "call_id" to response.id(),
                    "output" to response.responseData(),
                )
            }
            else -> emptyList()
        }

    private fun assistantItems(message: AssistantMessage): List<Map<String, Any>> {
        val items = mutableListOf<Map<String, Any>>()
        if (message.hasToolCalls()) {
            for (toolCall in message.toolCalls) {
                items += mapOf(
                    "type" to "function_call",
                    "call_id" to toolCall.id(),
                    "name" to toolCall.name(),
                    "arguments" to toolCall.arguments(),
                )
            }
        }
        val text = message.text
        if (!text.isNullOrBlank()) {
            items += mapOf(
                "type" to "message",
                "role" to "assistant",
                "content" to listOf(mapOf("type" to "output_text", "text" to text))
            )
        }
        return items
    }
}
