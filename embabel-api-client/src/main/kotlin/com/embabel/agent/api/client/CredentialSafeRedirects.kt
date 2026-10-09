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

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.client.AbstractBufferingClientHttpRequest
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.util.UriComponentsBuilder
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * A [ClientHttpRequestFactory] that follows redirects itself, so a credential reaches only the
 * origin it was granted for.
 *
 * The JDK client's own `Redirect.NORMAL` drops `Authorization` and `Cookie` on a cross-origin hop
 * and re-sends every other header, a declared API-key header such as `X-Api-Key` and every custom
 * credential header among them. Where a source redirects is the source's choice, never the realm
 * author's, so a source with an open redirect, a compromised one, or one that moved its API to a
 * third party received the key (embabel/me#2414). [delegate] must therefore not follow redirects;
 * [overJdkClient] builds one that does not.
 *
 * Each hop is judged against the request that produced it:
 *  - same origin (scheme, host and port): followed with every header, credentials included;
 *  - another origin: followed with only [CROSS_ORIGIN_HEADERS], and with
 *    [credentialQueryParameters] removed from the target's query. That is an allow-list of what
 *    may cross, not a list of credentials to strip, because a strip-list leaks whatever credential
 *    shape nobody listed: a header parameter the spec declares, a header an interceptor adds. It is
 *    followed rather than refused because real APIs redirect a download to a signed URL on another
 *    host (GitHub archives and release assets land on a CDN), and that URL carries its own
 *    authorisation. Once dropped, the credentials stay dropped for the rest of the chain;
 *  - https to http: refused. A request asked for over TLS is not continued in clear, with
 *    credentials or without, and the JDK's NORMAL policy refused it too, so no source that worked
 *    before stops working;
 *  - beyond [maxHops]: refused.
 *
 * A refusal is an [IOException] naming the hop, which the caller sees as a failed call.
 */
class CredentialSafeRedirects(
    private val delegate: ClientHttpRequestFactory,
    private val maxHops: Int,
    private val credentialQueryParameters: Set<String>,
) : ClientHttpRequestFactory {

    override fun createRequest(uri: URI, httpMethod: HttpMethod): ClientHttpRequest =
        FollowingRequest(uri, httpMethod)

    /* Buffered, because a 307 or 308 must re-send the same body to the next hop. */
    private inner class FollowingRequest(
        private val uri: URI,
        private val method: HttpMethod,
    ) : AbstractBufferingClientHttpRequest() {
        override fun getURI(): URI = uri
        override fun getMethod(): HttpMethod = method
        override fun executeInternal(headers: HttpHeaders, bufferedOutput: ByteArray): ClientHttpResponse =
            follow(Hop(uri, method, HttpHeaders.copyOf(headers), bufferedOutput))
    }

    private class Hop(val uri: URI, val method: HttpMethod, val headers: HttpHeaders, val body: ByteArray)

    private fun follow(first: Hop): ClientHttpResponse {
        var hop = first
        var followed = 0
        while (true) {
            val response = send(hop)
            val status = response.statusCode.value()
            val location = response.headers.getFirst(HttpHeaders.LOCATION)
            if (status !in REDIRECT_STATUSES || location.isNullOrBlank()) return response
            response.close()
            if (followed == maxHops) {
                throw IOException("Stopped after $maxHops redirects; ${hop.uri} redirected again to $location")
            }
            followed++
            hop = next(hop, status, location)
        }
    }

    private fun send(hop: Hop): ClientHttpResponse {
        val request = delegate.createRequest(hop.uri, hop.method)
        request.headers.putAll(hop.headers)
        if (hop.body.isNotEmpty()) request.body.write(hop.body)
        return request.execute()
    }

    private fun next(from: Hop, status: Int, location: String): Hop {
        val target = try {
            from.uri.resolve(location)
        } catch (e: IllegalArgumentException) {
            throw IOException("${from.uri} redirected to an unreadable Location '$location'", e)
        }
        val scheme = target.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IOException("${from.uri} redirected to $target, which is not an http or https URL; not followed")
        }
        if (from.uri.scheme.equals("https", ignoreCase = true) && scheme == "http") {
            throw IOException("${from.uri} redirected from https to $target over plain http; not followed")
        }

        /* The same method rewrites the JDK client applied: a 303 always becomes a GET, and so does
         * a POST answered with 301 or 302. Only 307 and 308 promise the body may be sent again. */
        val becomesGet = (status == 303 && from.method != HttpMethod.HEAD) ||
            (status in 301..302 && from.method == HttpMethod.POST)

        val sameOrigin = sameOrigin(from.uri, target)
        val headers = if (sameOrigin) HttpHeaders.copyOf(from.headers) else crossOriginHeaders(from.headers)
        if (becomesGet) {
            headers.remove(HttpHeaders.CONTENT_TYPE)
            headers.remove(HttpHeaders.CONTENT_LENGTH)
        }
        return Hop(
            uri = if (sameOrigin) target else withoutCredentialQuery(target),
            method = if (becomesGet) HttpMethod.GET else from.method,
            headers = headers,
            body = if (becomesGet) ByteArray(0) else from.body,
        )
    }

    private fun crossOriginHeaders(headers: HttpHeaders): HttpHeaders {
        val kept = HttpHeaders()
        CROSS_ORIGIN_HEADERS.forEach { name ->
            headers.get(name)?.let { kept.addAll(name, it) }
        }
        return kept
    }

    /* A query-string API key lives in the URL, and a source that echoes its query string into
     * Location would otherwise hand the key to the next host inside the target itself. */
    private fun withoutCredentialQuery(target: URI): URI {
        if (credentialQueryParameters.isEmpty() || target.rawQuery == null) return target
        val builder = UriComponentsBuilder.fromUri(target)
        credentialQueryParameters.forEach { builder.replaceQueryParam(it) }
        return builder.build(true).toUri()
    }

    companion object {

        /* The JDK client's own default limit (jdk.httpclient.redirects.retrylimit). */
        const val MAX_HOPS = 5

        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        /**
         * What may follow a request to another origin: content negotiation and the user agent.
         * Everything else stays behind, whatever it carries.
         */
        val CROSS_ORIGIN_HEADERS: List<String> = listOf(
            HttpHeaders.ACCEPT,
            HttpHeaders.ACCEPT_CHARSET,
            HttpHeaders.ACCEPT_ENCODING,
            HttpHeaders.ACCEPT_LANGUAGE,
            HttpHeaders.CONTENT_LANGUAGE,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.USER_AGENT,
        )

        /**
         * The factory every credentialed API client is built on: a JDK client that never follows
         * a redirect itself, wrapped so that redirects are followed here.
         */
        fun overJdkClient(
            connectTimeout: Duration?,
            readTimeout: Duration?,
            credentialQueryParameters: Set<String>,
        ): ClientHttpRequestFactory {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .apply { connectTimeout?.let { connectTimeout(it) } }
                .build()
            val jdk = JdkClientHttpRequestFactory(client).apply {
                readTimeout?.let { setReadTimeout(it) }
            }
            return CredentialSafeRedirects(jdk, MAX_HOPS, credentialQueryParameters)
        }

        internal fun sameOrigin(a: URI, b: URI): Boolean =
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.host.equals(b.host, ignoreCase = true) &&
                effectivePort(a) == effectivePort(b)

        private fun effectivePort(uri: URI): Int = when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
    }
}
