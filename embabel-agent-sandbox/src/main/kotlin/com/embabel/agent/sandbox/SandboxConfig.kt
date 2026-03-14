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

/**
 * Declarative sandbox configuration for agent executors.
 *
 * Designed to be embedded in YAML action specs so any executor
 * can opt into sandboxed execution via configuration.
 *
 * Example YAML:
 * ```yaml
 * sandbox:
 *   enabled: true
 *   image: node:20-slim
 *   memory: 2g
 *   cpus: "2.0"
 *   network: true
 *   propagateEnv:
 *     - ANTHROPIC_API_KEY
 *     - GITHUB_TOKEN
 * ```
 *
 * When [enabled] is false (the default), execution runs on the host.
 */
data class SandboxConfig(
    /** Whether sandboxed execution is enabled. */
    val enabled: Boolean = false,
    /** Docker image to use. */
    val image: String = "node:20-slim",
    /** Memory limit (e.g., "2g", "512m"). */
    val memory: String = "2g",
    /** CPU limit (e.g., "2.0"). */
    val cpus: String = "2.0",
    /** Whether to allow network access. Claude Code needs this for API calls. */
    val network: Boolean = true,
    /**
     * Environment variable names to propagate from the host into the container.
     * Values are read from [System.getenv] at execution time.
     */
    val propagateEnv: List<String> = listOf("ANTHROPIC_API_KEY"),
) {

    /**
     * Create a [SandboxedExecutor] from this config, or null if not enabled.
     */
    fun createExecutor(): SandboxedExecutor? {
        if (!enabled) return null
        val envFromHost = propagateEnv
            .mapNotNull { key -> System.getenv(key)?.let { key to it } }
            .toMap()
        return DockerExecutor(
            image = image,
            networkEnabled = network,
            memoryLimit = memory,
            cpuLimit = cpus,
            baseEnvironment = envFromHost,
        )
    }
}
