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

import java.nio.file.Path

/**
 * Supported script languages.
 */
enum class ScriptLanguage(val extensions: Set<String>) {
    PYTHON(setOf("py")),
    BASH(setOf("sh", "bash")),
    JAVASCRIPT(setOf("js", "mjs")),
    KOTLIN_SCRIPT(setOf("kts"));

    companion object {
        /**
         * Detect the script language from a file name.
         *
         * @param fileName the name of the script file
         * @return the detected language, or null if not recognized
         */
        fun fromFileName(fileName: String): ScriptLanguage? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return entries.find { ext in it.extensions }
        }
    }
}

/**
 * A script from a skill's scripts directory.
 *
 * @param skillName the name of the skill this script belongs to
 * @param fileName the name of the script file (e.g., "build.sh")
 * @param language the detected script language
 * @param basePath the base path of the skill directory
 */
data class SkillScript(
    val skillName: String,
    val fileName: String,
    val language: ScriptLanguage,
    val basePath: Path,
) {
    /**
     * The full path to the script file.
     */
    val scriptPath: Path
        get() = basePath.resolve("scripts").resolve(fileName)

    /**
     * A unique identifier for this script, suitable for use as a tool name.
     * Format: {skillName}_{scriptNameWithoutExtension}
     */
    val toolName: String
        get() = "${skillName}_${fileName.substringBeforeLast('.')}"
}
