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

import com.embabel.agent.api.client.ToolNames
import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder

/**
 * A [Tool] that wraps a single OpenAPI operation, executing it via [RestClient].
 *
 * Each instance represents one API operation (e.g., `GET /pets/{petId}`)
 * materialized at runtime from an OpenAPI spec — no code generation needed.
 */
class OpenApiOperationTool(
    private val baseUrl: String,
    private val path: String,
    private val httpMethod: PathItem.HttpMethod,
    private val operation: Operation,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : Tool {

    override val definition: Tool.Definition = Tool.Definition(
        name = operationName(httpMethod, path, operation),
        description = operationDescription(operation),
        inputSchema = buildInputSchema(operation),
    ).let { def ->
        val outputSchema = extractOutputSchema(operation)
        if (outputSchema != null) def.withMetadata(OUTPUT_SCHEMA_KEY, outputSchema) else def
    }

    override fun call(input: String): Tool.Result {
        return try {
            @Suppress("UNCHECKED_CAST")
            val params: Map<String, Any?> = if (input.isBlank()) {
                emptyMap()
            } else {
                objectMapper.readValue(input, Map::class.java) as Map<String, Any?>
            }

            val resolvedPath = resolvePath(path, params)
            val queryParams = resolveQueryParams(params)
            val body = resolveBody(params)

            val uri = buildUri(resolvedPath, queryParams)

            logger.info("Calling {} {} (baseUrl={})", httpMethod, uri, baseUrl)

            val response = executeRequest(uri, body)
            Tool.Result.text(response ?: "")
        } catch (e: RestClientResponseException) {
            val errorBody = e.responseBodyAsString
            val message = "HTTP ${e.statusCode.value()} from $httpMethod $baseUrl$path: ${errorBody.take(200)}"
            logger.warn(message)
            Tool.Result.error(message, e)
        } catch (e: Exception) {
            logger.warn("Error calling {} {} at {}: {}", httpMethod, path, baseUrl, e.message)
            Tool.Result.error("Error calling $httpMethod $path at $baseUrl: ${e.message}", e)
        }
    }

    private fun resolvePath(path: String, params: Map<String, Any?>): String {
        var resolved = path
        pathParameterNames().forEach { paramName ->
            val value = params[paramName]
            if (value != null) {
                resolved = resolved.replace("{$paramName}", value.toString())
            }
        }
        return resolved
    }

    private fun resolveQueryParams(params: Map<String, Any?>): Map<String, List<String>> {
        val pathParams = pathParameterNames().toSet()
        val queryParamNames = (operation.parameters ?: emptyList())
            .filter { it.`in` == "query" }
            .map { it.name }
            .toSet()

        return params
            .filter { it.key in queryParamNames && it.key !in pathParams && it.key != "body" }
            .filter { it.value != null }
            .mapValues { entry ->
                when (val v = entry.value) {
                    is Collection<*> -> v.mapNotNull { it?.toString() }
                    else -> listOf(v.toString())
                }
            }
    }

    private fun pathParameterNames(): List<String> {
        val regex = "\\{([^}]+)}".toRegex()
        return regex.findAll(path).map { it.groupValues[1] }.toList()
    }

    /**
     * Build the request body from the caller's flat argument map.
     *
     * Inputs are accepted in two shapes:
     *   1. **Flat** (preferred — matches the typed surface):
     *      `{owner, repo, title, body, labels}` — body fields appear at
     *      the top level alongside path/query params. The tool gathers
     *      every property the request-body schema declares (minus path/
     *      query names, since those win on collision).
     *   2. **Wrapper** (legacy / non-object body):
     *      `{owner, repo, body: {title, body}}` — body nested under a
     *      reserved `body` key. Used when the request body is non-object
     *      (raw string, array) or when the caller chose to send a wrapper.
     *
     * If both shapes are present (legacy `body: {...}` plus flat fields),
     * the flat fields override matching keys in the wrapper.
     *
     * The flat shape eliminates the body-name-collision bug for ops whose
     * request body has a property named `body` (e.g. GitHub `issues/create`,
     * `issues/create-comment`, `pulls/create`).
     */
    private fun resolveBody(params: Map<String, Any?>): Any? {
        val bodySchema = operation.requestBody?.content?.values?.firstOrNull()?.schema
            ?: return null

        val bodyProps = bodySchema.properties?.keys?.toSet().orEmpty()
        val isObjectBody = (bodySchema.type == "object" || bodyProps.isNotEmpty())

        if (!isObjectBody) {
            // Non-object body — use the wrapper key directly.
            return params["body"]
        }

        val pathParams = pathParameterNames().toSet()
        val queryParamNames = (operation.parameters ?: emptyList())
            .filter { it.`in` == "query" }
            .map { it.name }
            .toSet()
        val flat = params.filter { (k, _) ->
            k in bodyProps && k !in pathParams && k !in queryParamNames
        }

        @Suppress("UNCHECKED_CAST")
        val wrapper = params["body"] as? Map<String, Any?>
        return when {
            wrapper != null && flat.isNotEmpty() -> wrapper + flat
            wrapper != null -> wrapper
            flat.isNotEmpty() -> flat
            else -> null
        }
    }

    private fun buildUri(resolvedPath: String, queryParams: Map<String, List<String>>): String {
        val builder = UriComponentsBuilder
            .fromUriString(baseUrl.trimEnd('/') + resolvedPath)

        queryParams.forEach { (key, values) ->
            values.forEach { value ->
                builder.queryParam(key, value)
            }
        }

        return builder.build().toUriString()
    }

    private fun executeRequest(uri: String, body: Any?): String? {
        return when (httpMethod) {
            PathItem.HttpMethod.GET -> restClient.get().uri(uri)
                .retrieve().body(String::class.java)

            PathItem.HttpMethod.DELETE -> restClient.delete().uri(uri)
                .retrieve().body(String::class.java)

            PathItem.HttpMethod.POST -> executeWithBody(restClient.post().uri(uri), body)
            PathItem.HttpMethod.PUT -> executeWithBody(restClient.put().uri(uri), body)
            PathItem.HttpMethod.PATCH -> executeWithBody(restClient.patch().uri(uri), body)

            else -> throw UnsupportedOperationException("HTTP method $httpMethod not supported")
        }
    }

    private fun executeWithBody(
        spec: RestClient.RequestBodySpec,
        body: Any?,
    ): String? {
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
        }
        return spec.retrieve().body(String::class.java)
    }

    companion object {

        /**
         * Metadata key for the JSON Schema string describing the tool's
         * response. Populated from the OpenAPI spec's `responses.2xx`
         * content schema when available.
         */
        const val OUTPUT_SCHEMA_KEY: String = "outputSchema"

        private val logger = LoggerFactory.getLogger(OpenApiOperationTool::class.java)

        /**
         * Extract the JSON Schema of the success response (first 2xx with
         * a JSON content schema) from an OpenAPI operation. Returns the
         * schema as a JSON string, or `null` if unavailable.
         */
        private fun extractOutputSchema(operation: Operation): String? {
            val responses = operation.responses ?: return null
            // Try 200, 201, then any 2xx
            val successResponse = responses["200"]
                ?: responses["201"]
                ?: responses.entries.firstOrNull { it.key.startsWith("2") }?.value
                ?: return null
            val mediaType = successResponse.content
                ?.get("application/json")
                ?: successResponse.content?.values?.firstOrNull()
                ?: return null
            val schema = mediaType.schema ?: return null
            return try {
                jacksonObjectMapper().writeValueAsString(schemaToMap(schema))
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Convert a Swagger [Schema] to a Map suitable for JSON
         * serialization. Handles object, array, primitive, oneOf/anyOf
         * combinators, and nullable types.
         *
         * The output is consumed by `JavaScriptCodeSurfaceBuilder` /
         * `PythonCodeSurfaceBuilder` to emit typed interfaces; missing
         * fields here become `unknown` in the generated TypeScript.
         */
        private fun schemaToMap(schema: Schema<*>): Map<String, Any?> = buildMap {
            // Type inference: explicit `type` wins; otherwise infer from
            // structure. A schema with `properties` but no `type` is
            // implicitly object; a schema with `items` is implicitly array.
            val inferredType = schema.type ?: when {
                schema is ArraySchema || schema.items != null -> "array"
                schema.properties != null -> "object"
                schema.allOf != null || schema.oneOf != null || schema.anyOf != null -> "object"
                else -> null
            }
            inferredType?.let { put("type", it) }

            schema.description?.let { put("description", it) }
            schema.format?.let { put("format", it) }
            // OpenAPI 3.0 nullable + 3.1 type:["x","null"] both map here.
            if (schema.nullable == true) put("nullable", true)

            if (schema.properties != null) {
                put("properties", schema.properties.mapValues { (_, v) -> schemaToMap(v) })
            }
            if (schema.required != null) {
                put("required", schema.required)
            }
            // Array items — handle both ArraySchema.items (typed) and
            // generic Schema.items (post-resolveFully sometimes lands here).
            val items = (schema as? ArraySchema)?.items ?: schema.items
            if (items != null) {
                put("items", schemaToMap(items))
            }

            // allOf / oneOf / anyOf — emit a union or merged shape.
            // Downstream TS emitter treats these as union types when the
            // shape isn't an object merge.
            schema.allOf?.let { branches ->
                if (branches.isNotEmpty()) put("allOf", branches.map { schemaToMap(it) })
            }
            schema.oneOf?.let { branches ->
                if (branches.isNotEmpty()) put("oneOf", branches.map { schemaToMap(it) })
            }
            schema.anyOf?.let { branches ->
                if (branches.isNotEmpty()) put("anyOf", branches.map { schemaToMap(it) })
            }

            schema.enum?.let { put("enum", it) }
            // Additional properties — `Record<string, T>`-like maps.
            when (val ap = schema.additionalProperties) {
                is Schema<*> -> put("additionalProperties", schemaToMap(ap))
                is Boolean -> put("additionalProperties", ap)
                else -> {}
            }
        }

        internal fun operationName(
            httpMethod: PathItem.HttpMethod,
            path: String,
            operation: Operation,
        ): String {
            // Prefer operationId if available
            if (!operation.operationId.isNullOrBlank()) {
                return ToolNames.sanitize(operation.operationId)
            }
            // Synthesize from method + path: GET /pets/{petId} → get_pets_by_petId
            val synthesized = path
                .replace("{", "by_")
                .replace("}", "")
                .replace("/", "_")
                .replace("-", "_")
                .trimStart('_')
                .trimEnd('_')
                .replace("__", "_")
            return ToolNames.sanitize("${httpMethod.name.lowercase()}_$synthesized")
        }

        internal fun operationDescription(operation: Operation): String {
            return listOfNotNull(
                operation.summary,
                operation.description?.takeIf { it != operation.summary },
            ).joinToString(". ").ifBlank { "No description available" }
        }

        internal fun buildInputSchema(operation: Operation): Tool.InputSchema {
            val parameters = mutableListOf<Tool.Parameter>()

            // Path and query parameters
            operation.parameters?.forEach { param ->
                parameters.add(mapParameter(param))
            }
            val pathQueryNames = parameters.map { it.name }.toSet()

            // Request body — INLINE the body's properties at the top level
            // so the typed surface presents a flat callable shape:
            //   issuesCreate({owner, repo, title, body, labels})
            // not the legacy nested wrapper:
            //   issuesCreate({owner, repo, body: {title, body, labels}})
            // The wrapper form created a name-collision footgun (a request
            // body containing a property named `body` — every GitHub
            // `issues/create`, `issues/create-comment`, `pulls/create` — got
            // reduced to a string by the LLM and rejected with HTTP 422).
            operation.requestBody?.content?.values?.firstOrNull()?.schema?.let { schema ->
                val bodyRequiredOverall = operation.requestBody?.required ?: false
                @Suppress("UNCHECKED_CAST")
                val properties = schema.properties as? Map<String, Schema<*>>
                if (!properties.isNullOrEmpty()) {
                    val bodyRequired = (schema.required as? List<*>)?.map { it.toString() }?.toSet().orEmpty()
                    for ((propName, propSchema) in properties) {
                        if (propName in pathQueryNames) continue // path/query wins on collision
                        parameters.add(
                            mapSchemaToParameter(
                                name = propName,
                                schema = propSchema,
                                description = propSchema.description ?: propName,
                                required = bodyRequiredOverall && propName in bodyRequired,
                            ),
                        )
                    }
                } else {
                    // Non-object body (raw string, array, etc.) — keep the
                    // legacy `body` wrapper since there's nothing to flatten.
                    parameters.add(mapSchemaToParameter("body", schema, "Request body", true))
                }
            }

            return Tool.InputSchema.of(*parameters.toTypedArray())
        }

        private fun mapParameter(param: Parameter): Tool.Parameter {
            val type = mapSchemaType(param.schema)
            val itemType = if (type == Tool.ParameterType.ARRAY && param.schema != null) {
                if (param.schema is ArraySchema) {
                    (param.schema as ArraySchema).items?.let { mapSchemaType(it) } ?: Tool.ParameterType.STRING
                } else {
                    Tool.ParameterType.STRING
                }
            } else null
            return Tool.Parameter(
                name = param.name,
                type = type,
                description = param.description ?: param.name,
                required = param.required ?: (param.`in` == "path"),
                // OpenAPI 3.1 nullable enums commonly include explicit null
                // (the GitHub spec uses this for state filters). Drop nulls
                // rather than NPE on `.toString()`. Same fix as
                // `OpenApiModelBuilder.convertSchema`.
                enumValues = param.schema?.enum?.mapNotNull { it?.toString() },
                itemType = itemType,
            )
        }

        private fun mapSchemaToParameter(
            name: String,
            schema: Schema<*>,
            description: String,
            required: Boolean,
        ): Tool.Parameter {
            val type = mapSchemaType(schema)

            val properties = if (type == Tool.ParameterType.OBJECT && schema.properties != null) {
                val requiredProps = schema.required?.toSet() ?: emptySet()
                schema.properties.map { (propName, propSchema) ->
                    mapSchemaToParameter(
                        name = propName,
                        schema = propSchema,
                        description = propSchema.description ?: propName,
                        required = propName in requiredProps,
                    )
                }
            } else null

            val itemType = if (type == Tool.ParameterType.ARRAY) {
                if (schema is ArraySchema) {
                    schema.items?.let { mapSchemaType(it) } ?: Tool.ParameterType.STRING
                } else {
                    // Fallback for array params without ArraySchema (e.g. Swagger 2.0 conversion)
                    Tool.ParameterType.STRING
                }
            } else null

            return Tool.Parameter(
                name = name,
                type = type,
                description = schema.description ?: description,
                required = required,
                enumValues = schema.enum?.mapNotNull { it?.toString() },
                properties = properties,
                itemType = itemType,
            )
        }

        private fun mapSchemaType(schema: Schema<*>?): Tool.ParameterType {
            return when (schema?.type) {
                "string" -> Tool.ParameterType.STRING
                "integer" -> Tool.ParameterType.INTEGER
                "number" -> Tool.ParameterType.NUMBER
                "boolean" -> Tool.ParameterType.BOOLEAN
                "array" -> Tool.ParameterType.ARRAY
                "object" -> Tool.ParameterType.OBJECT
                else -> Tool.ParameterType.STRING
            }
        }
    }
}
