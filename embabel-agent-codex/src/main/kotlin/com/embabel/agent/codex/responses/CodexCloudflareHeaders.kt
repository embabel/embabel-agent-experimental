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
import java.util.Base64

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
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = String(Base64.getUrlDecoder().decode(padBase64(parts[1])))
            extractNestedAccountId(payload) ?: extractJsonStringValue(payload, FLAT_ACCOUNT_ID_CLAIM)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractNestedAccountId(payload: String): String? {
        val authObj = extractJsonObject(payload, AUTH_CLAIM_OBJECT) ?: return null
        return extractJsonStringValue(authObj, "chatgpt_account_id")
    }

    private fun extractJsonObject(json: String, key: String): String? {
        val escapedKey = "\"$key\""
        val keyIndex = json.indexOf(escapedKey)
        if (keyIndex == -1) return null
        val braceStart = json.indexOf('{', keyIndex + escapedKey.length)
        if (braceStart == -1) return null
        var depth = 0
        for (i in braceStart until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(braceStart, i + 1)
                }
            }
        }
        return null
    }

    private fun padBase64(input: String): String {
        val remainder = input.length % 4
        return if (remainder == 0) input else input + "=".repeat(4 - remainder)
    }

    private fun extractJsonStringValue(json: String, key: String): String? {
        val escapedKey = "\"${key}\""
        val keyIndex = json.indexOf(escapedKey)
        if (keyIndex == -1) return null
        val colonIndex = json.indexOf(':', keyIndex + escapedKey.length)
        if (colonIndex == -1) return null
        val valueStart = json.indexOfFirst(colonIndex + 1) { it == '"' }
        if (valueStart == -1) return null
        val valueEnd = json.indexOf('"', valueStart + 1)
        if (valueEnd == -1) return null
        return json.substring(valueStart + 1, valueEnd)
    }

    private fun String.indexOfFirst(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (i in startIndex until length) {
            if (predicate(this[i])) return i
        }
        return -1
    }
}
