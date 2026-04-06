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
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * OpenAI Java SDK implementation of {@link LlmMessageSender}.
 * <p>
 * Demonstrates framework-agnostic tool loop by implementing the LlmMessageSender
 * interface using OpenAI's official Java SDK directly (without Spring AI or LangChain4j).
 * <p>
 * <h2>Tool Loop Integration</h2>
 * This class is called by {@code DefaultToolLoop} which manages conversation history:
 * <ul>
 *   <li><b>Iteration 1:</b> Called with [SystemMessage, UserMessage]. May return tool calls.</li>
 *   <li><b>Iteration 2+:</b> Called with full history including AssistantMessageWithToolCalls
 *       and ToolResultMessage from previous iterations.</li>
 * </ul>
 * The tool loop executes tools and builds history. This class only converts message formats
 * and calls the OpenAI Chat Completions API.
 * <p>
 * <h2>Key Responsibilities</h2>
 * <ul>
 *   <li>Convert Embabel messages to OpenAI SDK message params</li>
 *   <li>Convert Embabel tools to OpenAI function definitions</li>
 *   <li>Call OpenAI Chat Completions API: {@code client.chat().completions().create(params)}</li>
 *   <li>Convert response back to Embabel format</li>
 * </ul>
 */
public class OpenAiLlmMessageSender implements LlmMessageSender {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiLlmMessageSender.class);

    private final OpenAIClient client;
    private final ChatModel model;

    public OpenAiLlmMessageSender(OpenAIClient client) {
        this(client, ChatModel.GPT_4O_MINI);
    }

    public OpenAiLlmMessageSender(OpenAIClient client, ChatModel model) {
        this.client = client;
        this.model = model;
    }

    @NotNull
    @Override
    public LlmMessageResponse call(
            @NotNull List<? extends Message> messages,
            @NotNull List<? extends Tool> tools) {

        // Build request with messages
        var paramsBuilder = ChatCompletionCreateParams.builder()
            .model(model)
            .maxCompletionTokens(4096);

        // Add messages using convenience methods
        for (var msg : messages) {
            addMessageToParams(paramsBuilder, msg);
        }

        // Add tools as function definitions.
        // Note: Using empty parameters schema since our demo tools have no input parameters.
        // For tools with parameters, extract schema from tool.getDefinition().getInputSchema()
        // and populate the "properties" and "required" fields accordingly.
        for (var tool : tools) {
            var def = tool.getDefinition();
            paramsBuilder.addTool(ChatCompletionFunctionTool.builder()
                .function(FunctionDefinition.builder()
                    .name(def.getName())
                    .description(def.getDescription())
                    .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of()))
                        .putAdditionalProperty("required", JsonValue.from(List.of()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                    .build())
                .build());
        }

        var params = paramsBuilder.build();

        logger.debug("Calling OpenAI with {} messages and {} tools",
            messages.size(), tools.size());

        // Call OpenAI API
        ChatCompletion completion = client.chat().completions().create(params);

        // Get the first choice's message
        var choice = completion.choices().getFirst();
        var responseMessage = choice.message();

        logger.debug("OpenAI response: hasToolCalls={}, contentLength={}",
                responseMessage.toolCalls().isPresent(),
            responseMessage.content().map(String::length).orElse(0));

        // Convert response to Embabel format
        var embabelMessage = convertResponseMessage(responseMessage);
        var usage = convertUsage(completion);

        return new LlmMessageResponse(
            embabelMessage,
            responseMessage.content().orElse(""),
            usage
        );
    }

    /**
     * Add an Embabel message to the OpenAI params builder.
     * <p>
     * Message types by iteration:
     * <ul>
     *   <li><b>Iteration 1:</b> SystemMessage, UserMessage only</li>
     *   <li><b>Iteration 2+:</b> Also includes AssistantMessageWithToolCalls (LLM's tool requests)
     *       and ToolResultMessage (tool execution results from DefaultToolLoop)</li>
     * </ul>
     */
    private void addMessageToParams(ChatCompletionCreateParams.Builder builder, Message msg) {
        // Iteration 1: Initial messages
        if (msg instanceof com.embabel.chat.SystemMessage sysMsg) {
            builder.addDeveloperMessage(sysMsg.getContent());
        } else if (msg instanceof com.embabel.chat.UserMessage userMsg) {
            builder.addUserMessage(userMsg.getContent());
        } else if (msg instanceof AssistantMessageWithToolCalls toolCallMsg) {
            // Iteration 2+: LLM's previous response that requested tool calls.
            // Tool loop adds this to history after executing tools.
            var assistantBuilder = ChatCompletionAssistantMessageParam.builder()
                .content(toolCallMsg.getContent());
            for (var tc : toolCallMsg.getToolCalls()) {
                assistantBuilder.addToolCall(ChatCompletionMessageToolCall.ofFunction(
                    ChatCompletionMessageFunctionToolCall.builder()
                        .id(tc.getId())
                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(tc.getName())
                            .arguments(tc.getArguments())
                            .build())
                        .build()));
            }
            builder.addMessage(assistantBuilder.build());
        } else if (msg instanceof ToolResultMessage toolResult) {
            // Iteration 2+: Tool execution results from tool loop.
            builder.addMessage(ChatCompletionToolMessageParam.builder()
                .toolCallId(toolResult.getToolCallId())
                .content(toolResult.getContent())
                .build());
        } else if (msg instanceof AssistantMessage assistantMsg) {
            builder.addAssistantMessage(assistantMsg.getContent());
        }
    }

    /**
     * Convert OpenAI ChatCompletionMessage to Embabel Message.
     */
    private Message convertResponseMessage(ChatCompletionMessage message) {
        var toolCallsOpt = message.toolCalls();

        if (toolCallsOpt.isPresent() && !toolCallsOpt.get().isEmpty()) {
            var embabelToolCalls = toolCallsOpt.get().stream()
                .map(tc -> {
                    var fn = tc.asFunction();
                    return new ToolCall(
                        fn.id(),
                        fn.function().name(),
                        fn.function().arguments()
                    );
                })
                .toList();

            return new AssistantMessageWithToolCalls(
                message.content().orElse(""),
                embabelToolCalls
            );
        } else {
            return new AssistantMessage(message.content().orElse(""));
        }
    }

    /**
     * Convert OpenAI ChatCompletion usage to Embabel Usage.
     */
    private Usage convertUsage(ChatCompletion completion) {
        var usage = completion.usage();
        if (usage.isEmpty()) {
            return null;
        }
        var u = usage.get();
        return new Usage(
            (int) u.promptTokens(),
            (int) u.completionTokens(),
            u
        );
    }
}
