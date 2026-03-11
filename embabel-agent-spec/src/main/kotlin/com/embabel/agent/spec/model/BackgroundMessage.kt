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

import com.embabel.chat.AssistantMessage
import com.embabel.chat.ContentPart
import com.embabel.chat.TextPart
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Message from background infrastructure
 */
@JsonClassDescription("Assistant message from background: Use to notify user if that appears to be the goal of the action")
@JsonIgnoreProperties(value = ["role", "name", "timestamp", "awaitable", "assets"], ignoreUnknown = true)
class BackgroundMessage(content: String) : AssistantMessage(
    content = content,
) {

    @JsonCreator
    constructor(
        @JsonProperty("content") content: String? = null,
        @JsonProperty("parts") parts: List<ContentPart>? = null,
    ) : this(
        content?.trim()?.takeIf { it.isNotEmpty() }
            ?: parts.orEmpty()
                .filterIsInstance<TextPart>()
                .joinToString("") { it.text }
                .trim()
                .also {
                    require(it.isNotEmpty()) {
                        "BackgroundMessage requires non-empty 'content' or text parts"
                    }
                }
    )
}
