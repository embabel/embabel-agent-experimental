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

import com.embabel.agent.codex.auth.CodexAccessTokenProvider
import com.embabel.agent.codex.auth.CodexCredentials
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.web.client.HttpClientErrorException

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ContentPart(
    val type: String? = null,
    val text: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OutputItem(
    val type: String? = null,
    val text: String? = null,
    val content: List<ContentPart>? = null,
    val name: String? = null,
    val arguments: String? = null,
    @JsonProperty("call_id") val callId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ResponsesApiResponse(
    val output: List<OutputItem>? = null,
    val error: ResponseError? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ResponseError(
    val message: String? = null,
    val code: String? = null,
)

class CodexResponsesClient(
    private val tokenProvider: CodexAccessTokenProvider,
    private val credentials: CodexCredentials,
    private val transport: CodexHttpTransport = RestClientCodexHttpTransport(),
) {

    private val objectMapper = jacksonObjectMapper()

    fun create(
        model: String,
        input: List<Map<String, Any>>,
        tools: List<Map<String, Any>> = emptyList(),
        instructions: String? = null,
        maxOutputTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
    ): CodexResponse {
        return try {
            doCreate(model, input, tools, instructions, maxOutputTokens, temperature, topP)
        } catch (e: HttpClientErrorException.Unauthorized) {
            tokenProvider.invalidateAndRefresh()
            doCreate(model, input, tools, instructions, maxOutputTokens, temperature, topP)
        }
    }

    private fun doCreate(
        model: String,
        input: List<Map<String, Any>>,
        tools: List<Map<String, Any>>,
        instructions: String?,
        maxOutputTokens: Int?,
        temperature: Double?,
        topP: Double?,
    ): CodexResponse {
        val requestBody = buildRequestBody(model, input, tools, instructions, maxOutputTokens, temperature, topP)
        val accessToken = tokenProvider.accessToken()
        val headers = CodexCloudflareHeaders.build(credentials) + mapOf(
            "Authorization" to "Bearer $accessToken",
            "Accept" to "text/event-stream",
        )
        val url = "${credentials.baseUrl.trimEnd('/')}/responses"
        val raw = transport.post(url, headers, requestBody)
        return parseRaw(raw)
    }

    private fun buildRequestBody(
        model: String,
        input: List<Map<String, Any>>,
        tools: List<Map<String, Any>>,
        instructions: String?,
        maxOutputTokens: Int?,
        temperature: Double?,
        topP: Double?,
    ): String {
        val map = mutableMapOf<String, Any>(
            "model" to model,
            "input" to input,
            "store" to false,
            "stream" to true,
        )
        if (!instructions.isNullOrBlank()) map["instructions"] = instructions
        if (tools.isNotEmpty()) map["tools"] = tools
        if (maxOutputTokens != null && maxOutputTokens > 0) map["max_output_tokens"] = maxOutputTokens
        if (temperature != null) map["temperature"] = temperature
        if (topP != null) map["top_p"] = topP
        return objectMapper.writeValueAsString(map)
    }

    internal fun parseRaw(raw: String): CodexResponse {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("{")) {
            parseJsonResponse(trimmed)
        } else {
            parseSseResponse(raw)
        }
    }

    private fun parseJsonResponse(raw: String): CodexResponse {
        val response: ResponsesApiResponse = objectMapper.readValue(raw)
        response.error?.let { throw responseFailure(it) }
        val outputItems = response.output ?: emptyList()
        val textParts = outputItems.flatMap { item -> extractText(item) }
        val functionCalls = outputItems.filter { it.type == "function_call" }
            .mapNotNull { item ->
                val name = item.name ?: return@mapNotNull null
                FunctionCall(name = name, arguments = item.arguments ?: "{}", callId = item.callId)
            }
        return CodexResponse(outputText = textParts.joinToString("\n"), functionCalls = functionCalls, raw = raw)
    }

    private fun parseSseResponse(raw: String): CodexResponse {
        val deltas = mutableListOf<String>()
        var completedText: String? = null
        var completed = false
        val functionCalls = mutableListOf<FunctionCall>()
        val normalized = raw.replace("\r\n", "\n")

        for (block in normalized.split("\n\n")) {
            val dataLines = block.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trimStart() }
                .toList()
            if (dataLines.isEmpty()) continue
            val data = dataLines.joinToString("\n")
            if (data == "[DONE]") continue
            val node = try {
                objectMapper.readTree(data)
            } catch (e: Exception) {
                throw CodexResponseException("Malformed Codex SSE event", e)
            }
            when (node.path("type").asText(null)) {
                "response.output_text.delta" -> {
                    val delta = node.path("delta").asText(null)
                    if (!delta.isNullOrEmpty()) deltas += delta
                }
                "response.completed" -> {
                    completed = true
                    val responseNode = node.path("response")
                    responseError(responseNode)?.let { throw responseFailure(it) }
                    completedText = extractCompletedText(responseNode)
                    functionCalls += extractFunctionCalls(responseNode.path("output"))
                }
                "response.failed" -> {
                    val error = responseError(node.path("response")) ?: responseError(node)
                    throw responseFailure(error ?: ResponseError(message = "Codex response failed"))
                }
            }
        }

        if (!completed) {
            throw CodexResponseException("Codex SSE stream ended without response.completed")
        }

        val outputText = completedText?.takeIf { it.isNotBlank() } ?: deltas.joinToString("")
        return CodexResponse(outputText = outputText, functionCalls = functionCalls, raw = raw)
    }

    private fun responseError(node: JsonNode): ResponseError? {
        val errorNode = node.path("error")
        if (errorNode.isMissingNode || errorNode.isNull) return null
        return ResponseError(
            message = errorNode.path("message").asText(null),
            code = errorNode.path("code").asText(null),
        )
    }

    private fun responseFailure(error: ResponseError): CodexResponseException {
        val detail = listOfNotNull(error.code, error.message)
            .filter { it.isNotBlank() }
            .joinToString(": ")
            .ifBlank { "unknown error" }
        return CodexResponseException("Codex response failed: $detail")
    }

    private fun extractCompletedText(responseNode: JsonNode): String {
        val texts = mutableListOf<String>()
        for (item in responseNode.path("output")) {
            if (item.path("type").asText() != "message") continue
            for (part in item.path("content")) {
                val type = part.path("type").asText()
                if (type == "output_text" || type == "text") {
                    part.path("text").asText(null)?.let { texts += it }
                }
            }
        }
        return texts.joinToString("\n")
    }

    private fun extractFunctionCalls(outputNode: JsonNode): List<FunctionCall> {
        val calls = mutableListOf<FunctionCall>()
        for (item in outputNode) {
            if (item.path("type").asText() != "function_call") continue
            val name = item.path("name").asText(null) ?: continue
            calls += FunctionCall(
                name = name,
                arguments = item.path("arguments").asText("{}"),
                callId = item.path("call_id").asText(null),
            )
        }
        return calls
    }

    private fun extractText(item: OutputItem): List<String> {
        val nested = item.content
            ?.filter { it.type == "output_text" || it.type == "text" }
            ?.mapNotNull { it.text }
            .orEmpty()
        if (nested.isNotEmpty()) return nested
        return listOfNotNull(item.text?.takeIf { item.type == "message" || item.type == "output_text" })
    }
}
