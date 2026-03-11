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
package com.embabel.agent.spec.model

import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BackgroundMessageTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Nested
    inner class ValidPayloads {

        @Test
        fun `deserializes simple content payload`() {
            val json = """
                {
                  "content": "Hallo! Wie kann ich Ihnen heute helfen?"
                }
            """.trimIndent()

            val message = objectMapper.readValue(json, BackgroundMessage::class.java)

            assertEquals("Hallo! Wie kann ich Ihnen heute helfen?", message.content)
        }

        @Test
        fun `deserializes inherited message payload by deriving content from text parts`() {
            val json = """
                {
                  "role": "ASSISTANT",
                  "name": "Greeting",
                  "timestamp": "2026-03-11T00:00:00Z",
                  "parts": [
                    {
                      "text": "Hallo! Wie kann ich Ihnen heute helfen?"
                    }
                  ]
                }
            """.trimIndent()

            val message = objectMapper.readValue(json, BackgroundMessage::class.java)

            assertEquals("Hallo! Wie kann ich Ihnen heute helfen?", message.content)
        }

        @Test
        fun `deserializes parts using typed text parts`() {
            val json = """
                {
                  "parts": [
                    {
                      "text": "Systems nominal."
                    }
                  ]
                }
            """.trimIndent()

            val message = objectMapper.readValue(json, BackgroundMessage::class.java)

            assertEquals("Systems nominal.", message.content)
        }

        @Test
        fun `concatenates content across multiple parts`() {
            val json = """
                {
                  "parts": [
                    {
                      "text": "Hallo! "
                    },
                    {
                      "text": "Wie kann ich Ihnen "
                    },
                    {
                      "text": "heute helfen?"
                    }
                  ]
                }
            """.trimIndent()

            val message = objectMapper.readValue(json, BackgroundMessage::class.java)

            assertEquals("Hallo! Wie kann ich Ihnen heute helfen?", message.content)
        }

        @Test
        fun `prefers explicit content over derived parts`() {
            val json = """
                {
                  "content": "Use this value",
                  "parts": [
                    {
                      "text": "Ignore this"
                    }
                  ]
                }
            """.trimIndent()

            val message = objectMapper.readValue(json, BackgroundMessage::class.java)

            assertEquals("Use this value", message.content)
        }

        @Test
        fun `trims explicit content before using it`() {
            val json = """
                {
                  "content": "  Guten Tag  "
                }
            """.trimIndent()

            val message = objectMapper.readValue(json, BackgroundMessage::class.java)

            assertEquals("Guten Tag", message.content)
        }

        @Test
        fun `ignores unknown top level properties`() {
            val json = """
                {
                  "content": "Hello",
                  "role": "ASSISTANT",
                  "timestamp": "2026-03-11T00:00:00Z",
                  "unexpected": {
                    "nested": true
                  }
                }
            """.trimIndent()

            val message = objectMapper.readValue(json, BackgroundMessage::class.java)

            assertEquals("Hello", message.content)
        }
    }

    @Nested
    inner class InvalidPayloads {

        @Test
        fun `rejects payload with blank content and no text parts`() {
            val json = """
                {
                  "content": "   ",
                  "parts": [
                    {
                      "mimeType": "image/png",
                      "data": "AQID"
                    }
                  ]
                }
            """.trimIndent()

            val exception = assertThrows(ValueInstantiationException::class.java) {
                objectMapper.readValue(json, BackgroundMessage::class.java)
            }

            assertEquals(
                "BackgroundMessage requires non-empty 'content' or text parts",
                exception.cause?.message
            )
        }

        @Test
        fun `rejects payload with empty parts`() {
            val json = """
                {
                  "parts": []
                }
            """.trimIndent()

            val exception = assertThrows(ValueInstantiationException::class.java) {
                objectMapper.readValue(json, BackgroundMessage::class.java)
            }

            assertEquals(
                "BackgroundMessage requires non-empty 'content' or text parts",
                exception.cause?.message
            )
        }

        @Test
        fun `rejects payload when all text parts are blank`() {
            val json = """
                {
                  "parts": [
                    {
                      "text": "   "
                    },
                    {
                      "text": " "
                    }
                  ]
                }
            """.trimIndent()

            val exception = assertThrows(ValueInstantiationException::class.java) {
                objectMapper.readValue(json, BackgroundMessage::class.java)
            }

            assertEquals(
                "BackgroundMessage requires non-empty 'content' or text parts",
                exception.cause?.message
            )
        }
    }
}
