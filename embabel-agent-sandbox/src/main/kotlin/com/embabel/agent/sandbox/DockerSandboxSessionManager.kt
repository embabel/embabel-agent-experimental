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
package com.embabel.agent.sandbox

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Docker-backed [SandboxSessionManager] that manages persistent sandbox sessions.
 *
 * Sessions are stored in memory and backed by Docker containers.
 * Idle sessions are paused (container stopped) and eventually evicted (container removed).
 *
 * @param maxSessionsPerOwner maximum concurrent sessions per owner (0 = unlimited)
 * @param maxTotalSessions maximum total concurrent sessions (0 = unlimited)
 * @param pauseGracePeriod how long a paused session survives before being closed
 */
class DockerSandboxSessionManager(
    private val maxSessionsPerOwner: Int = 5,
    private val maxTotalSessions: Int = 20,
    private val pauseGracePeriod: Duration = 1.hours,
) : SandboxSessionManager {

    private val logger = LoggerFactory.getLogger(DockerSandboxSessionManager::class.java)

    private val sessions = ConcurrentHashMap<String, DockerSandboxSession>()

    override fun create(
        label: String,
        config: SandboxConfig,
        owner: String?,
        ttl: Duration,
    ): SandboxSession {
        // Enforce limits
        if (maxTotalSessions > 0 && sessions.size >= maxTotalSessions) {
            evictExpired()
            if (sessions.size >= maxTotalSessions) {
                throw IllegalStateException(
                    "Maximum total sessions ($maxTotalSessions) reached. Close existing sessions first."
                )
            }
        }

        if (owner != null && maxSessionsPerOwner > 0) {
            val ownerCount = sessions.values.count { it.owner == owner }
            if (ownerCount >= maxSessionsPerOwner) {
                throw IllegalStateException(
                    "Maximum sessions per owner ($maxSessionsPerOwner) reached for '$owner'."
                )
            }
        }

        val session = DockerSandboxSession(
            label = label,
            config = config,
            owner = owner,
            ttl = ttl,
        )

        sessions[session.id] = session
        logger.info(
            "Created session '{}' (id={}, owner={}, ttl={})",
            label, session.id, owner ?: "none", ttl,
        )

        return session
    }

    override fun get(id: String): SandboxSession? {
        val session = sessions[id] ?: return null
        if (session.state == SandboxSession.SessionState.CLOSED) {
            sessions.remove(id)
            return null
        }
        return session
    }

    override fun list(owner: String?): List<SandboxSession> {
        return sessions.values
            .filter { it.state != SandboxSession.SessionState.CLOSED }
            .filter { owner == null || it.owner == owner }
            .sortedByDescending { it.lastActiveAt }
    }

    override fun evictExpired() {
        val now = Instant.now()

        for ((id, session) in sessions) {
            when (session.state) {
                SandboxSession.SessionState.ACTIVE -> {
                    // Pause if idle beyond TTL
                    val idleSince = java.time.Duration.between(session.lastActiveAt, now)
                    if (idleSince > session.ttl.toJavaDuration()) {
                        logger.info(
                            "Session '{}' ({}) idle for {} — pausing",
                            session.label, id, idleSince,
                        )
                        session.pause()
                    }
                }
                SandboxSession.SessionState.PAUSED -> {
                    // Close if paused beyond grace period
                    val pausedSince = java.time.Duration.between(session.lastActiveAt, now)
                    if (pausedSince > (session.ttl + pauseGracePeriod).toJavaDuration()) {
                        logger.info(
                            "Session '{}' ({}) paused for {} — closing",
                            session.label, id, pausedSince,
                        )
                        session.close()
                        sessions.remove(id)
                    }
                }
                SandboxSession.SessionState.CLOSED -> {
                    sessions.remove(id)
                }
            }
        }
    }

    override fun closeAll() {
        logger.info("Closing all {} sandbox sessions", sessions.size)
        for ((id, session) in sessions) {
            try {
                session.close()
            } catch (e: Exception) {
                logger.warn("Failed to close session '{}': {}", id, e.message)
            }
        }
        sessions.clear()
    }
}
