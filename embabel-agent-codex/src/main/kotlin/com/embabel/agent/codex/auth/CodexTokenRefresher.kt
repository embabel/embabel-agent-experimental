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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String? = null,
    @JsonProperty("refresh_token") val refreshToken: String? = null,
    val error: String? = null,
    @JsonProperty("error_description") val errorDescription: String? = null,
)

class CodexTokenRefresher(
    private val restClient: RestClient = RestClient.create(),
) {

    private val objectMapper = jacksonObjectMapper()

    fun refresh(credentials: CodexCredentials): CodexCredentials {
        val responseBody = try {
            restClient.post()
                .uri(CodexOAuthConstants.TOKEN_URL)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(CodexOAuthBodies.refreshToken(credentials.refreshToken))
                .retrieve()
                .body<String>()
                ?: throw CodexAuthException("Empty response from token endpoint")
        } catch (e: RestClientResponseException) {
            throw refreshFailure(e.responseBodyAsString, e)
        }

        val tokenResponse = objectMapper.readValue(responseBody, TokenResponse::class.java)
        if (tokenResponse.error != null) {
            throw CodexAuthException(
                "Token refresh failed: ${tokenResponse.error} - ${tokenResponse.errorDescription}"
            )
        }

        val newAccessToken = tokenResponse.accessToken
            ?: throw CodexAuthException("No access_token in refresh response")

        return credentials.copy(
            accessToken = newAccessToken,
            refreshToken = tokenResponse.refreshToken ?: credentials.refreshToken,
            lastRefresh = Instant.now(),
        )
    }

    private fun refreshFailure(body: String, cause: RestClientResponseException): CodexAuthException {
        val tokenResponse = runCatching { objectMapper.readValue(body, TokenResponse::class.java) }.getOrNull()
        val detail = when {
            tokenResponse?.error != null ->
                "${tokenResponse.error} - ${tokenResponse.errorDescription}"
            body.isNotBlank() -> body.take(200)
            else -> cause.message ?: "HTTP ${cause.statusCode.value()}"
        }
        return CodexAuthException("Token refresh failed: $detail", cause)
    }
}
