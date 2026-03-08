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

import com.embabel.agent.api.client.*
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenApiLearnerTest {

    private val learner = OpenApiLearner()

    private val specUrl = javaClass.classLoader.getResource("petstore-minimal.json")!!.toString()
    private val authSpecUrl = javaClass.classLoader.getResource("petstore-with-auth.json")!!.toString()

    private fun learned() = learner.learn(specUrl)

    // --- LearnedApi inspection ---

    @Test
    fun `inspect returns correct name`() {
        assertEquals("petstore", learned().name)
    }

    @Test
    fun `inspect returns description from spec`() {
        assertEquals("A minimal petstore API for testing", learned().description)
    }

    @Test
    fun `inspect returns no auth requirements when spec has none`() {
        val requirements = learned().authRequirements
        assertEquals(1, requirements.size)
        assertInstanceOf(AuthRequirement.None::class.java, requirements[0])
    }

    // --- Auth requirements extraction ---

    @Test
    fun `inspect extracts API key auth requirement`() {
        val learned = learner.learn(authSpecUrl)
        val apiKeyReq = learned.authRequirements.filterIsInstance<AuthRequirement.ApiKey>()
        assertEquals(1, apiKeyReq.size)
        assertEquals("X-API-Key", apiKeyReq[0].name)
        assertEquals(ApiKeyLocation.HEADER, apiKeyReq[0].location)
    }

    @Test
    fun `inspect extracts bearer auth requirement`() {
        val learned = learner.learn(authSpecUrl)
        val bearerReq = learned.authRequirements.filterIsInstance<AuthRequirement.Bearer>()
        assertEquals(1, bearerReq.size)
        assertEquals("bearer", bearerReq[0].scheme)
    }

    // --- Tool creation ---

    @Test
    fun `create returns ProgressiveTool`() {
        val tool = learned().create()
        assertInstanceOf(ProgressiveTool::class.java, tool)
        assertInstanceOf(Tool::class.java, tool)
    }

    @Test
    fun `created tool has correct name`() {
        assertEquals("petstore", learned().create().definition.name)
    }

    @Test
    fun `created tool has correct description`() {
        assertEquals(
            "A minimal petstore API for testing",
            learned().create().definition.description,
        )
    }

    @Test
    fun `call returns enabled tools message`() {
        val result = learned().create().call("")
        assertInstanceOf(Tool.Result.Text::class.java, result)
        assertTrue((result as Tool.Result.Text).content.contains("Enabled"))
    }

    @Test
    fun `innerTools contains all operations`() {
        val tool = learned().create()
        val allInnerTools = collectAllLeafTools(tool)
        val names = allInnerTools.map { it.definition.name }.toSet()
        assertTrue("listPets" in names)
        assertTrue("addPet" in names)
        assertTrue("getPetById" in names)
        assertTrue("deletePet" in names)
        assertTrue("getInventory" in names)
        assertTrue("createUser" in names)
        assertEquals(7, allInnerTools.size)
    }

    @Test
    fun `created tool is an UnfoldingTool for framework injection compatibility`() {
        val tool = learned().create()
        assertInstanceOf(UnfoldingTool::class.java, tool,
            "Tool must be an UnfoldingTool so the framework's UnfoldingToolInjectionStrategy can unwrap it")
    }

    @Test
    fun `created tool from credential store is an UnfoldingTool`() {
        val store = MapCredentialStore()
        val tool = learned().create(store)
        assertInstanceOf(UnfoldingTool::class.java, tool)
    }

    // --- Operation tool details ---

    @Test
    fun `operation with operationId uses it as tool name`() {
        val allTools = collectAllLeafTools(learned().create())
        assertNotNull(allTools.find { it.definition.name == "listPets" })
    }

    @Test
    fun `operation without operationId synthesizes name`() {
        val allTools = collectAllLeafTools(learned().create())
        assertNotNull(allTools.find { it.definition.name.contains("no_tags") })
    }

    @Test
    fun `path parameters are mapped correctly`() {
        val getPet = findTool(learned().create(), "getPetById")!!
        val petIdParam = getPet.definition.inputSchema.parameters.find { it.name == "petId" }
        assertNotNull(petIdParam)
        assertEquals(Tool.ParameterType.INTEGER, petIdParam!!.type)
        assertTrue(petIdParam.required)
    }

    @Test
    fun `query parameters with enum values are mapped`() {
        val listPets = findTool(learned().create(), "listPets")!!
        val statusParam = listPets.definition.inputSchema.parameters.find { it.name == "status" }
        assertNotNull(statusParam)
        assertEquals(Tool.ParameterType.STRING, statusParam!!.type)
        assertFalse(statusParam.required)
        assertEquals(listOf("available", "pending", "sold"), statusParam.enumValues)
    }

    @Test
    fun `request body is mapped as body parameter`() {
        val addPet = findTool(learned().create(), "addPet")!!
        val bodyParam = addPet.definition.inputSchema.parameters.find { it.name == "body" }
        assertNotNull(bodyParam)
        assertEquals(Tool.ParameterType.OBJECT, bodyParam!!.type)
        assertNotNull(bodyParam.properties)
        assertTrue(bodyParam.properties!!.any { it.name == "name" })
    }

    @Test
    fun `operation description combines summary and description`() {
        val listPets = findTool(learned().create(), "listPets")!!
        assertTrue(listPets.definition.description.contains("List all pets"))
        assertTrue(listPets.definition.description.contains("Returns all pets"))
    }

    @Test
    fun `tool tree can be formatted`() {
        val tree = Tool.formatToolTree("test", listOf(learned().create()))
        assertTrue(tree.contains("petstore"))
    }

    @Test
    fun `invalid spec URL throws`() {
        assertThrows(Exception::class.java) {
            learner.learn("file:///nonexistent.json")
        }
    }

    // --- Helpers ---

    private fun collectAllLeafTools(tool: Tool): List<Tool> {
        return when (tool) {
            is ProgressiveTool -> {
                val inner = tool.innerTools(mockProcess())
                inner.flatMap { collectAllLeafTools(it) }
            }
            else -> listOf(tool)
        }
    }

    private fun findTool(root: Tool, name: String): Tool? =
        collectAllLeafTools(root).find { it.definition.name == name }

    private fun mockProcess(): com.embabel.agent.core.AgentProcess =
        org.mockito.Mockito.mock(com.embabel.agent.core.AgentProcess::class.java)
}
