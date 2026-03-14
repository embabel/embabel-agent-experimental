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
package com.embabel.agent.api.client.explorer

import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * A [Tool] that allows an AI agent to make HTTP requests for API exploration.
 *
 * Supports GET and POST with optional headers and body. Responses are
 * truncated to avoid overwhelming the LLM context.
 */
class HttpProbeTool(
    private val maxResponseLength: Int = 4000,
) : Tool {

    private val objectMapper = jacksonObjectMapper()
    private val restClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build()
            )
        )
        .build()

    override val definition: Tool.Definition = Tool.Definition(
        name = "http_probe",
        description = "Make an HTTP request to explore an API endpoint. " +
            "Use GET to fetch pages/specs, POST for GraphQL introspection. " +
            "Returns status code, content-type, and truncated response body.",
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter(
                name = "url",
                type = Tool.ParameterType.STRING,
                description = "The full URL to request",
                required = true,
            ),
            Tool.Parameter(
                name = "method",
                type = Tool.ParameterType.STRING,
                description = "HTTP method: GET or POST",
                required = false,
                enumValues = listOf("GET", "POST"),
            ),
            Tool.Parameter(
                name = "body",
                type = Tool.ParameterType.STRING,
                description = "Request body (for POST). Use JSON string for GraphQL introspection.",
                required = false,
            ),
            Tool.Parameter(
                name = "content_type",
                type = Tool.ParameterType.STRING,
                description = "Content-Type header (default: application/json for POST)",
                required = false,
            ),
        ),
    )

    override fun call(input: String): Tool.Result {
        return try {
            @Suppress("UNCHECKED_CAST")
            val params = objectMapper.readValue(input, Map::class.java) as Map<String, Any?>

            val url = params["url"] as? String ?: return Tool.Result.error("url is required")
            val method = (params["method"] as? String)?.uppercase() ?: "GET"
            val body = params["body"] as? String
            val contentType = params["content_type"] as? String

            logger.debug("HTTP probe: {} {}", method, url)

            val response = when (method) {
                "GET" -> executeGet(url)
                "POST" -> executePost(url, body, contentType)
                else -> return Tool.Result.error("Unsupported method: $method. Use GET or POST.")
            }

            Tool.Result.text(response)
        } catch (e: RestClientResponseException) {
            val truncatedBody = e.responseBodyAsString.take(maxResponseLength)
            val response = "HTTP ${e.statusCode.value()}\nContent-Type: ${e.responseHeaders?.contentType ?: "unknown"}\n\n$truncatedBody"
            Tool.Result.text(response)
        } catch (e: Exception) {
            logger.debug("HTTP probe error: {}", e.message)
            Tool.Result.error("Request failed: ${e.message}")
        }
    }

    private fun executeGet(url: String): String {
        val response = restClient.get()
            .uri(url)
            .exchange { _, clientResponse ->
                val status = clientResponse.statusCode.value()
                val contentType = clientResponse.headers.contentType?.toString() ?: "unknown"
                val body = clientResponse.body.readAllBytes().decodeToString().take(maxResponseLength)
                "HTTP $status\nContent-Type: $contentType\n\n$body"
            }
        return response ?: "No response"
    }

    private fun executePost(url: String, body: String?, contentType: String?): String {
        val mediaType = if (contentType != null) MediaType.parseMediaType(contentType) else MediaType.APPLICATION_JSON
        val spec = restClient.post()
            .uri(url)
            .contentType(mediaType)
        if (body != null) {
            spec.body(body)
        }
        val response = spec.exchange { _, clientResponse ->
            val status = clientResponse.statusCode.value()
            val respContentType = clientResponse.headers.contentType?.toString() ?: "unknown"
            val respBody = clientResponse.body.readAllBytes().decodeToString().take(maxResponseLength)
            "HTTP $status\nContent-Type: $respContentType\n\n$respBody"
        }
        return response ?: "No response"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HttpProbeTool::class.java)
    }
}
