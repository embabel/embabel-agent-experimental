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

import com.embabel.agent.channel.LoggingOutputChannelEvent
import com.embabel.agent.channel.MessageOutputChannelEvent
import com.embabel.agent.channel.OutputChannel
import com.embabel.agent.channel.OutputChannelEvent
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion

class ChannelRespondingOutputChannel(
    private val channel: MessageChannelUnion,
) : OutputChannel {
    private var progressMessage: Message? = null

    override fun send(
        event: OutputChannelEvent,
    ) {
        when (event) {
            is LoggingOutputChannelEvent -> {
                DiscordMessageUtils.sendLongMessage(channel, event.message)
            }

            is MessageOutputChannelEvent -> {
                DiscordMessageUtils.sendLongMessage(channel, event.message.content)
            }

            else -> {
                // Handle other event types if necessary
            }
        }

    }
}
