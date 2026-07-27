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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileCodexAuthStoreTest {

    private val objectMapper = jacksonObjectMapper().apply {
        findAndRegisterModules()
    }

    @Test
    fun `default Embabel store is separate from Codex CLI auth`() {
        assertEquals(
            Path.of(System.getProperty("user.home"), ".embabel", "codex-auth.json"),
            defaultEmbabelCodexPath(),
        )
        assertFalse(defaultEmbabelCodexPath() == defaultCodexCliPath())
    }

    @Nested
    inner class SaveAndLoad {

        @Test
        fun `save and load roundtrip`(@TempDir dir: Path) {
            val storePath = dir.resolve("codex-auth.json")
            val store = FileCodexAuthStore(storePath, objectMapper)
            val credentials = CodexCredentials(
                accessToken = "test-access-token",
                refreshToken = "test-refresh-token",
                accountId = "acct-123",
                lastRefresh = Instant.parse("2025-01-01T00:00:00Z"),
            )
            store.save(credentials)
            val loaded = store.load()
            assertNotNull(loaded)
            assertEquals(credentials.accessToken, loaded.accessToken)
            assertEquals(credentials.refreshToken, loaded.refreshToken)
            assertEquals(credentials.accountId, loaded.accountId)
        }

        @Test
        fun `load returns null when file missing`(@TempDir dir: Path) {
            val storePath = dir.resolve("non-existent.json")
            val store = FileCodexAuthStore(storePath, objectMapper)
            assertNull(store.load())
        }

        @Test
        fun `clear removes stored credentials`(@TempDir dir: Path) {
            val storePath = dir.resolve("codex-auth.json")
            val store = FileCodexAuthStore(storePath, objectMapper)
            store.save(CodexCredentials(accessToken = "tok", refreshToken = "ref"))
            store.clear()
            assertNull(store.load())
        }

        @Test
        fun `save restricts credentials to the owner on POSIX`(@TempDir dir: Path) {
            assumeTrue(Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView::class.java))
            val storePath = dir.resolve("codex-auth.json")
            val store = FileCodexAuthStore(storePath, objectMapper)

            store.save(CodexCredentials(accessToken = "tok", refreshToken = "ref"))

            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(storePath),
            )
        }

        @Test
        fun `save replaces a symbolic link without overwriting its target`(@TempDir dir: Path) {
            val target = dir.resolve("unrelated.json")
            target.writeText("do not overwrite")
            val storePath = dir.resolve("codex-auth.json")
            Files.createSymbolicLink(storePath, target)
            val store = FileCodexAuthStore(storePath, objectMapper)

            store.save(CodexCredentials(accessToken = "tok", refreshToken = "ref"))

            assertFalse(Files.isSymbolicLink(storePath))
            assertEquals("do not overwrite", target.readText())
            assertEquals("tok", store.load()?.accessToken)
        }
    }

    @Nested
    inner class ImportFromCodexCli {

        @Test
        fun `import valid codex cli auth json`(@TempDir dir: Path) {
            val cliAuthPath = dir.resolve("auth.json")
            cliAuthPath.writeText(
                """
                {
                  "tokens": {
                    "access_token": "cli-access",
                    "refresh_token": "cli-refresh",
                    "account_id": "acct-cli-456"
                  },
                  "auth_mode": "chatgpt",
                  "last_refresh": "2025-06-01T12:00:00Z"
                }
                """.trimIndent()
            )
            val storePath = dir.resolve("embabel-auth.json")
            val store = FileCodexAuthStore(storePath, objectMapper)
            val result = store.importFromCodexCli(cliAuthPath)
            assertTrue(result)
            val loaded = store.load()
            assertNotNull(loaded)
            assertEquals("cli-access", loaded.accessToken)
            assertEquals("cli-refresh", loaded.refreshToken)
            assertEquals("acct-cli-456", loaded.accountId)
            assertEquals("chatgpt", loaded.authMode)
        }

        @Test
        fun `import returns false when file missing`(@TempDir dir: Path) {
            val store = FileCodexAuthStore(dir.resolve("auth.json"), objectMapper)
            val result = store.importFromCodexCli(dir.resolve("nonexistent.json"))
            assertTrue(!result)
        }

        @Test
        fun `import with last_refresh works without caller registering JavaTimeModule`(@TempDir dir: Path) {
            val cliAuthPath = dir.resolve("auth.json")
            cliAuthPath.writeText(
                """
                {
                  "tokens": {
                    "access_token": "cli-access",
                    "refresh_token": "cli-refresh"
                  },
                  "auth_mode": "chatgpt",
                  "last_refresh": "2026-07-03T16:22:35.327347Z"
                }
                """.trimIndent()
            )
            val bareMapper = jacksonObjectMapper()
            val store = FileCodexAuthStore(dir.resolve("embabel-auth.json"), bareMapper)
            assertTrue(store.importFromCodexCli(cliAuthPath))
            assertNotNull(store.load())
        }
    }
}
