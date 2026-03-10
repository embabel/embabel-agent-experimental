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
package com.embabel.agent.executor

import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * A [Tool] that delegates to an [AgentExecutor] for typed execution.
 *
 * This provides a generic way to expose any agent executor as a tool that other LLMs
 * can invoke. The tool accepts a prompt and returns the result as text.
 *
 * @param executor the agent executor to delegate to
 * @param toolName the name to expose this tool as
 * @param toolDescription the description for the tool
 */
open class AgentExecutorTool(
    private val executor: AgentExecutor,
    toolName: String = "agent_executor",
    toolDescription: String = "Execute a task using an AI agent. Provide a specific prompt describing what to do.",
) : Tool {

    private val objectMapper = jacksonObjectMapper()

    override val definition: Tool.Definition = Tool.Definition(
        name = toolName,
        description = toolDescription,
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string(
                name = "prompt",
                description = "The task to perform. Be specific about what the expected outcome should be.",
                required = true,
            ),
        ),
    )

    override fun call(input: String): Tool.Result {
        val prompt = parsePrompt(input)
        val request = AgentRequest(
            prompt = { prompt },
            outputClass = String::class.java,
        )
        return when (val result = executor.executeTyped(request)) {
            is TypedResult.Success -> Tool.Result.withArtifact(result.value, result)
            is TypedResult.Failure -> Tool.Result.error(result.error)
        }
    }

    private fun parsePrompt(input: String): String {
        if (input.isBlank()) {
            throw IllegalArgumentException("prompt is required")
        }
        return try {
            val parsed = objectMapper.readValue<Map<String, Any?>>(input)
            parsed["prompt"]?.toString() ?: input
        } catch (e: Exception) {
            input
        }
    }
}
