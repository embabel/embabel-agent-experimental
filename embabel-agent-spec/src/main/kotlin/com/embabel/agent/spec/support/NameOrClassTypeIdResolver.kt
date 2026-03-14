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
package com.embabel.agent.spec.support

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.DatabindContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase

/**
 * A Jackson [com.fasterxml.jackson.databind.jsontype.TypeIdResolver] that supports both:
 * - Short names registered via `@JsonSubTypes` or `@JsonTypeName` (e.g., "action", "goal")
 * - Fully qualified class names (e.g., "com.example.MyImplementation")
 *
 * Resolution order:
 * 1. Short names from `@JsonSubTypes` on the base type (seeded at [init])
 * 2. Names registered via `registerSubtypes()` / `@JsonTypeName`
 * 3. Fully qualified class name via `Class.forName()`
 *
 * This makes polymorphic type hierarchies extensible — any implementation
 * on the classpath can be referenced by FQN without pre-registration.
 *
 * Usage:
 * ```kotlin
 * @JsonTypeInfo(use = JsonTypeInfo.Id.CUSTOM, property = "type")
 * @JsonTypeIdResolver(NameOrClassTypeIdResolver::class)
 * @JsonSubTypes(
 *     JsonSubTypes.Type(value = FooImpl::class, name = "foo"),
 * )
 * interface MyInterface
 * ```
 */
class NameOrClassTypeIdResolver : TypeIdResolverBase() {

    private val nameToType = mutableMapOf<String, Class<*>>()
    private val typeToName = mutableMapOf<Class<*>, String>()

    override fun init(bt: JavaType) {
        super.init(bt)
        // Seed from @JsonSubTypes on the base type
        bt.rawClass.getAnnotation(JsonSubTypes::class.java)?.value?.forEach { sub ->
            nameToType[sub.name] = sub.value.java
            typeToName[sub.value.java] = sub.name
        }
    }

    override fun idFromValue(value: Any): String = idFromClass(value.javaClass)

    override fun idFromValueAndType(value: Any?, suggestedType: Class<*>): String = idFromClass(suggestedType)

    private fun idFromClass(clazz: Class<*>): String {
        typeToName[clazz]?.let { return it }
        clazz.getAnnotation(JsonTypeName::class.java)?.value?.let { name ->
            nameToType[name] = clazz
            typeToName[clazz] = name
            return name
        }
        return clazz.name
    }

    override fun typeFromId(context: DatabindContext, id: String): JavaType {
        // 1. Check registered short names
        nameToType[id]?.let { return context.constructType(it) }

        // 2. If it contains dots, try as FQN
        if (id.contains('.')) {
            return try {
                val clazz = Class.forName(id)
                clazz.getAnnotation(JsonTypeName::class.java)?.value?.let { name ->
                    nameToType[name] = clazz
                }
                nameToType[id] = clazz
                typeToName[clazz] = id
                context.constructType(clazz)
            } catch (e: ClassNotFoundException) {
                throw IllegalArgumentException(
                    "Type id '$id' looks like a class name but was not found on the classpath."
                )
            }
        }

        // 3. Short name not found
        val knownNames = nameToType.keys.sorted()
        throw IllegalArgumentException(
            "Unknown type id '$id'. Known names: $knownNames. " +
                "You can also use a fully qualified class name."
        )
    }

    override fun getMechanism(): JsonTypeInfo.Id = JsonTypeInfo.Id.CUSTOM
}
