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

data class DeviceCodeChallenge(
    val userCode: String,
    val deviceAuthId: String,
    val intervalSeconds: Int,
    val verificationUrl: String,
)

data class AuthorizationGrant(
    val authorizationCode: String,
    val codeVerifier: String,
)

sealed interface DevicePollResult {
    data object Pending : DevicePollResult

    data class SlowDown(
        val retryAfterSeconds: Long,
    ) : DevicePollResult

    data class Authorized(
        val grant: AuthorizationGrant,
    ) : DevicePollResult
}
