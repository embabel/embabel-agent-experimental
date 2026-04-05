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

import com.embabel.agent.api.client.ApiCredentials
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Live integration tests against public APIs.
 * Tagged so they can be excluded from CI builds.
 *
 * These tests verify:
 * - Spec parsing (Swagger 2.0, OpenAPI 3.0, 3.1)
 * - Base URL resolution (top-level servers, path-level servers, relative URLs)
 * - Tool materialization (operation naming, parameter mapping, tag grouping)
 * - Actual HTTP calls (GET, POST with various parameter types)
 * - Raw spec caching (fetchRawSpec + parseSpec from content)
 */
@Tag("live")
class OpenApiLearnerLiveTest {

    private val learner = OpenApiLearner()

    // =====================================================================
    // Petstore v2 (Swagger 2.0, auto-converted to OpenAPI 3.0)
    // =====================================================================

    private val petstoreV2Spec = "https://petstore.swagger.io/v2/swagger.json"

    @Test
    fun `petstore-v2 - inspect`() {
        val learned = learner.learn(petstoreV2Spec)
        assertEquals("swagger_petstore", learned.name)
        assertTrue(learned.authRequirements.isNotEmpty())
        assertNotNull(learned.spec, "LearnedApi should include a spec for serialization")
    }

    @Test
    fun `petstore-v2 - tool tree`() {
        val tool = learner.learn(petstoreV2Spec).create()
        println(Tool.formatToolTree("petstore-v2", listOf(tool)))
        assertInstanceOf(ProgressiveTool::class.java, tool)
    }

    @Test
    fun `petstore-v2 - GET findPetsByStatus`() {
        val tool = learner.learn(petstoreV2Spec).create()
        val result = callTool(tool, "findPetsByStatus", """{"status": "available"}""")
        assertSuccess(result, "findPetsByStatus")
    }

    @Test
    fun `petstore-v2 - GET getInventory`() {
        val tool = learner.learn(petstoreV2Spec).create()
        val result = callTool(tool, "getInventory", "")
        assertSuccess(result, "getInventory")
    }

    @Test
    fun `petstore-v2 - POST addPet`() {
        val tool = learner.learn(petstoreV2Spec).create()
        val result = callTool(
            tool, "addPet",
            """{"body": {"name": "Embabel Test Dog", "photoUrls": ["https://example.com/dog.jpg"], "status": "available"}}""",
        )
        assertSuccess(result, "addPet")
        assertTrue((result as Tool.Result.Text).content.contains("Embabel Test Dog"))
    }

    // =====================================================================
    // Petstore v3 (OpenAPI 3.0.4)
    // =====================================================================

    private val petstoreV3Spec = "https://petstore3.swagger.io/api/v3/openapi.json"

    @Test
    fun `petstore-v3 - inspect`() {
        val learned = learner.learn(petstoreV3Spec)
        println("Name: ${learned.name}")
        println("Description: ${learned.description}")
        assertNotNull(learned.name)
        assertNotNull(learned.spec)
    }

    @Test
    fun `petstore-v3 - base URL resolves to petstore3`() {
        val rawSpec = OpenApiLearner.fetchRawSpec(petstoreV3Spec)
        val openApi = OpenApiLearner.parseSpec(petstoreV3Spec, rawSpec)
        val baseUrl = OpenApiLearner.resolveBaseUrl(openApi, petstoreV3Spec)
        println("Petstore v3 baseUrl: $baseUrl")
        assertTrue(baseUrl.contains("petstore3.swagger.io") || baseUrl.contains("petstore.swagger.io")) {
            "Base URL should point to petstore: $baseUrl"
        }
    }

    @Test
    fun `petstore-v3 - GET findPetsByStatus`() {
        val tool = learner.learn(petstoreV3Spec).create()
        val result = callTool(tool, "findPetsByStatus", """{"status": "available"}""")
        assertSuccess(result, "findPetsByStatus")
    }

