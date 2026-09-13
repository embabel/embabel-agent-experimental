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

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodexDeviceCodeClientTest {

    @Test
    fun `treats forbidden and not found as authorization pending`() {
        val (client, server) = clientAndServer()
        server.expect(requestTo(CodexOAuthConstants.DEVICE_TOKEN_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.FORBIDDEN))
        server.expect(requestTo(CodexOAuthConstants.DEVICE_TOKEN_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertEquals(DevicePollResult.Pending, client.pollOnce("device", "code"))
        assertEquals(DevicePollResult.Pending, client.pollOnce("device", "code"))
        server.verify()
    }

    @Test
    fun `reports retry delay for rate limiting`() {
        val (client, server) = clientAndServer()
        server.expect(requestTo(CodexOAuthConstants.DEVICE_TOKEN_URL))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "7"))

        assertEquals(DevicePollResult.SlowDown(7), client.pollOnce("device", "code"))
        server.verify()
    }

    @Test
    fun `fails immediately for terminal HTTP and OAuth errors`() {
        val (httpClient, httpServer) = clientAndServer()
        httpServer.expect(requestTo(CodexOAuthConstants.DEVICE_TOKEN_URL))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertFailsWith<CodexAuthException> { httpClient.pollOnce("device", "code") }
        httpServer.verify()

        val (oauthClient, oauthServer) = clientAndServer()
        oauthServer.expect(requestTo(CodexOAuthConstants.DEVICE_TOKEN_URL))
            .andRespond(
                withSuccess(
                    """{"error":"access_denied","error_description":"User denied access"}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        val error = assertFailsWith<CodexAuthException> { oauthClient.pollOnce("device", "code") }
        assertTrue(error.message.orEmpty().contains("access_denied"))
        oauthServer.verify()
    }

    @Test
    fun `interactive login honors server retry delay`() {
        val (client, server) = clientAndServer()
        server.expect(requestTo(CodexOAuthConstants.DEVICE_TOKEN_URL))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "7"))
        server.expect(requestTo(CodexOAuthConstants.DEVICE_TOKEN_URL))
            .andRespond(
                withSuccess(
                    """{"authorization_code":"auth-code","code_verifier":"verifier"}""",
                    MediaType.APPLICATION_JSON,
                )
            )
        val sleeps = mutableListOf<Long>()
        val challenge = DeviceCodeChallenge("user-code", "device-id", 3, "https://example.test")

        val grant = client.loginInteractive(challenge, maxWaitSeconds = 30) { sleeps += it }

        assertEquals(AuthorizationGrant("auth-code", "verifier"), grant)
        assertEquals(listOf(7L), sleeps)
        server.verify()
    }

    private fun clientAndServer(): Pair<CodexDeviceCodeClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return CodexDeviceCodeClient(builder.build()) to server
    }
}
