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

import com.embabel.agent.api.tool.Tool
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Live integration tests for [HttpProbeTool] against public APIs.
 */
@Tag("live")
class HttpProbeToolLiveTest {

    private val tool = HttpProbeTool()

    @Test
    fun `GET returns status and body`() {
        val result = tool.call("""{"url": "https://xkcd.com/info.0.json"}""")
        assertIsText(result)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("HTTP 200")) { "Expected 200, got: $text" }
        assertTrue(text.contains("Content-Type:")) { "Expected Content-Type header" }
        assertTrue(text.contains("\"num\"")) { "Expected xkcd JSON with 'num' field, got: $text" }
    }

    @Test
    fun `GET follows redirects`() {
        val result = tool.call("""{"url": "http://xkcd.com/info.0.json"}""")
        assertIsText(result)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("HTTP 200")) { "Expected 200 after redirect, got: $text" }
    }

    @Test
    fun `GET returns 404 for missing page`() {
        val result = tool.call("""{"url": "https://xkcd.com/nonexistent-endpoint-12345"}""")
        assertIsText(result)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("HTTP 404")) { "Expected 404, got: $text" }
    }

    @Test
    fun `POST to GraphQL endpoint with introspection query`() {
        val input = """
            {
                "url": "https://countries.trevorblades.com/graphql",
                "method": "POST",
                "body": "{\"query\":\"{ __schema { queryType { name } } }\"}"
            }
        """.trimIndent()
        val result = tool.call(input)
        assertIsText(result)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("HTTP 200")) { "Expected 200, got: $text" }
        assertTrue(text.contains("__schema") || text.contains("queryType")) {
            "Expected GraphQL introspection response, got: $text"
        }
    }

    @Test
    fun `POST to GraphQL with country query`() {
        val input = """
            {
                "url": "https://countries.trevorblades.com/graphql",
                "method": "POST",
                "body": "{\"query\":\"{ country(code: \\\"US\\\") { name capital } }\"}"
            }
        """.trimIndent()
        val result = tool.call(input)
        assertIsText(result)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("United States")) { "Expected US data, got: $text" }
    }

    @Test
    fun `GET detects OpenAPI spec`() {
        val result = tool.call("""{"url": "https://petstore3.swagger.io/api/v3/openapi.json"}""")
        assertIsText(result)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("HTTP 200")) { "Expected 200, got: $text" }
        assertTrue(text.contains("openapi") || text.contains("swagger")) {
            "Expected OpenAPI spec content, got: ${text.take(200)}"
        }
    }

    @Test
    fun `method defaults to GET`() {
        val result = tool.call("""{"url": "https://xkcd.com/info.0.json"}""")
        assertIsText(result)
        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("HTTP 200"))
    }

    @Test
    fun `returns error for invalid URL`() {
        val result = tool.call("""{"url": "https://this-domain-does-not-exist-12345.com"}""")
        assertInstanceOf(Tool.Result.Error::class.java, result)
    }

    @Test
    fun `returns error when url is missing`() {
        val result = tool.call("""{"method": "GET"}""")
        assertInstanceOf(Tool.Result.Error::class.java, result)
    }

    @Test
    fun `truncates long responses`() {
        val smallTool = HttpProbeTool(maxResponseLength = 100)
        val result = smallTool.call("""{"url": "https://petstore3.swagger.io/api/v3/openapi.json"}""")
        assertIsText(result)
        val text = (result as Tool.Result.Text).content
        // The body portion should be truncated - total text includes headers so will be > 100
        // but the body portion itself should be limited
        assertTrue(text.length < 300) { "Expected truncated response, got ${text.length} chars" }
    }

    private fun assertIsText(result: Tool.Result) {
        if (result is Tool.Result.Error) {
            fail<Unit>("Expected text result but got error: ${result.message}")
        }
        assertInstanceOf(Tool.Result.Text::class.java, result)
    }
}
