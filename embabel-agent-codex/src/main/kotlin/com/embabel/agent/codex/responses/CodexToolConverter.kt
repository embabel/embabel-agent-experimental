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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback

object CodexToolConverter {

    private val objectMapper = jacksonObjectMapper()

    fun fromPromptOptions(options: ChatOptions?): List<Map<String, Any>> {
        val toolCallbacks = (options as? ToolCallingChatOptions)?.toolCallbacks.orEmpty()
        return fromToolCallbacks(toolCallbacks)
    }

    fun fromToolCallbacks(toolCallbacks: List<ToolCallback>): List<Map<String, Any>> =
        toolCallbacks.map { callback ->
            val definition = callback.toolDefinition
            val parameters = parseSchema(definition.name(), definition.inputSchema())
            mapOf(
                "type" to "function",
                "name" to definition.name(),
                "description" to definition.description(),
                "strict" to false,
                "parameters" to parameters,
            )
        }

    private fun parseSchema(toolName: String, inputSchema: String): Map<String, Any> =
        try {
            objectMapper.readValue(inputSchema)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid input schema for tool '$toolName'", e)
        }
}
