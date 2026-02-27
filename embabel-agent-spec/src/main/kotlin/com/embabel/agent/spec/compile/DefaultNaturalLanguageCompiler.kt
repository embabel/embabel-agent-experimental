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
package com.embabel.agent.spec.compile

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.DomainType
import com.embabel.agent.spec.model.GoalSpec
import com.embabel.agent.spec.model.PromptedActionSpec
import com.embabel.agent.spec.model.StepSpecContext
import com.embabel.common.ai.model.LlmOptions
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory

/**
 * Binding information for a domain type, used in template rendering.
 */
internal data class DomainTypeBinding(
    val domainType: DomainType,
) {
    val name: String = domainType.name
    val description: String = domainType.description
    val varname: String = PromptedActionSpec.variableNameFor(domainType.name)
}

/**
 * LLM output structure for compiled actions.
 * This intermediate class is populated by the LLM and then converted to PromptedActionSpec.
 */
internal data class CompiledAction(
    @param:JsonPropertyDescription("Unique name for the action, in camelCase")
    val name: String,

    @param:JsonPropertyDescription("Brief description of what the action does")
    val description: String,

    @param:JsonPropertyDescription("Set of input type names from the available domain types")
    val inputTypeNames: Set<String>,

    @param:JsonPropertyDescription("Output type name from the available domain types")
    val outputTypeName: String,

    @param:JsonPropertyDescription(
        "SpEL preconditions that gate execution. If the description says 'when X is Y' or 'if X', " +
        "extract it here as 'spel:varname.property.contains(value)' or similar. " +
        "Do NOT duplicate this condition as a Jinja if/else in the prompt."
    )
    val pre: List<String> = emptyList(),

    @param:JsonPropertyDescription("Postcondition strings: what must be true after execution. Can use SpEL")
    val post: List<String> = emptyList(),

    @param:JsonPropertyDescription(
        "Jinja2/Mustache template prompt rendered at runtime. " +
        "Use {{variableName}} syntax to reference input values. " +
        "Variable names are the type names with first letter lowercased (e.g., UserInput -> userInput). " +
        "NEVER use Jinja {% if %} blocks for conditions that belong in 'pre'. " +
        "Assume preconditions are already satisfied."
    )
    val prompt: String,

    @param:JsonPropertyDescription("List of tool group roles that this action needs (e.g., 'web', 'math')")
    val toolGroups: List<String> = emptyList(),

    @param:JsonPropertyDescription("Whether the output can be null, triggering replanning")
    val nullable: Boolean = false,

    @param:JsonPropertyDescription("Must be 'action'")
    val stepType: String = "action",
)

/**
 * LLM output structure for compiled goals.
 */
internal data class CompiledGoal(
    @param:JsonPropertyDescription("Unique name for the goal, in camelCase")
    val name: String,

    @param:JsonPropertyDescription("Brief description of what achieving this goal means")
    val description: String,

    @param:JsonPropertyDescription("Output type name that represents the goal being achieved")
    val outputTypeName: String,

    @param:JsonPropertyDescription("Must be 'goal'")
    val stepType: String = "goal",
)

/**
 * Default implementation of [NaturalLanguageCompiler] that uses an LLM to
 * compile natural language specifications into executable step specs.
 *
 * The compiler builds prompts that describe the available domain types and tool groups,
 * then asks the LLM to generate structured specs that can be executed by the agent framework.
 *
 * @param ai The AI interface for LLM operations
 * @param llmOptions LLM configuration for compilation (defaults to lower temperature for consistency)
 */
class DefaultNaturalLanguageCompiler(
    private val ai: Ai,
    private val llmOptions: LlmOptions = LlmOptions(temperature = 0.1),
) : NaturalLanguageCompiler {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun compileAction(
        spec: NaturalLanguageActionSpec,
        context: StepSpecContext,
    ): CompilationResult<PromptedActionSpec> {
        logger.info("Compiling action '{}' from natural language", spec.name)

        return try {
            val bindings = context.dataDictionary.domainTypes.map { DomainTypeBinding(it) }

            val compiled = ai
                .withLlm(llmOptions)
                .withId("compile-action:${spec.name}")
                .rendering("compiler/compile_action")
                .createObject(
                    CompiledAction::class.java,
                    mapOf(
                        "actionSpecification" to spec,
                        "bindings" to bindings,
                        "toolGroups" to context.toolGroups,
                    ),
                )

            val actionSpec = PromptedActionSpec(
                name = compiled.name,
                description = compiled.description,
                inputTypeNames = compiled.inputTypeNames,
                outputTypeName = compiled.outputTypeName,
                pre = compiled.pre,
                post = compiled.post,
                prompt = compiled.prompt,
                toolGroups = compiled.toolGroups,
                nullable = compiled.nullable,
                llm = LlmOptions(), // Use default LLM options at runtime
            )

            logger.info(
                "Successfully compiled action '{}': inputs={}, output={}, pre={}, tools={}",
                actionSpec.name,
                actionSpec.inputTypeNames,
                actionSpec.outputTypeName,
                actionSpec.pre,
                actionSpec.toolGroups,
            )

            CompilationResult.success(actionSpec)
        } catch (e: Exception) {
            logger.error("Failed to compile action '{}': {}", spec.name, e.message, e)
            CompilationResult.failure(
                message = "Failed to compile action: ${e.message}",
                source = spec.name,
                cause = e,
            )
        }
    }

    override fun compileGoal(
        spec: NaturalLanguageGoalSpec,
        context: StepSpecContext,
    ): CompilationResult<GoalSpec> {
        logger.info("Compiling goal '{}' from natural language", spec.name)

        return try {
            val bindings = context.dataDictionary.domainTypes.map { DomainTypeBinding(it) }

            val compiled = ai
                .withLlm(llmOptions)
                .withId("compile-goal:${spec.name}")
                .rendering("compiler/compile_goal")
                .createObject(
                    CompiledGoal::class.java,
                    mapOf(
                        "goalSpecification" to spec,
                        "bindings" to bindings,
                    ),
                )

            val goalSpec = GoalSpec(
                name = compiled.name,
                description = compiled.description,
                outputTypeName = compiled.outputTypeName,
            )

            logger.info(
                "Successfully compiled goal '{}': output={}",
                goalSpec.name,
                goalSpec.outputTypeName,
            )

            CompilationResult.success(goalSpec)
        } catch (e: Exception) {
            logger.error("Failed to compile goal '{}': {}", spec.name, e.message, e)
            CompilationResult.failure(
                message = "Failed to compile goal: ${e.message}",
                source = spec.name,
                cause = e,
            )
        }
    }
}
