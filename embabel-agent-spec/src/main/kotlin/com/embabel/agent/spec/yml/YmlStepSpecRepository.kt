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
package com.embabel.agent.spec.yml

import com.embabel.agent.spec.model.StepSpec
import com.embabel.agent.spec.persistence.StepSpecRepository
import org.slf4j.LoggerFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.dataformat.yaml.YAMLWriteFeature
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue
import java.io.File

/**
 * Look for YML files in a directory to load and save StepDefinition entities
 * @param dir Directory to load/save YML files
 * @param additionalSubtypes additional [StepSpec] subtypes to register for deserialization
 */
class YmlStepSpecRepository(
    val dir: String,
    additionalSubtypes: List<Class<out StepSpec<*>>> = emptyList(),
) : StepSpecRepository {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val yamlMapper = YAMLMapper.builder()
        .addModule(kotlinModule())
        .findAndAddModules()
        .disable(YAMLWriteFeature.USE_NATIVE_TYPE_ID)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .apply {
            if (additionalSubtypes.isNotEmpty()) {
                registerSubtypes(*additionalSubtypes.toTypedArray())
            }
        }
        .build()

    override fun save(entity: StepSpec<*>): StepSpec<*> {
        val dirFile = File(dir)
        if (!dirFile.exists()) {
            dirFile.mkdirs()
        }

        val file = File(dirFile, "${entity.name}.yml")
        yamlMapper.writeValue(file, entity)
        logger.info("Saved entity to {}", file.absolutePath)
        return entity
    }

    override fun findAll(): Iterable<StepSpec<*>> {
        val dirFile = File(dir)
        if (!dirFile.exists()) {
            return emptyList()
        }

        return dirFile.listFiles { file -> file.extension == "yml" }
            ?.mapNotNull { file ->
                try {
                    yamlMapper.readValue<StepSpec<*>>(file)
                } catch (e: Exception) {
                    logger.warn("Failed to read {}: {}", file.name, e.message)
                    null
                }
            }
            ?: emptyList()
    }
}
