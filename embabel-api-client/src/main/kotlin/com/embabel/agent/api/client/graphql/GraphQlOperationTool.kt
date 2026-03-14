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

import com.embabel.agent.api.client.ToolNames
import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * A [Tool] that wraps a single GraphQL query or mutation.
 *
 * Each instance represents one field on the Query or Mutation root type,
 * materialized at runtime from an introspection result.
 */
class GraphQlOperationTool(
    private val endpoint: String,
    private val field: GraphQlField,
    private val operationType: OperationType,
    private val selectionSet: String,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : Tool {

    enum class OperationType { QUERY, MUTATION }

    override val definition: Tool.Definition = Tool.Definition(
        name = ToolNames.sanitize(field.name),
        description = buildDescription(),
        inputSchema = buildInputSchema(),
    )

    override fun call(input: String): Tool.Result {
        return try {
            @Suppress("UNCHECKED_CAST")
            val params: Map<String, Any?> = if (input.isBlank()) {
                emptyMap()
            } else {
                objectMapper.readValue(input, Map::class.java) as Map<String, Any?>
            }

            val query = buildQuery(params)
            val requestBody = mutableMapOf<String, Any>("query" to query)
            if (params.isNotEmpty()) {
                requestBody["variables"] = params
            }

            logger.debug("GraphQL {} {}: {}", operationType, field.name, query)

            val response = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(requestBody))
                .retrieve()
                .body(String::class.java)

            parseResponse(response)
        } catch (e: RestClientResponseException) {
            val message = "HTTP ${e.statusCode.value()} from GraphQL $endpoint: ${e.responseBodyAsString}"
            logger.warn(message)
            Tool.Result.error(message, e)
        } catch (e: Exception) {
            logger.warn("Error calling GraphQL {}: {}", field.name, e.message)
            Tool.Result.error("Error calling GraphQL ${field.name}: ${e.message}", e)
        }
    }

    private fun buildDescription(): String {
        val parts = mutableListOf<String>()
        if (field.description != null) {
            parts.add(field.description)
        }
        val returnType = field.type.leafName() ?: "unknown"
        val prefix = if (operationType == OperationType.MUTATION) "Mutation" else "Query"
        parts.add("$prefix returning $returnType")
        return parts.joinToString(". ")
    }

    private fun buildInputSchema(): Tool.InputSchema {
        val parameters = field.args.map { arg ->
            Tool.Parameter(
                name = arg.name,
                type = mapGraphQlType(arg.type),
                description = arg.description ?: arg.name,
                required = arg.type.isNonNull(),
                enumValues = null,
            )
        }
        return Tool.InputSchema.of(*parameters.toTypedArray())
    }

    private fun buildQuery(params: Map<String, Any?>): String {
        val keyword = if (operationType == OperationType.MUTATION) "mutation" else "query"
        val argDefs = if (field.args.isNotEmpty() && params.isNotEmpty()) {
            val defs = field.args
                .filter { params.containsKey(it.name) }
                .joinToString(", ") { "\$${it.name}: ${graphQlTypeName(it.type)}" }
            if (defs.isNotEmpty()) "($defs)" else ""
        } else ""

        val argPass = if (field.args.isNotEmpty() && params.isNotEmpty()) {
            val pass = field.args
                .filter { params.containsKey(it.name) }
                .joinToString(", ") { "${it.name}: \$${it.name}" }
            if (pass.isNotEmpty()) "($pass)" else ""
        } else ""

        return "$keyword$argDefs { ${field.name}$argPass $selectionSet }"
    }

    private fun parseResponse(response: String?): Tool.Result {
        if (response == null) return Tool.Result.error("Empty response from GraphQL endpoint")

        @Suppress("UNCHECKED_CAST")
        val json = objectMapper.readValue(response, Map::class.java) as Map<String, Any?>

        val errors = json["errors"]
        if (errors != null) {
            @Suppress("UNCHECKED_CAST")
            val errorList = errors as? List<Map<String, Any?>>
            val message = errorList?.joinToString("; ") { it["message"]?.toString() ?: "Unknown error" }
                ?: errors.toString()
            return Tool.Result.error("GraphQL error: $message")
        }

        @Suppress("UNCHECKED_CAST")
        val data = json["data"] as? Map<String, Any?>
        val result = data?.get(field.name)
        return Tool.Result.text(objectMapper.writeValueAsString(result))
    }

    companion object {

        private val logger = LoggerFactory.getLogger(GraphQlOperationTool::class.java)

        internal fun mapGraphQlType(type: GraphQlTypeRef): Tool.ParameterType {
            return when (type.leafKind()) {
                "SCALAR" -> when (type.leafName()) {
                    "Int" -> Tool.ParameterType.INTEGER
                    "Float" -> Tool.ParameterType.NUMBER
                    "Boolean" -> Tool.ParameterType.BOOLEAN
                    else -> Tool.ParameterType.STRING // String, ID, custom scalars
                }
                "ENUM" -> Tool.ParameterType.STRING
                "INPUT_OBJECT" -> Tool.ParameterType.OBJECT
                else -> Tool.ParameterType.STRING
            }.let { baseType ->
                if (type.isList()) Tool.ParameterType.ARRAY else baseType
            }
        }

        internal fun graphQlTypeName(type: GraphQlTypeRef): String {
            return when (type.kind) {
                "NON_NULL" -> "${graphQlTypeName(type.ofType!!)}!"
                "LIST" -> "[${graphQlTypeName(type.ofType!!)}]"
                else -> type.name ?: "String"
            }
        }
    }
}
