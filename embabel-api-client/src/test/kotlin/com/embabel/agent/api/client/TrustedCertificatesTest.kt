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

import com.embabel.agent.api.client.openapi.OpenApiClientProperties
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException

/**
 * An API signed by an authority the JVM does not trust, which is what a Kubernetes cluster's API
 * is, is reached by a client given that authority's certificate, and by no other. The server here
 * holds a certificate made for the test by the JDK's own `keytool`, so no public authority vouches
 * for it and nothing is checked in.
 */
class TrustedCertificatesTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var server: HttpsServer
    private lateinit var pem: String

    @BeforeEach
    fun start() {
        val store = dir.resolve("server.p12")
        keytool(
            "-genkeypair", "-alias", "api", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
            "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD,
        )
        pem = keytool("-exportcert", "-alias", "api", "-rfc", "-keystore", store.toString(), "-storepass", PASSWORD)
        val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, PASSWORD.toCharArray()) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD.toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(context)
            createContext("/pods") { exchange ->
                val body = """{"items":[]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun get(trustedCertificates: String?): String? =
        RestClient.builder()
            .requestFactory(CredentialSafeRedirects.overJdkClient(TIMEOUT, TIMEOUT, emptySet(), trustedCertificates))
            .build()
            .get().uri(URI("https://127.0.0.1:${server.address.port}/pods")).retrieve().body(String::class.java)

    @Test
    fun `a client given the API's authority reaches it`() {
        assertEquals("""{"items":[]}""", get(pem))
    }

    @Test
    fun `a client not given it is refused, as the JVM's own trust refuses it`() {
        val refused = assertThrows<ResourceAccessException> { get(null) }
        assertTrue(generateSequence<Throwable>(refused) { it.cause }.any { it is SSLHandshakeException }, "refused for the certificate: $refused")
    }

    @Test
    fun `the authority is read as a setting carries it, on one line or as a kubeconfig's Base64`() {
        assertEquals("""{"items":[]}""", get(pem.trim().replace("\n", "\\n")))
        assertEquals("""{"items":[]}""", get(Base64.getEncoder().encodeToString(pem.toByteArray())))
        val der = TrustedCertificates.parse(pem).single().encoded
        assertEquals("""{"items":[]}""", get(Base64.getEncoder().encodeToString(der)))
    }

    @Test
    fun `text that holds no certificate is refused rather than trusted or ignored`() {
        assertThrows<IllegalArgumentException> { get("not a certificate") }
        assertThrows<IllegalArgumentException> { get("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----") }
    }

    @Test
    fun `one client is shared per authority, and it is not the client that trusts the JVM's`() {
        val trusting = SharedHttpClients.redirectRefusing(TIMEOUT, pem)
        assertSame(trusting, SharedHttpClients.redirectRefusing(TIMEOUT, Base64.getEncoder().encodeToString(pem.toByteArray())))
        assertNotSame(trusting, SharedHttpClients.redirectRefusing(TIMEOUT))
        assertSame(SharedHttpClients.redirectRefusing(TIMEOUT), SharedHttpClients.redirectRefusing(TIMEOUT, "  "))
    }

    @Test
    fun `the properties an API's tools are built with carry its authority`() {
        val properties = OpenApiClientProperties(trustedCertificates = pem)
        assertEquals(pem, properties.trustedCertificates)
        assertEquals(null, OpenApiClientProperties().trustedCertificates)
    }

    /* The JDK's keytool, so the test needs no certificate library and keeps no key. Returns what it printed. */
    private fun keytool(vararg arguments: String): String {
        val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()) + arguments)
            .redirectErrorStream(true).start()
        val printed = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: $printed" }
        return printed
    }

    private companion object {
        const val PASSWORD = "changeit"
        val TIMEOUT: Duration = Duration.ofSeconds(7)
    }
}
