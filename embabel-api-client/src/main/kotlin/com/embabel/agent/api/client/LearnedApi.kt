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
package com.embabel.agent.api.client

import com.embabel.agent.api.tool.progressive.ProgressiveTool
import com.embabel.common.core.types.Timestamped
import java.time.Instant

/**
 * The result of an [ApiLearner] learning an API. Describes what was learned
 * (name, description, auth requirements) and provides a [create]
 * method to produce a usable [com.embabel.agent.api.tool.progressive.ProgressiveTool] once credentials are supplied.
 */
data class LearnedApi(
    val name: String,
    val description: String,
    val authRequirements: List<AuthRequirement>,
    private val factory: (ApiCredentials) -> ProgressiveTool,
): Timestamped {

    override val timestamp: Instant = Instant.now()

    /**
     * Create a [ProgressiveTool] for this API, supplying any required credentials.
     */
    fun create(credentials: ApiCredentials = ApiCredentials.None): ProgressiveTool =
        factory(credentials)
}
