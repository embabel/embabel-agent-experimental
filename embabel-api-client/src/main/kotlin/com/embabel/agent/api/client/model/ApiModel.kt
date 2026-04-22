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
package com.embabel.agent.api.client.model

import com.embabel.agent.api.client.AuthRequirement

/**
 * Unified intermediate representation of an API surface.
 *
 * Both OpenAPI and GraphQL specs parse into this model. Downstream consumers
 * project from it without loss:
 *
 * - **Tool projection** (`[toTools]`) — for LLM tool use
 * - **Interface generation** — for typed client codegen in sandboxes
 * - **Tag filtering** (`[filterByTags]`) — narrows the model before either projection
 *
 * This is the canonical form that gets cached ([LearnedApiSpec] stores the raw
 * spec; reconstruction goes through [ApiModel]).
 */
data class ApiModel(
    val name: String,
    val description: String,
    val baseUrl: String,
    val auth: List<AuthRequirement> = emptyList(),
    val resources: List<ApiResource> = emptyList(),
    val types: Map<String, ApiType> = emptyMap(),
) {

    /**
     * Return a new [ApiModel] containing only the resources whose name
     * matches one of [tags] (case-insensitive). Named types are preserved
     * in full — dead-type elimination is a separate concern.
     */
    fun filterByTags(tags: Set<String>): ApiModel {
        val lowerTags = tags.map { it.lowercase() }.toSet()
        return copy(
            resources = resources.filter { it.name.lowercase() in lowerTags },
        )
    }

    /** All operations across all resources. */
    val allOperations: List<ApiOperation>
        get() = resources.flatMap { it.operations }
}

/**
 * A group of related operations — corresponds to an OpenAPI tag
 * or a GraphQL root type (Query / Mutation).
 */
data class ApiResource(
    val name: String,
    val description: String? = null,
    val operations: List<ApiOperation> = emptyList(),
)

/**
 * A single API operation — one HTTP endpoint or one GraphQL field.
 */
data class ApiOperation(
    val name: String,
    val description: String? = null,
    val method: HttpMethod,
    val path: String,
    val parameters: List<ApiParameter> = emptyList(),
    val requestBody: ApiSchema? = null,
    val responses: Map<String, ApiResponse> = emptyMap(),
    val tags: List<String> = emptyList(),
)

data class ApiResponse(
    val description: String? = null,
    val schema: ApiSchema? = null,
)

data class ApiParameter(
    val name: String,
    val description: String? = null,
    val location: ParameterLocation,
    val required: Boolean = false,
    val schema: ApiSchema,
)

enum class ParameterLocation {
    PATH, QUERY, HEADER, COOKIE,
}

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS,
}

/**
 * Rich schema tree that preserves the full type information from the spec.
 *
 * Unlike [Tool.Parameter] (a flat, lossy projection for LLMs), this retains
 * nested objects, named references, response schemas, and enum definitions.
 */
sealed interface ApiSchema {
    val description: String?

    data class Primitive(
        val type: PrimitiveType,
        val format: String? = null,
        val enumValues: List<String>? = null,
        override val description: String? = null,
    ) : ApiSchema

    data class Object(
        val name: String? = null,
        val properties: List<ApiProperty> = emptyList(),
        val required: Set<String> = emptySet(),
        override val description: String? = null,
    ) : ApiSchema

    data class Array(
        val items: ApiSchema,
        override val description: String? = null,
    ) : ApiSchema

    /**
     * Reference to a named type in [ApiModel.types].
     */
    data class Ref(
        val typeName: String,
        override val description: String? = null,
    ) : ApiSchema
}

enum class PrimitiveType {
    STRING, INTEGER, NUMBER, BOOLEAN,
}

data class ApiProperty(
    val name: String,
    val schema: ApiSchema,
    val description: String? = null,
)

/**
 * A named, reusable type definition — from `components/schemas` in OpenAPI
 * or a named type in GraphQL.
 */
data class ApiType(
    val name: String,
    val schema: ApiSchema,
)
