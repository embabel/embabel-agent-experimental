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
 * What kind of authentication an API requires.
 * Extracted from the API spec (e.g., OpenAPI securitySchemes).
 */
sealed interface AuthRequirement {
    data object None : AuthRequirement
    data class ApiKey(val name: String, val location: ApiKeyLocation) : AuthRequirement
    data class Bearer(val scheme: String = "bearer") : AuthRequirement
    data class OAuth2(
        val scopes: List<String> = emptyList(),
        val authUrl: String? = null,
        val tokenUrl: String? = null,
    ) : AuthRequirement
}

enum class ApiKeyLocation {
    HEADER, QUERY, COOKIE
}
