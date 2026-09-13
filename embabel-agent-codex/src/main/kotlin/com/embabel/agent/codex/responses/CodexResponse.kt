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

import org.springframework.ai.chat.metadata.Usage
import com.embabel.agent.core.NonRetryable

data class FunctionCall(
    val name: String,
    val arguments: String,
    val callId: String? = null,
)

data class CodexResponse(
    val outputText: String,
    val functionCalls: List<FunctionCall> = emptyList(),
    val raw: String,
    val usage: Usage? = null,
    val id: String? = null,
    val model: String? = null,
)

open class CodexResponseException(
    message: String,
    cause: Throwable? = null,
    val code: String? = null,
) : RuntimeException(message, cause)

class CodexTerminalResponseException(message: String, code: String?) :
    CodexResponseException(message, code = code), NonRetryable
