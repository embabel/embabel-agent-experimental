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
package com.embabel.agent.mcp

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.support.springai.toSpringToolCallbacks
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.apache.catalina.startup.Tomcat
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.McpToolUtils
import java.net.ServerSocket

/**
 * An ephemeral MCP SSE server that exposes tool objects to external processes (e.g., Claude CLI).
 *
 * Takes a list of [Tool] instances and serves them over MCP SSE on a random available port.
 * The server starts on construction and stops on [close].
 *
 * @param tools list of tools to expose
 * @param serverName name for the MCP server (used in config JSON)
 */
class EphemeralMcpToolServer(
    tools: List<Tool>,
    private val serverName: String = "embabel-tools",
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val jsonMapper = jacksonObjectMapper()

    val port: Int = findAvailablePort()
    val url: String = "http://localhost:$port/sse"

    private val transportProvider: HttpServletSseServerTransportProvider
    private val mcpServer: McpSyncServer
    private val tomcat: Tomcat

    init {
        logger.info("Starting ephemeral MCP server '{}' on port {} with {} tools: {}",
            serverName, port, tools.size,
            tools.joinToString(", ") { it.definition.name })

        // Convert to Spring AI ToolCallbacks, then to MCP SyncToolSpecifications
        val toolCallbacks = tools.toSpringToolCallbacks()
        val toolSpecs = McpToolUtils.toSyncToolSpecification(toolCallbacks)

        // Create transport provider
        transportProvider = HttpServletSseServerTransportProvider.builder()
            .jsonMapper(JacksonMcpJsonMapper(jsonMapper))
            .messageEndpoint("/message")
            .sseEndpoint("/sse")
            .build()

        // Build MCP server with tools
        mcpServer = McpServer.sync(transportProvider)
            .serverInfo(serverName, "1.0.0")
            .capabilities(
                McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .build()
            )
            .tools(toolSpecs)
            .build()

        // Start embedded Tomcat
        tomcat = Tomcat().apply {
            setPort(port)
            connector // force connector initialization
            val context = addContext("", null)
            Tomcat.addServlet(context, "mcp-sse", transportProvider)
            context.addServletMappingDecoded("/*", "mcp-sse")
            start()
        }

        logger.info("Ephemeral MCP server '{}' started at {}", serverName, url)
    }

    /**
     * Generates the MCP config JSON that can be passed to Claude CLI via `--mcp-config`.
     */
    fun toMcpConfigJson(): String =
        jsonMapper.writeValueAsString(
            mapOf(
                "mcpServers" to mapOf(
                    serverName to mapOf(
                        "type" to "sse",
                        "url" to url,
                    )
                )
            )
        )

    override fun close() {
        logger.info("Stopping ephemeral MCP server '{}' on port {}", serverName, port)
        try {
            mcpServer.closeGracefully()
        } catch (e: Exception) {
            logger.debug("Error closing MCP server: {}", e.message)
        }
        try {
            tomcat.stop()
            tomcat.destroy()
        } catch (e: Exception) {
            logger.debug("Error stopping Tomcat: {}", e.message)
        }
    }

    companion object {
        private fun findAvailablePort(): Int =
            ServerSocket(0).use { it.localPort }
    }
}
