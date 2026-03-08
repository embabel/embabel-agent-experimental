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

import java.util.concurrent.ConcurrentHashMap

/**
 * Store for API credentials, keyed by API name.
 * Provides a central place to manage credentials for learned APIs,
 * supporting both static configuration and runtime updates.
 */
interface CredentialStore {

    /**
     * Get credentials for the given API name.
     * Returns [ApiCredentials.None] if no credentials are stored.
     */
    fun credentialsFor(apiName: String): ApiCredentials

    /**
     * Store or update credentials for the given API name.
     */
    fun setCredentials(apiName: String, credentials: ApiCredentials)
}

/**
 * In-memory [CredentialStore] backed by a [ConcurrentHashMap].
 */
class MapCredentialStore(
    initial: Map<String, ApiCredentials> = emptyMap(),
) : CredentialStore {

    private val credentials = ConcurrentHashMap<String, ApiCredentials>(initial)

    override fun credentialsFor(apiName: String): ApiCredentials =
        credentials[apiName] ?: ApiCredentials.None

    override fun setCredentials(apiName: String, credentials: ApiCredentials) {
        this.credentials[apiName] = credentials
    }
}
