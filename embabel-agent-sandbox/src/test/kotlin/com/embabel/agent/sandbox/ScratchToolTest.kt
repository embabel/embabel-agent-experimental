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

import com.embabel.agent.api.tool.Tool
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach

class ScratchToolTest {

    private var scratch: ScratchTool? = null

    @AfterEach
    fun cleanup() {
        scratch?.close()
    }

    private fun resultText(result: Tool.Result): String {
        assertTrue(result is Tool.Result.Text, "Expected Text result, got: $result")
        return (result as Tool.Result.Text).content
    }

    private fun assumeSandboxAvailable() {
        assumeTrue(DockerExecutor.isDockerAvailable(), "Docker not available")
        assumeTrue(
            DockerExecutor.imageExists(ScratchTool.DEFAULT_IMAGE),
            "Sandbox image ${ScratchTool.DEFAULT_IMAGE} not available"
        )
    }

    @Test
    fun `basic command execution`() {
        assumeSandboxAvailable()

        scratch = ScratchTool.default()
        val text = resultText(scratch!!.call("""{"command": "echo hello world"}"""))
        assertTrue(text.contains("hello world"), "Should contain output: $text")
    }

    @Test
    fun `state persists between calls`() {
        assumeSandboxAvailable()

        scratch = ScratchTool.default()
        scratch!!.call("""{"command": "echo 'test content' > /tmp/myfile.txt"}""")
        val text = resultText(scratch!!.call("""{"command": "cat /tmp/myfile.txt"}"""))
        assertTrue(text.contains("test content"), "File content should persist: $text")
    }

    @Test
    fun `reports non-zero exit code`() {
        assumeSandboxAvailable()

        scratch = ScratchTool.default()
        val text = resultText(scratch!!.call("""{"command": "exit 42"}"""))
        assertTrue(text.contains("Exit code 42"), "Should report exit code: $text")
    }

    @Test
    fun `python execution`() {
        assumeSandboxAvailable()

        scratch = ScratchTool.default()
        val text = resultText(scratch!!.call("""{"command": "python3 -c 'print(2 + 2)'"}"""))
        assertTrue(text.contains("4"), "Should contain result: $text")
    }

    @Test
    fun `node execution`() {
        assumeSandboxAvailable()

        scratch = ScratchTool.default()
        val text = resultText(scratch!!.call("""{"command": "node -e 'console.log(3 * 7)'"}"""))
        assertTrue(text.contains("21"), "Should contain result: $text")
    }

    @Test
    fun `java version available`() {
        assumeSandboxAvailable()

        scratch = ScratchTool.default()
        val text = resultText(scratch!!.call("""{"command": "java -version 2>&1 | head -1"}"""))
        assertTrue(text.contains("openjdk") || text.contains("java"), "Should show java version: $text")
    }

    @Test
    fun `sqlite works`() {
        assumeSandboxAvailable()

        scratch = ScratchTool.default()
        val text = resultText(scratch!!.call("""{"command": "sqlite3 :memory: 'SELECT 1+1;'"}"""))
        assertTrue(text.contains("2"), "Should contain sqlite result: $text")
    }

    @Test
    fun `graphviz generates svg`() {
        assumeSandboxAvailable()

        scratch = ScratchTool.default()
        val text = resultText(scratch!!.call("""{"command": "echo 'digraph { A -> B }' | dot -Tsvg | head -3"}"""))
        assertTrue(text.contains("svg"), "Should contain SVG output: $text")
    }

    @Test
    fun `pip install and use package`() {
        assumeSandboxAvailable()

        scratch = ScratchTool.default()
        val text = resultText(scratch!!.call("""{"command": "python3 -c 'import pandas; print(pandas.__version__)'"}"""))
        assertTrue(text.isNotBlank(), "Should show pandas version: $text")
    }
}
