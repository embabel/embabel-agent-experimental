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

import com.embabel.agent.api.client.AuthRequirement
import com.embabel.agent.api.client.ToolNames
import com.embabel.agent.api.client.model.*
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter

/**
 * Converts a parsed Swagger [OpenAPI] object into an [ApiModel] IR,
 * preserving full schema information for both tool projection and
 * interface generation.
 */
internal object OpenApiModelBuilder {

    fun build(source: String, openApi: OpenAPI): ApiModel {
        val name = OpenApiLearner.deriveApiName(openApi)
        val description = OpenApiLearner.deriveApiDescription(openApi, source)
        val baseUrl = OpenApiLearner.resolveBaseUrl(openApi, source)
        val auth = OpenApiLearner.extractAuthRequirements(openApi)
        val types = extractNamedTypes(openApi)
        val operations = extractOperations(openApi, baseUrl)
        val resources = groupIntoResources(openApi, operations)

        return ApiModel(
            name = name,
            description = description,
            baseUrl = baseUrl,
            auth = auth,
            resources = resources,
            types = types,
        )
    }

    private fun extractNamedTypes(openApi: OpenAPI): Map<String, ApiType> {
        val schemas = openApi.components?.schemas ?: return emptyMap()
        return schemas.mapValues { (typeName, schema) ->
            ApiType(
                name = typeName,
                schema = convertSchema(schema, typeName),
            )
        }
    }

    private fun extractOperations(
        openApi: OpenAPI,
        baseUrl: String,
    ): List<ApiOperation> {
        val operations = mutableListOf<ApiOperation>()

        openApi.paths?.forEach { (path, pathItem) ->
            pathItem.readOperationsMap()?.forEach { (method, operation) ->
                // Merge path-level params with operation-level params
                val pathParams = pathItem.parameters ?: emptyList()
                val opParams = operation.parameters ?: emptyList()
                val existingNames = opParams.map { it.name }.toSet()
                val mergedParams = opParams + pathParams.filter { it.name !in existingNames }

                operations.add(
                    ApiOperation(
                        name = operationName(method, path, operation),
                        description = operationDescription(operation),
                        method = convertMethod(method),
                        path = path,
                        parameters = mergedParams.map { convertParameter(it) },
                        requestBody = extractRequestBody(operation),
                        responses = extractResponses(operation),
                        tags = operation.tags?.takeIf { it.isNotEmpty() } ?: listOf("default"),
                    )
                )
            }
        }

        return operations
    }

    private fun groupIntoResources(
        openApi: OpenAPI,
        operations: List<ApiOperation>,
    ): List<ApiResource> {
        val byTag = mutableMapOf<String, MutableList<ApiOperation>>()
        for (op in operations) {
            for (tag in op.tags) {
                byTag.getOrPut(tag) { mutableListOf() }.add(op)
            }
        }

        // Use tag descriptions from the spec if available
        val tagDescriptions = openApi.tags
            ?.associate { it.name to it.description }
            ?: emptyMap()

        return byTag.map { (tag, ops) ->
            ApiResource(
                name = tag,
                description = tagDescriptions[tag],
                operations = ops,
            )
        }
    }

    // --- Schema conversion ---

