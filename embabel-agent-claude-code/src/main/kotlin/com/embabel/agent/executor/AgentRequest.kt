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
import com.embabel.common.core.types.ZeroToOne

/**
 * A request for typed agent execution.
 *
 * @param prompt lazily evaluated prompt
 * @param outputClass the expected output type for JSON deserialization
 * @param fitnessFunction evaluates quality of the output
 * @param tools tools to expose via ephemeral MCP server
 * @param maxRetries maximum number of retries if fitness is below threshold (0 = no retry)
 * @param fitnessThreshold minimum acceptable fitness score
 */
data class AgentRequest<T>(
    val prompt: () -> String,
    val outputClass: Class<T>,
    val fitnessFunction: FitnessFunction<T> = { 1.0 },
    val tools: List<Tool> = emptyList(),
    val maxRetries: Int = 0,
    val fitnessThreshold: ZeroToOne = 0.8,
) {

    fun withFitnessFunction(fitnessFunction: FitnessFunction<T>): AgentRequest<T> = copy(fitnessFunction = fitnessFunction)
    fun withTools(tools: List<Tool>): AgentRequest<T> = copy(tools = tools)
    fun withTool(tool: Tool): AgentRequest<T> = copy(tools = tools + tool)
    fun withMaxRetries(maxRetries: Int): AgentRequest<T> = copy(maxRetries = maxRetries)
    fun withFitnessThreshold(fitnessThreshold: ZeroToOne): AgentRequest<T> = copy(fitnessThreshold = fitnessThreshold)

    companion object {

       fun fromPrompt(prompt: () -> String): Builder = Builder(prompt)
    }

    class Builder(private val prompt: () -> String) {

        fun<T> returning(outputClass: Class<T>) = AgentRequest(
            prompt = prompt,
            outputClass = outputClass,
        )
    }

}
