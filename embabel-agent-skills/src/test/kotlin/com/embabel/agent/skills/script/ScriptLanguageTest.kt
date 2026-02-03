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
package com.embabel.agent.skills.script

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ScriptLanguageTest {

    @Test
    fun `fromFileName detects Python scripts`() {
        assertEquals(ScriptLanguage.PYTHON, ScriptLanguage.fromFileName("script.py"))
        assertEquals(ScriptLanguage.PYTHON, ScriptLanguage.fromFileName("my_script.py"))
    }

    @Test
    fun `fromFileName detects Bash scripts`() {
        assertEquals(ScriptLanguage.BASH, ScriptLanguage.fromFileName("build.sh"))
        assertEquals(ScriptLanguage.BASH, ScriptLanguage.fromFileName("deploy.bash"))
    }

    @Test
    fun `fromFileName detects JavaScript scripts`() {
        assertEquals(ScriptLanguage.JAVASCRIPT, ScriptLanguage.fromFileName("index.js"))
        assertEquals(ScriptLanguage.JAVASCRIPT, ScriptLanguage.fromFileName("module.mjs"))
    }

    @Test
    fun `fromFileName detects Kotlin scripts`() {
        assertEquals(ScriptLanguage.KOTLIN_SCRIPT, ScriptLanguage.fromFileName("script.kts"))
    }

    @Test
    fun `fromFileName is case insensitive`() {
        assertEquals(ScriptLanguage.PYTHON, ScriptLanguage.fromFileName("script.PY"))
        assertEquals(ScriptLanguage.BASH, ScriptLanguage.fromFileName("build.SH"))
    }

    @Test
    fun `fromFileName returns null for unknown extensions`() {
        assertNull(ScriptLanguage.fromFileName("file.txt"))
        assertNull(ScriptLanguage.fromFileName("file.md"))
        assertNull(ScriptLanguage.fromFileName("file"))
    }

    @Test
    fun `fromFileName returns null for empty string`() {
        assertNull(ScriptLanguage.fromFileName(""))
    }
}
