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
package com.embabel.agent.api.client.openapi

import com.embabel.agent.api.client.*
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.agent.core.AgentProcess
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient

/**
 * [ApiLearner] that learns APIs from OpenAPI specs.
 *
 * Supports both OpenAPI 3.x and Swagger 2.0 specs (auto-converted).
 *
 * Example:
 * ```kotlin
 * val learner = OpenApiLearner()
 * val learned = learner.inspect("https://petstore3.swagger.io/api/v3/openapi.json")
 *
 * // Check what auth is needed
 * println(learned.authRequirements) // [AuthRequirement.ApiKey(name="api_key", location=HEADER)]
 *
 * // Provide credentials and get a tool
 * val petstore = learned.create(ApiCredentials.ApiKey("my-key"))
 * // petstore is a ProgressiveTool — hand it directly to an agent
 * ```
 */
class OpenApiLearner : ApiLearner {

    override fun learn(source: String): LearnedApi {
        val openApi = parseSpec(source)

        val name = deriveApiName(openApi)
        val description = deriveApiDescription(openApi, source)
        val authRequirements = extractAuthRequirements(openApi)

        return LearnedApi(
            name = name,
            description = description,
            authRequirements = authRequirements,
            factory = { credentials -> buildTool(source, openApi, credentials) },
        )
    }

    private fun buildTool(
        source: String,
        openApi: OpenAPI,
        credentials: ApiCredentials,
    ): ProgressiveTool {
        val baseUrl = resolveBaseUrl(openApi, source)
        val restClient = buildRestClient(openApi, credentials)
        val allTools = materializeTools(openApi, baseUrl, restClient)
        val toolsByTag = groupByTag(openApi, allTools)

        val apiName = deriveApiName(openApi)
        val apiDescription = deriveApiDescription(openApi, source)

        logger.info(
            "Learned API '{}' from {}: {} operations in {} tags",
            apiName, source, allTools.size, toolsByTag.size,
        )

        val delegate = if (toolsByTag.size == 1) {
            UnfoldingTool.of(
                name = apiName,
                description = apiDescription,
                innerTools = allTools.values.toList(),
            )
        } else {
            UnfoldingTool.byCategory(
                name = apiName,
                description = apiDescription,
                toolsByCategory = toolsByTag.mapValues { it.value.toList() },
                removeOnInvoke = false,
            )
        }

        return delegate
    }

    private fun buildRestClient(
        openApi: OpenAPI,
        credentials: ApiCredentials,
    ): RestClient {
        val builder = RestClient.builder()
        applyCredentials(builder, openApi, credentials)
        return builder.build()
    }

    private fun applyCredentials(
        builder: RestClient.Builder,
        openApi: OpenAPI,
        credentials: ApiCredentials,
    ) {
        when (credentials) {
            is ApiCredentials.None -> {}

            is ApiCredentials.Token -> {
                builder.defaultHeader("Authorization", "Bearer ${credentials.token}")
            }

            is ApiCredentials.ApiKey -> {
                val apiKeyScheme = openApi.components?.securitySchemes?.values
                    ?.filterIsInstance<SecurityScheme>()
                    ?.find { it.type == SecurityScheme.Type.APIKEY }

                if (apiKeyScheme != null) {
                    when (apiKeyScheme.`in`) {
                        SecurityScheme.In.HEADER ->
                            builder.defaultHeader(apiKeyScheme.name, credentials.value)
                        SecurityScheme.In.QUERY ->
                            builder.requestInterceptor { request, body, execution ->
                                val uri = request.uri
                                val separator = if (uri.query != null) "&" else "?"
                                val newUri = java.net.URI("${uri}${separator}${apiKeyScheme.name}=${credentials.value}")
                                execution.execute(
                                    object : org.springframework.http.HttpRequest by request {
                                        override fun getURI() = newUri
                                    },
                                    body,
                                )
                            }
                        SecurityScheme.In.COOKIE ->
                            builder.defaultHeader("Cookie", "${apiKeyScheme.name}=${credentials.value}")
                        else ->
                            builder.defaultHeader(apiKeyScheme.name, credentials.value)
                    }
                } else {
                    builder.defaultHeader("Authorization", credentials.value)
                }
            }

            is ApiCredentials.Multiple -> {
                credentials.credentials.forEach { applyCredentials(builder, openApi, it) }
            }
        }
    }

