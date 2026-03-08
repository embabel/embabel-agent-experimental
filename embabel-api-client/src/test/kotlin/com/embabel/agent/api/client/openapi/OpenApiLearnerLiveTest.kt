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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Live integration tests against public APIs.
 * Tagged so they can be excluded from CI builds.
 */
@Tag("live")
class OpenApiLearnerLiveTest {

    private val learner = OpenApiLearner()

    // --- Petstore (Swagger 2.0, auto-converted to OpenAPI 3.0) ---

    private val petstoreSpec = "https://petstore.swagger.io/v2/swagger.json"

    @Test
    fun `petstore - inspect`() {
        val learned = learner.learn(petstoreSpec)
        println("Name: ${learned.name}")
        println("Description: ${learned.description}")
        println("Auth: ${learned.authRequirements}")
        assertEquals("swagger_petstore", learned.name)
        assertTrue(learned.authRequirements.isNotEmpty())
    }

    @Test
    fun `petstore - tool tree`() {
        val tool = learner.learn(petstoreSpec).create()
        println(Tool.formatToolTree("petstore", listOf(tool)))
        assertInstanceOf(ProgressiveTool::class.java, tool)
    }

    @Test
    fun `petstore - GET findPetsByStatus`() {
        val tool = learner.learn(petstoreSpec).create()
        val result = callTool(tool, "findPetsByStatus", """{"status": "available"}""")
        assertSuccess(result, "findPetsByStatus")
    }

    @Test
    fun `petstore - GET getInventory`() {
        val tool = learner.learn(petstoreSpec).create()
        val result = callTool(tool, "getInventory", "")
        assertSuccess(result, "getInventory")
    }

    @Test
    fun `petstore - POST addPet`() {
        val tool = learner.learn(petstoreSpec).create()
        val result = callTool(
            tool, "addPet",
            """{"body": {"name": "Embabel Test Dog", "photoUrls": ["https://example.com/dog.jpg"], "status": "available"}}""",
        )
        assertSuccess(result, "addPet")
        assertTrue((result as Tool.Result.Text).content.contains("Embabel Test Dog"))
    }

    // --- FakeRestAPI (OpenAPI 3.0.1, full CRUD, no auth) ---

    private val fakeRestApiSpec = "https://fakerestapi.azurewebsites.net/swagger/v1/swagger.json"

    @Test
    fun `fakerestapi - inspect`() {
        val learned = learner.learn(fakeRestApiSpec)
        println("Name: ${learned.name}")
        println("Auth: ${learned.authRequirements}")
        assertNotNull(learned.name)
    }

    @Test
    fun `fakerestapi - tool tree`() {
        val tool = learner.learn(fakeRestApiSpec).create()
        println(Tool.formatToolTree("fakerestapi", listOf(tool)))
    }

    @Test
    fun `fakerestapi - GET activities`() {
        val tool = learner.learn(fakeRestApiSpec).create()
        val result = callTool(tool, "apiV1ActivitiesGet", "")
        // Operation names may vary — find any GET activities tool
        if (result == null) {
            val allTools = collectAllLeafTools(tool)
            val activitiesTool = allTools.find {
                it.definition.name.lowercase().contains("activit") &&
                    it.definition.name.lowercase().let { n -> !n.contains("id") }
            }
            assertNotNull(activitiesTool) {
                "No activities GET tool found. Available: ${allTools.map { it.definition.name }}"
            }
            val r = activitiesTool!!.call("")
            assertSuccess(r, activitiesTool.definition.name)
        } else {
            assertSuccess(result, "activities GET")
        }
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

    // --- httpbin (Swagger 2.0, echo service with POST/PUT/DELETE) ---

    private val httpbinSpec = "https://httpbin.org/spec.json"

    @Test
    fun `httpbin - inspect`() {
        val learned = learner.learn(httpbinSpec)
        println("Name: ${learned.name}")
        println("Ops: ${collectAllLeafTools(learned.create()).size}")
        assertNotNull(learned.name)
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

    // --- Helpers ---

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
