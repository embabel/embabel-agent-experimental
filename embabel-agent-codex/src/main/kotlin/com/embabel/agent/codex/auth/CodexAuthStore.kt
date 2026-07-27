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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText

interface CodexAuthStore {
    fun load(): CodexCredentials?
    fun save(credentials: CodexCredentials)
    fun clear()
    fun importFromCodexCli(codexAuthJsonPath: Path = defaultCodexCliPath()): Boolean
}

fun defaultCodexCliPath(): Path = Path.of(System.getProperty("user.home"), ".codex", "auth.json")

fun defaultEmbabelCodexPath(): Path =
    Path.of(System.getProperty("user.home"), ".embabel", "codex-auth.json")

@JsonIgnoreProperties(ignoreUnknown = true)
private data class CodexCliAuthJson(
    val tokens: CodexCliTokens? = null,
    @JsonProperty("auth_mode") val authMode: String? = null,
    @JsonProperty("last_refresh") val lastRefresh: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class CodexCliTokens(
    @JsonProperty("access_token") val accessToken: String? = null,
    @JsonProperty("refresh_token") val refreshToken: String? = null,
    @JsonProperty("account_id") val accountId: String? = null,
    @JsonProperty("id_token") val idToken: String? = null,
)

class FileCodexAuthStore(
    private val path: Path,
    objectMapper: ObjectMapper,
) : CodexAuthStore {

    private val objectMapper: ObjectMapper = objectMapper.copy().findAndRegisterModules()

    override fun load(): CodexCredentials? {
        if (!path.exists()) return null
        return try {
            objectMapper.readValue<CodexCredentials>(path.readText())
        } catch (e: Exception) {
            null
        }
    }

    override fun save(credentials: CodexCredentials) {
        val absolutePath = path.toAbsolutePath()
        val parent = absolutePath.parent ?: throw CodexAuthException("Credential path has no parent: $path")
        Files.createDirectories(parent)
        val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val tempPath = if (supportsPosix(parent)) {
            Files.createTempFile(
                parent,
                ".${absolutePath.fileName}.",
                ".tmp",
                PosixFilePermissions.asFileAttribute(ownerOnly),
            )
        } else {
            Files.createTempFile(parent, ".${absolutePath.fileName}.", ".tmp")
        }
        try {
            Files.writeString(tempPath, objectMapper.writeValueAsString(credentials), StandardCharsets.UTF_8)
            setOwnerOnlyPermissions(tempPath, ownerOnly)
            try {
                Files.move(tempPath, absolutePath, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempPath, absolutePath, REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(absolutePath, ownerOnly)
        } catch (e: Exception) {
            throw CodexAuthException("Failed to save Codex credentials to $path", e)
        } finally {
            Files.deleteIfExists(tempPath)
        }
    }

    override fun clear() {
        path.toFile().delete()
    }

    override fun importFromCodexCli(codexAuthJsonPath: Path): Boolean {
        if (!codexAuthJsonPath.exists()) return false
        return try {
            val cliAuth = objectMapper.readValue<CodexCliAuthJson>(codexAuthJsonPath.readText())
            val tokens = cliAuth.tokens ?: return false
            val accessToken = tokens.accessToken ?: return false
            val refreshToken = tokens.refreshToken ?: return false
            val lastRefresh = cliAuth.lastRefresh?.let { runCatching { Instant.parse(it) }.getOrNull() }
            save(
                CodexCredentials(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accountId = tokens.accountId,
                    lastRefresh = lastRefresh,
                    authMode = cliAuth.authMode ?: "chatgpt",
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun setOwnerOnlyPermissions(file: Path, permissions: Set<PosixFilePermission>) {
        if (supportsPosix(file)) {
            Files.setPosixFilePermissions(file, permissions)
        }
    }

    private fun supportsPosix(file: Path): Boolean =
        Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView::class.java)
}
