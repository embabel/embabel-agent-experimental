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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolNamesTest {

    private val validPattern = Regex("^[a-zA-Z0-9_-]+$")

    @Test
    fun `leaves valid names unchanged`() {
        assertEquals("listPets", ToolNames.sanitize("listPets"))
        assertEquals("get_pets", ToolNames.sanitize("get_pets"))
        assertEquals("my-tool", ToolNames.sanitize("my-tool"))
    }

    @Test
    fun `replaces dots with underscores`() {
        assertEquals("info_0_json", ToolNames.sanitize("info.0.json"))
    }

    @Test
    fun `replaces slashes with underscores`() {
        assertEquals("get_info_0_json", ToolNames.sanitize("get/info.0.json"))
    }

    @Test
    fun `replaces colons and hashes`() {
        assertEquals("my_api_operation_1", ToolNames.sanitize("my.api:operation#1"))
    }

    @Test
    fun `collapses consecutive underscores`() {
        assertEquals("a_b", ToolNames.sanitize("a___b"))
    }

    @Test
    fun `trims leading and trailing underscores`() {
        assertEquals("foo", ToolNames.sanitize(".foo."))
    }

    @Test
    fun `result always matches LLM tool name pattern`() {
        val edgeCases = listOf(
            "simple", "with spaces", "with/slashes", "with.dots",
            "with:colons", "with#hashes", "123numeric", "UPPER_case",
            "combo.of/all:chars#here!", "---dashes---",
        )
        edgeCases.forEach { input ->
            val result = ToolNames.sanitize(input)
            assertTrue(result.matches(validPattern), "sanitize('$input') = '$result' doesn't match pattern")
        }
    }
}
