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

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexPromptConverterTest {

    @Nested
    inner class ConvertMessages {

        @Test
        fun `converts user message to input item`() {
            val messages = listOf(UserMessage("Hello Codex"))
            val items = CodexPromptConverter.toInputItems(messages)
            assertEquals(1, items.size)
            assertEquals("message", items[0]["type"])
            assertEquals("user", items[0]["role"])
        }

        @Test
        fun `moves system message into instructions`() {
            val conversion = CodexPromptConverter.convert(
                listOf(SystemMessage("You are a helpful assistant"), UserMessage("Hi"))
            )
            assertEquals("You are a helpful assistant", conversion.instructions)
            assertEquals(1, conversion.inputItems.size)
            assertEquals("user", conversion.inputItems[0]["role"])
        }

        @Test
        fun `system-only prompt has instructions and empty input`() {
            val conversion = CodexPromptConverter.convert(listOf(SystemMessage("sys")))
            assertEquals("sys", conversion.instructions)
            assertTrue(conversion.inputItems.isEmpty())
        }

        @Test
        fun `content contains input_text type`() {
            val messages = listOf(UserMessage("test text"))
            val items = CodexPromptConverter.toInputItems(messages)
            @Suppress("UNCHECKED_CAST")
            val content = items[0]["content"] as List<Map<String, Any>>
            assertEquals("input_text", content[0]["type"])
            assertEquals("test text", content[0]["text"])
        }

        @Test
        fun `converts assistant tool calls to function_call items`() {
            val assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(
                    listOf(
                        AssistantMessage.ToolCall("call_1", "function", "lookup", """{"q":"x"}""")
                    )
                )
                .build()
            val items = CodexPromptConverter.toInputItems(listOf(assistant))
            assertEquals(1, items.size)
            assertEquals("function_call", items[0]["type"])
            assertEquals("call_1", items[0]["call_id"])
            assertEquals("lookup", items[0]["name"])
        }

        @Test
        fun `converts tool response messages to function_call_output`() {
            val toolResponse = ToolResponseMessage.builder()
                .responses(
                    listOf(
                        ToolResponseMessage.ToolResponse("call_1", "lookup", """{"ok":true}""")
                    )
                )
                .build()
            val items = CodexPromptConverter.toInputItems(listOf(toolResponse))
            assertEquals(1, items.size)
            assertEquals("function_call_output", items[0]["type"])
            assertEquals("call_1", items[0]["call_id"])
            assertEquals("""{"ok":true}""", items[0]["output"])
        }

        @Test
        fun `user-only conversion has no instructions`() {
            val conversion = CodexPromptConverter.convert(listOf(UserMessage("hi")))
            assertNull(conversion.instructions)
        }
    }
}
