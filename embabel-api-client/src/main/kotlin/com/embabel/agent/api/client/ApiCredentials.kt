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

/**
 * Credentials supplied by the caller to satisfy [AuthRequirement]s.
 */
sealed interface ApiCredentials {
    data object None : ApiCredentials
    data class Token(val token: String) : ApiCredentials
    data class ApiKey(val value: String) : ApiCredentials
    /** Arbitrary HTTP headers — for APIs that need custom auth headers (e.g. RapidAPI). */
    data class CustomHeaders(val headers: Map<String, String>) : ApiCredentials
    data class Multiple(val credentials: List<ApiCredentials>) : ApiCredentials

    /**
     * OAuth2 credentials with access token, refresh token, and provider config.
     * The [accessToken] is used for API calls. When it expires, the [refreshToken]
     * is used to obtain a new one via the [tokenUrl].
     */
    data class OAuth2(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiry: java.time.Instant? = null,
        val tokenUrl: String,
        val clientId: String,
        val clientSecret: String,
        val scopes: String? = null,
    ) : ApiCredentials {

        /** Whether the access token has expired or will expire within the buffer period. */
        fun isExpired(bufferSeconds: Long = 300): Boolean {
            val exp = expiry ?: return false
            return java.time.Instant.now().plusSeconds(bufferSeconds).isAfter(exp)
        }

        /** Create a copy with a refreshed access token and new expiry. */
        fun withRefreshedToken(newAccessToken: String, newExpiry: java.time.Instant?, newRefreshToken: String? = null): OAuth2 =
            copy(
                accessToken = newAccessToken,
                expiry = newExpiry,
                refreshToken = newRefreshToken ?: refreshToken,
            )
    }
}
