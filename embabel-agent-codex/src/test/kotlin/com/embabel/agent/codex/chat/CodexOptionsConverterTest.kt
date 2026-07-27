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

import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CodexOptionsConverterTest {

    @Nested
    inner class ConvertOptions {

        @Test
        fun `maps llm options onto codex chat options`() {
            val converted = CodexOptionsConverter.convertOptions(
                LlmOptions(model = "gpt-5.6-sol")
                    .withTemperature(0.2)
                    .withMaxTokens(1024)
                    .withTopP(0.9)
            )
            assertEquals("gpt-5.6-sol", converted.model)
            assertEquals(0.2, converted.temperature)
            assertEquals(1024, converted.maxTokens)
            assertEquals(0.9, converted.topP)
        }
    }
}
