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

import com.embabel.agent.api.client.LearnedApiSpec
import com.embabel.agent.api.client.openapi.OpenApiLearner
import com.embabel.agent.api.client.openapi.OpenApiModelBuilder
import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.DomainTypePropertyDefinition
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValuePropertyDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiModelDataDictionaryTest {

    private fun modelFor(resourceName: String): ApiModel {
        val url = javaClass.classLoader.getResource(resourceName)!!.toString()
        val rawSpec = OpenApiLearner.fetchRawSpec(url)
        // Use the ref-preserving parser — the dictionary is meant to carry
        // cross-type links (Pet.category → Category) rather than inlined
        // structural copies. This is the same path
        // [LearnedApiSpec.OpenApi.toModel] uses in production.
        val openApi = OpenApiLearner.parseSpecPreservingRefs(url, rawSpec)
        return OpenApiModelBuilder.build(url, openApi)
    }

    @Test
    fun `spec without components_schemas yields an empty dictionary`() {
        val dict = modelFor("petstore-minimal.json").toDataDictionary()
        assertTrue(dict.domainTypes.isEmpty(), "expected empty dictionary, got ${dict.domainTypes.map { it.name }}")
    }

    @Test
    fun `dictionary name matches the model name so callers can scope namespaces`() {
        val model = modelFor("petstore-with-refs.json")
        assertEquals(model.name, model.toDataDictionary().name)
    }

    @Test
    fun `every named component schema becomes a DynamicType`() {
        val dict = modelFor("petstore-with-refs.json").toDataDictionary()
        val names = dict.domainTypes.map { it.name }.toSet()
        assertEquals(setOf("Pet", "Category"), names)
        for (type in dict.domainTypes) assertInstanceOf(DynamicType::class.java, type)
    }

    @Test
    fun `required primitive properties become ValuePropertyDefinition with cardinality ONE`() {
        val pet = modelFor("petstore-with-refs.json").toDataDictionary()
            .domainTypes.first { it.name == "Pet" }
        // Pet.required = [id, name]
        val id = pet.ownProperties.first { it.name == "id" } as ValuePropertyDefinition
        val name = pet.ownProperties.first { it.name == "name" } as ValuePropertyDefinition
        assertEquals(Cardinality.ONE, id.cardinality)
        assertEquals(Cardinality.ONE, name.cardinality)
        assertEquals("number", id.type) // OpenAPI integer maps to TypeScript-friendly "number"
        assertEquals("string", name.type)
    }

    @Test
    fun `non-required primitive properties become OPTIONAL`() {
        val pet = modelFor("petstore-with-refs.json").toDataDictionary()
            .domainTypes.first { it.name == "Pet" }
        // Pet.tag is in properties but NOT in required
        val tag = pet.ownProperties.first { it.name == "tag" } as ValuePropertyDefinition
        assertEquals(Cardinality.OPTIONAL, tag.cardinality)
    }

    @Test
    fun `cross-type ref properties become DomainTypePropertyDefinition linked by name to the target type`() {
        // Pet.category is `$ref: '#/components/schemas/Category'`. With the
        // ref-preserving parser the IR keeps the link, and the dictionary
        // surfaces it as a DomainTypePropertyDefinition naming Category —
        // so the planner can chain Pet → Category by walking the
        // dictionary, not by structural matching.
        //
        // We assert linkage by name (not by instance identity) because
        // [com.embabel.agent.core.DynamicType] is an immutable `data class`
        // so the embedded `rel.type` is a stub — same name, no own
        // properties — distinct from the fully-populated dictionary entry.
        // This matches Embabel's existing convention: relationships carry
        // a type label, consumers look up full definitions via the
        // dictionary by name (e.g.
        // [com.embabel.agent.core.DataDictionary.domainTypeForLabels]).
        val dict = modelFor("petstore-with-refs.json").toDataDictionary()
        val pet = dict.domainTypes.first { it.name == "Pet" }
        val categoryProp = pet.ownProperties.firstOrNull { it.name == "category" }
            ?: error("Expected Pet.category to be present after ref preservation")
        assertInstanceOf(DomainTypePropertyDefinition::class.java, categoryProp)
        val rel = categoryProp as DomainTypePropertyDefinition
        // Optional in petstore-with-refs (not in Pet.required) so cardinality
        // is OPTIONAL, type names the Category DynamicType.
        assertEquals(Cardinality.OPTIONAL, rel.cardinality)
        assertEquals("Category", rel.type.name)
        // Look up Category via the dictionary to confirm the link is
        // resolvable end-to-end and the populated definition is reachable.
        val category = dict.domainTypeForLabels(setOf("Category"))
            ?: error("Expected Category to be discoverable in the dictionary by name")
        assertEquals("Category", category.name)
        assertTrue(
            category.ownProperties.any { it.name == "id" },
            "Expected populated Category in dictionary to expose `id` property",
        )
    }

    @Test
    fun `dictionary is exposed via LearnedApiSpec_toModel and round trips through the converter`() {
        // Sanity check the wiring: a caller that holds a LearnedApiSpec.OpenApi
        // (the cached form persisted to disk) can reach a DataDictionary
        // without re-running the OpenApi parser themselves.
        val url = javaClass.classLoader.getResource("petstore-with-refs.json")!!.toString()
        val rawSpec = OpenApiLearner.fetchRawSpec(url)
        val spec = LearnedApiSpec.OpenApi(source = url, rawSpec = rawSpec)
        val model = spec.toModel()
        assertNotNull(model)
        val dict = model!!.toDataDictionary()
        assertEquals(setOf("Pet", "Category"), dict.domainTypes.map { it.name }.toSet())
    }
}
