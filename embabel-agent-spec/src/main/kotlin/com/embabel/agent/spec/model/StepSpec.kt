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

import com.embabel.agent.core.AgentSystemStep
import com.embabel.common.core.types.NamedAndDescribed
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.embabel.agent.spec.support.NameOrClassTypeIdResolver
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver

/**
 * Definition of a step in an agent system: Action or Goal.
 *
 * Well-known subtypes are listed in [JsonSubTypes] and resolved by short name
 * (e.g., `"action"`, `"goal"`). Additional subtypes can be registered via
 * `registerSubtypes()` with `@JsonTypeName`.
 *
 * The [NameOrClassTypeIdResolver] also supports fully qualified class names as
 * stepType values, so any [StepSpec] implementation on the classpath can be
 * used without pre-registration (e.g., `"com.example.MyCustomExecutor"`).
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.CUSTOM,
    include = JsonTypeInfo.As.PROPERTY,
    property = "stepType"
)
@JsonTypeIdResolver(NameOrClassTypeIdResolver::class)
@JsonSubTypes(
    JsonSubTypes.Type(value = PromptedActionSpec::class, name = "action"),
    JsonSubTypes.Type(value = GoalSpec::class, name = "goal")
)
interface StepSpec<T : AgentSystemStep> : NamedAndDescribed {

    val stepType: String

    /**
     * Emit an instance of the step for execution
     */
    fun emit(stepContext: StepSpecContext): T

}
