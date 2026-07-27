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

import java.time.Duration
import java.time.Instant
import java.util.Base64

private val TOKEN_EXPIRY_THRESHOLD: Duration = Duration.ofMinutes(50)
private val REFRESH_SKEW: Duration = Duration.ofMinutes(2)

class CodexAccessTokenProvider(
    private val store: CodexAuthStore,
    private val refresher: CodexTokenRefresher,
) {

    private val refreshLock = Any()

    fun accessToken(): String {
        val credentials = store.load()
            ?: throw CodexAuthException("No Codex credentials found. Run device login or import ~/.codex/auth.json")
        return if (needsRefresh(credentials)) {
            refreshOrFallback(credentials).accessToken
        } else {
            credentials.accessToken
        }
    }

    fun invalidateAndRefresh(): String =
        refreshAndStore(force = true).accessToken

    private fun needsRefresh(credentials: CodexCredentials): Boolean {
        jwtExpiry(credentials.accessToken)?.let { exp ->
            return Instant.now().isAfter(exp.minus(REFRESH_SKEW))
        }
        val lastRefresh = credentials.lastRefresh ?: return true
        return Instant.now().isAfter(lastRefresh.plus(TOKEN_EXPIRY_THRESHOLD))
    }

    private fun refreshOrFallback(credentials: CodexCredentials): CodexCredentials {
        return try {
            refreshAndStore(force = false)
        } catch (e: CodexAuthException) {
            if (credentials.accessToken.isNotBlank() && !isAccessTokenExpired(credentials.accessToken)) {
                credentials
            } else {
                throw e
            }
        }
    }

    private fun refreshAndStore(force: Boolean): CodexCredentials {
        synchronized(refreshLock) {
            val latest = store.load()
                ?: throw CodexAuthException("No Codex credentials found")
            if (!force && !needsRefresh(latest)) {
                return latest
            }
            val refreshed = refresher.refresh(latest)
            store.save(refreshed)
            return refreshed
        }
    }

    companion object {
        internal fun jwtExpiry(accessToken: String): Instant? {
            val parts = accessToken.split('.')
            if (parts.size < 2) return null
            return try {
                val payload = String(Base64.getUrlDecoder().decode(pad(parts[1])))
                val expMatch = Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload) ?: return null
                Instant.ofEpochSecond(expMatch.groupValues[1].toLong())
            } catch (_: Exception) {
                null
            }
        }

        private fun isAccessTokenExpired(accessToken: String): Boolean {
            val exp = jwtExpiry(accessToken) ?: return false
            return Instant.now().isAfter(exp)
        }

        private fun pad(value: String): String {
            val rem = value.length % 4
            return if (rem == 0) value else value + "=".repeat(4 - rem)
        }
    }
}
