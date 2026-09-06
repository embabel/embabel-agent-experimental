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

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object CodexOAuthBodies {

    fun refreshToken(refreshToken: String): String =
        "grant_type=refresh_token" +
            "&client_id=${encode(CodexOAuthConstants.CLIENT_ID)}" +
            "&refresh_token=${encode(refreshToken)}"

    fun authorizationCode(code: String, codeVerifier: String): String =
        "grant_type=authorization_code" +
            "&client_id=${encode(CodexOAuthConstants.CLIENT_ID)}" +
            "&code=${encode(code)}" +
            "&code_verifier=${encode(codeVerifier)}" +
            "&redirect_uri=${encode(CodexOAuthConstants.CALLBACK_REDIRECT)}"

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
