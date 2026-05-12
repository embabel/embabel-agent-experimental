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

import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DomainTypePropertyDefinition
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.PropertyDefinition
import com.embabel.agent.core.ValuePropertyDefinition

/**
 * Project an [ApiModel]'s named types ([ApiModel.types]) into an Embabel
 * [DataDictionary] of [DynamicType]s.
 *
 * Each named OpenAPI / GraphQL type becomes a [DynamicType] inside the
 * dictionary, with properties mapped from the spec:
 *
 * | OpenAPI / IR                     | DomainType                                   |
 * |----------------------------------|----------------------------------------------|
 * | Primitive (string / number / …)  | [ValuePropertyDefinition], cardinality ONE   |
 * | Array of primitive               | [ValuePropertyDefinition], cardinality LIST  |
 * | $ref to named type T             | [DomainTypePropertyDefinition], type = T     |
 * | Array of $ref to T               | [DomainTypePropertyDefinition], LIST, type=T |
 * | Inline anonymous object          | omitted from properties (lossy in v1)        |
 *
 * Optional vs. required is read from the enclosing object's `required` set:
 * a property NOT listed there gets [Cardinality.OPTIONAL] for scalar shapes.
 *
 * The dictionary's [DataDictionary.name] is the [ApiModel.name], so callers
 * (e.g. a per-pack `LearnedApi` aggregator) can scope namespaces by source.
 *
 * Cross-type references (`Pet.category` → `Category`) survive when the IR
 * is built from a model parsed via
 * [com.embabel.agent.api.client.openapi.OpenApiLearner.parseSpecPreservingRefs]
 * — the path [com.embabel.agent.api.client.LearnedApiSpec.OpenApi.toModel]
 * uses in production. Inline anonymous objects (request/response bodies
 * with no name) are still dropped from the dictionary in this version;
 * lifting them would require synthesising satellite type names and is a
 * separate decision.
 */
fun ApiModel.toDataDictionary(): DataDictionary {
    // Pre-build a registry keyed by name so $ref-to-T properties can resolve
    // to the same DynamicType instance the dictionary exposes top-level.
    // Two-pass: stub each named type with no properties, then fill them in.
    // Lets a self-referential schema (e.g. tree-shaped `StructuralElement`)
    // resolve cleanly without infinite recursion at conversion time.
    val stubs: MutableMap<String, DynamicType> = types.entries.associateTo(LinkedHashMap()) { (name, apiType) ->
        name to DynamicType(name = name, description = apiType.schema.description ?: name)
    }
    val resolved: MutableMap<String, DynamicType> = LinkedHashMap()
    for ((name, apiType) in types) {
        val properties = propertiesOf(apiType.schema, stubs)
        resolved[name] = stubs[name]!!.copy(ownProperties = properties)
    }
    return DataDictionary.fromDomainTypes(name, resolved.values.toList())
}

private fun propertiesOf(
    schema: ApiSchema,
    stubs: Map<String, DynamicType>,
): List<PropertyDefinition> {
    if (schema !is ApiSchema.Object) return emptyList()
    return schema.properties.mapNotNull { property ->
        toPropertyDefinition(property, schema.required, stubs)
    }
}

private fun toPropertyDefinition(
    property: ApiProperty,
    required: Set<String>,
    stubs: Map<String, DynamicType>,
): PropertyDefinition? {
    val isRequired = property.name in required
    val description = property.description ?: property.name
    return when (val s = property.schema) {
        is ApiSchema.Primitive -> ValuePropertyDefinition(
            name = property.name,
            type = primitiveTypeName(s.type),
            cardinality = if (isRequired) Cardinality.ONE else Cardinality.OPTIONAL,
            description = description,
        )

        is ApiSchema.Ref -> {
            val target = stubs[s.typeName] ?: return null
            DomainTypePropertyDefinition(
                name = property.name,
                type = target,
                cardinality = if (isRequired) Cardinality.ONE else Cardinality.OPTIONAL,
                description = description,
            )
        }

        is ApiSchema.Array -> arrayProperty(property.name, s, description, stubs)

        // Inline anonymous object — lossy in v1. Skip the property from the
        // dictionary rather than synthesising an unnamed satellite type.
        // Phase 2 will lift these into named satellites or preserve refs end
        // to end so anonymous objects become rare in practice.
        is ApiSchema.Object -> null
    }
}

private fun arrayProperty(
    name: String,
    array: ApiSchema.Array,
    description: String,
    stubs: Map<String, DynamicType>,
): PropertyDefinition? = when (val items = array.items) {
    is ApiSchema.Primitive -> ValuePropertyDefinition(
        name = name,
        type = primitiveTypeName(items.type),
        cardinality = Cardinality.LIST,
        description = description,
    )

    is ApiSchema.Ref -> {
        val target = stubs[items.typeName] ?: return null
        DomainTypePropertyDefinition(
            name = name,
            type = target,
            cardinality = Cardinality.LIST,
            description = description,
        )
    }

    // array-of-array and array-of-anonymous-object lose information in v1.
    // Cardinality.LIST + a placeholder string type is the closest honest
    // representation we can emit without lifting anonymous types.
    else -> null
}

private fun primitiveTypeName(type: PrimitiveType): String = when (type) {
    PrimitiveType.STRING -> "string"
    PrimitiveType.INTEGER -> "number"
    PrimitiveType.NUMBER -> "number"
    PrimitiveType.BOOLEAN -> "boolean"
}
