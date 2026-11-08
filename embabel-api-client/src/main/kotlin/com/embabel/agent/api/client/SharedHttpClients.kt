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

import java.net.http.HttpClient
import java.time.Duration
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The JDK [HttpClient]s this module sends through, shared rather than built per tool.
 *
 * A JDK client owns a selector thread (`HttpClient-N-SelectorManager`) that lives until the
 * client is closed or collected, and a client held by a learned tool is never collected while
 * the tool is reachable. Building one per tool therefore cost a thread per tool per build, and
 * an application that rebuilt its tools per context or per credential change grew without bound
 * (#51, #52). A client is thread-safe and carries no per-request state, so nothing is lost by
 * sharing it: credentials, query parameters and the read timeout all travel with the request.
 *
 * What cannot travel with the request is the redirect policy and the connect timeout, which are
 * fixed when a client is built. Those are the keys: one client that follows redirects, and one
 * that never does per distinct connect timeout. Connect timeouts come from configuration, so the
 * number of distinct values is the number of configurations, not the number of tools.
 *
 * The clients are never closed. Their threads are daemons, and there is one per key for the life
 * of the JVM, which is the bound this exists to establish.
 */
internal object SharedHttpClients {

    /**
     * For unauthenticated fetches (a spec, an introspection query, an exploratory probe), where
     * no credential rides on the request and the JDK's own redirect handling is safe.
     */
    val redirectFollowing: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /* Optional, because ConcurrentHashMap refuses a null key and "no connect timeout" is a key. */
    private val nonFollowing = ConcurrentHashMap<Optional<Duration>, HttpClient>()

    /**
     * For credentialed calls, which [CredentialSafeRedirects] follows itself. This client must
     * never follow a redirect: if it did, a credential header would go with it to another origin.
     */
    fun redirectRefusing(connectTimeout: Duration?): HttpClient =
        nonFollowing.computeIfAbsent(Optional.ofNullable(connectTimeout)) { key ->
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .apply { key.ifPresent { connectTimeout(it) } }
                .build()
        }
}
