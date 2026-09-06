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

import org.springframework.web.client.RestClient
import org.springframework.web.client.body

fun interface CodexHttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): String
}

class RestClientCodexHttpTransport(
    private val restClient: RestClient = RestClient.create(),
) : CodexHttpTransport {

    override fun post(url: String, headers: Map<String, String>, body: String): String {
        var request = restClient.post()
            .uri(url)
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> request = request.header(k, v) }
        return request.body(body)
            .retrieve()
            .body<String>()
            ?: ""
    }
}