    @Test
    fun `petstore-v3 - tool names are valid identifiers`() {
        val tool = learner.learn(petstoreV3Spec).create()
        val allTools = collectAllLeafTools(tool)
        val validPattern = Regex("^[a-zA-Z0-9_-]+$")
        allTools.forEach {
            assertTrue(it.definition.name.matches(validPattern)) {
                "Tool name '${it.definition.name}' contains invalid characters"
            }
        }
        assertTrue(allTools.size >= 10) { "Expected at least 10 operations, got ${allTools.size}" }
        println("Petstore v3 tools (${allTools.size}): ${allTools.map { it.definition.name }}")
    }

    // =====================================================================
    // FakeRestAPI (OpenAPI 3.0.1, full CRUD, no auth)
    // =====================================================================

    private val fakeRestApiSpec = "https://fakerestapi.azurewebsites.net/swagger/v1/swagger.json"

    @Test
    fun `fakerestapi - inspect`() {
        val learned = learner.learn(fakeRestApiSpec)
        assertNotNull(learned.name)
        assertNotNull(learned.spec)
    }

    @Test
    fun `fakerestapi - tool tree`() {
        val tool = learner.learn(fakeRestApiSpec).create()
        println(Tool.formatToolTree("fakerestapi", listOf(tool)))
    }

