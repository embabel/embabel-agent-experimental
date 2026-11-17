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

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.HexFormat
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * The certificate authorities ONE API's calls trust, in place of the JVM's own.
 *
 * An API inside an organisation is usually signed by an authority no public trust store carries:
 * a Kubernetes cluster's own CA, a company's internal one. The choices otherwise are to add that
 * authority to the JVM's trust store, which trusts it for every call the application makes, or
 * to stop verifying, which trusts everybody. This trusts the given certificates for the one
 * client built with them and nothing else: a call through that client is refused unless the
 * server's certificate chains to one of them, and no other client is affected.
 *
 * The text is what an operator already has:
 *  - one or more PEM certificates (`-----BEGIN CERTIFICATE-----`), as a file holds them;
 *  - the same with its line breaks written as `\n`, as a single-line setting often carries it;
 *  - Base64 of either PEM or DER, which is what a kubeconfig's `certificate-authority-data` is.
 */
internal object TrustedCertificates {

    /** The certificates in [text]; refuses text that holds none. */
    fun parse(text: String): List<X509Certificate> {
        val bytes = bytesOf(text)
        val certificates = try {
            CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes))
        } catch (e: CertificateException) {
            throw IllegalArgumentException("the trusted certificates could not be read: ${e.message}", e)
        }
        require(certificates.isNotEmpty()) { "the trusted certificates hold no certificate" }
        return certificates.map { it as X509Certificate }
    }

    /** An [SSLContext] that trusts [certificates] and no other authority. */
    fun sslContext(certificates: List<X509Certificate>): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("trusted-$index", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }

    /** What tells two sets of certificates apart, whatever form each was written in. */
    fun fingerprint(certificates: List<X509Certificate>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        certificates.map { it.encoded }.sortedBy { HexFormat.of().formatHex(it) }.forEach(digest::update)
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun bytesOf(text: String): ByteArray {
        val unescaped = text.trim().replace("\\n", "\n")
        if (PEM_HEADER in unescaped) return unescaped.toByteArray()
        return try {
            Base64.getMimeDecoder().decode(unescaped)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("the trusted certificates are neither PEM nor Base64 of a certificate", e)
        }
    }

    private const val PEM_HEADER = "-----BEGIN CERTIFICATE-----"
}
