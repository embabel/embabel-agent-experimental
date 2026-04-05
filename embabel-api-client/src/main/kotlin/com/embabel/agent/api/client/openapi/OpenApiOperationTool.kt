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
    )

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
            val body = params["body"]

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

        private val logger = LoggerFactory.getLogger(OpenApiOperationTool::class.java)

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

            // Request body
            operation.requestBody?.content?.values?.firstOrNull()?.schema?.let { schema ->
                parameters.add(mapSchemaToParameter("body", schema, "Request body", true))
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
                enumValues = param.schema?.enum?.map { it.toString() },
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
                enumValues = schema.enum?.map { it.toString() },
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