    @Test
    fun `fakerestapi - GET activities`() {
        val tool = learner.learn(fakeRestApiSpec).create()
        val allTools = collectAllLeafTools(tool)
        val activitiesTool = allTools.find {
            it.definition.name.lowercase().contains("activit") &&
                !it.definition.name.lowercase().contains("id")
        }
        assertNotNull(activitiesTool) {
            "No activities GET tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = activitiesTool!!.call("")
        assertSuccess(result, activitiesTool.definition.name)
    }

    @Test
    fun `fakerestapi - POST activity`() {
        val tool = learner.learn(fakeRestApiSpec).create()
        val allTools = collectAllLeafTools(tool)
        val postTool = allTools.find {
            it.definition.name.lowercase().contains("activit") &&
                it.definition.name.lowercase().contains("post")
        }
        assertNotNull(postTool) {
            "No activities POST tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = postTool!!.call(
            """{"body": {"id": 0, "title": "Embabel Test", "dueDate": "2026-01-01T00:00:00", "completed": false}}""",
        )
        assertSuccess(result, postTool.definition.name)
        assertTrue((result as Tool.Result.Text).content.contains("Embabel Test"))
    }

    // =====================================================================
    // httpbin (Swagger 2.0, echo service)
    // =====================================================================

    private val httpbinSpec = "https://httpbin.org/spec.json"

    @Test
    fun `httpbin - inspect`() {
        val learned = learner.learn(httpbinSpec)
        assertNotNull(learned.name)
        assertNotNull(learned.spec)
    }

    @Test
    fun `httpbin - GET ip`() {
        val tool = learner.learn(httpbinSpec).create()
        val allTools = collectAllLeafTools(tool)
        val ipTool = allTools.find { it.definition.name.lowercase() == "get_ip" || it.definition.name == "get__ip" }
        assertNotNull(ipTool) {
            "No ip tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = ipTool!!.call("")
        assertSuccess(result, "ip")
        assertTrue((result as Tool.Result.Text).content.contains("origin"))
    }

    @Test
    fun `httpbin - GET uuid`() {
        val tool = learner.learn(httpbinSpec).create()
        val allTools = collectAllLeafTools(tool)
        val uuidTool = allTools.find { it.definition.name.lowercase().contains("uuid") }
        assertNotNull(uuidTool) {
            "No uuid tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = uuidTool!!.call("")
        assertSuccess(result, "uuid")
        assertTrue((result as Tool.Result.Text).content.contains("uuid"))
    }

    // =====================================================================
    // XKCD (OpenAPI 3.0, no auth, dots in paths, GitHub-hosted spec)
    // =====================================================================

    private val xkcdSpec = "https://raw.githubusercontent.com/APIs-guru/openapi-directory/main/APIs/xkcd.com/1.0.0/openapi.yaml"

    @Test
    fun `xkcd - inspect`() {
        val learned = learner.learn(xkcdSpec)
        assertEquals("xkcd", learned.name)
        assertNotNull(learned.spec)
    }

    @Test
    fun `xkcd - base URL resolves to xkcd not github`() {
        val rawSpec = OpenApiLearner.fetchRawSpec(xkcdSpec)
        val openApi = OpenApiLearner.parseSpec(xkcdSpec, rawSpec)
        val baseUrl = OpenApiLearner.resolveBaseUrl(openApi, xkcdSpec)
        println("XKCD baseUrl: $baseUrl")
        assertTrue(baseUrl.contains("xkcd.com")) {
            "Base URL should be xkcd.com but was: $baseUrl"
        }
    }

    @Test
    fun `xkcd - tool names are valid`() {
        val tool = learner.learn(xkcdSpec).create()
        val allTools = collectAllLeafTools(tool)
        val validPattern = Regex("^[a-zA-Z0-9_-]+$")
        allTools.forEach {
            assertTrue(it.definition.name.matches(validPattern)) {
                "Tool name '${it.definition.name}' contains invalid characters"
            }
        }
        println("XKCD tools: ${allTools.map { it.definition.name }}")
    }

    @Test
    fun `xkcd - GET current comic`() {
        val tool = learner.learn(xkcdSpec).create()
        val allTools = collectAllLeafTools(tool)
        val currentTool = allTools.find { it.definition.name.contains("info_0_json") && !it.definition.name.contains("comicId") }
        assertNotNull(currentTool) {
            "No current comic tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = currentTool!!.call("")
        assertSuccess(result, currentTool.definition.name)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("title")) { "Expected comic JSON with 'title' field" }
        assertTrue(text.contains("img")) { "Expected comic JSON with 'img' field" }
    }

    @Test
    fun `xkcd - GET comic by id`() {
        val tool = learner.learn(xkcdSpec).create()
        val allTools = collectAllLeafTools(tool)
        val byIdTool = allTools.find { it.definition.name.contains("comicId") }
        assertNotNull(byIdTool) {
            "No comic-by-id tool found. Available: ${allTools.map { it.definition.name }}"
        }
        val result = byIdTool!!.call("""{"comicId": "327"}""")
        assertSuccess(result, byIdTool.definition.name)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("327") || text.contains("num")) { "Expected comic 327 data" }
    }

    @Test
    fun `xkcd - cached spec roundtrip works`() {
        val learned = learner.learn(xkcdSpec)
        assertNotNull(learned.spec)

        // Simulate deserialization: reconstruct from spec without network
        val factory = learned.spec!!.toFactory()
        val tool = factory(ApiCredentials.None)
        assertInstanceOf(ProgressiveTool::class.java, tool)
        val allTools = collectAllLeafTools(tool)
        assertTrue(allTools.isNotEmpty()) { "Cached spec should produce tools" }
    }

    // =====================================================================
    // Open-Meteo (path-level servers, no top-level servers, GitHub-hosted)
    // =====================================================================

    private val openMeteoSpec = "https://raw.githubusercontent.com/open-meteo/open-meteo/main/openapi.yml"

    @Test
    fun `open-meteo - resolves path-level server URL`() {
        val rawSpec = OpenApiLearner.fetchRawSpec(openMeteoSpec)
        val openApi = OpenApiLearner.parseSpec(openMeteoSpec, rawSpec)

        println("Top-level servers: ${openApi.servers?.map { it.url }}")
        openApi.paths?.forEach { (path, pathItem) ->
            println("Path: $path, servers: ${pathItem.servers?.map { it.url }}")
        }

        val baseUrl = OpenApiLearner.resolveBaseUrl(openApi, openMeteoSpec)
        println("Resolved baseUrl: $baseUrl")

        assertTrue(baseUrl.contains("api.open-meteo.com")) {
            "Base URL should be api.open-meteo.com but was: $baseUrl"
        }
    }

    @Test
    fun `open-meteo - GET current weather`() {
        val learned = learner.learn(openMeteoSpec)
        println("Name: ${learned.name}")

        val tool = learned.create()
        val allTools = collectAllLeafTools(tool)
        println("Tools: ${allTools.map { it.definition.name }}")

        val forecastTool = allTools.find { it.definition.name.contains("forecast") }
        assertNotNull(forecastTool) {
            "No forecast tool found. Available: ${allTools.map { it.definition.name }}"
        }

        val result = forecastTool!!.call(
            """{"latitude": 53.48, "longitude": -2.24, "current_weather": true}""",
        )
        assertSuccess(result, forecastTool.definition.name)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("temperature")) { "Expected weather data with temperature: $text" }
    }

    @Test
    fun `open-meteo - spec has serializable LearnedApiSpec`() {
        val learned = learner.learn(openMeteoSpec)
        assertNotNull(learned.spec)
        assertInstanceOf(com.embabel.agent.api.client.LearnedApiSpec.OpenApi::class.java, learned.spec)
        val openApiSpec = learned.spec as com.embabel.agent.api.client.LearnedApiSpec.OpenApi
        assertTrue(openApiSpec.rawSpec.contains("open-meteo") || openApiSpec.rawSpec.contains("forecast")) {
            "Raw spec should contain API content"
        }
    }

    // =====================================================================
    // PokeAPI (OpenAPI 3.1.0, large spec, many endpoints)
    // =====================================================================

    private val pokeApiSpec = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/openapi.yml"

    @Test
    fun `pokeapi - inspect`() {
        val learned = learner.learn(pokeApiSpec)
        println("Name: ${learned.name}")
        println("Description: ${learned.description}")
        assertNotNull(learned.name)
        assertNotNull(learned.spec)
    }

    @Test
    fun `pokeapi - has many operations`() {
        val tool = learner.learn(pokeApiSpec).create()
        val allTools = collectAllLeafTools(tool)
        println("PokeAPI tools (${allTools.size}): ${allTools.take(10).map { it.definition.name }}...")
        assertTrue(allTools.size >= 5) { "Expected multiple operations, got ${allTools.size}" }
    }

    @Test
    fun `pokeapi - base URL resolves to pokeapi`() {
        val rawSpec = OpenApiLearner.fetchRawSpec(pokeApiSpec)
        val openApi = OpenApiLearner.parseSpec(pokeApiSpec, rawSpec)
        val baseUrl = OpenApiLearner.resolveBaseUrl(openApi, pokeApiSpec)
        println("PokeAPI baseUrl: $baseUrl")
        assertTrue(baseUrl.contains("pokeapi.co")) {
            "Base URL should be pokeapi.co but was: $baseUrl"
        }
    }

    // =====================================================================
    // Cross-cutting: raw spec caching and LearnedApiSpec serialization
    // =====================================================================

    @Test
    fun `fetchRawSpec handles HTTP URLs`() {
        val raw = OpenApiLearner.fetchRawSpec(petstoreV2Spec)
        assertTrue(raw.contains("swagger") || raw.contains("openapi"))
    }

    @Test
    fun `parseSpec from raw content matches parseSpec from URL`() {
        val rawSpec = OpenApiLearner.fetchRawSpec(petstoreV2Spec)
        val fromContent = OpenApiLearner.parseSpec(petstoreV2Spec, rawSpec)
        val fromUrl = OpenApiLearner.parseSpec(petstoreV2Spec)

        assertEquals(fromUrl.info?.title, fromContent.info?.title)
        assertEquals(fromUrl.paths?.size, fromContent.paths?.size)
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun callTool(root: Tool, name: String, input: String): Tool.Result? {
        val allTools = collectAllLeafTools(root)
        val tool = allTools.find { it.definition.name == name } ?: return null
        return tool.call(input)
    }

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
                    org.mockito.Mockito.mock(com.embabel.agent.core.AgentProcess::class.java),
                )
                inner.flatMap { collectAllLeafTools(it) }
            }
            else -> listOf(tool)
        }
    }
}
