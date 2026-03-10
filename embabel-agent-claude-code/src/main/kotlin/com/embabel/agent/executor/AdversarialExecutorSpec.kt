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

import com.embabel.agent.claudecode.ClaudeCodeAgentExecutor
import com.embabel.agent.claudecode.ClaudeCodePermissionMode
import com.embabel.agent.core.Action
import com.embabel.agent.spec.model.ActionSpec
import com.embabel.agent.spec.model.StepSpecContext
import com.fasterxml.jackson.annotation.JsonTypeName

/**
 * Serializable spec for an [AdversarialExecutor] — a generator paired with
 * an LLM-evaluated critic in a retry loop.
 *
 * The [criticPrompt] is a Jinja2 template rendered at runtime with access to
 * `{{output}}` (the generator's result) plus all blackboard variables.
 * The rendered prompt is sent to a critic executor which returns
 * [ActionFeedback] JSON (score + feedback + accepted).
 *
 * Saved as YAML in the workspace goap/ directory. At deploy time,
 * [emit] constructs the live [AdversarialExecutor] with the critic
 * wired to a [ClaudeCodeAgentExecutor] that evaluates the prompt.
 *
 * @param generator the wrapped generator executor spec (e.g., [ClaudeCodeAgentExecutor])
 * @param criticPrompt Jinja2 template for the critic — has access to `{{output}}` and all input variables
 * @param maxAttempts maximum generate-critique cycles (default 3)
 * @param failOnReject if true, fail when the critic never accepts; if false, return the best attempt
 */
@JsonTypeName("adversarial")
data class AdversarialExecutorSpec(
    override val name: String,
    override val description: String,
    val generator: AgentExecutor,
    val criticPrompt: String,
    val maxAttempts: Int = 3,
    val failOnReject: Boolean = false,
) : ActionSpec {

    override val stepType: String = "adversarial"

    override fun emit(stepContext: StepSpecContext): Action {
        val criticExecutor = ClaudeCodeAgentExecutor(
            name = "${name}-critic",
            description = "Critic for $name",
            prompt = criticPrompt,
            outputTypeName = "com.embabel.agent.executor.ActionFeedback",
            defaultPermissionMode = ClaudeCodePermissionMode.PLAN,
        )
        val critic = AdversarialExecutor.criticFrom(criticExecutor)
        val adversarial = AdversarialExecutor(generator, critic, maxAttempts, failOnReject)
        return adversarial.emit(stepContext)
    }
}
