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

import com.embabel.agent.api.client.openapi.OpenApiOperationTool
import com.embabel.agent.api.tool.Tool
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * The interceptor seam, and the guarantees that make it safe to have at all.
 *
 * Two of these matter more than the rest. A rejected call must NOT reach the network — the point is
 * to spend nothing on a mistake we could see coming. And an interceptor that throws must never
 * replace a real API error with its own, because a broken diagnostic is worse than none.
 */
class ApiCallInterceptorTest {

    private fun searchOp() = Operation().apply {
        operationId = "searchPapers"
        parameters = listOf(
            Parameter().name("q").`in`("query").schema(StringSchema()),
            Parameter().name("per_page").`in`("query").schema(StringSchema()),
        )
    }

    private fun toolWith(
        interceptors: List<ApiCallInterceptor>,
        operation: Operation = searchOp(),
    ): Pair<OpenApiOperationTool, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val tool = OpenApiOperationTool(
            baseUrl = "https://api.example.com",
            path = "/search",
            httpMethod = PathItem.HttpMethod.GET,
            operation = operation,
            restClient = builder.build(),
            interceptors = interceptors,
        )
        return tool to server
    }

    /** Records what it saw; never interferes. */
    private class Recording : ApiCallInterceptor {
        var seen: ApiCall? = null
        var errorSeen: ApiCallError? = null
        override fun beforeCall(call: ApiCall): ApiCallError? { seen = call; return null }
        override fun onError(call: ApiCall, error: ApiCallError): ApiCallError { errorSeen = error; return error }
    }

    @Nested
    inner class DefaultBehaviour {

        /**
         * The whole point of defaulting to empty: query-param forwarding is PERMISSIVE on GET, so a
         * pre-flight rejector would break callers that work today. Nobody opting in must change
         * nothing.
         */
        @Test
        fun `with no interceptors an undeclared argument is still forwarded, exactly as before`() {
            val (tool, server) = toolWith(emptyList())
            server.expect(requestTo("https://api.example.com/search?q=cats&undeclared=kept"))
                .andRespond(withSuccess("""{"ok":true}""", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"q": "cats", "undeclared": "kept"}""")

            assertInstanceOf(Tool.Result.Text::class.java, result)
            server.verify()
        }
    }

    @Nested
    inner class BeforeCall {

        @Test
        fun `a rejected call never reaches the network`() {
            val rejector = object : ApiCallInterceptor {
                override fun beforeCall(call: ApiCall) = ApiCallError(null, "nope: ${call.unknownArguments}")
            }
            val (tool, server) = toolWith(listOf(rejector))
            // No expectation registered: any request at all fails verification.

            val result = tool.call("""{"searchTerm": "cats"}""")

            val err = assertInstanceOf(Tool.Result.Error::class.java, result)
            assertTrue(err.message.contains("nope"), err.message)
            server.verify()
        }

        @Test
        fun `returning null lets the call proceed untouched`() {
            val recording = Recording()
            val (tool, server) = toolWith(listOf(recording))
            server.expect(requestTo("https://api.example.com/search?q=cats"))
                .andRespond(withSuccess("""{"ok":true}""", MediaType.APPLICATION_JSON))

            assertInstanceOf(Tool.Result.Text::class.java, tool.call("""{"q": "cats"}"""))
            server.verify()
        }

        @Test
        fun `the call describes what was supplied and what is declared`() {
            val recording = Recording()
            val (tool, server) = toolWith(listOf(recording))
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/search")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

            tool.call("""{"q": "cats", "searchTerm": "dogs"}""")

            val seen = recording.seen ?: error("beforeCall was never invoked")
            assertEquals("searchPapers", seen.operationName)
            assertEquals("GET", seen.httpMethod)
            assertTrue(seen.url.startsWith("https://api.example.com/search"), seen.url)
            assertEquals(setOf("q", "searchTerm"), seen.suppliedArgumentNames)
            assertEquals(setOf("q", "per_page"), seen.declaredParameterNames)
            assertEquals(setOf("searchTerm"), seen.unknownArguments)
        }

        /** A throwing interceptor must not take the call down with it. */
        @Test
        fun `an interceptor that throws is skipped, and the call proceeds`() {
            val exploding = object : ApiCallInterceptor {
                override fun beforeCall(call: ApiCall): ApiCallError? = throw IllegalStateException("boom")
            }
            val (tool, server) = toolWith(listOf(exploding))
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/search")))
                .andRespond(withSuccess("""{"ok":true}""", MediaType.APPLICATION_JSON))

            assertInstanceOf(Tool.Result.Text::class.java, tool.call("""{"q": "cats"}"""))
            server.verify()
        }

        @Test
        fun `the first rejection wins and later interceptors are not consulted`() {
            val calls = AtomicInteger(0)
            val first = object : ApiCallInterceptor {
                override fun beforeCall(call: ApiCall) = ApiCallError(null, "first")
            }
            val second = object : ApiCallInterceptor {
                override fun beforeCall(call: ApiCall): ApiCallError? { calls.incrementAndGet(); return null }
            }
            val (tool, _) = toolWith(listOf(first, second))

            val err = assertInstanceOf(Tool.Result.Error::class.java, tool.call("""{"q": "x"}"""))
            assertTrue(err.message.contains("first"), err.message)
            assertEquals(0, calls.get(), "a decided call must not be re-examined")
        }
    }

    @Nested
    inner class OnError {

        @Test
        fun `a 4xx is passed to the interceptor with its status and can be improved`() {
            val annotating = object : ApiCallInterceptor {
                override fun onError(call: ApiCall, error: ApiCallError) =
                    error.copy(message = "${error.message} [status=${error.status}]")
            }
            val (tool, server) = toolWith(listOf(annotating))
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/search")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("bad param"))

            val err = assertInstanceOf(Tool.Result.Error::class.java, tool.call("""{"q": "x"}"""))
            assertTrue(err.message.contains("[status=400]"), err.message)
            assertTrue(err.message.contains("bad param"), "the remote's own diagnostic must survive")
        }

        /** Improving an error must never be able to destroy it. */
        @Test
        fun `an interceptor that throws on error leaves the original error intact`() {
            val exploding = object : ApiCallInterceptor {
                override fun onError(call: ApiCall, error: ApiCallError): ApiCallError = throw IllegalStateException("boom")
            }
            val (tool, server) = toolWith(listOf(exploding))
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/search")))
                .andRespond(withServerError().body("upstream exploded"))

            val err = assertInstanceOf(Tool.Result.Error::class.java, tool.call("""{"q": "x"}"""))
            assertTrue(err.message.contains("500"), err.message)
            assertFalse(err.message.contains("boom"), "the interceptor's own failure must not surface")
        }

        @Test
        fun `interceptors compose in order, each seeing the previous one's message`() {
            val a = object : ApiCallInterceptor {
                override fun onError(call: ApiCall, error: ApiCallError) = error.copy(message = error.message + " |a")
            }
            val b = object : ApiCallInterceptor {
                override fun onError(call: ApiCall, error: ApiCallError) = error.copy(message = error.message + " |b")
            }
            val (tool, server) = toolWith(listOf(a, b))
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/search")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("nope"))

            val err = assertInstanceOf(Tool.Result.Error::class.java, tool.call("""{"q": "x"}"""))
            assertTrue(err.message.endsWith("|a |b"), err.message)
        }
    }

    /**
     * The shipped interceptor. Its own contract, tested without the HTTP stack — these are decisions
     * about text and policy, not about transport.
     */
    @Nested
    inner class DeclaredParameterDiagnosing {

        private val interceptor = DeclaredParameterDiagnosingInterceptor()

        private fun call(supplied: Set<String>, declared: Set<String>) = ApiCall(
            operationName = "searchPapers", httpMethod = "GET",
            url = "https://api.example.com/search",
            suppliedArgumentNames = supplied, declaredParameterNames = declared,
        )

        @Test
        fun `an undeclared argument is rejected pre-flight and the real names are given`() {
            val err = interceptor.beforeCall(call(setOf("searchTerm"), setOf("q", "per_page")))
                ?: error("an undeclared argument must be rejected")
            assertTrue(err.message.contains("searchTerm"), err.message)
            assertTrue(err.message.contains("per_page, q"), "names are listed sorted: ${err.message}")
            assertTrue(err.message.contains("NOT sent"), err.message)
            assertNull(err.status, "nothing was sent, so there is no status to report")
        }

        @Test
        fun `a correctly shaped call is not rejected`() {
            assertNull(interceptor.beforeCall(call(setOf("q", "per_page"), setOf("q", "per_page"))))
        }

        /** An empty declaration set means the spec told us nothing — guessing would be worse. */
        @Test
        fun `a spec that declares nothing never rejects`() {
            assertNull(interceptor.beforeCall(call(setOf("anything"), emptySet())))
        }

        @Test
        fun `singular and plural read correctly`() {
            val one = interceptor.beforeCall(call(setOf("x"), setOf("q")))!!.message
            assertTrue(one.contains("not a parameter."), one)
            val two = interceptor.beforeCall(call(setOf("x", "y"), setOf("q")))!!.message
            assertTrue(two.contains("not parameters."), two)
        }

        @Test
        fun `a 4xx is annotated with the declared names, appended not replaced`() {
            val out = interceptor.onError(
                call(setOf("q"), setOf("q", "per_page")),
                ApiCallError(422, "Unprocessable: per_page must be an integer"),
            )
            assertTrue(out.message.startsWith("Unprocessable"), out.message)
            assertTrue(out.message.contains("per_page, q"), "names are listed sorted: ${out.message}")
        }

        /** A 5xx is the remote's problem; listing parameters would misdirect the caller. */
        @Test
        fun `a 5xx is left alone`() {
            val original = ApiCallError(503, "Service Unavailable")
            assertEquals(original, interceptor.onError(call(setOf("q"), setOf("q")), original))
        }

        @Test
        fun `an error with no status is left alone`() {
            val original = ApiCallError(null, "Request cancelled")
            assertEquals(original, interceptor.onError(call(setOf("q"), setOf("q")), original))
        }

        /** A large operation must not flood the caller's context with parameter names. */
        @Test
        fun `a long parameter list is capped and says how many were omitted`() {
            val declared = (1..40).map { "p$it" }.toSet()
            val msg = DeclaredParameterDiagnosingInterceptor(maxParametersListed = 5)
                .beforeCall(call(setOf("nope"), declared))!!.message
            assertTrue(msg.contains("(+35 more)"), msg)
        }
    }
}
