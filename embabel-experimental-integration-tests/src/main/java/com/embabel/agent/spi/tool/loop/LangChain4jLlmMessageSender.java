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
package com.embabel.agent.spi.tool.loop;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.core.Usage;
import com.embabel.agent.spi.loop.LlmMessageResponse;
import com.embabel.agent.spi.loop.LlmMessageSender;
import com.embabel.chat.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j implementation of {@link LlmMessageSender}.
 * <p>
 * Demonstrates framework-agnostic tool loop by implementing the LlmMessageSender
 * interface using LangChain4j's ChatLanguageModel.
 * <p>
 * Key responsibilities:
 * <ul>
 *   <li>Convert Embabel messages to LangChain4j messages</li>
 *   <li>Convert Embabel tools to LangChain4j ToolSpecifications</li>
 *   <li>Call LangChain4j ChatLanguageModel</li>
 *   <li>Convert response back to Embabel format</li>
 * </ul>
 */
public class LangChain4jLlmMessageSender implements LlmMessageSender {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jLlmMessageSender.class);

    private final ChatLanguageModel chatModel;

    public LangChain4jLlmMessageSender(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    @NotNull
    @Override
    public LlmMessageResponse call(
            @NotNull List<? extends Message> messages,
            @NotNull List<? extends Tool> tools) {
        // Convert Embabel messages to LangChain4j messages
        var lc4jMessages = convertMessages(new ArrayList<>(messages));

        // Convert Embabel tools to LangChain4j tool specifications
        var toolSpecs = convertTools(new ArrayList<>(tools));

        // Build request with tool specifications (definitions only, NOT execution)
        // LangChain4j's ChatLanguageModel.chat() does NOT execute tools automatically.
        // It returns tool execution requests in the response, which the DefaultToolLoop
        // will handle. This is different from AiServices which auto-executes tools.
        var requestBuilder = ChatRequest.builder().messages(lc4jMessages);
        if (!toolSpecs.isEmpty()) {
            requestBuilder.toolSpecifications(toolSpecs);
        }
        var request = requestBuilder.build();

        logger.debug("Calling LangChain4j with {} messages and {} tools (tool execution disabled)",
            lc4jMessages.size(), toolSpecs.size());

        // ChatLanguageModel.chat() returns tool call requests but does NOT execute them
        ChatResponse response = chatModel.chat(request);
        var aiMessage = response.aiMessage();

        logger.debug("LangChain4j response: hasToolCalls={}, contentLength={}",
            aiMessage.hasToolExecutionRequests(),
            aiMessage.text() != null ? aiMessage.text().length() : 0);

        // Convert response to Embabel format
        var embabelMessage = convertAiMessage(aiMessage);
        var usage = convertUsage(response.tokenUsage());

        return new LlmMessageResponse(
            embabelMessage,
            aiMessage.text() != null ? aiMessage.text() : "",
            usage
        );
    }

    /**
     * Convert Embabel messages to LangChain4j ChatMessages.
     */
    private List<ChatMessage> convertMessages(List<Message> messages) {
        var result = new ArrayList<ChatMessage>();

        for (var msg : messages) {
            if (msg instanceof com.embabel.chat.SystemMessage sysMsg) {
                result.add(dev.langchain4j.data.message.SystemMessage.from(sysMsg.getContent()));
            } else if (msg instanceof com.embabel.chat.UserMessage userMsg) {
                result.add(dev.langchain4j.data.message.UserMessage.from(userMsg.getContent()));
            } else if (msg instanceof AssistantMessageWithToolCalls toolCallMsg) {
                // Convert tool calls
                var toolRequests = toolCallMsg.getToolCalls().stream()
                    .map(tc -> ToolExecutionRequest.builder()
                        .id(tc.getId())
                        .name(tc.getName())
                        .arguments(tc.getArguments())
                        .build())
                    .toList();
                result.add(AiMessage.from(toolCallMsg.getContent(), toolRequests));
            } else if (msg instanceof ToolResultMessage toolResult) {
                result.add(ToolExecutionResultMessage.from(
                    toolResult.getToolCallId(),
                    toolResult.getToolName(),
                    toolResult.getContent()
                ));
            } else if (msg instanceof AssistantMessage assistantMsg) {
                result.add(AiMessage.from(assistantMsg.getContent()));
            }
        }

        return result;
    }

    /**
     * Convert Embabel Tools to LangChain4j ToolSpecifications.
     * <p>
     * Note: Using name and description only since our demo tools have no input parameters.
     * For tools with parameters, use ToolSpecification.builder().addParameter() to define
     * each parameter with its name, type, and description from tool.getDefinition().getInputSchema().
     */
    private List<ToolSpecification> convertTools(List<Tool> tools) {
        return tools.stream()
            .map(tool -> {
                var def = tool.getDefinition();
                return ToolSpecification.builder()
                    .name(def.getName())
                    .description(def.getDescription())
                    .build();
            })
            .toList();
    }

    /**
     * Convert LangChain4j AiMessage to Embabel Message.
     */
    private Message convertAiMessage(AiMessage aiMessage) {
        if (aiMessage.hasToolExecutionRequests()) {
            var toolCalls = aiMessage.toolExecutionRequests().stream()
                .map(req -> new ToolCall(
                    req.id(),
                    req.name(),
                    req.arguments()
                ))
                .toList();
            return new AssistantMessageWithToolCalls(
                aiMessage.text() != null ? aiMessage.text() : "",
                toolCalls
            );
        } else {
            return new AssistantMessage(aiMessage.text() != null ? aiMessage.text() : "");
        }
    }

    /**
     * Convert LangChain4j TokenUsage to Embabel Usage.
     */
    private Usage convertUsage(TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return null;
        }
        return new Usage(
            tokenUsage.inputTokenCount(),
            tokenUsage.outputTokenCount(),
            tokenUsage  // native usage
        );
    }
}
