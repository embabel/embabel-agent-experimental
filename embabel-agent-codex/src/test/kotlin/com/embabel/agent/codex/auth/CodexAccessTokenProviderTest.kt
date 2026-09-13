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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals

class CodexAccessTokenProviderTest {

    private val store = mockk<CodexAuthStore>()
    private val refresher = mockk<CodexTokenRefresher>()

    private fun jwt(expEpochSeconds: Long): String {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":$expEpochSeconds}""".toByteArray())
        return "$header.$payload.sig"
    }

    @Nested
    inner class AccessToken {

        @Test
        fun `returns existing token when jwt exp is in the future`() {
            val token = jwt(Instant.now().plusSeconds(3600).epochSecond)
            val credentials = CodexCredentials(
                accessToken = token,
                refreshToken = "refresh",
                lastRefresh = Instant.now().minusSeconds(60 * 60 * 5),
            )
            every { store.load() } returns credentials

            val provider = CodexAccessTokenProvider(store, refresher)
            assertEquals(token, provider.accessToken())
            verify(exactly = 0) { refresher.refresh(any()) }
        }

        @Test
        fun `falls back to access token when refresh fails but jwt still valid`() {
            val token = jwt(Instant.now().plusSeconds(600).epochSecond)
            val credentials = CodexCredentials(
                accessToken = token,
                refreshToken = "refresh",
                lastRefresh = Instant.EPOCH,
            )
            every { store.load() } returns credentials
            every { refresher.refresh(any()) } throws CodexAuthException("refresh_token_invalidated")

            val provider = CodexAccessTokenProvider(store, refresher)
            assertEquals(token, provider.accessToken())
        }

        @Test
        fun `refreshes when jwt exp is past`() {
            val stale = jwt(Instant.now().minusSeconds(10).epochSecond)
            val freshToken = jwt(Instant.now().plusSeconds(3600).epochSecond)
            val credentials = CodexCredentials(
                accessToken = stale,
                refreshToken = "refresh",
                lastRefresh = Instant.now(),
            )
            val fresh = credentials.copy(accessToken = freshToken, lastRefresh = Instant.now())
            every { store.load() } returns credentials
            every { refresher.refresh(credentials) } returns fresh
            every { store.save(fresh) } returns Unit

            val provider = CodexAccessTokenProvider(store, refresher)
            assertEquals(freshToken, provider.accessToken())
            verify(exactly = 1) { store.save(fresh) }
        }

        @Test
        fun `invalidateAndRefresh always refreshes`() {
            val credentials = CodexCredentials(
                accessToken = jwt(Instant.now().plusSeconds(3600).epochSecond),
                refreshToken = "refresh",
                lastRefresh = Instant.now(),
            )
            val fresh = credentials.copy(accessToken = "new")
            every { store.load() } returns credentials
            every { refresher.refresh(credentials) } returns fresh
            every { store.save(fresh) } returns Unit

            val provider = CodexAccessTokenProvider(store, refresher)
            assertEquals("new", provider.invalidateAndRefresh())
        }
    }

    @Nested
    inner class JwtExpiry {

        @Test
        fun `parses exp claim`() {
            val exp = Instant.now().plusSeconds(100)
            val parsed = CodexAccessTokenProvider.jwtExpiry(jwt(exp.epochSecond))
            assertEquals(exp.epochSecond, parsed!!.epochSecond)
        }

        @Test
        fun `returns null for opaque tokens`() {
            assertEquals(null, CodexAccessTokenProvider.jwtExpiry("not-a-jwt"))
        }
    }
}
