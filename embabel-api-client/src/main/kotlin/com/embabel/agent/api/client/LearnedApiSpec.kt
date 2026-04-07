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

import com.embabel.agent.api.client.graphql.GraphQlLearner
import com.embabel.agent.api.client.openapi.OpenApiLearner
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/**
 * Serializable specification that captures everything needed to reconstruct
 * a [LearnedApi]'s factory lambda without re-fetching remote specs.
 *
 * Uses Jackson's FQN-based polymorphic serialization so that each subclass
 * is identified by its fully qualified class name in the serialized form.
 * This means new spec types can be added without updating a discriminator registry.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
sealed interface LearnedApiSpec {

    /** The source URL or endpoint this spec was learned from. */
    val source: String

    /** When this spec was learned/cached. Used to decide when to refresh. */
    val learnedAt: Instant

    /**
     * Create a factory function that produces a [ProgressiveTool] given credentials.
     */
    fun toFactory(): (ApiCredentials) -> ProgressiveTool

    /**
     * OpenAPI spec — stores the raw spec content so it can be re-parsed without network access.
     */
    data class OpenApi(
        override val source: String,
        val rawSpec: String,
        override val learnedAt: Instant = Instant.now(),
    ) : LearnedApiSpec {

        override fun toFactory(): (ApiCredentials) -> ProgressiveTool = { credentials ->
            val openApi = OpenApiLearner.parseSpec(source, rawSpec)
            OpenApiLearner.buildTool(source, openApi, credentials)
        }
    }

    /**
     * GraphQL endpoint — stores the root type names discovered during introspection.
     * Introspection is re-done at tool creation time to get field details,
     * but root type discovery (the expensive part) is cached here.
     */
    data class GraphQl(
        override val source: String,
        val apiName: String,
        val queryTypeName: String?,
        val mutationTypeName: String?,
        override val learnedAt: Instant = Instant.now(),
    ) : LearnedApiSpec {

        override fun toFactory(): (ApiCredentials) -> ProgressiveTool = { credentials ->
            GraphQlLearner.buildTool(source, apiName, queryTypeName, mutationTypeName, credentials)
        }
    }
}
