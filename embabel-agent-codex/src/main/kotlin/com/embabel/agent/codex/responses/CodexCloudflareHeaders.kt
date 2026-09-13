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
import com.embabel.agent.codex.auth.CodexJwtClaims

private const val USER_AGENT = "codex_cli_rs/0.0.0 (Embabel Agent)"
private const val ORIGINATOR = "codex_cli_rs"
private const val AUTH_CLAIM_OBJECT = "https://api.openai.com/auth"
private const val FLAT_ACCOUNT_ID_CLAIM = "https://api.openai.com/auth.chatgpt_account_id"

object CodexCloudflareHeaders {

    fun build(credentials: CodexCredentials): Map<String, String> {
        val accountId = credentials.accountId ?: extractAccountIdFromJwt(credentials.accessToken)
        return buildMap {
            put("User-Agent", USER_AGENT)
            put("originator", ORIGINATOR)
            accountId?.let { put("ChatGPT-Account-ID", it) }
        }
    }

    fun extractAccountIdFromJwt(token: String): String? {
        val payload = CodexJwtClaims.payload(token) ?: return null
        val nested = payload.path(AUTH_CLAIM_OBJECT).path("chatgpt_account_id")
        val claim = if (nested.isTextual) nested else payload.path(FLAT_ACCOUNT_ID_CLAIM)
        return claim.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }
    }
}
