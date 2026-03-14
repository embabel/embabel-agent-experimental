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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import com.embabel.agent.core.AgentProcess
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * Live integration tests for [GraphQlLearner] against public GraphQL APIs.
 * Tagged so they can be excluded from CI builds.
 */
@Tag("live")
class GraphQlLearnerLiveTest {

    private val learner = GraphQlLearner()
    private val objectMapper = jacksonObjectMapper()

    // --- Countries API (queries only, no auth) ---

    private val countriesEndpoint = "https://countries.trevorblades.com/graphql"

    @Test
    fun `countries - inspect`() {
        val learned = learner.learn(countriesEndpoint)
        assertEquals("countries_trevorblades_com", learned.name)
        assertTrue(learned.description.contains("GraphQL"))
    }

    @Test
    fun `countries - tool tree is progressive`() {
        val tool = learner.learn(countriesEndpoint).create()
        assertInstanceOf(ProgressiveTool::class.java, tool)
        val allTools = collectAllLeafTools(tool)
        assertTrue(allTools.size >= 3) { "Expected at least 3 tools (country, countries, continents), got ${allTools.size}" }
    }

    @Test
    fun `countries - all tool names are valid for LLM APIs`() {
        val tool = learner.learn(countriesEndpoint).create()
        assertAllToolNamesValid(tool)
    }

    @Test
    fun `countries - all tool schemas are valid JSON`() {
        val tool = learner.learn(countriesEndpoint).create()
        assertAllSchemasValid(tool)
    }

    @Test
    fun `countries - GET country by code returns expected fields`() {
        val tool = learner.learn(countriesEndpoint).create()
        val countryTool = findTool(tool, "country")
        val result = countryTool.call("""{"code": "US"}""")
        val json = assertSuccessJson(result, "country")
        assertTrue(json.containsKey("name")) { "Expected 'name' field in country, got: $json" }
        assertEquals("United States", json["name"]) { "Expected country name 'United States'" }
    }

    @Test
    fun `countries - GET continents returns array with known continents`() {
        val tool = learner.learn(countriesEndpoint).create()
        val continentsTool = findTool(tool, "continents")
        val result = continentsTool.call("")
        assertSuccess(result, "continents")
        val text = (result as Tool.Result.Text).content
        val list = objectMapper.readValue(text, List::class.java)
        assertTrue(list.size >= 7) { "Expected at least 7 continents, got ${list.size}" }
    }

    @Test
    fun `countries - GET language returns English`() {
        val tool = learner.learn(countriesEndpoint).create()
        val langTool = findTool(tool, "language")
        val result = langTool.call("""{"code": "en"}""")
        val json = assertSuccessJson(result, "language")
        assertEquals("English", json["name"]) { "Expected language name 'English', got: ${json["name"]}" }
    }

    // --- Rick and Morty API (queries only, no auth, strict depth limits) ---

    private val rickAndMortyEndpoint = "https://rickandmortyapi.com/graphql"

    @Test
    fun `rickandmorty - inspect`() {
        val learned = learner.learn(rickAndMortyEndpoint)
        assertEquals("rickandmortyapi_com", learned.name)
    }

