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
package com.embabel.agent.claudecode

import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EphemeralMcpToolServerTest {

    private val objectMapper = jacksonObjectMapper()

    private fun createSimpleTool(name: String = "test_tool"): Tool = Tool.of(
        name = name,
        description = "A test tool",
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("input", "Test input"),
        ),
    ) { Tool.Result.text("result") }

    @Test
    fun `server starts and stops cleanly`() {
        val tool = createSimpleTool()
        val server = EphemeralMcpToolServer(listOf(tool))

        assertTrue(server.port > 0, "Port should be a positive number")
        assertTrue(server.url.startsWith("http://localhost:"), "URL should point to localhost")
        assertTrue(server.url.endsWith("/sse"), "URL should end with /sse")

        server.close()
    }

    @Test
    fun `server uses random available port`() {
        val tool = createSimpleTool()
        val server1 = EphemeralMcpToolServer(listOf(tool), serverName = "server1")
        val server2 = EphemeralMcpToolServer(listOf(tool), serverName = "server2")

        try {
            assertTrue(server1.port != server2.port, "Ports should be different")
        } finally {
            server1.close()
            server2.close()
        }
    }

    @Test
    fun `toMcpConfigJson produces valid JSON with correct structure`() {
        val tool = createSimpleTool()
        val server = EphemeralMcpToolServer(listOf(tool), serverName = "my-tools")

        try {
            val configJson = server.toMcpConfigJson()
            val config = objectMapper.readTree(configJson)

            assertNotNull(config.get("mcpServers"), "Should have mcpServers key")
            val serverConfig = config.get("mcpServers").get("my-tools")
            assertNotNull(serverConfig, "Should have server entry with correct name")
            assertEquals("sse", serverConfig.get("type").asText())
            assertEquals(server.url, serverConfig.get("url").asText())
        } finally {
            server.close()
        }
    }

    @Test
    fun `server accepts multiple tools`() {
        val tool1 = createSimpleTool("tool_one")
        val tool2 = createSimpleTool("tool_two")
        val server = EphemeralMcpToolServer(listOf(tool1, tool2))

        try {
            assertTrue(server.port > 0)
        } finally {
            server.close()
        }
    }

    @Test
    fun `close is idempotent`() {
        val tool = createSimpleTool()
        val server = EphemeralMcpToolServer(listOf(tool))

        server.close()
        server.close() // Should not throw
    }
}
