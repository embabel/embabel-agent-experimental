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

/**
 * Convert an [ApiSchema] to a JSON Schema map representation.
 *
 * Used by tool metadata (output schemas) and interface generation.
 * This is a lossless conversion — the full schema tree is preserved.
 */
fun apiSchemaToJsonSchema(schema: ApiSchema): Map<String, Any?> = buildMap {
    when (schema) {
        is ApiSchema.Primitive -> {
            put("type", schema.type.name.lowercase())
            schema.format?.let { put("format", it) }
            schema.enumValues?.let { put("enum", it) }
        }
        is ApiSchema.Object -> {
            put("type", "object")
            if (schema.properties.isNotEmpty()) {
                put("properties", schema.properties.associate { it.name to apiSchemaToJsonSchema(it.schema) })
            }
            if (schema.required.isNotEmpty()) {
                put("required", schema.required.toList())
            }
        }
        is ApiSchema.Array -> {
            put("type", "array")
            put("items", apiSchemaToJsonSchema(schema.items))
        }
        is ApiSchema.Ref -> {
            put("\$ref", "#/components/schemas/${schema.typeName}")
        }
    }
    schema.description?.let { put("description", it) }
}
