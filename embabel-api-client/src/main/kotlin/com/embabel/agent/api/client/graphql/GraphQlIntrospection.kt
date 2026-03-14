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

/**
 * GraphQL introspection query and data model.
 *
 * Uses a compact introspection approach that works within depth limits
 * imposed by many public GraphQL APIs: first discovers the root query/mutation
 * type names, then fetches fields for each type individually.
 */
object GraphQlIntrospection {

    /**
     * Query to discover root type names.
     */
    val SCHEMA_QUERY = """
        { __schema { queryType { name } mutationType { name } } }
    """.trimIndent()

    /**
     * Query to discover fields of a specific type, with argument types
     * resolved to 4 levels of wrapping (NON_NULL, LIST, etc.).
     */
    fun fieldsQuery(typeName: String) = """
        { __type(name: "$typeName") {
            fields {
                name description
                args {
                    name description
                    type { kind name ofType { kind name ofType { kind name ofType { kind name } } } }
                }
                type { kind name ofType { kind name ofType { kind name ofType { kind name } } } }
            }
        } }
    """.trimIndent()

    /**
     * Shallow query for return types only (no args). Used as fallback
     * for APIs with strict depth limits.
     */
    fun shallowReturnTypesQuery(typeName: String) = """
        { __type(name: "$typeName") {
            fields { name description type { kind name ofType { kind name } } }
        } }
    """.trimIndent()

    /**
     * Shallow query for args only (no return types). Used as fallback
     * for APIs with strict depth limits.
     */
    fun shallowArgsQuery(typeName: String) = """
        { __type(name: "$typeName") {
            fields { name args { name type { kind name } } }
        } }
    """.trimIndent()

    /**
     * Query to discover scalar fields of a return type (for building selection sets).
     */
    fun scalarFieldsQuery(typeName: String) = """
        { __type(name: "$typeName") {
            fields { name type { kind name ofType { kind name } } }
        } }
    """.trimIndent()

    /**
     * Query to discover input object fields.
     */
    fun inputTypeQuery(typeName: String) = """
        { __type(name: "$typeName") {
            inputFields {
                name description
                type { kind name ofType { kind name ofType { kind name } } }
            }
        } }
    """.trimIndent()

    /**
     * Query to discover enum values.
     */
    fun enumQuery(typeName: String) = """
        { __type(name: "$typeName") {
            enumValues { name description }
        } }
    """.trimIndent()
}

/**
 * A GraphQL type reference, potentially wrapped in NON_NULL or LIST.
 */
data class GraphQlTypeRef(
    val kind: String,
    val name: String?,
    val ofType: GraphQlTypeRef? = null,
) {
    /**
     * Unwrap NON_NULL and LIST wrappers to get the leaf type name.
     */
    fun leafName(): String? = when (kind) {
        "NON_NULL", "LIST" -> ofType?.leafName()
        else -> name
    }

    /**
     * Whether the outermost wrapper is NON_NULL.
     */
    fun isNonNull(): Boolean = kind == "NON_NULL"

    /**
     * Whether this type is a list (possibly wrapped in NON_NULL).
     */
    fun isList(): Boolean = when (kind) {
        "LIST" -> true
        "NON_NULL" -> ofType?.isList() == true
        else -> false
    }

    /**
     * The leaf kind (SCALAR, OBJECT, ENUM, INPUT_OBJECT, etc.).
     */
    fun leafKind(): String = when (kind) {
        "NON_NULL", "LIST" -> ofType?.leafKind() ?: kind
        else -> kind
    }
}

data class GraphQlField(
    val name: String,
    val description: String?,
    val args: List<GraphQlArgument> = emptyList(),
    val type: GraphQlTypeRef,
)

data class GraphQlArgument(
    val name: String,
    val description: String?,
    val type: GraphQlTypeRef,
)

data class GraphQlInputField(
    val name: String,
    val description: String?,
    val type: GraphQlTypeRef,
)

data class GraphQlEnumValue(
    val name: String,
    val description: String?,
)
