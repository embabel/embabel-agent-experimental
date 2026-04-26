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
import com.embabel.agent.api.client.model.ApiModel
import com.embabel.agent.api.client.model.canonicalOpId
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import com.embabel.agent.api.tool.progressive.UnfoldingTool
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
        val rawSpec = fetchRawSpec(source)
        val openApi = parseSpec(source, rawSpec)

        val name = deriveApiName(openApi)
        val description = deriveApiDescription(openApi, source)
        val authRequirements = extractAuthRequirements(openApi)
        val spec = LearnedApiSpec.OpenApi(source = source, rawSpec = rawSpec)

        return LearnedApi(
            name = name,
            description = description,
            authRequirements = authRequirements,
            spec = spec,
            factory = { credentials -> buildTool(source, openApi, credentials) },
        )
    }

    companion object {

        private val logger = LoggerFactory.getLogger(OpenApiLearner::class.java)

        /**
         * Fetch the raw spec content from a URL or file path.
         */
        internal fun fetchRawSpec(source: String): String {
            val uri = java.net.URI(source)
            if (uri.scheme == "file" || uri.scheme == null) {
                // Local file — read directly
                val path = if (uri.scheme == "file") java.nio.file.Path.of(uri) else java.nio.file.Path.of(source)
                return java.nio.file.Files.readString(path)
            }
            val httpClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build()
            val request = java.net.http.HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build()
            val response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalArgumentException("Failed to fetch spec from $source: HTTP ${response.statusCode()}")
            }
            return response.body()
        }

        /**
         * Parse an OpenAPI spec from raw content. If [rawContent] is provided,
         * parses from the string directly; otherwise fetches from [source].
         */
        fun parseSpec(source: String, rawContent: String? = null): OpenAPI {
            val parseOptions = ParseOptions().apply {
                // `isResolve` alone resolves only EXTERNAL $refs. Internal
                // component refs (`$ref: "#/components/schemas/Issue"`) stay
                // as bare ref nodes whose `.type` and `.properties` are null,
                // so downstream type-extraction sees `{type: "object"}` with
                // no fields and emits `Record<string, unknown>` in the
                // generated TypeScript surface — wiping out the type info
                // we just learned. `isResolveFully` inlines internal refs so
                // schemas carry their actual structure. Cost: response
                // schemas get larger (no shared named types). Acceptable
                // trade for now; named-type extraction is a separate fix.
                isResolve = true
                isResolveFully = true
                isResolveCombinators = true
            }
            val result = if (rawContent != null) {
                OpenAPIParser().readContents(rawContent, null, parseOptions)
            } else {
                OpenAPIParser().readLocation(source, null, parseOptions)
            }

            if (result.openAPI == null) {
                val errors = result.messages?.joinToString("; ") ?: "Unknown error"
                throw IllegalArgumentException("Failed to parse OpenAPI spec from $source: $errors")
            }

            return result.openAPI
        }

        /**
         * Build an [ApiModel] from a parsed OpenAPI spec.
         *
         * This is the canonical intermediate representation that preserves
         * full schema information. Use it for interface generation, client
         * codegen, or any downstream consumer that needs more than the lossy
         * tool projection.
         */
        fun buildModel(source: String, openApi: OpenAPI): ApiModel =
            OpenApiModelBuilder.build(source, openApi)

        /**
         * Build a [ProgressiveTool] from a parsed OpenAPI spec.
         * Called by both the learner and [LearnedApiSpec.OpenApi.toFactory].
         *
         * Internally constructs an [ApiModel] and uses it for grouping and
         * optional tag filtering, then materializes [OpenApiOperationTool]s
         * for HTTP execution.
         *
         * @param tags optional set of OpenAPI tag names to include. When non-null,
         *   only operations tagged with one of these values are exposed as tools.
         *   Tag matching is case-insensitive.
         */
        fun buildTool(
            source: String,
            openApi: OpenAPI,
            credentials: ApiCredentials,
            tags: Set<String>? = null,
            operationIds: Set<String>? = null,
            nameOverride: String? = null,
        ): ProgressiveTool {
            val model = buildModel(source, openApi)
            val tagFiltered = if (tags != null) model.filterByTags(tags) else model
            // operationIds, when set, narrows further. The two filters
            // compose: tags narrow to a coarse subset, operationIds picks
            // exact ops from within. Specifying only operationIds (no
            // tags) lets the user pin a curated micro-surface from the
            // entire spec without the tag intermediate.
            val filtered = if (operationIds != null) tagFiltered.filterByOperationIds(operationIds) else tagFiltered
            if (operationIds != null) {
                // Surface op-ids that didn't match anything — silent drops
                // turn a typo in apis.yml into "tool quietly missing" hours
                // later. Compare against the post-tag-filter model so an
                // op-id that exists but was excluded by a tag also warns.
                val present = tagFiltered.allOperations.map { it.name.canonicalOpId() }.toSet()
                val missing = operationIds.filter { it.canonicalOpId() !in present }
                if (missing.isNotEmpty()) {
                    logger.warn(
                        "Requested operationIds not found in spec '{}': {}",
                        filtered.name, missing,
                    )
                }
            }

            val restClient = buildRestClient(openApi, credentials)
            val allTools = materializeTools(openApi, filtered.baseUrl, restClient)

            // Use the model's resource grouping (respects tag filtering)
            val includedNames = filtered.allOperations.map { it.name }.toSet()
            val includedTools = allTools.filterKeys { it in includedNames }

            val toolsByResource = filtered.resources.associate { resource ->
                resource.name to resource.operations.mapNotNull { op ->
                    includedTools[op.name]
                }
            }.filterValues { it.isNotEmpty() }

            logger.info(
                "Learned API '{}' from {}: {} operations in {} resources{}",
                filtered.name, source, includedTools.size, toolsByResource.size,
                if (tags != null) " (filtered from ${allTools.size} total)" else "",
            )

            // The pack/workspace can override the spec-derived name so the
            // gateway namespace matches what the pack author declared in
            // apis.yml (and what their prompt examples reference). Without
            // this, a pack with `name: gh` lands on `gateway.<spec-title>`,
            // the model follows the examples to `gateway.gh.*`, and every
            // call errors out — see issue trail in the assistant repo.
            val toolName = nameOverride?.takeIf { it.isNotBlank() } ?: filtered.name
            return if (toolsByResource.size == 1) {
                UnfoldingTool.of(
                    name = toolName,
                    description = filtered.description,
                    innerTools = toolsByResource.values.single(),
                )
            } else {
                UnfoldingTool.byCategory(
                    name = toolName,
                    description = filtered.description,
                    toolsByCategory = toolsByResource,
                    removeOnInvoke = false,
                )
            }
        }

        private fun buildRestClient(
            openApi: OpenAPI,
            credentials: ApiCredentials,
        ): RestClient {
            val httpClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build()
            val requestFactory = org.springframework.http.client.JdkClientHttpRequestFactory(httpClient)
            val builder = RestClient.builder().requestFactory(requestFactory)
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

                is ApiCredentials.CustomHeaders -> {
                    credentials.headers.forEach { (name, value) ->
                        builder.defaultHeader(name, value)
                    }
                }

                is ApiCredentials.Multiple -> {
                    credentials.credentials.forEach { applyCredentials(builder, openApi, it) }
                }

                is ApiCredentials.OAuth2 -> {
                    builder.defaultHeader("Authorization", "Bearer ${credentials.accessToken}")
                }
            }
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
            // Check top-level servers first (ignore auto-generated "/" placeholder)
            val serverUrl = openApi.servers?.firstOrNull()?.url
                ?.takeIf { it != "/" }

            if (serverUrl != null) {
                if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                    return serverUrl
                }
            }

            // Check path-level servers (some specs define servers per-path, not globally)
            val pathServerUrl = openApi.paths?.values?.firstOrNull()
                ?.servers?.firstOrNull()?.url
            if (pathServerUrl != null &&
                (pathServerUrl.startsWith("http://") || pathServerUrl.startsWith("https://"))
            ) {
                return pathServerUrl
            }

            // Relative server URL — resolve against source
            if (serverUrl != null) {
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
