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

/**
 * Learns an API from a machine-readable description and produces a [LearnedApi].
 *
 * Each implementation handles a specific API description format
 * (OpenAPI, GraphQL, etc.) but the caller interaction is always the same:
 * learn, check auth requirements, provide credentials, get a tool.
 */
interface ApiLearner {

    /**
     * Learn an API from its source, extracting structure and auth requirements.
     *
     * @param source The API source (spec URL, endpoint, etc.)
     * @return A [LearnedApi] describing what was discovered
     */
    fun learn(source: String): LearnedApi
}

/**
 * The result of an [ApiLearner] learning an API. Describes what was learned
 * (name, description, auth requirements) and provides a [create]
 * method to produce a usable [ProgressiveTool] once credentials are supplied.
 */
data class LearnedApi(
    val name: String,
    val description: String,
    val authRequirements: List<AuthRequirement>,
    private val factory: (ApiCredentials) -> ProgressiveTool,
) {

    /**
     * Create a [ProgressiveTool] for this API, supplying any required credentials.
     */
    fun create(credentials: ApiCredentials = ApiCredentials.None): ProgressiveTool =
        factory(credentials)
}

/**
 * What kind of authentication an API requires.
 * Extracted from the API spec (e.g., OpenAPI securitySchemes).
 */
sealed interface AuthRequirement {
    data object None : AuthRequirement
    data class ApiKey(val name: String, val location: ApiKeyLocation) : AuthRequirement
    data class Bearer(val scheme: String = "bearer") : AuthRequirement
    data class OAuth2(val scopes: List<String> = emptyList()) : AuthRequirement
}

enum class ApiKeyLocation {
    HEADER, QUERY, COOKIE
}

/**
 * Credentials supplied by the caller to satisfy [AuthRequirement]s.
 */
sealed interface ApiCredentials {
    data object None : ApiCredentials
    data class Token(val token: String) : ApiCredentials
    data class ApiKey(val value: String) : ApiCredentials
    data class Multiple(val credentials: List<ApiCredentials>) : ApiCredentials
}
