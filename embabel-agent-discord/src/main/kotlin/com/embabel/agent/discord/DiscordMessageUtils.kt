/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.discord

import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion

object DiscordMessageUtils {
    private const val DISCORD_MESSAGE_LIMIT = 2000

    fun splitMessage(content: String): List<String> {
        if (content.length <= DISCORD_MESSAGE_LIMIT) {
            return listOf(content)
        }

        val messages = mutableListOf<String>()
        var currentMessage = StringBuilder()
        val lines = content.split('\n')

        for (line in lines) {
            val proposedLength = currentMessage.length + line.length + 1

            if (proposedLength > DISCORD_MESSAGE_LIMIT && currentMessage.isNotEmpty()) {
                messages.add(currentMessage.toString().trimEnd())
                currentMessage = StringBuilder()
            }

            if (line.length > DISCORD_MESSAGE_LIMIT) {
                if (currentMessage.isNotEmpty()) {
                    messages.add(currentMessage.toString().trimEnd())
                    currentMessage = StringBuilder()
                }

                var remainingLine = line
                while (remainingLine.length > DISCORD_MESSAGE_LIMIT) {
                    val chunk = remainingLine.substring(0, DISCORD_MESSAGE_LIMIT)
                    messages.add(chunk)
                    remainingLine = remainingLine.substring(DISCORD_MESSAGE_LIMIT)
                }
                if (remainingLine.isNotEmpty()) {
                    currentMessage.append(remainingLine).append('\n')
                }
            } else {
                if (currentMessage.isNotEmpty()) {
                    currentMessage.append('\n')
                }
                currentMessage.append(line)
            }
        }

        if (currentMessage.isNotEmpty()) {
            messages.add(currentMessage.toString().trimEnd())
        }

        return messages
    }

    fun sendLongMessage(
        channel: MessageChannelUnion,
        content: String,
    ) {
        val messageParts = splitMessage(content)
        messageParts.forEach { part ->
            channel.sendMessage(part).queue()
        }
    }
}
