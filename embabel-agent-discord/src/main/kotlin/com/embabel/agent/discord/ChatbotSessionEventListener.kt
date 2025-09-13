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

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.chat.*
import com.embabel.chat.agent.ProcessWaitingHandler
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component

/**
 * Discord SessionEventListener that uses an Embabel Chatbot
 */
@Component
@ConditionalOnBean(Chatbot::class)
class ChatbotSessionEventListener(
    private val discordSessionService: DiscordSessionService,
    private val chatbot: Chatbot,
    private val asyncer: Asyncer,
    private val discordConfigProperties: DiscordConfigProperties,
) : ListenerAdapter() {

    private val logger = LoggerFactory.getLogger(ChatbotSessionEventListener::class.java)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) {
            return
        }

        val session = discordSessionService.getOrCreateSession(event)
        val message = event.message.contentRaw
        val channel = event.channel
        val guild = if (session.isDirectMessage) null else event.guild

        if (session.isDirectMessage) {
            respondToDm(event, session)
            return
        } else {
            reactToChannelMessage(event, session)
        }
    }

    private fun reactToChannelMessage(
        event: MessageReceivedEvent,
        session: DiscordUserSession,
    ) {
        logger.info(
            "User {} sent a message in channel {} at {}}",
            session.user, session.channelId, session.lastActivity,
        )
    }

    private fun respondToDm(
        event: MessageReceivedEvent,
        discordUserSession: DiscordUserSession,
    ) {
        logger.info("Responding to DM from user: ${discordUserSession.user}")
        val chatSession = chatSessionFor(discordUserSession, event)
        asyncer.async {
            chatSession.onUserMessage(
                userMessage = UserMessage(content = event.message.contentRaw),
            )
        }
    }

    private fun chatSessionFor(
        discordUserSession: DiscordUserSession,
        event: MessageReceivedEvent,
    ): ChatSession {
        return discordUserSession.sessionData.getOrPut("chatSession") {
            chatbot.createSession(
                discordUserSession.user,
                systemMessage = null,
                outputChannel = ChannelRespondingOutputChannel(
                    channel = event.channel,
                )
            )
        } as ChatSession
    }
}

class DiscordProcessWaitingHandler : ProcessWaitingHandler {
    override fun handleProcessWaitingException(
        pwe: ProcessWaitingException,
        basis: Any,
    ): AssistantMessage {
        TODO("Not yet implemented")
    }
}

/**
 * Listens for Embabel messages and responds in the same channel
 * as the given Discord event.
 */
class ChannelRespondingMessageListener(
    private val event: MessageReceivedEvent,
) {
    private var progressMessage: net.dv8tion.jda.api.entities.Message? = null

    fun onMessage(
        message: Message,
        conversation: Conversation,
    ) {
        event.channel.sendTyping().queue()
        if (!conversation.messages.contains(message)) {
            // This is a progress message - update or create progress indicator
            if (progressMessage == null) {
                progressMessage = try {
                    val progressContent = "🔄 ${message.content}"
                    if (progressContent.length > 2000) {
                        event.channel.sendMessage("🔄 [Message too long for progress display]").complete()
                    } else {
                        event.channel.sendMessage(progressContent).complete()
                    }
                } catch (e: Exception) {
                    // If we can't send the progress message, just continue
                    null
                }
            } else {
                val progressContent = "🔄 ${message.content}"
                val editContent =
                    if (progressContent.length > 2000) "🔄 [Message too long for progress display]" else progressContent
                progressMessage!!.editMessage(editContent).queue(
                    { /* success */ },
                    {
                        // Message no longer exists, create a new one
                        progressMessage = try {
                            val progressContent = "🔄 ${message.content}"
                            if (progressContent.length > 2000) {
                                event.channel.sendMessage("🔄 [Message too long for progress display]").complete()
                            } else {
                                event.channel.sendMessage(progressContent).complete()
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                )
            }
        } else {
            // Clean up progress message and send final response
            progressMessage?.delete()?.queue(
                { /* success */ },
                { /* ignore delete failures */ }
            )
            progressMessage = null
            DiscordMessageUtils.sendLongMessage(event.channel, message.content)
        }
    }
}
