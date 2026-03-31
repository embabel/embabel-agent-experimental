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
package com.embabel.agent.api.client.graphql

import com.embabel.agent.api.client.*
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * [ApiLearner] that learns GraphQL APIs via introspection.
 *
 * Uses a multi-step introspection approach to stay within depth limits
 * imposed by many public GraphQL APIs.
 *
 * Example:
 * ```kotlin
 * val learner = GraphQlLearner()
 * val learned = learner.learn("https://countries.trevorblades.com/graphql")
 * val tool = learned.create()
 * // tool is a ProgressiveTool with queries (and mutations if present)
 * ```
 */
class GraphQlLearner(
    private val defaultAuthRequirements: List<AuthRequirement> = listOf(AuthRequirement.None),
) : ApiLearner {

    override fun learn(source: String): LearnedApi {
        val restClient = buildIntrospectionClient()
        val objectMapper = jacksonObjectMapper()

        val rootTypes = discoverRootTypes(source, restClient, objectMapper)
        val schemaDescription = rootTypes.description

        val name = if (!schemaDescription.isNullOrBlank()) {
            ToolNames.sanitize(schemaDescription.take(40))
        } else {
            deriveApiName(source)
        }

        val description = if (!schemaDescription.isNullOrBlank()) {
            schemaDescription
        } else {
            "GraphQL API at $source"
        }

        return LearnedApi(
            name = name,
            description = description,
            authRequirements = defaultAuthRequirements,
            factory = { credentials ->
                buildTool(source, name, rootTypes.queryTypeName, rootTypes.mutationTypeName, credentials, objectMapper)
            },
        )
    }

    private fun buildTool(
        endpoint: String,
        apiName: String,
        queryTypeName: String?,
        mutationTypeName: String?,
        credentials: ApiCredentials,
        objectMapper: ObjectMapper,
    ): ProgressiveTool {
        val restClient = buildApiClient(credentials)

        val queryTools = if (queryTypeName != null) {
            materializeTools(endpoint, queryTypeName, GraphQlOperationTool.OperationType.QUERY, restClient, objectMapper)
        } else emptyList()

        val mutationTools = if (mutationTypeName != null) {
            materializeTools(endpoint, mutationTypeName, GraphQlOperationTool.OperationType.MUTATION, restClient, objectMapper)
        } else emptyList()

        logger.info(
            "Learned GraphQL API '{}' from {}: {} queries, {} mutations",
            apiName, endpoint, queryTools.size, mutationTools.size,
        )

        return if (mutationTools.isEmpty()) {
            UnfoldingTool.of(
                name = apiName,
                description = "GraphQL API at $endpoint",
                innerTools = queryTools,
            )
        } else {
            UnfoldingTool.byCategory(
                name = apiName,
                description = "GraphQL API at $endpoint",
                toolsByCategory = buildMap {
                    if (queryTools.isNotEmpty()) put("queries", queryTools)
                    if (mutationTools.isNotEmpty()) put("mutations", mutationTools)
                },
                removeOnInvoke = false,
            )
        }
    }

    private fun materializeTools(
        endpoint: String,
        typeName: String,
        operationType: GraphQlOperationTool.OperationType,
        restClient: RestClient,
        objectMapper: ObjectMapper,
    ): List<Tool> {
        val fields = introspectFields(endpoint, typeName, restClient, objectMapper)
        return fields.map { field ->
            val selectionSet = buildSelectionSet(endpoint, field.type, restClient, objectMapper)
            GraphQlOperationTool(
                endpoint = endpoint,
                field = field,
                operationType = operationType,
                selectionSet = selectionSet,
                restClient = restClient,
                objectMapper = objectMapper,
            )
        }
    }

    /**
     * Result of schema root type discovery, including optional schema description.
     */
    data class SchemaRootTypes(
        val queryTypeName: String?,
        val mutationTypeName: String?,
        val description: String?,
    )

    companion object {

        private val logger = LoggerFactory.getLogger(GraphQlLearner::class.java)

        internal fun deriveApiName(endpoint: String): String {
            return try {
                val uri = java.net.URI(endpoint)
                val host = uri.host ?: "graphql"
                host.replace(Regex("[^a-z0-9]+"), "_").trim('_')
            } catch (_: Exception) {
                "graphql_api"
            }
        }

        private fun buildIntrospectionClient(): RestClient {
            val httpClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build()
            return RestClient.builder()
                .requestFactory(JdkClientHttpRequestFactory(httpClient))
                .build()
        }

        private fun buildApiClient(credentials: ApiCredentials): RestClient {
            val httpClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build()
            val builder = RestClient.builder()
                .requestFactory(JdkClientHttpRequestFactory(httpClient))

            when (credentials) {
                is ApiCredentials.None -> {}
                is ApiCredentials.Token -> builder.defaultHeader("Authorization", "Bearer ${credentials.token}")
                is ApiCredentials.ApiKey -> builder.defaultHeader("Authorization", credentials.value)
                is ApiCredentials.CustomHeaders -> credentials.headers.forEach { (name, value) ->
                    builder.defaultHeader(name, value)
                }
                is ApiCredentials.Multiple -> credentials.credentials.forEach { cred ->
                    if (cred is ApiCredentials.Token) {
                        builder.defaultHeader("Authorization", "Bearer ${cred.token}")
                    }
                }
                is ApiCredentials.OAuth2 -> builder.defaultHeader("Authorization", "Bearer ${credentials.accessToken}")
            }

            return builder.build()
        }

        private fun executeGraphQl(
            endpoint: String,
            query: String,
            restClient: RestClient,
            objectMapper: ObjectMapper,
        ): Map<String, Any?> {
            val body = objectMapper.writeValueAsString(mapOf("query" to query))
            val response = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String::class.java)
                ?: throw IllegalStateException("Empty response from $endpoint")

            @Suppress("UNCHECKED_CAST")
            val json = objectMapper.readValue(response, Map::class.java) as Map<String, Any?>
            val errors = json["errors"]
            if (errors != null && json["data"] == null) {
                throw IllegalStateException("GraphQL introspection error: $errors")
            }
            @Suppress("UNCHECKED_CAST")
            return json["data"] as? Map<String, Any?> ?: emptyMap()
        }

        internal fun discoverRootTypes(
            endpoint: String,
            restClient: RestClient,
            objectMapper: ObjectMapper,
        ): SchemaRootTypes {
            // Try with description first (GraphQL June 2018+ spec), fall back without
            val data = try {
                executeGraphQl(endpoint, GraphQlIntrospection.SCHEMA_QUERY_WITH_DESCRIPTION, restClient, objectMapper)
            } catch (e: Exception) {
                logger.debug("Schema description not supported at {}, falling back: {}", endpoint, e.message)
                executeGraphQl(endpoint, GraphQlIntrospection.SCHEMA_QUERY, restClient, objectMapper)
            }
            @Suppress("UNCHECKED_CAST")
            val schema = data["__schema"] as? Map<String, Any?> ?: return SchemaRootTypes(null, null, null)
            @Suppress("UNCHECKED_CAST")
            val queryType = (schema["queryType"] as? Map<String, Any?>)?.get("name") as? String
            @Suppress("UNCHECKED_CAST")
            val mutationType = (schema["mutationType"] as? Map<String, Any?>)?.get("name") as? String
            val description = schema["description"] as? String
            return SchemaRootTypes(queryType, mutationType, description)
        }

        internal fun introspectFields(
            endpoint: String,
            typeName: String,
            restClient: RestClient,
            objectMapper: ObjectMapper,
        ): List<GraphQlField> {
            return try {
                // Try full-depth introspection first
                val data = executeGraphQl(endpoint, GraphQlIntrospection.fieldsQuery(typeName), restClient, objectMapper)
                @Suppress("UNCHECKED_CAST")
                val type = data["__type"] as? Map<String, Any?> ?: return emptyList()
                @Suppress("UNCHECKED_CAST")
                val fields = type["fields"] as? List<Map<String, Any?>> ?: return emptyList()
                fields.mapNotNull { parseField(it) }
                    .filter { !it.name.startsWith("_") }
            } catch (e: Exception) {
                logger.info("Full introspection failed for {} at {}, falling back to shallow queries: {}", typeName, endpoint, e.message)
                introspectFieldsShallow(endpoint, typeName, restClient, objectMapper)
            }
        }

        private fun introspectFieldsShallow(
            endpoint: String,
            typeName: String,
            restClient: RestClient,
            objectMapper: ObjectMapper,
        ): List<GraphQlField> {
            // Fetch return types and args in separate shallow queries
            val returnData = executeGraphQl(endpoint, GraphQlIntrospection.shallowReturnTypesQuery(typeName), restClient, objectMapper)
            val argsData = executeGraphQl(endpoint, GraphQlIntrospection.shallowArgsQuery(typeName), restClient, objectMapper)

            @Suppress("UNCHECKED_CAST")
            val returnFields = ((returnData["__type"] as? Map<String, Any?>)
                ?.get("fields") as? List<Map<String, Any?>>) ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val argsFields = ((argsData["__type"] as? Map<String, Any?>)
                ?.get("fields") as? List<Map<String, Any?>>) ?: emptyList()

            // Index args by field name
            val argsByField = argsFields.associate { field ->
                val name = field["name"] as? String ?: ""
                @Suppress("UNCHECKED_CAST")
                val rawArgs = field["args"] as? List<Map<String, Any?>> ?: emptyList()
                name to rawArgs.mapNotNull { parseShallowArgument(it) }
            }

            return returnFields.mapNotNull { field ->
                val name = field["name"] as? String ?: return@mapNotNull null
                if (name.startsWith("_")) return@mapNotNull null
                val description = field["description"] as? String
                @Suppress("UNCHECKED_CAST")
                val type = parseTypeRef(field["type"] as? Map<String, Any?>) ?: return@mapNotNull null
                val args = argsByField[name] ?: emptyList()
                GraphQlField(name = name, description = description, args = args, type = type)
            }
        }

        /**
         * Parse an argument from a shallow query where type has only kind+name (no ofType).
         * NON_NULL wrappers without ofType are inferred as STRING.
         */
        private fun parseShallowArgument(raw: Map<String, Any?>): GraphQlArgument? {
            val name = raw["name"] as? String ?: return null
            val description = raw["description"] as? String
            @Suppress("UNCHECKED_CAST")
            val rawType = raw["type"] as? Map<String, Any?>
            val type = parseTypeRef(rawType) ?: return null

            // If NON_NULL but missing ofType (depth-limited), infer the inner type
            val resolvedType = if (type.kind == "NON_NULL" && type.ofType == null) {
                val inferredInner = inferArgType(name)
                GraphQlTypeRef(kind = "NON_NULL", name = null, ofType = inferredInner)
            } else {
                type
            }

            return GraphQlArgument(name = name, description = description, type = resolvedType)
        }

        /**
         * Infer a GraphQL type for an argument when introspection depth was insufficient.
         */
        private fun inferArgType(argName: String): GraphQlTypeRef {
            return when {
                argName == "id" || argName.endsWith("Id") -> GraphQlTypeRef(kind = "SCALAR", name = "ID")
                argName == "ids" || argName.endsWith("Ids") -> GraphQlTypeRef(
                    kind = "LIST",
                    name = null,
                    ofType = GraphQlTypeRef(kind = "SCALAR", name = "ID"),
                )
                else -> GraphQlTypeRef(kind = "SCALAR", name = "String")
            }
        }

        private fun parseField(raw: Map<String, Any?>): GraphQlField? {
            val name = raw["name"] as? String ?: return null
            val description = raw["description"] as? String
            @Suppress("UNCHECKED_CAST")
            val type = parseTypeRef(raw["type"] as? Map<String, Any?>) ?: return null
            @Suppress("UNCHECKED_CAST")
            val rawArgs = raw["args"] as? List<Map<String, Any?>> ?: emptyList()
            val args = rawArgs.mapNotNull { parseArgument(it) }
            return GraphQlField(name = name, description = description, args = args, type = type)
        }

        private fun parseArgument(raw: Map<String, Any?>): GraphQlArgument? {
            val name = raw["name"] as? String ?: return null
            val description = raw["description"] as? String
            @Suppress("UNCHECKED_CAST")
            val type = parseTypeRef(raw["type"] as? Map<String, Any?>) ?: return null
            return GraphQlArgument(name = name, description = description, type = type)
        }

        internal fun parseTypeRef(raw: Map<String, Any?>?): GraphQlTypeRef? {
            if (raw == null) return null
            val kind = raw["kind"] as? String ?: return null
            val name = raw["name"] as? String
            @Suppress("UNCHECKED_CAST")
            val ofType = parseTypeRef(raw["ofType"] as? Map<String, Any?>)
            return GraphQlTypeRef(kind = kind, name = name, ofType = ofType)
        }

        internal fun buildSelectionSet(
            endpoint: String,
            returnType: GraphQlTypeRef,
            restClient: RestClient,
            objectMapper: ObjectMapper,
            depth: Int = 0,
            visited: Set<String> = emptySet(),
        ): String {
            if (depth > 2) return ""
            val typeName = returnType.leafName() ?: return ""
            val kind = returnType.leafKind()
            if (kind == "SCALAR" || kind == "ENUM") return ""
            if (typeName in visited) return ""

            return try {
                val data = executeGraphQl(endpoint, GraphQlIntrospection.scalarFieldsQuery(typeName), restClient, objectMapper)
                @Suppress("UNCHECKED_CAST")
                val type = data["__type"] as? Map<String, Any?> ?: return ""
                @Suppress("UNCHECKED_CAST")
                val fields = type["fields"] as? List<Map<String, Any?>> ?: return ""

                val fieldSelections = mutableListOf<String>()
                val nextVisited = visited + typeName

                for (field in fields) {
                    val fieldName = field["name"] as? String ?: continue
                    if (fieldName.startsWith("_")) continue
                    @Suppress("UNCHECKED_CAST")
                    val fieldTypeRef = parseTypeRef(field["type"] as? Map<String, Any?>) ?: continue
                    val leafKind = fieldTypeRef.leafKind()

                    when (leafKind) {
                        "SCALAR", "ENUM" -> fieldSelections.add(fieldName)
                        "OBJECT" -> {
                            val nestedSelection = buildSelectionSet(
                                endpoint, fieldTypeRef, restClient, objectMapper,
                                depth + 1, nextVisited,
                            )
                            if (nestedSelection.isNotEmpty()) {
                                fieldSelections.add("$fieldName $nestedSelection")
                            }
                        }
                    }
                }

                if (fieldSelections.isEmpty()) "" else "{ ${fieldSelections.joinToString(" ")} }"
            } catch (e: Exception) {
                logger.warn("Failed to build selection set for {} at {}: {}", typeName, endpoint, e.message)
                ""
            }
        }
    }
}
