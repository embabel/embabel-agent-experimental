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
package com.embabel.agent.eval.support

import com.embabel.agent.eval.client.MessageRole
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Data used to prime agent memory before evaluation begins.
 * Consumers decide how to process each seed type
 * (e.g. drive a conversation, ingest a document, replay a transcript).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
    JsonSubTypes.Type(value = TextSeed::class),
    JsonSubTypes.Type(value = ConversationSeed::class),
)
sealed interface Seed

/**
 * A block of text to seed as a document or knowledge source.
 */
data class TextSeed(
    val text: String,
) : Seed

/**
 * A single turn in a seed conversation.
 */
data class SeedMessage(
    val role: MessageRole = MessageRole.user,
    val content: String,
)

/**
 * A conversation transcript used to seed agent memory.
 * Can be user-only messages (assistant responses come from the live agent)
 * or a full two-sided transcript to replay.
 */
data class ConversationSeed(
    val conversation: List<SeedMessage>,
) : Seed
