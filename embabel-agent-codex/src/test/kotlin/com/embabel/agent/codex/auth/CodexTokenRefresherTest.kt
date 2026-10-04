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

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexTokenRefresherTest {

    @Nested
    inner class RefreshBody {

        @Test
        fun `refresh body uses refresh_token grant without redirect_uri`() {
            val body = CodexOAuthBodies.refreshToken("rt-abc")
            assertTrue(body.contains("grant_type=refresh_token"))
            assertTrue(body.contains("client_id=${CodexOAuthConstants.CLIENT_ID}"))
            assertTrue(body.contains("refresh_token=rt-abc"))
            assertFalse(body.contains("redirect_uri"))
        }

        @Test
        fun `refresh body form encodes token values`() {
            val body = CodexOAuthBodies.refreshToken("refresh+token & value")

            assertTrue(body.contains("refresh_token=refresh%2Btoken+%26+value"))
        }
    }
}
