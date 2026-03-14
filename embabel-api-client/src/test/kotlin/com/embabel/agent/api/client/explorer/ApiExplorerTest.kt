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
package com.embabel.agent.api.client.explorer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ApiExplorer] that don't require an LLM.
 */
class ApiExplorerTest {

    @Test
    fun `buildPrompt includes target URL`() {
        val prompt = ApiExplorer.buildPrompt("https://example.com/api")
        assertTrue(prompt.contains("https://example.com/api"))
    }

    @Test
    fun `buildPrompt includes GraphQL strategy`() {
        val prompt = ApiExplorer.buildPrompt("https://example.com")
        assertTrue(prompt.contains("GraphQL"))
        assertTrue(prompt.contains("__schema"))
    }

    @Test
    fun `buildPrompt includes OpenAPI strategy`() {
        val prompt = ApiExplorer.buildPrompt("https://example.com")
        assertTrue(prompt.contains("OpenAPI") || prompt.contains("openapi"))
        assertTrue(prompt.contains("swagger") || prompt.contains("Swagger"))
    }

    @Test
    fun `buildPrompt includes expected response format`() {
        val prompt = ApiExplorer.buildPrompt("https://example.com")
        assertTrue(prompt.contains("OPENAPI"))
        assertTrue(prompt.contains("GRAPHQL"))
        assertTrue(prompt.contains("UNKNOWN"))
        assertTrue(prompt.contains("specUrl"))
    }

    @Tag("live")
    @Test
    fun `learn delegates to OpenApiLearner for OPENAPI type`() {
        val explorer = ApiExplorer(
            ai = throwingAi(),
        )
        val discovered = DiscoveredApi(
            type = ApiType.OPENAPI,
            specUrl = "https://petstore3.swagger.io/api/v3/openapi.json",
            description = "Swagger Petstore",
        )
        val learned = explorer.learn(discovered)
        assertNotNull(learned)
        assertTrue(learned.description.contains("Pet Store") || learned.description.contains("OpenAPI")) {
            "Expected Petstore description, got: ${learned.description}"
        }
    }

    @Tag("live")
    @Test
    fun `learn delegates to GraphQlLearner for GRAPHQL type`() {
        val explorer = ApiExplorer(
            ai = throwingAi(),
        )
        val discovered = DiscoveredApi(
            type = ApiType.GRAPHQL,
            specUrl = "https://countries.trevorblades.com/graphql",
            description = "Countries GraphQL API",
        )
        val learned = explorer.learn(discovered)
        assertNotNull(learned)
        assertTrue(learned.description.contains("GraphQL"))
    }

    @Test
    fun `learn throws for UNKNOWN type`() {
        val explorer = ApiExplorer(
            ai = throwingAi(),
        )
        val discovered = DiscoveredApi(
            type = ApiType.UNKNOWN,
            specUrl = "https://example.com",
            description = "Could not determine API type",
        )
        assertThrows(IllegalStateException::class.java) {
            explorer.learn(discovered)
        }
    }

    /**
     * Returns an Ai that throws if used — for tests that only exercise [ApiExplorer.learn].
     */
    private fun throwingAi(): com.embabel.agent.api.common.Ai {
        return object : com.embabel.agent.api.common.Ai {
            override fun withLlm(llm: com.embabel.common.ai.model.LlmOptions): com.embabel.agent.api.common.PromptRunner {
                throw UnsupportedOperationException("LLM not available in tests")
            }

            override fun withEmbeddingService(criteria: com.embabel.common.ai.model.ModelSelectionCriteria): com.embabel.common.ai.model.EmbeddingService {
                throw UnsupportedOperationException("Embedding not available in tests")
            }
        }
    }
}
