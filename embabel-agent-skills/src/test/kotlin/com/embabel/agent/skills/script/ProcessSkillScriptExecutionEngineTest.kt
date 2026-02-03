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
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class ProcessSkillScriptExecutionEngineTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `supportedLanguages returns configured languages`() {
        val engine = ProcessSkillScriptExecutionEngine(
            supportedLanguages = setOf(ScriptLanguage.BASH, ScriptLanguage.PYTHON)
        )

        assertEquals(setOf(ScriptLanguage.BASH, ScriptLanguage.PYTHON), engine.supportedLanguages())
    }

    @Test
    fun `validate returns Denied for unsupported language`() {
        val engine = ProcessSkillScriptExecutionEngine(
            supportedLanguages = setOf(ScriptLanguage.BASH)
        )

        val script = createScript("test.py", ScriptLanguage.PYTHON, "print('hello')")
        val result = engine.validate(script)

        assertNotNull(result)
        assertTrue(result!!.reason.contains("not enabled"))
    }

    @Test
    fun `validate returns Denied for missing script file`() {
        val engine = ProcessSkillScriptExecutionEngine()

        val script = SkillScript(
            skillName = "test",
            fileName = "nonexistent.sh",
            language = ScriptLanguage.BASH,
            basePath = tempDir,
        )

        val result = engine.validate(script)

        assertNotNull(result)
        assertTrue(result!!.reason.contains("does not exist"))
    }

    @Test
    fun `validate returns null for valid script`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "echo hello")

        val result = engine.validate(script)

        assertNull(result)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute runs bash script successfully`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho 'Hello World'")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(0, success.exitCode)
        assertTrue(success.stdout.contains("Hello World"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute captures stderr`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho 'error message' >&2")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stderr.contains("error message"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute captures non-zero exit code`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\nexit 42")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(42, success.exitCode)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute passes arguments to script`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho \"Args: \$1 \$2\"")

        val result = engine.execute(script, args = listOf("foo", "bar"))

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.contains("Args: foo bar"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute provides stdin to script`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\nread input\necho \"Got: \$input\"")

        val result = engine.execute(script, stdin = "hello from stdin")

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.contains("Got: hello from stdin"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute times out long-running script`() {
        val engine = ProcessSkillScriptExecutionEngine(timeout = 500.milliseconds)
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\nsleep 10")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Failure)
        val failure = result as ScriptExecutionResult.Failure
        assertTrue(failure.timedOut)
        assertTrue(failure.error.contains("timed out"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute records duration`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho ok")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.duration > 0.milliseconds)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute uses custom environment variables`() {
        val engine = ProcessSkillScriptExecutionEngine(
            environment = mapOf("MY_VAR" to "my_value"),
            inheritEnvironment = true,
        )
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho \$MY_VAR")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.contains("my_value"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute works with Python scripts`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.py", ScriptLanguage.PYTHON, "print('Hello from Python')")

        val result = engine.execute(script)

        // This test will fail if python3 is not installed, which is acceptable
        if (result is ScriptExecutionResult.Failure && result.error.contains("Cannot run program")) {
            // Python not installed, skip this assertion
            return
        }

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(0, success.exitCode)
        assertTrue(success.stdout.contains("Hello from Python"))
    }

    @Test
    fun `execute returns Denied for unsupported language`() {
        val engine = ProcessSkillScriptExecutionEngine(
            supportedLanguages = setOf(ScriptLanguage.BASH)
        )
        val script = createScript("test.py", ScriptLanguage.PYTHON, "print('hello')")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Denied)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute collects artifacts from OUTPUT_DIR`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript(
            "test.sh",
            ScriptLanguage.BASH,
            """#!/bin/bash
echo "Creating artifacts..."
echo "Hello PDF" > "${'$'}OUTPUT_DIR/result.pdf"
echo "Hello JSON" > "${'$'}OUTPUT_DIR/data.json"
echo "Done"
"""
        )

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(2, success.artifacts.size)

        val artifactNames = success.artifacts.map { it.name }.toSet()
        assertTrue("result.pdf" in artifactNames)
        assertTrue("data.json" in artifactNames)

        // Check mime types are inferred
        val pdfArtifact = success.artifacts.find { it.name == "result.pdf" }!!
        assertEquals("application/pdf", pdfArtifact.mimeType)

        val jsonArtifact = success.artifacts.find { it.name == "data.json" }!!
        assertEquals("application/json", jsonArtifact.mimeType)

        // Check files exist at the paths
        assertTrue(Files.exists(pdfArtifact.path))
        assertTrue(Files.exists(jsonArtifact.path))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute returns empty artifacts when nothing written to OUTPUT_DIR`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho 'no artifacts'")

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.artifacts.isEmpty())
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `artifacts include file sizes`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript(
            "test.sh",
            ScriptLanguage.BASH,
            """#!/bin/bash
echo "Some content here" > "${'$'}OUTPUT_DIR/output.txt"
"""
        )

        val result = engine.execute(script)

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertEquals(1, success.artifacts.size)

        val artifact = success.artifacts[0]
        assertTrue(artifact.sizeBytes > 0)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute makes input files available in INPUT_DIR`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript(
            "test.sh",
            ScriptLanguage.BASH,
            """#!/bin/bash
# List input files
ls "${'$'}INPUT_DIR"
# Copy input to output
cat "${'$'}INPUT_DIR/input.txt" > "${'$'}OUTPUT_DIR/output.txt"
"""
        )

        // Create an input file
        val inputFile = tempDir.resolve("input.txt")
        Files.writeString(inputFile, "Hello from input file")

        val result = engine.execute(script, inputFiles = listOf(inputFile))

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.contains("input.txt"))
        assertEquals(1, success.artifacts.size)

        // Verify the content was copied
        val outputContent = Files.readString(success.artifacts[0].path)
        assertEquals("Hello from input file", outputContent)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `execute handles multiple input files`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript(
            "test.sh",
            ScriptLanguage.BASH,
            """#!/bin/bash
# Count input files
ls -1 "${'$'}INPUT_DIR" | wc -l | tr -d ' '
"""
        )

        // Create multiple input files
        val inputFile1 = tempDir.resolve("file1.txt")
        val inputFile2 = tempDir.resolve("file2.txt")
        val inputFile3 = tempDir.resolve("file3.pdf")
        Files.writeString(inputFile1, "content1")
        Files.writeString(inputFile2, "content2")
        Files.writeString(inputFile3, "content3")

        val result = engine.execute(script, inputFiles = listOf(inputFile1, inputFile2, inputFile3))

        assertTrue(result is ScriptExecutionResult.Success)
        val success = result as ScriptExecutionResult.Success
        assertTrue(success.stdout.trim().contains("3"))
    }

    @Test
    fun `execute returns Denied for non-existent input file`() {
        val engine = ProcessSkillScriptExecutionEngine()
        val script = createScript("test.sh", ScriptLanguage.BASH, "#!/bin/bash\necho ok")

        val nonExistentFile = tempDir.resolve("does-not-exist.txt")
        val result = engine.execute(script, inputFiles = listOf(nonExistentFile))

        assertTrue(result is ScriptExecutionResult.Denied)
        assertTrue((result as ScriptExecutionResult.Denied).reason.contains("does not exist"))
    }

    private fun createScript(
        fileName: String,
        language: ScriptLanguage,
        content: String,
    ): SkillScript {
        val scriptsDir = tempDir.resolve("scripts")
        Files.createDirectories(scriptsDir)

        val scriptFile = scriptsDir.resolve(fileName)
        Files.writeString(scriptFile, content)

        // Make executable on Unix
        scriptFile.toFile().setExecutable(true)

        return SkillScript(
            skillName = "test-skill",
            fileName = fileName,
            language = language,
            basePath = tempDir,
        )
    }
}
