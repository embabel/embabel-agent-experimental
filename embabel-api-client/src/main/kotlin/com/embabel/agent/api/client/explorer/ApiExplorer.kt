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

import com.embabel.agent.api.client.LearnedApi
import com.embabel.agent.api.client.graphql.GraphQlLearner
import com.embabel.agent.api.client.openapi.OpenApiLearner
import com.embabel.agent.api.common.Ai
import org.slf4j.LoggerFactory

/**
 * What kind of API was discovered at a URL.
 */
enum class ApiType {
    OPENAPI,
    GRAPHQL,
    UNKNOWN,
}

/**
 * The result of API exploration: the type of API found and the
 * URL to use for learning it.
 */
data class DiscoveredApi(
    val type: ApiType,
    val specUrl: String,
    val description: String,
)

/**
 * Agentic API explorer that probes a URL to discover what kind of API it exposes.
 *
 * Uses the [Ai] interface with an [HttpProbeTool] to let an LLM
 * autonomously probe endpoints, follow links, and determine whether the
 * target is an OpenAPI, GraphQL, or other type of API.
 *
 * Once discovered, delegates to the appropriate [com.embabel.agent.api.client.ApiLearner]
 * to produce a [LearnedApi].
 *
 * Example:
 * ```kotlin
 * val explorer = ApiExplorer(ai)
 * val learned = explorer.explore("https://rickandmortyapi.com")
 * // learned is a LearnedApi ready to create tools from
 * ```
 */
class ApiExplorer(
    private val ai: Ai,
    private val httpProbeTool: HttpProbeTool = HttpProbeTool(),
) {

    /**
     * Explore a URL to discover and learn its API.
     *
     * @param url the base URL to explore (e.g., "https://rickandmortyapi.com")
     * @return a [LearnedApi] ready to create tools from
     * @throws IllegalStateException if no API could be discovered
     */
    fun explore(url: String): LearnedApi {
        val discovered = discover(url)
        return learn(discovered)
    }

    /**
     * Discover what kind of API is at the given URL without learning it.
     *
     * @param url the base URL to explore
     * @return a [DiscoveredApi] describing what was found
     */
    fun discover(url: String): DiscoveredApi {
        // Short-circuit: if URL looks like a spec file, skip the LLM probe entirely
        val quickResult = detectSpecFromUrl(url)
        if (quickResult != null) {
            logger.info("Fast-detected {} spec from URL pattern: {}", quickResult.type, url)
            return quickResult
        }

        val prompt = buildPrompt(url)

        val result = ai.withDefaultLlm()
            .withTool(httpProbeTool)
            .creating(DiscoveredApi::class.java)
            .fromPrompt(prompt)

        logger.info("Discovered {} API at {}: {}", result.type, result.specUrl, result.description)
        return result
    }

    /**
     * Learn a previously discovered API.
     */
    fun learn(discovered: DiscoveredApi): LearnedApi {
        return when (discovered.type) {
            ApiType.OPENAPI -> OpenApiLearner().learn(discovered.specUrl)
            ApiType.GRAPHQL -> GraphQlLearner().learn(discovered.specUrl)
            ApiType.UNKNOWN -> throw IllegalStateException(
                "Cannot learn unknown API type at ${discovered.specUrl}: ${discovered.description}"
            )
        }
    }

    /**
     * Fast-detect if a URL is already a spec file based on URL patterns.
     * Avoids an entire LLM probe loop for obvious cases.
     */
    private fun detectSpecFromUrl(url: String): DiscoveredApi? {
        val lower = url.lowercase()
        val isOpenApi = lower.endsWith(".json") && (lower.contains("openapi") || lower.contains("swagger"))
            || lower.endsWith(".yaml") && (lower.contains("openapi") || lower.contains("swagger") || lower.contains("apis-guru"))
            || lower.endsWith(".yml") && (lower.contains("openapi") || lower.contains("swagger") || lower.contains("apis-guru"))
            || lower.contains("swagger.json") || lower.contains("swagger.yaml")
            || lower.contains("openapi.json") || lower.contains("openapi.yaml") || lower.contains("openapi.yml")
            || lower.contains("api-docs")
        if (isOpenApi) {
            return DiscoveredApi(ApiType.OPENAPI, url, "OpenAPI spec at $url")
        }

        val isGraphQl = lower.endsWith("/graphql") || lower.endsWith("/gql")
        if (isGraphQl) {
            return DiscoveredApi(ApiType.GRAPHQL, url, "GraphQL endpoint at $url")
        }

        return null
    }

    companion object {

        private val logger = LoggerFactory.getLogger(ApiExplorer::class.java)

        internal fun buildPrompt(url: String) = """
            You are an API discovery agent. Your job is to figure out what kind of API
            is available at or near the given URL, and find the right endpoint or spec URL
            to learn it from.

            Target URL: $url

            Use the http_probe tool to explore. Try these strategies in order:

            1. **Check for GraphQL**: POST to likely GraphQL endpoints ($url/graphql, $url/gql, $url/api, or $url itself)
               with body: {"query":"{ __schema { queryType { name } } }"}
               If you get a response with "data":{"__schema":...}, it's GraphQL.

            2. **Check for OpenAPI/Swagger specs**: GET these common paths:
               - $url/openapi.json, $url/openapi.yaml
               - $url/swagger.json, $url/swagger.yaml
               - $url/v3/api-docs, $url/v2/api-docs
               - $url/api-docs, $url/docs/openapi.json
               - $url/api/openapi.json
               If you get JSON/YAML with "openapi" or "swagger" fields, it's OpenAPI.

            3. **Check the base URL**: GET $url and look for:
               - Links to API documentation or specs
               - GraphQL playground indicators
               - OpenAPI/Swagger UI indicators
               - API description in the response

            4. **Try the URL directly as a spec**: GET $url itself — it might be a direct link to a spec file.

            Report what you find as JSON with this exact structure:
            {
              "type": "OPENAPI" or "GRAPHQL" or "UNKNOWN",
              "specUrl": "the URL to use for learning the API",
              "description": "brief description of what you found"
            }

            For GraphQL, specUrl should be the GraphQL endpoint (the URL you POST queries to).
            For OpenAPI, specUrl should be the URL of the spec file (JSON or YAML).

            Be thorough but efficient. Stop as soon as you identify the API type.
        """.trimIndent()
    }
}
