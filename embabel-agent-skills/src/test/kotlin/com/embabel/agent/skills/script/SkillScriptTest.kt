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
import java.nio.file.Paths

class SkillScriptTest {

    @Test
    fun `scriptPath returns correct path`() {
        val script = SkillScript(
            skillName = "my-skill",
            fileName = "build.sh",
            language = ScriptLanguage.BASH,
            basePath = Paths.get("/skills/my-skill"),
        )

        assertEquals(
            Paths.get("/skills/my-skill/scripts/build.sh"),
            script.scriptPath
        )
    }

    @Test
    fun `toolName generates correct format without extension`() {
        val script = SkillScript(
            skillName = "my-skill",
            fileName = "build.sh",
            language = ScriptLanguage.BASH,
            basePath = Paths.get("/skills/my-skill"),
        )

        assertEquals("my-skill_build", script.toolName)
    }

    @Test
    fun `toolName handles multiple dots in filename`() {
        val script = SkillScript(
            skillName = "my-skill",
            fileName = "my.script.name.py",
            language = ScriptLanguage.PYTHON,
            basePath = Paths.get("/skills/my-skill"),
        )

        assertEquals("my-skill_my.script.name", script.toolName)
    }

    @Test
    fun `toolName handles filename without extension`() {
        val script = SkillScript(
            skillName = "my-skill",
            fileName = "script",
            language = ScriptLanguage.BASH,
            basePath = Paths.get("/skills/my-skill"),
        )

        assertEquals("my-skill_script", script.toolName)
    }
}
