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
import org.junit.jupiter.api.Test

class OpenApiTagFilterTest {

    private val specUrl = javaClass.classLoader.getResource("petstore-extended.json")!!.toString()

    private fun buildWithTags(tags: Set<String>? = null): ProgressiveTool {
        val rawSpec = OpenApiLearner.fetchRawSpec(specUrl)
        val openApi = OpenApiLearner.parseSpec(specUrl, rawSpec)
        return OpenApiLearner.buildTool(specUrl, openApi, ApiCredentials.None, tags)
    }

    private fun collectAllLeafTools(tool: Tool): List<Tool> = when (tool) {
        is ProgressiveTool -> {
            val inner = tool.innerTools(org.mockito.Mockito.mock(com.embabel.agent.core.AgentProcess::class.java))
            inner.flatMap { collectAllLeafTools(it) }
        }
        else -> listOf(tool)
    }

    @Test
    fun `null tags returns all operations`() {
        val tool = buildWithTags(null)
        val allTools = collectAllLeafTools(tool)
        assertEquals(16, allTools.size)
    }

    @Test
    fun `filter to single tag returns flat tool`() {
        val tool = buildWithTags(setOf("store"))
        val allTools = collectAllLeafTools(tool)
        assertEquals(4, allTools.size)
        val names = allTools.map { it.definition.name }.toSet()
        assertTrue("getInventory" in names)
        assertTrue("placeOrder" in names)
        assertTrue("getOrderById" in names)
        assertTrue("deleteOrder" in names)
    }

    @Test
    fun `filter to multiple tags returns categorized tool`() {
        val tool = buildWithTags(setOf("pets", "store"))
        val allTools = collectAllLeafTools(tool)
        assertEquals(11, allTools.size)
        val names = allTools.map { it.definition.name }.toSet()
        assertTrue("listPets" in names)
        assertTrue("getInventory" in names)
        assertFalse("createUser" in names, "user operations should be excluded")
    }

    @Test
    fun `tag filter is case insensitive`() {
        val tool = buildWithTags(setOf("STORE"))
        val allTools = collectAllLeafTools(tool)
        assertEquals(4, allTools.size)
    }

    @Test
    fun `filter with nonexistent tag returns empty tool`() {
        val tool = buildWithTags(setOf("nonexistent"))
        // Should still return a tool (empty unfolding tool)
        assertNotNull(tool)
        val allTools = collectAllLeafTools(tool)
        assertTrue(allTools.isEmpty())
    }

    @Test
    fun `buildModel returns full model`() {
        val rawSpec = OpenApiLearner.fetchRawSpec(specUrl)
        val openApi = OpenApiLearner.parseSpec(specUrl, rawSpec)
        val model = OpenApiLearner.buildModel(specUrl, openApi)
        assertEquals("petstore_extended", model.name)
        assertEquals(4, model.resources.size)
        assertEquals(16, model.allOperations.size)
    }
}