    private fun convertSchema(schema: Schema<*>, nameHint: String? = null): ApiSchema {
        // Check for $ref — the parser resolves refs but we can detect named types
        // by checking if this schema has no inline properties but has a title or
        // was obtained from components/schemas
        return when (schema.type) {
            "string" -> ApiSchema.Primitive(
                type = PrimitiveType.STRING,
                format = schema.format,
                // `schema.enum` is `List<Any?>` — OpenAPI 3.1 nullable enums
                // commonly include `null` as a valid value (e.g. GitHub's
                // spec uses this for state filters). Drop null entries
                // rather than calling `.toString()` on them, which NPEs.
                // Loss of the explicit-null enum value is acceptable here:
                // typed-string surface doesn't represent it, and consumers
                // already handle absent-value via the optional/nullable
                // marker on the enclosing property.
                enumValues = schema.enum?.mapNotNull { it?.toString() },
                description = schema.description,
            )
            "integer" -> ApiSchema.Primitive(
                type = PrimitiveType.INTEGER,
                format = schema.format,
                description = schema.description,
            )
            "number" -> ApiSchema.Primitive(
                type = PrimitiveType.NUMBER,
                format = schema.format,
                description = schema.description,
            )
            "boolean" -> ApiSchema.Primitive(
                type = PrimitiveType.BOOLEAN,
                description = schema.description,
            )
            "array" -> {
                val itemSchema = if (schema is ArraySchema && schema.items != null) {
                    convertSchema(schema.items)
                } else {
                    ApiSchema.Primitive(type = PrimitiveType.STRING)
                }
                ApiSchema.Array(
                    items = itemSchema,
                    description = schema.description,
                )
            }
            else -> convertObjectSchema(schema, nameHint)
        }
    }

    private fun convertObjectSchema(schema: Schema<*>, nameHint: String? = null): ApiSchema {
        val properties = schema.properties?.map { (propName, propSchema) ->
            ApiProperty(
                name = propName,
                schema = convertSchema(propSchema),
                description = propSchema.description,
            )
        } ?: emptyList()

        return ApiSchema.Object(
            name = nameHint,
            properties = properties,
            required = schema.required?.toSet() ?: emptySet(),
            description = schema.description,
        )
    }

    // --- Parameter conversion ---

    private fun convertParameter(param: Parameter): ApiParameter {
        val schema = if (param.schema != null) {
            convertSchema(param.schema)
        } else {
            ApiSchema.Primitive(type = PrimitiveType.STRING)
        }

        return ApiParameter(
            name = param.name,
            description = param.description,
            location = when (param.`in`) {
                "path" -> ParameterLocation.PATH
                "query" -> ParameterLocation.QUERY
                "header" -> ParameterLocation.HEADER
                "cookie" -> ParameterLocation.COOKIE
                else -> ParameterLocation.QUERY
            },
            required = param.required ?: (param.`in` == "path"),
            schema = schema,
        )
    }

    // --- Request body ---

    private fun extractRequestBody(operation: Operation): ApiSchema? {
        val content = operation.requestBody?.content ?: return null
        val mediaType = content["application/json"]
            ?: content.values.firstOrNull()
            ?: return null
        val schema = mediaType.schema ?: return null
        return convertSchema(schema)
    }

    // --- Responses ---

    private fun extractResponses(operation: Operation): Map<String, ApiResponse> {
        val responses = operation.responses ?: return emptyMap()
        return responses.mapValues { (_, response) ->
            val schema = response.content
                ?.get("application/json")
                ?.schema
                ?.let { convertSchema(it) }
            ApiResponse(
                description = response.description,
                schema = schema,
            )
        }
    }

    // --- Naming (delegates to existing logic in OpenApiOperationTool) ---

    private fun operationName(
        method: PathItem.HttpMethod,
        path: String,
        operation: Operation,
    ): String = OpenApiOperationTool.operationName(method, path, operation)

    private fun operationDescription(operation: Operation): String =
        OpenApiOperationTool.operationDescription(operation)

    private fun convertMethod(method: PathItem.HttpMethod): HttpMethod = when (method) {
        PathItem.HttpMethod.GET -> HttpMethod.GET
        PathItem.HttpMethod.POST -> HttpMethod.POST
        PathItem.HttpMethod.PUT -> HttpMethod.PUT
        PathItem.HttpMethod.DELETE -> HttpMethod.DELETE
        PathItem.HttpMethod.PATCH -> HttpMethod.PATCH
        PathItem.HttpMethod.HEAD -> HttpMethod.HEAD
        PathItem.HttpMethod.OPTIONS -> HttpMethod.OPTIONS
        PathItem.HttpMethod.TRACE -> HttpMethod.GET
    }
}
