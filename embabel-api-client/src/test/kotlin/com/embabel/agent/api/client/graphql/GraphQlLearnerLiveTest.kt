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

    // --- Countries API (queries only, no auth) ---

    private val countriesEndpoint = "https://countries.trevorblades.com/graphql"

    @Test
    fun `countries - inspect`() {
        val learned = learner.learn(countriesEndpoint)
        println("Name: ${learned.name}")
        println("Description: ${learned.description}")
        println("Auth: ${learned.authRequirements}")
        assertEquals("countries_trevorblades_com", learned.name)
    }

    @Test
    fun `countries - tool tree`() {
        val tool = learner.learn(countriesEndpoint).create()
        println(Tool.formatToolTree("countries", listOf(tool)))
        assertInstanceOf(ProgressiveTool::class.java, tool)
    }

    @Test
    fun `countries - tool names are valid`() {
        val tool = learner.learn(countriesEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val validPattern = Regex("^[a-zA-Z0-9_-]+$")
        allTools.forEach {
            assertTrue(it.definition.name.matches(validPattern)) {
                "Tool name '${it.definition.name}' contains invalid characters"
            }
        }
        println("Countries tools: ${allTools.map { it.definition.name }}")
    }

    @Test
    fun `countries - GET country by code`() {
        val tool = learner.learn(countriesEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val countryTool = allTools.find { it.definition.name == "country" }
        assertNotNull(countryTool) {
            "No 'country' tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = countryTool!!.call("""{"code": "US"}""")
        assertSuccess(result, "country")
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("United States")) { "Expected US data, got: $text" }
    }

    @Test
    fun `countries - GET continents`() {
        val tool = learner.learn(countriesEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val continentsTool = allTools.find { it.definition.name == "continents" }
        assertNotNull(continentsTool) {
            "No 'continents' tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = continentsTool!!.call("")
        assertSuccess(result, "continents")
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("Africa") || text.contains("Europe") || text.contains("Asia")) {
            "Expected continent names, got: $text"
        }
    }

    @Test
    fun `countries - GET language`() {
        val tool = learner.learn(countriesEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val langTool = allTools.find { it.definition.name == "language" }
        assertNotNull(langTool) {
            "No 'language' tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = langTool!!.call("""{"code": "en"}""")
        assertSuccess(result, "language")
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("English")) { "Expected English language data, got: $text" }
    }

    // --- Rick and Morty API (queries only, no auth, strict depth limits) ---

    private val rickAndMortyEndpoint = "https://rickandmortyapi.com/graphql"

    @Test
    fun `rickandmorty - inspect`() {
        val learned = learner.learn(rickAndMortyEndpoint)
        println("Name: ${learned.name}")
        println("Description: ${learned.description}")
        println("Auth: ${learned.authRequirements}")
        assertEquals("rickandmortyapi_com", learned.name)
    }

    @Test
    fun `rickandmorty - tool tree`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        println(Tool.formatToolTree("rickandmorty", listOf(tool)))
        assertInstanceOf(ProgressiveTool::class.java, tool)
    }

    @Test
    fun `rickandmorty - tool names are valid`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val validPattern = Regex("^[a-zA-Z0-9_-]+$")
        allTools.forEach {
            assertTrue(it.definition.name.matches(validPattern)) {
                "Tool name '${it.definition.name}' contains invalid characters"
            }
        }
        println("Rick and Morty tools: ${allTools.map { it.definition.name }}")
    }

    @Test
    fun `rickandmorty - GET character by id`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val charTool = allTools.find { it.definition.name == "character" }
        assertNotNull(charTool) {
            "No 'character' tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = charTool!!.call("""{"id": "1"}""")
        assertSuccess(result, "character")
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("Rick") || text.contains("Sanchez")) { "Expected Rick Sanchez data, got: $text" }
    }

    @Test
    fun `rickandmorty - GET episode by id`() {
        val tool = learner.learn(rickAndMortyEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val epTool = allTools.find { it.definition.name == "episode" }
        assertNotNull(epTool) {
            "No 'episode' tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = epTool!!.call("""{"id": "1"}""")
        assertSuccess(result, "episode")
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("Pilot") || text.contains("S01E01") || text.contains("name")) {
            "Expected episode data, got: $text"
        }
    }

    // --- GraphQLZero (queries + mutations, no auth) ---

    private val graphQlZeroEndpoint = "https://graphqlzero.almansi.me/api"

    @Test
    fun `graphqlzero - inspect`() {
        val learned = learner.learn(graphQlZeroEndpoint)
        println("Name: ${learned.name}")
        println("Auth: ${learned.authRequirements}")
        assertNotNull(learned.name)
    }

    @Test
    fun `graphqlzero - has queries and mutations`() {
        val tool = learner.learn(graphQlZeroEndpoint).create()
        println(Tool.formatToolTree("graphqlzero", listOf(tool)))
        // Should use byCategory since mutations exist
        assertInstanceOf(ProgressiveTool::class.java, tool)
    }

    @Test
    fun `graphqlzero - GET post by id`() {
        val tool = learner.learn(graphQlZeroEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val postTool = allTools.find { it.definition.name == "post" }
        assertNotNull(postTool) {
            "No 'post' tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = postTool!!.call("""{"id": "1"}""")
        assertSuccess(result, "post")
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("title") || text.contains("body") || text.contains("id")) {
            "Expected post data, got: $text"
        }
    }

    @Test
    fun `graphqlzero - GET user by id`() {
        val tool = learner.learn(graphQlZeroEndpoint).create()
        val allTools = collectAllLeafTools(tool)
        val userTool = allTools.find { it.definition.name == "user" }
        assertNotNull(userTool) {
            "No 'user' tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = userTool!!.call("""{"id": "1"}""")
        assertSuccess(result, "user")
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("name") || text.contains("email") || text.contains("Leanne")) {
            "Expected user data, got: $text"
        }
    }

    // --- Helpers ---

    private fun assertSuccess(result: Tool.Result?, toolName: String) {
        assertNotNull(result) { "$toolName returned null" }
        if (result is Tool.Result.Error) {
            fail<Unit>("$toolName failed: ${result.message}")
        }
        assertInstanceOf(Tool.Result.Text::class.java, result)
        println("$toolName OK: ${(result as Tool.Result.Text).content.take(200)}")
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
