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

import com.embabel.agent.skills.spec.SkillMetadata
import com.embabel.agent.skills.support.LoadedSkill
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class LoadedSkillScriptToolsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `getScriptTools returns tools for supported scripts`() {
        // Create skill directory with scripts
        val skillDir = tempDir.resolve("my-skill")
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("scripts/build.sh"), "#!/bin/bash\necho hello")
        Files.writeString(skillDir.resolve("scripts/test.py"), "print('hello')")
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: my-skill\ndescription: Test\n---\n")

        val skill = LoadedSkill(
            skillMetadata = TestSkillMetadata("my-skill"),
            basePath = skillDir,
        )

        val engine = AllLanguagesEngine()
        val tools = skill.getScriptTools(engine)

        assertEquals(2, tools.size)
        val toolNames = tools.map { it.definition.name }.toSet()
        assertTrue("my-skill_build" in toolNames)
        assertTrue("my-skill_test" in toolNames)
    }

    @Test
    fun `getScriptTools filters by engine supported languages`() {
        val skillDir = tempDir.resolve("my-skill")
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("scripts/build.sh"), "#!/bin/bash\necho hello")
        Files.writeString(skillDir.resolve("scripts/test.py"), "print('hello')")

        val skill = LoadedSkill(
            skillMetadata = TestSkillMetadata("my-skill"),
            basePath = skillDir,
        )

        // Engine only supports Bash
        val bashOnlyEngine = object : SkillScriptExecutionEngine {
            override fun supportedLanguages() = setOf(ScriptLanguage.BASH)
            override fun execute(script: SkillScript, args: List<String>, stdin: String?, inputFiles: List<Path>) =
                ScriptExecutionResult.Success("", "", 0, 1.milliseconds)
        }

        val tools = skill.getScriptTools(bashOnlyEngine)

        assertEquals(1, tools.size)
        assertEquals("my-skill_build", tools[0].definition.name)
    }

    @Test
    fun `getScriptTools returns empty list when no scripts directory`() {
        val skillDir = tempDir.resolve("my-skill")
        Files.createDirectories(skillDir)

        val skill = LoadedSkill(
            skillMetadata = TestSkillMetadata("my-skill"),
            basePath = skillDir,
        )

        val tools = skill.getScriptTools(AllLanguagesEngine())

        assertTrue(tools.isEmpty())
    }

    @Test
    fun `getScriptTools ignores files with unrecognized extensions`() {
        val skillDir = tempDir.resolve("my-skill")
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("scripts/readme.txt"), "Not a script")
        Files.writeString(skillDir.resolve("scripts/build.sh"), "#!/bin/bash")

        val skill = LoadedSkill(
            skillMetadata = TestSkillMetadata("my-skill"),
            basePath = skillDir,
        )

        val tools = skill.getScriptTools(AllLanguagesEngine())

        assertEquals(1, tools.size)
        assertEquals("my-skill_build", tools[0].definition.name)
    }

    @Test
    fun `getScripts returns all scripts regardless of engine support`() {
        val skillDir = tempDir.resolve("my-skill")
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("scripts/build.sh"), "#!/bin/bash")
        Files.writeString(skillDir.resolve("scripts/test.py"), "print('hello')")
        Files.writeString(skillDir.resolve("scripts/app.js"), "console.log('hi')")

        val skill = LoadedSkill(
            skillMetadata = TestSkillMetadata("my-skill"),
            basePath = skillDir,
        )

        val scripts = skill.getScripts()

        assertEquals(3, scripts.size)
        val languages = scripts.map { it.language }.toSet()
        assertEquals(setOf(ScriptLanguage.BASH, ScriptLanguage.PYTHON, ScriptLanguage.JAVASCRIPT), languages)
    }

    @Test
    fun `returned ScriptTool is functional`() {
        val skillDir = tempDir.resolve("my-skill")
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(skillDir.resolve("scripts/build.sh"), "#!/bin/bash\necho hello")

        val skill = LoadedSkill(
            skillMetadata = TestSkillMetadata("my-skill"),
            basePath = skillDir,
        )

        val engine = AllLanguagesEngine()
        val tools = skill.getScriptTools(engine)

        assertEquals(1, tools.size)
        val tool = tools[0]

        // Verify tool can be called
        val result = tool.call("{}")
        assertTrue(result is com.embabel.agent.api.tool.Tool.Result.Text)
    }

    private class TestSkillMetadata(
        override val name: String,
        override val description: String = "Test skill",
        override val license: String? = null,
        override val compatibility: String? = null,
        override val metadata: Map<String, String>? = null,
        override val allowedTools: String? = null,
        override val instructions: String? = null,
    ) : SkillMetadata

    private class AllLanguagesEngine : SkillScriptExecutionEngine {
        override fun supportedLanguages() = ScriptLanguage.entries.toSet()
        override fun execute(script: SkillScript, args: List<String>, stdin: String?, inputFiles: List<Path>) =
            ScriptExecutionResult.Success("ok", "", 0, 1.milliseconds)
    }
}
