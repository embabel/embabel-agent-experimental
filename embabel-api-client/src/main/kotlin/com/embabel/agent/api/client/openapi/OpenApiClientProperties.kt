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
package com.embabel.agent.api.client.openapi

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * HTTP client tuning for the OpenAPI gateway. Apply via `application.yml`:
 *
 * ```yaml
 * embabel:
 *   api-client:
 *     openapi:
 *       connect-timeout: 5s
 *       read-timeout: 20s
 * ```
 */
@ConfigurationProperties(prefix = "embabel.api-client.openapi")
data class OpenApiClientProperties(
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(20),
)
