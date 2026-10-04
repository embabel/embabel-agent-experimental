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
package com.embabel.agent.codex.auth

import com.embabel.agent.codex.responses.CodexCloudflareHeaders
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.HttpServerErrorException
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CodexAuthContractsTest {
    @Nested
    inner class JwtParsing {
        @Test
        fun `uses json structure rather than matching claim names inside strings`() {
            val payload = """{"description":"fake exp: 123","exp":456,"https://api.openai.com/auth":{"chatgpt_account_id":"acct\"quoted"}}"""
            assertEquals(Instant.ofEpochSecond(456), CodexAccessTokenProvider.jwtExpiry(token(payload)))
            assertEquals("acct\"quoted", CodexCloudflareHeaders.extractAccountIdFromJwt(token(payload)))
            assertNull(CodexAccessTokenProvider.jwtExpiry(token("""{"exp":"456"}""")))
        }

        @Test
        fun `rejects empty invalid or nonobject json without throwing`() {
            for (payload in listOf("", "{", "null", "[]")) {
                assertNull(CodexCloudflareHeaders.extractAccountIdFromJwt(token(payload)))
                assertNull(CodexAccessTokenProvider.jwtExpiry(token(payload)))
            }
        }
    }

    @Nested
    inner class RefreshFailures {
        @Test
        fun `preserves retryable status for rate limits and server failures`() {
            for (status in listOf(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE)) {
                val builder = RestClient.builder()
                val server = MockRestServiceServer.bindTo(builder).build()
                server.expect(requestTo(CodexOAuthConstants.TOKEN_URL)).andRespond(
                    withStatus(status).contentType(MediaType.APPLICATION_JSON).body("""{"error":"temporarily_unavailable"}"""))
                val error = assertFailsWith<RestClientResponseException> {
                    CodexTokenRefresher(builder.build()).refresh(CodexCredentials("token", "refresh"))
                }
                assertEquals(status, error.statusCode)
                server.verify()
            }
        }

        @Test
        fun `classifies rejected refresh credentials as terminal auth`() {
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            server.expect(requestTo(CodexOAuthConstants.TOKEN_URL)).andRespond(
                withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"invalid_grant"}"""))
            assertFailsWith<CodexAuthException> {
                CodexTokenRefresher(builder.build()).refresh(CodexCredentials("token", "refresh"))
            }
            server.verify()
        }

        @Test
        fun `keeps an unexpired token when proactive refresh is temporarily unavailable`() {
            val access = token("""{"exp":${Instant.now().plusSeconds(90).epochSecond}}""")
            val credentials = CodexCredentials(access, "refresh")
            val store = mockk<CodexAuthStore>()
            val refresher = mockk<CodexTokenRefresher>()
            every { store.load() } returns credentials
            every { refresher.refresh(credentials) } throws HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)
            assertEquals(access, CodexAccessTokenProvider(store, refresher).accessToken())
        }
    }

    private fun token(payload: String): String =
        "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())}.signature"
}
