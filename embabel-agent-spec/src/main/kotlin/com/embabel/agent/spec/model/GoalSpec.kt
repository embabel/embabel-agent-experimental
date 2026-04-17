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
package com.embabel.agent.spec.model

import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.Export
import com.embabel.agent.core.Goal
import com.embabel.agent.core.IoBinding
import com.embabel.agent.domain.io.UserInput
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Serializable Goal data.
 *
 * @param export whether to export this goal as a tool. When true, the goal is
 * published locally with [UserInput] as the starting input type, making it
 * callable by the chat LLM via [com.embabel.agent.tools.agent.GoalTool].
 */
data class GoalSpec(
    override val name: String,
    override val description: String,
    val outputTypeName: String,
    val export: Boolean = false,
    @param:JsonPropertyDescription("Type of step, must be 'goal'")
    override val stepType: String = "goal",
) : StepSpec<Goal> {

    override fun emit(stepContext: StepSpecContext): Goal {
        val resolvedOutputType = stepContext.dataDictionary.domainTypes
            .find { it.name == outputTypeName }
            ?: DynamicType(name = outputTypeName)
        return Goal(
            description = description,
            name = name,
            inputs = setOf(IoBinding(PromptedActionSpec.variableNameFor(outputTypeName), outputTypeName)),
            outputType = resolvedOutputType,
            export = if (export) {
                Export(
                    local = true,
                    startingInputTypes = setOf(UserInput::class.java),
                )
            } else {
                Export()
            },
        )
    }
}