    @Test
    fun `rickandmorty - tool tree is progressive`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        assertInstanceOf(ProgressiveTool::class.java, tool)
        val allTools = collectAllLeafTools(tool)
        assertTrue(allTools.size >= 5) {
            "Expected at least 5 tools (character, characters, episode, episodes, location, locations, etc.), got ${allTools.size}: ${allTools.map { it.definition.name }}"
        }
    }

    @Test
    fun `rickandmorty - all tool names are valid for LLM APIs`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        assertAllToolNamesValid(tool)
    }

    @Test
    fun `rickandmorty - all tool schemas are valid JSON with items for arrays`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        assertAllSchemasValid(tool)
    }

    @Test
    fun `rickandmorty - charactersByIds has valid array schema with items`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val byIdsTool = allTools.find { it.definition.name == "charactersByIds" }
        assertNotNull(byIdsTool) {
            "No 'charactersByIds' tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val schema = parseSchema(byIdsTool!!.definition.inputSchema.toJsonSchema())
        @Suppress("UNCHECKED_CAST")
        val properties = schema["properties"] as Map<String, Any?>
        assertTrue(properties.containsKey("ids")) { "Expected 'ids' property in charactersByIds schema" }
        @Suppress("UNCHECKED_CAST")
        val idsSchema = properties["ids"] as Map<String, Any?>
        assertEquals("array", idsSchema["type"]) { "Expected ids to be array type" }
        assertTrue(idsSchema.containsKey("items")) {
            "Array parameter 'ids' missing 'items' — this causes LLM API 400 errors. Schema: $idsSchema"
        }
    }

    @Test
    fun `rickandmorty - GET character by id returns Rick Sanchez`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        val charTool = findTool(tool, "character")
        val result = charTool.call("""{"id": "1"}""")
        val json = assertSuccessJson(result, "character")
        assertTrue(json["name"]?.toString()?.contains("Rick") == true) {
            "Expected Rick Sanchez, got: ${json["name"]}"
        }
    }

    @Test
    fun `rickandmorty - GET episode by id returns Pilot`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        val epTool = findTool(tool, "episode")
        val result = epTool.call("""{"id": "1"}""")
        val json = assertSuccessJson(result, "episode")
        assertEquals("Pilot", json["name"]) { "Expected episode name 'Pilot', got: ${json["name"]}" }
    }

    @Test
    fun `rickandmorty - GET episodes with pagination returns results`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        val episodesTool = findTool(tool, "episodes")
        val result = episodesTool.call("""{"page": 1}""")
        assertSuccess(result, "episodes")
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("Pilot") || text.contains("name") || text.contains("results")) {
            "Expected episode data from paginated query, got: $text"
        }
    }

    @Test
    fun `rickandmorty - GET characters with pagination returns results`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        val charsTool = findTool(tool, "characters")
        val result = charsTool.call("""{"page": 1}""")
        assertSuccess(result, "characters")
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("Rick") || text.contains("name") || text.contains("results")) {
            "Expected character data from paginated query, got: $text"
        }
    }

    @Test
    fun `rickandmorty - GET location by id`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        val locTool = findTool(tool, "location")
        val result = locTool.call("""{"id": "1"}""")
        val json = assertSuccessJson(result, "location")
        assertTrue(json.containsKey("name")) { "Expected 'name' field in location, got: $json" }
        assertEquals("Earth (C-137)", json["name"]) { "Expected location 'Earth (C-137)'" }
    }

    // --- GraphQLZero (queries + mutations, no auth) ---

    private val graphQlZeroEndpoint = "https://graphqlzero.almansi.me/api"

    @Test
    fun `graphqlzero - inspect`() {
        val learned = learner.learn(graphQlZeroEndpoint)
        assertNotNull(learned.name)
        assertTrue(learned.name.isNotBlank())
    }

    @Test
    fun `graphqlzero - has queries and mutations in categories`() {
        val tool = learner.learn(graphQlZeroEndpoint).create()
        assertInstanceOf(ProgressiveTool::class.java, tool)
        val allTools = collectAllLeafTools(tool)
        assertTrue(allTools.size >= 5) {
            "Expected at least 5 tools (posts, post, users, user, albums, etc.), got ${allTools.size}: ${allTools.map { it.definition.name }}"
        }
    }

    @Test
    fun `graphqlzero - all tool names are valid for LLM APIs`() {
        val tool = learner.learn(graphQlZeroEndpoint).create()
        assertAllToolNamesValid(tool)
    }

    @Test
    fun `graphqlzero - all tool schemas are valid JSON`() {
        val tool = learner.learn(graphQlZeroEndpoint).create()
        assertAllSchemasValid(tool)
    }

    @Test
    fun `graphqlzero - GET post by id returns post data`() {
        val tool = learner.learn(graphQlZeroEndpoint).create()
        val postTool = findTool(tool, "post")
        val result = postTool.call("""{"id": "1"}""")
        val json = assertSuccessJson(result, "post")
        assertTrue(json.containsKey("title")) { "Expected 'title' field in post, got: $json" }
        assertTrue(json.containsKey("body")) { "Expected 'body' field in post, got: $json" }
    }

    @Test
    fun `graphqlzero - GET user by id returns user data`() {
        val tool = learner.learn(graphQlZeroEndpoint).create()
        val userTool = findTool(tool, "user")
        val result = userTool.call("""{"id": "1"}""")
        val json = assertSuccessJson(result, "user")
        assertTrue(json.containsKey("name")) { "Expected 'name' field in user, got: $json" }
        assertTrue(json.containsKey("email")) { "Expected 'email' field in user, got: $json" }
    }

    // --- SWAPI (Star Wars API, queries only, no auth) ---

    private val swapiEndpoint = "https://swapi-graphql.netlify.app/.netlify/functions/graphql"

    @Test
    fun `swapi - inspect`() {
        val learned = learner.learn(swapiEndpoint)
        assertNotNull(learned.name)
        assertTrue(learned.name.isNotBlank())
        assertTrue(learned.description.contains("GraphQL"))
    }

    @Test
    fun `swapi - all tool names are valid for LLM APIs`() {
        val tool = learner.learn(swapiEndpoint).create()
        assertAllToolNamesValid(tool)
    }

    @Test
    fun `swapi - all tool schemas are valid JSON`() {
        val tool = learner.learn(swapiEndpoint).create()
        assertAllSchemasValid(tool)
    }

    @Test
    fun `swapi - has expected tools`() {
        val tool = learner.learn(swapiEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val names = allTools.map { it.definition.name }
        assertTrue(names.contains("person") || names.contains("allPeople")) {
            "Expected person/allPeople tool. Available: $names"
        }
        assertTrue(names.contains("film") || names.contains("allFilms")) {
            "Expected film/allFilms tool. Available: $names"
        }
    }

    @Test
    fun `swapi - GET person by id returns Luke Skywalker`() {
        val tool = learner.learn(swapiEndpoint).create()
        val personTool = findTool(tool, "person")
        val result = personTool.call("""{"personID": "1"}""")
        val json = assertSuccessJson(result, "person")
        assertEquals("Luke Skywalker", json["name"]) { "Expected Luke Skywalker, got: ${json["name"]}" }
    }

    @Test
    fun `swapi - GET film by id`() {
        val tool = learner.learn(swapiEndpoint).create()
        val filmTool = findTool(tool, "film")
        val result = filmTool.call("""{"filmID": "1"}""")
        val json = assertSuccessJson(result, "film")
        assertTrue(json.containsKey("title")) { "Expected 'title' field in film, got: $json" }
        assertEquals("A New Hope", json["title"]) { "Expected 'A New Hope', got: ${json["title"]}" }
    }

    // --- Pokemon GraphQL API (queries only, no auth) ---

    private val pokemonEndpoint = "https://graphql-pokeapi.graphcdn.app/"

    @Test
    fun `pokemon - inspect`() {
        val learned = learner.learn(pokemonEndpoint)
        assertNotNull(learned.name)
        assertTrue(learned.name.isNotBlank())
    }

    @Test
    fun `pokemon - all tool names are valid for LLM APIs`() {
        val tool = learner.learn(pokemonEndpoint).create()
        assertAllToolNamesValid(tool)
    }

    @Test
    fun `pokemon - all tool schemas are valid JSON`() {
        val tool = learner.learn(pokemonEndpoint).create()
        assertAllSchemasValid(tool)
    }

    @Test
    fun `pokemon - has expected tools`() {
        val tool = learner.learn(pokemonEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val names = allTools.map { it.definition.name }
        assertTrue(names.contains("pokemon") || names.contains("pokemons")) {
            "Expected pokemon/pokemons tool. Available: $names"
        }
    }

    @Test
    fun `pokemon - GET pokemon by name returns pikachu`() {
        val tool = learner.learn(pokemonEndpoint).create()
        val pokemonTool = findTool(tool, "pokemon")
        val result = pokemonTool.call("""{"name": "pikachu"}""")
        val json = assertSuccessJson(result, "pokemon")
        assertEquals("pikachu", json["name"]) { "Expected pikachu, got: ${json["name"]}" }
    }

    // --- Helpers ---

    private fun findTool(root: Tool, name: String): Tool {
        val allTools = collectAllLeafTools(root)
        return allTools.find { it.definition.name == name }
            ?: fail("No '$name' tool found. Available: ${allTools.map { it.definition.name }}")
    }

    private fun assertSuccess(result: Tool.Result?, toolName: String) {
        assertNotNull(result) { "$toolName returned null" }
        if (result is Tool.Result.Error) {
            fail<Unit>("$toolName failed: ${result.message}")
        }
        assertInstanceOf(Tool.Result.Text::class.java, result)
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertSuccessJson(result: Tool.Result?, toolName: String): Map<String, Any?> {
        assertSuccess(result, toolName)
        val text = (result as Tool.Result.Text).content
        assertFalse(text.isBlank()) { "$toolName returned blank content" }
        return try {
            objectMapper.readValue(text, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            fail("$toolName returned non-JSON content: ${text.take(200)}")
        }
    }

    private fun assertAllToolNamesValid(root: Tool) {
        val allTools = collectAllLeafTools(root)
        val validPattern = Regex("^[a-zA-Z0-9_-]+$")
        allTools.forEach {
            assertTrue(it.definition.name.matches(validPattern)) {
                "Tool name '${it.definition.name}' contains invalid characters"
            }
            assertTrue(it.definition.name.length <= 64) {
                "Tool name '${it.definition.name}' exceeds 64 char limit (${it.definition.name.length})"
            }
        }
    }

    private fun assertAllSchemasValid(root: Tool) {
        val allTools = collectAllLeafTools(root)
        allTools.forEach { tool ->
            val schemaJson = tool.definition.inputSchema.toJsonSchema()
            val schema = try {
                parseSchema(schemaJson)
            } catch (e: Exception) {
                fail<Map<String, Any?>>("Tool '${tool.definition.name}' has invalid JSON schema: $schemaJson")
            }
            assertEquals("object", schema["type"]) {
                "Tool '${tool.definition.name}' schema type should be 'object'"
            }
            @Suppress("UNCHECKED_CAST")
            val properties = schema["properties"] as? Map<String, Any?> ?: emptyMap()
            properties.forEach { (propName, propValue) ->
                @Suppress("UNCHECKED_CAST")
                val propSchema = propValue as? Map<String, Any?> ?: return@forEach
                if (propSchema["type"] == "array") {
                    assertTrue(propSchema.containsKey("items")) {
                        "Tool '${tool.definition.name}' parameter '$propName' is array but missing 'items' — " +
                            "this causes LLM API 400 errors. Schema: $propSchema"
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSchema(json: String): Map<String, Any?> {
        return objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
    }

    private fun collectAllLeafTools(tool: Tool): List<Tool> {
        return when (tool) {
            is ProgressiveTool -> {
                val inner = tool.innerTools(
                    Mockito.mock(AgentProcess::class.java),
                )
                inner.flatMap { collectAllLeafTools(it) }
            }
            else -> listOf(tool)
        }
    }
}
