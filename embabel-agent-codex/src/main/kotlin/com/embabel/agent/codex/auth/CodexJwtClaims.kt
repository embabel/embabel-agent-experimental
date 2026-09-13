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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

/** Reads routing/expiry hints only; token authentication remains the server's responsibility. */
internal object CodexJwtClaims {
    private val mapper = jacksonObjectMapper()

    fun payload(token: String): JsonNode? = try {
        val parts = token.split('.')
        if (parts.size != 3) null else mapper.readTree(Base64.getUrlDecoder().decode(parts[1]))
            ?.takeIf { it.isObject }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.io.IOException) {
        null
    }
}
