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
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.spi.InternalExtensionApi
import com.embabel.agent.codex.responses.CodexReasoningEffort

object CodexOptionsConverter : OptionsConverter {

    override fun convertOptions(options: LlmOptions, model: String): CodexChatOptions =
        CodexChatOptions(
            modelName = model,
            temperature = options.temperature,
            maxTokens = options.maxTokens,
            topP = options.topP,
            reasoningEffort = options.getCodexReasoningEffort(),
        )
}

private const val CODEX_REASONING_EFFORT = "codex.reasoningEffort"

@OptIn(InternalExtensionApi::class)
fun LlmOptions.withCodexReasoningEffort(effort: CodexReasoningEffort): LlmOptions =
    withExtension(CODEX_REASONING_EFFORT, effort)

@OptIn(InternalExtensionApi::class)
fun LlmOptions.getCodexReasoningEffort(): CodexReasoningEffort? =
    getExtension(CODEX_REASONING_EFFORT)
