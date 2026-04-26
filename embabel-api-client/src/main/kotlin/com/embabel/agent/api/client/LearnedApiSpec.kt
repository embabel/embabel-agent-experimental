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
import com.embabel.agent.api.client.model.ApiModel
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
     *
     * @param tags optional set of tag names to include. When non-null, only
     *   operations tagged with one of these values are exposed as tools.
     * @param operationIds optional set of exact operationIds to include.
     *   Composes with [tags] (intersection); pin a micro-surface from a
     *   large spec without tag granularity (e.g. GitHub's `repos` tag has
     *   ~80 ops; you usually want only `repos/get`).
     * @param nameOverride optional name to use for the resulting tool
     *   instead of the one derived from the spec. The pack/workspace uses
     *   this so the gateway namespace matches the pack author's declared
     *   name (e.g. `gh`), not the verbose spec title (`githubV3RestApi`).
     */
    fun toFactory(
        tags: Set<String>? = null,
        operationIds: Set<String>? = null,
        nameOverride: String? = null,
    ): (ApiCredentials) -> ProgressiveTool

    /**
     * Build an [ApiModel] from this spec, if supported.
     *
     * The model is the rich intermediate representation that preserves full
     * schema information for both tool projection and interface generation.
     * Returns `null` for spec types that don't yet support model construction.
     */
    fun toModel(): ApiModel? = null

    /**
     * OpenAPI spec — stores the raw spec content so it can be re-parsed without network access.
     */
    data class OpenApi(
        override val source: String,
        val rawSpec: String,
        override val learnedAt: Instant = Instant.now(),
    ) : LearnedApiSpec {

        override fun toFactory(
            tags: Set<String>?,
            operationIds: Set<String>?,
            nameOverride: String?,
        ): (ApiCredentials) -> ProgressiveTool = { credentials ->
            val openApi = OpenApiLearner.parseSpec(source, rawSpec)
            OpenApiLearner.buildTool(source, openApi, credentials, tags, operationIds, nameOverride)
        }

        override fun toModel(): ApiModel {
            val openApi = OpenApiLearner.parseSpec(source, rawSpec)
            return OpenApiLearner.buildModel(source, openApi)
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

        override fun toFactory(
            tags: Set<String>?,
            operationIds: Set<String>?,
            nameOverride: String?,
        ): (ApiCredentials) -> ProgressiveTool = { credentials ->
            // GraphQL doesn't have OpenAPI tags or operationIds —
            // ignored. Filtering by GraphQL field name is a separate
            // feature when needed.
            val effectiveName = nameOverride?.takeIf { it.isNotBlank() } ?: apiName
            GraphQlLearner.buildTool(source, effectiveName, queryTypeName, mutationTypeName, credentials)
        }
    }
}
