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
package com.embabel.agent.codex.responses

import com.embabel.agent.codex.auth.CodexCredentials
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CodexCloudflareHeadersTest {

    @Nested
    inner class ExtractAccountId {

        @Test
        fun `extracts account id from nested openai auth claim`() {
            val payload = """{"sub":"user-123","https://api.openai.com/auth":{"chatgpt_account_id":"acct-nested-456"}}"""
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
            val jwt = "header.$encoded.signature"
            val result = CodexCloudflareHeaders.extractAccountIdFromJwt(jwt)
            assertEquals("acct-nested-456", result)
        }

        @Test
        fun `extracts account id from JWT payload claim`() {
            val payload = """{"sub":"user-123","https://api.openai.com/auth.chatgpt_account_id":"acct-test-789"}"""
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
            val jwt = "header.$encoded.signature"
            val result = CodexCloudflareHeaders.extractAccountIdFromJwt(jwt)
            assertEquals("acct-test-789", result)
        }

        @Test
        fun `returns null when claim missing from JWT`() {
            val payload = """{"sub":"user-123","other_claim":"value"}"""
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
            val jwt = "header.$encoded.signature"
            val result = CodexCloudflareHeaders.extractAccountIdFromJwt(jwt)
            assertNull(result)
        }

        @Test
        fun `returns null for malformed token`() {
            val result = CodexCloudflareHeaders.extractAccountIdFromJwt("not-a-jwt")
            assertNull(result)
        }
    }

    @Nested
    inner class BuildHeaders {

        @Test
        fun `includes account id from credentials when present`() {
            val credentials = CodexCredentials(
                accessToken = "tok",
                refreshToken = "ref",
                accountId = "acct-from-creds",
            )
            val headers = CodexCloudflareHeaders.build(credentials)
            assertEquals("acct-from-creds", headers["ChatGPT-Account-ID"])
        }

        @Test
        fun `includes user-agent and originator headers`() {
            val credentials = CodexCredentials(accessToken = "tok", refreshToken = "ref")
            val headers = CodexCloudflareHeaders.build(credentials)
            assertNotNull(headers["User-Agent"])
            assertNotNull(headers["originator"])
            assertEquals("codex_cli_rs", headers["originator"])
        }
    }
}
