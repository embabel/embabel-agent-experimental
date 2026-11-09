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
package com.embabel.agent.sandbox.docker

import com.embabel.agent.sandbox.SandboxConfig
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Starting a session with a fake Docker CLI: no daemon needed. `docker run -d` prints the container id
 * on stdout, and anything else (the pull progress of an image not yet local) on stderr.
 */
@DisabledOnOs(OS.WINDOWS)
class DockerSandboxSessionStartTest {

    @TempDir
    lateinit var dir: Path

    private fun fakeDocker(stdout: String, stderr: String, exit: Int = 0): String {
        val script = dir.resolve("docker")
        Files.writeString(
            script,
            "#!/bin/sh\nprintf '%s' '$stderr' >&2\nprintf '%s\\n' '$stdout'\nexit $exit\n",
        )
        script.toFile().setExecutable(true)
        return script.toString()
    }

    private fun start(docker: String) =
        DockerSandboxSession(label = "t", config = SandboxConfig(enabled = true), ttl = 1.hours, docker = docker)

    @Test
    fun `the first session after an image pull records the container id, not the pull progress`() {
        val docker = fakeDocker(
            stdout = "4f2a9c1e7b3d0123456789abcdef",
            stderr = "Unable to find image 'node:20-slim' locally\n20-slim: Pulling from library/node\nStatus: Downloaded newer image for node:20-slim\n",
        )

        assertEquals("4f2a9c1e7b3d", start(docker).containerId)
    }

    /** A pull's progress can outgrow the pipe; it must be drained even when the shared pool is busy. */
    @Test
    fun `a start drains a full stderr pipe while the common pool is busy`() {
        val script = dir.resolve("docker")
        Files.writeString(script, "#!/bin/sh\nhead -c 300000 /dev/zero | tr '\\0' x >&2\nprintf '%s\\n' 4f2a9c1e7b3d0123\n")
        script.toFile().setExecutable(true)
        val release = CountDownLatch(1)
        val pool = ForkJoinPool.commonPool()
        val running = CountDownLatch(pool.parallelism)
        repeat(pool.parallelism) { pool.execute { running.countDown(); release.await() } }
        try {
            assertTrue(running.await(10, TimeUnit.SECONDS), "common pool was not saturated")
            val id = assertTimeoutPreemptively<String?>(Duration.ofSeconds(20)) { start(script.toString()).containerId }
            assertEquals("4f2a9c1e7b3d", id)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a failed start reports what Docker wrote on stderr`() {
        val docker = fakeDocker(stdout = "", stderr = "docker: Error response from daemon: no space left on device.", exit = 125)

        val failure = assertThrows<RuntimeException> { start(docker) }

        assertContains(failure.message.orEmpty(), "no space left on device")
    }
}
