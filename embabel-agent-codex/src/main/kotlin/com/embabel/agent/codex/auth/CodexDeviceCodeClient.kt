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
private data class UserCodeResponse(
    @JsonProperty("user_code") val userCode: String? = null,
    @JsonProperty("device_auth_id") val deviceAuthId: String? = null,
    @JsonProperty("interval") val interval: Int? = null,
    @JsonProperty("verification_uri") val verificationUri: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DevicePollResponse(
    @JsonProperty("authorization_code") val authorizationCode: String? = null,
    @JsonProperty("code_verifier") val codeVerifier: String? = null,
    val error: String? = null,
    @JsonProperty("error_description") val errorDescription: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DeviceTokenResponse(
    @JsonProperty("access_token") val accessToken: String? = null,
    @JsonProperty("refresh_token") val refreshToken: String? = null,
    @JsonProperty("account_id") val accountId: String? = null,
    val error: String? = null,
)

class CodexDeviceCodeClient(
    private val restClient: RestClient = RestClient.create(),
) {

    private val objectMapper = jacksonObjectMapper()

    fun requestUserCode(): DeviceCodeChallenge {
        val body = restClient.post()
            .uri(CodexOAuthConstants.DEVICE_USERCODE_URL)
            .header("Content-Type", "application/json")
            .body("""{"client_id":"${CodexOAuthConstants.CLIENT_ID}"}""")
            .retrieve()
            .body<String>()
            ?: throw CodexAuthException("Empty response requesting user code")

        val resp = objectMapper.readValue(body, UserCodeResponse::class.java)
        return DeviceCodeChallenge(
            userCode = resp.userCode ?: throw CodexAuthException("No user_code in response"),
            deviceAuthId = resp.deviceAuthId ?: throw CodexAuthException("No device_auth_id in response"),
            intervalSeconds = resp.interval ?: 5,
            verificationUrl = resp.verificationUri ?: CodexOAuthConstants.DEVICE_PAGE,
        )
    }

    fun pollOnce(deviceAuthId: String, userCode: String): DevicePollResult {
        val body = try {
            restClient.post()
                .uri(CodexOAuthConstants.DEVICE_TOKEN_URL)
                .header("Content-Type", "application/json")
                .body("""{"device_auth_id":"$deviceAuthId","user_code":"$userCode"}""")
                .retrieve()
                .body<String>()
                ?: throw CodexAuthException("Empty response polling device authorization")
        } catch (e: RestClientResponseException) {
            return when (e.statusCode.value()) {
                403, 404 -> DevicePollResult.Pending
                429 -> DevicePollResult.SlowDown(
                    e.responseHeaders?.getFirst("Retry-After")?.toLongOrNull() ?: DEFAULT_SLOW_DOWN_SECONDS
                )
                else -> throw pollFailure(e)
            }
        }

        val resp = objectMapper.readValue(body, DevicePollResponse::class.java)
        return when (resp.error) {
            "authorization_pending" -> DevicePollResult.Pending
            "slow_down" -> DevicePollResult.SlowDown(DEFAULT_SLOW_DOWN_SECONDS)
            null -> {
                val code = resp.authorizationCode
                    ?: throw CodexAuthException("Device authorization response has no authorization_code")
                val verifier = resp.codeVerifier
                    ?: throw CodexAuthException("Device authorization response has no code_verifier")
                DevicePollResult.Authorized(AuthorizationGrant(authorizationCode = code, codeVerifier = verifier))
            }
            else -> throw CodexAuthException(
                "Device authorization failed: ${resp.error}" +
                    (resp.errorDescription?.let { " - $it" } ?: "")
            )
        }
    }

    fun loginInteractive(
        challenge: DeviceCodeChallenge,
        maxWaitSeconds: Int = 300,
        sleeper: (Long) -> Unit = { Thread.sleep(it * 1000) },
    ): AuthorizationGrant {
        val deadline = Instant.now().plusSeconds(maxWaitSeconds.toLong())
        var intervalSeconds = challenge.intervalSeconds.coerceAtLeast(1).toLong()
        while (Instant.now().isBefore(deadline)) {
            when (val result = pollOnce(challenge.deviceAuthId, challenge.userCode)) {
                is DevicePollResult.Authorized -> return result.grant
                DevicePollResult.Pending -> sleeper(intervalSeconds)
                is DevicePollResult.SlowDown -> {
                    intervalSeconds = maxOf(intervalSeconds + 1, result.retryAfterSeconds)
                    sleeper(intervalSeconds)
                }
            }
        }
        throw CodexAuthException("Device authorization timed out after ${maxWaitSeconds}s")
    }

    fun exchange(grant: AuthorizationGrant): CodexCredentials {
        val body = try {
            restClient.post()
                .uri(CodexOAuthConstants.TOKEN_URL)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(CodexOAuthBodies.authorizationCode(grant.authorizationCode, grant.codeVerifier))
                .retrieve()
                .body<String>()
                ?: throw CodexAuthException("Empty response during code exchange")
        } catch (e: RestClientResponseException) {
            throw CodexAuthException("Code exchange failed: HTTP ${e.statusCode.value()}", e)
        }

        val resp = objectMapper.readValue(body, DeviceTokenResponse::class.java)
        if (resp.error != null) throw CodexAuthException("Code exchange failed: ${resp.error}")
        return CodexCredentials(
            accessToken = resp.accessToken ?: throw CodexAuthException("No access_token"),
            refreshToken = resp.refreshToken ?: throw CodexAuthException("No refresh_token"),
            accountId = resp.accountId,
            lastRefresh = Instant.now(),
        )
    }

    private fun pollFailure(cause: RestClientResponseException): CodexAuthException {
        val response = runCatching {
            objectMapper.readValue(cause.responseBodyAsString, DevicePollResponse::class.java)
        }.getOrNull()
        val detail = response?.errorDescription ?: response?.error ?: "HTTP ${cause.statusCode.value()}"
        return CodexAuthException("Device authorization polling failed: $detail", cause)
    }

    companion object {
        private const val DEFAULT_SLOW_DOWN_SECONDS = 5L
    }
}
