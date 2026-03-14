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
package com.embabel.agent.api.client

/**
 * Utility for sanitizing tool names to comply with LLM API requirements.
 *
 * Most LLM APIs (OpenAI, Anthropic, etc.) require tool/function names
 * to match `^[a-zA-Z0-9_-]+$`.
 */
object ToolNames {

    private val INVALID_CHARS = Regex("[^a-zA-Z0-9_-]")
    private val CONSECUTIVE_UNDERSCORES = Regex("_+")

    /**
     * Sanitize a name so it is valid as an LLM tool/function name.
     * Replaces invalid characters with underscores and collapses runs of underscores.
     */
    fun sanitize(name: String): String {
        val sanitized = name
            .replace(INVALID_CHARS, "_")
            .replace(CONSECUTIVE_UNDERSCORES, "_")
            .trim('_')
        return sanitized.ifEmpty { "unnamed_tool" }
    }
}