    companion object {

        private val logger = LoggerFactory.getLogger(OpenApiLearner::class.java)

        internal fun parseSpec(source: String): OpenAPI {
            val parseOptions = ParseOptions().apply {
                isResolve = true
            }
            val result = OpenAPIParser().readLocation(source, null, parseOptions)

            if (result.openAPI == null) {
                val errors = result.messages?.joinToString("; ") ?: "Unknown error"
                throw IllegalArgumentException("Failed to parse OpenAPI spec from $source: $errors")
            }

            return result.openAPI
        }

        internal fun extractAuthRequirements(openApi: OpenAPI): List<AuthRequirement> {
            val schemes = openApi.components?.securitySchemes ?: return listOf(AuthRequirement.None)

            val requirements = schemes.values.mapNotNull { scheme ->
                when (scheme.type) {
                    SecurityScheme.Type.APIKEY -> AuthRequirement.ApiKey(
                        name = scheme.name,
                        location = when (scheme.`in`) {
                            SecurityScheme.In.HEADER -> ApiKeyLocation.HEADER
                            SecurityScheme.In.QUERY -> ApiKeyLocation.QUERY
                            SecurityScheme.In.COOKIE -> ApiKeyLocation.COOKIE
                            else -> ApiKeyLocation.HEADER
                        },
                    )

                    SecurityScheme.Type.HTTP -> AuthRequirement.Bearer(
                        scheme = scheme.scheme ?: "bearer",
                    )

                    SecurityScheme.Type.OAUTH2 -> {
                        val scopes = scheme.flows?.let { flows ->
                            (flows.authorizationCode?.scopes?.keys
                                ?: flows.clientCredentials?.scopes?.keys
                                ?: flows.implicit?.scopes?.keys
                                ?: emptySet())
                                .toList()
                        } ?: emptyList()
                        AuthRequirement.OAuth2(scopes = scopes)
                    }

                    else -> null
                }
            }

            return requirements.ifEmpty { listOf(AuthRequirement.None) }
        }

        internal fun resolveBaseUrl(openApi: OpenAPI, source: String): String {
            val serverUrl = openApi.servers?.firstOrNull()?.url

            if (serverUrl != null) {
                if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                    return serverUrl
                }
                val sourceUri = java.net.URI(source)
                val baseAuthority = "${sourceUri.scheme}://${sourceUri.authority}"
                return baseAuthority + serverUrl
            }

            val sourceUri = java.net.URI(source)
            return "${sourceUri.scheme}://${sourceUri.authority}"
        }

        internal fun deriveApiName(openApi: OpenAPI): String {
            val title = openApi.info?.title ?: "api"
            return title
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
        }

        internal fun deriveApiDescription(openApi: OpenAPI, source: String): String {
            return openApi.info?.description
                ?: openApi.info?.title?.let { "API operations for $it" }
                ?: "API operations from $source"
        }

        private fun materializeTools(
            openApi: OpenAPI,
            baseUrl: String,
            restClient: RestClient,
        ): Map<String, Tool> {
            val tools = mutableMapOf<String, Tool>()

            openApi.paths?.forEach { (path, pathItem) ->
                pathItem.readOperationsMap()?.forEach { (method, operation) ->
                    val mergedOperation = operation.apply {
                        val pathParams = pathItem.parameters ?: emptyList()
                        val opParams = parameters ?: emptyList()
                        val existingNames = opParams.map { it.name }.toSet()
                        parameters = opParams + pathParams.filter { it.name !in existingNames }
                    }

                    val tool = OpenApiOperationTool(
                        baseUrl = baseUrl,
                        path = path,
                        httpMethod = method,
                        operation = mergedOperation,
                        restClient = restClient,
                    )
                    tools[tool.definition.name] = tool
                }
            }

            return tools
        }

        private fun groupByTag(
            openApi: OpenAPI,
            tools: Map<String, Tool>,
        ): Map<String, List<Tool>> {
            val tagMap = mutableMapOf<String, MutableList<Tool>>()

            openApi.paths?.forEach { (path, pathItem) ->
                pathItem.readOperationsMap()?.forEach { (method, operation) ->
                    val toolName = OpenApiOperationTool.operationName(method, path, operation)
                    val tool = tools[toolName] ?: return@forEach

                    val tags = operation.tags?.takeIf { it.isNotEmpty() } ?: listOf("default")
                    tags.forEach { tag ->
                        tagMap.getOrPut(tag) { mutableListOf() }.add(tool)
                    }
                }
            }

            return tagMap
        }
    }
}
