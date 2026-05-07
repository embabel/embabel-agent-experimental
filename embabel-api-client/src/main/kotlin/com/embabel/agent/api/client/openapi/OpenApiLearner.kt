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
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Schema
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
class OpenApiLearner(
    private val clientProperties: OpenApiClientProperties = OpenApiClientProperties(),
) : ApiLearner {

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
            factory = { credentials -> buildTool(source, openApi, credentials, clientProperties = clientProperties) },
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
         *
         * Returns a fully-resolved [OpenAPI] — internal `$ref` nodes are
         * inlined into their referenced schema bodies. This is the form the
         * runtime tool path ([OpenApiOperationTool]) expects: it walks
         * `operation.requestBody.content.schema.properties` directly and
         * needs the full inline structure to build [Tool.InputSchema] and
         * the JSON-string output schema.
         *
         * For ref-preserving consumers (the IR, the data dictionary), use
         * [parseSpecPreservingRefs] — that variant keeps `$ref` nodes intact
         * so downstream can emit named-type references instead of inlining.
         */
        fun parseSpec(source: String, rawContent: String? = null): OpenAPI =
            parseInternal(source, rawContent, resolveFully = true)

        /**
         * Parse an OpenAPI spec while preserving internal `$ref` nodes.
         *
         * The Swagger parser leaves `$ref: "#/components/schemas/Foo"` as a
         * bare node whose `.type` and `.properties` are null, with the
         * original `$ref` string still on the schema. Used by
         * [LearnedApiSpec.OpenApi.toModel] so the IR can emit
         * [com.embabel.agent.api.client.model.ApiSchema.Ref] entries that
         * survive into the [com.embabel.agent.core.DataDictionary] as
         * cross-type links.
         *
         * `components.schemas` is preserved in the resulting [OpenAPI]
         * either way; this variant additionally preserves the ref nodes
         * inside operation request/response bodies and inside other
         * components' property schemas.
         */
        fun parseSpecPreservingRefs(source: String, rawContent: String? = null): OpenAPI =
            parseInternal(source, rawContent, resolveFully = false)

        private fun parseInternal(
            source: String,
            rawContent: String?,
            resolveFully: Boolean,
        ): OpenAPI {
            val parseOptions = ParseOptions().apply {
                isResolve = true
                isResolveFully = resolveFully
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
            clientProperties: OpenApiClientProperties = OpenApiClientProperties(),
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

            val restClient = buildRestClient(openApi, credentials, clientProperties)
            // Use the model's resource grouping (respects tag filtering)
            val includedNames = filtered.allOperations.map { it.name }.toSet()
            // Pre-computing the curated name set lets `materializeTools`
            // scope the per-source named-types JSON to types reachable
            // from these operations only — uncurated ops' types get
            // dropped from the registry, which for big specs (Sheets,
            // Docs, GitHub) cuts the JSON the LLM has to load by ~95%.
            val allTools = materializeTools(openApi, filtered.baseUrl, restClient, includedNames)
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
            clientProperties: OpenApiClientProperties,
        ): RestClient {
            val httpClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(clientProperties.connectTimeout)
                .build()
            val requestFactory = org.springframework.http.client.JdkClientHttpRequestFactory(httpClient).apply {
                setReadTimeout(clientProperties.readTimeout)
            }
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
            curatedOperationNames: Set<String>,
        ): Map<String, Tool> {
            val tools = mutableMapOf<String, Tool>()
            // Snapshot the named-types registry once so each operation's
            // `$ref` deref / output-schema serialization sees the same
            // map. `components.schemas` is preserved by both
            // `parseSpec` (resolved) and `parseSpecPreservingRefs` (refs
            // intact), so this works in either parse mode.
            @Suppress("UNCHECKED_CAST")
            val componentsSchemas: Map<String, Schema<*>> =
                (openApi.components?.schemas as? Map<String, Schema<*>>) ?: emptyMap()

            // First pass: build the curated Operation set. We need to
            // know the Swagger-level operation objects (not just names)
            // to feed the named-types reachability walk. Done as a
            // single pre-pass to avoid duplicating the path-walk logic.
            val curatedOps = mutableListOf<Operation>()
            val mergedByName = mutableMapOf<String, OperationLocation>()
            openApi.paths?.forEach { (path, pathItem) ->
                pathItem.readOperationsMap()?.forEach { (method, operation) ->
                    val mergedOperation = operation.apply {
                        val pathParams = pathItem.parameters ?: emptyList()
                        val opParams = parameters ?: emptyList()
                        val existingNames = opParams.map { it.name }.toSet()
                        parameters = opParams + pathParams.filter { it.name !in existingNames }
                    }
                    val name = OpenApiOperationTool.operationName(method, path, mergedOperation)
                    mergedByName[name] = OperationLocation(path, method, mergedOperation)
                    if (name in curatedOperationNames) curatedOps.add(mergedOperation)
                }
            }

            // Compute the per-source named-types JSON ONCE, scoped to
            // types reachable from curated ops, and share the resulting
            // String by reference across every sibling tool. Each
            // `Tool.Definition.metadata[NAMED_TYPES_KEY]` then points at
            // the same trimmed JSON instead of one independent copy.
            val namedTypesJson = OpenApiOperationTool.namedTypesAsJson(componentsSchemas, curatedOps)

            for ((name, location) in mergedByName) {
                val tool = OpenApiOperationTool(
                    baseUrl = baseUrl,
                    path = location.path,
                    httpMethod = location.method,
                    operation = location.operation,
                    restClient = restClient,
                    componentsSchemas = componentsSchemas,
                    namedTypesJson = namedTypesJson,
                )
                tools[tool.definition.name] = tool
            }

            return tools
        }

        private data class OperationLocation(
            val path: String,
            val method: PathItem.HttpMethod,
            val operation: Operation,
        )

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
