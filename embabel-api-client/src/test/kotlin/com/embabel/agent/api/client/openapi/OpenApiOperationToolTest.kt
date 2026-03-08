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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.ProgressiveTool
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.*
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient

class OpenApiOperationToolTest {

    // --- Shared spec loading ---

    private val minimalSpec: OpenAPI by lazy {
        OpenApiLearner.parseSpec(
            javaClass.classLoader.getResource("petstore-minimal.json")!!.toString()
        )
    }

    private val extendedSpec: OpenAPI by lazy {
        OpenApiLearner.parseSpec(
            javaClass.classLoader.getResource("petstore-extended.json")!!.toString()
        )
    }

    private val authSpec: OpenAPI by lazy {
        OpenApiLearner.parseSpec(
            javaClass.classLoader.getResource("petstore-with-auth.json")!!.toString()
        )
    }

    // ======================================================================
    // operationName
    // ======================================================================

    @Nested
    inner class OperationNameTests {

        @Test
        fun `uses operationId when available`() {
            val op = Operation().apply { operationId = "listPets" }
            assertEquals(
                "listPets",
                OpenApiOperationTool.operationName(PathItem.HttpMethod.GET, "/pets", op),
            )
        }

        @Test
        fun `synthesizes name from method and simple path`() {
            val op = Operation()
            assertEquals(
                "get_pets",
                OpenApiOperationTool.operationName(PathItem.HttpMethod.GET, "/pets", op),
            )
        }

        @Test
        fun `synthesizes name with path parameters`() {
            val op = Operation()
            assertEquals(
                "get_pets_by_petId",
                OpenApiOperationTool.operationName(PathItem.HttpMethod.GET, "/pets/{petId}", op),
            )
        }

        @Test
        fun `synthesizes name for nested path with parameters`() {
            val op = Operation()
            assertEquals(
                "post_pets_by_petId_uploadImage",
                OpenApiOperationTool.operationName(PathItem.HttpMethod.POST, "/pets/{petId}/uploadImage", op),
            )
        }

        @Test
        fun `sanitizes hyphens in path`() {
            val op = Operation()
            assertEquals(
                "get_my_pets",
                OpenApiOperationTool.operationName(PathItem.HttpMethod.GET, "/my-pets", op),
            )
        }

        @Test
        fun `handles multiple path parameters`() {
            val op = Operation()
            val name = OpenApiOperationTool.operationName(
                PathItem.HttpMethod.GET, "/owners/{ownerId}/pets/{petId}", op,
            )
            assertTrue(name.contains("by_ownerId"))
            assertTrue(name.contains("by_petId"))
        }

        @Test
        fun `post method uses post prefix`() {
            val op = Operation()
            assertTrue(
                OpenApiOperationTool.operationName(PathItem.HttpMethod.POST, "/pets", op)
                    .startsWith("post_"),
            )
        }

        @Test
        fun `delete method uses delete prefix`() {
            val op = Operation()
            assertTrue(
                OpenApiOperationTool.operationName(PathItem.HttpMethod.DELETE, "/pets/{petId}", op)
                    .startsWith("delete_"),
            )
        }

        @Test
        fun `put method uses put prefix`() {
            val op = Operation()
            assertTrue(
                OpenApiOperationTool.operationName(PathItem.HttpMethod.PUT, "/pets/{petId}", op)
                    .startsWith("put_"),
            )
        }

        // Verify names from all 3 specs

        @Test
        fun `minimal spec - all operationIds are used`() {
            val tools = materializeFromSpec(minimalSpec)
            val names = tools.map { it.definition.name }.toSet()
            assertTrue("listPets" in names)
            assertTrue("addPet" in names)
            assertTrue("getPetById" in names)
            assertTrue("deletePet" in names)
            assertTrue("getInventory" in names)
            assertTrue("createUser" in names)
        }

        @Test
        fun `minimal spec - operation without operationId gets synthesized name`() {
            val tools = materializeFromSpec(minimalSpec)
            val names = tools.map { it.definition.name }.toSet()
            assertTrue(names.any { it.contains("no_tags") }, "Expected synthesized name for /no-tags: $names")
        }

        @Test
        fun `extended spec - all operationIds are used`() {
            val tools = materializeFromSpec(extendedSpec)
            val names = tools.map { it.definition.name }.toSet()
            assertTrue("listPets" in names)
            assertTrue("addPet" in names)
            assertTrue("getPetById" in names)
            assertTrue("updatePet" in names)
            assertTrue("deletePet" in names)
            assertTrue("uploadPetImage" in names)
            assertTrue("getInventory" in names)
            assertTrue("placeOrder" in names)
            assertTrue("getOrderById" in names)
            assertTrue("deleteOrder" in names)
            assertTrue("createUser" in names)
            assertTrue("getUserByName" in names)
            assertTrue("updateUser" in names)
            assertTrue("deleteUser" in names)
            assertTrue("findPetsByStatus" in names)
        }

        @Test
        fun `auth spec - operationId is used`() {
            val tools = materializeFromSpec(authSpec)
            val names = tools.map { it.definition.name }.toSet()
            assertTrue("listPets" in names)
        }
    }

    // ======================================================================
    // operationDescription
    // ======================================================================

    @Nested
    inner class OperationDescriptionTests {

        @Test
        fun `summary only`() {
            val op = Operation().apply { summary = "List all pets" }
            assertEquals("List all pets", OpenApiOperationTool.operationDescription(op))
        }

        @Test
        fun `description only`() {
            val op = Operation().apply { description = "Returns all pets in the store" }
            assertEquals("Returns all pets in the store", OpenApiOperationTool.operationDescription(op))
        }

        @Test
        fun `combines summary and description`() {
            val op = Operation().apply {
                summary = "List all pets"
                description = "Returns all pets in the store"
            }
            val desc = OpenApiOperationTool.operationDescription(op)
            assertTrue(desc.contains("List all pets"))
            assertTrue(desc.contains("Returns all pets in the store"))
        }

        @Test
        fun `deduplicates when summary equals description`() {
            val op = Operation().apply {
                summary = "List all pets"
                description = "List all pets"
            }
            assertEquals("List all pets", OpenApiOperationTool.operationDescription(op))
        }

        @Test
        fun `no summary or description`() {
            val op = Operation()
            assertEquals("No description available", OpenApiOperationTool.operationDescription(op))
        }

        @Test
        fun `extended spec - listPets combines summary and description`() {
            val listPets = findToolFromSpec(extendedSpec, "listPets")!!
            assertTrue(listPets.definition.description.contains("List all pets"))
            assertTrue(listPets.definition.description.contains("optional filtering"))
        }

        @Test
        fun `extended spec - findPetsByStatus combines summary and description`() {
            val tool = findToolFromSpec(extendedSpec, "findPetsByStatus")!!
            assertTrue(tool.definition.description.contains("Finds pets by status"))
            assertTrue(tool.definition.description.contains("comma separated"))
        }
    }

    // ======================================================================
    // buildInputSchema — parameter mapping
    // ======================================================================

    @Nested
    inner class InputSchemaTests {

        // --- Path parameters ---

        @Test
        fun `path parameter is required with correct type`() {
            val getPet = findToolFromSpec(minimalSpec, "getPetById")!!
            val petIdParam = getPet.definition.inputSchema.parameters.find { it.name == "petId" }
            assertNotNull(petIdParam)
            assertEquals(Tool.ParameterType.INTEGER, petIdParam!!.type)
            assertTrue(petIdParam.required)
        }

        @Test
        fun `string path parameter`() {
            val tool = findToolFromSpec(extendedSpec, "getUserByName")!!
            val param = tool.definition.inputSchema.parameters.find { it.name == "username" }
            assertNotNull(param)
            assertEquals(Tool.ParameterType.STRING, param!!.type)
            assertTrue(param.required)
        }

        // --- Query parameters ---

        @Test
        fun `string query parameter with enum values`() {
            val listPets = findToolFromSpec(minimalSpec, "listPets")!!
            val statusParam = listPets.definition.inputSchema.parameters.find { it.name == "status" }
            assertNotNull(statusParam)
            assertEquals(Tool.ParameterType.STRING, statusParam!!.type)
            assertFalse(statusParam.required)
            assertEquals(listOf("available", "pending", "sold"), statusParam.enumValues)
        }

        @Test
        fun `integer query parameter`() {
            val listPets = findToolFromSpec(minimalSpec, "listPets")!!
            val limitParam = listPets.definition.inputSchema.parameters.find { it.name == "limit" }
            assertNotNull(limitParam)
            assertEquals(Tool.ParameterType.INTEGER, limitParam!!.type)
            assertFalse(limitParam.required)
        }

        @Test
        fun `array query parameter has correct type and itemType`() {
            val listPets = findToolFromSpec(extendedSpec, "listPets")!!
            val statusParam = listPets.definition.inputSchema.parameters.find { it.name == "status" }
            assertNotNull(statusParam)
            assertEquals(Tool.ParameterType.ARRAY, statusParam!!.type)
            assertNotNull(statusParam.itemType, "Array parameter must have itemType")
            assertEquals(Tool.ParameterType.STRING, statusParam.itemType)
        }

        @Test
        fun `array query parameter with enum on items`() {
            val tool = findToolFromSpec(extendedSpec, "findPetsByStatus")!!
            val statusParam = tool.definition.inputSchema.parameters.find { it.name == "status" }
            assertNotNull(statusParam)
            assertEquals(Tool.ParameterType.ARRAY, statusParam!!.type)
            assertEquals(Tool.ParameterType.STRING, statusParam.itemType)
            assertTrue(statusParam.required)
        }

        @Test
        fun `string array query parameter without enum`() {
            val listPets = findToolFromSpec(extendedSpec, "listPets")!!
            val tagsParam = listPets.definition.inputSchema.parameters.find { it.name == "tags" }
            assertNotNull(tagsParam)
            assertEquals(Tool.ParameterType.ARRAY, tagsParam!!.type)
            assertEquals(Tool.ParameterType.STRING, tagsParam.itemType)
            assertFalse(tagsParam.required)
        }

        // --- Request body ---

        @Test
        fun `request body mapped as body parameter`() {
            val addPet = findToolFromSpec(minimalSpec, "addPet")!!
            val bodyParam = addPet.definition.inputSchema.parameters.find { it.name == "body" }
            assertNotNull(bodyParam)
            assertEquals(Tool.ParameterType.OBJECT, bodyParam!!.type)
            assertTrue(bodyParam.required)
        }

        @Test
        fun `request body has nested properties`() {
            val addPet = findToolFromSpec(minimalSpec, "addPet")!!
            val bodyParam = addPet.definition.inputSchema.parameters.find { it.name == "body" }!!
            assertNotNull(bodyParam.properties)
            assertTrue(bodyParam.properties!!.any { it.name == "name" })
            assertTrue(bodyParam.properties!!.any { it.name == "tag" })
        }

        @Test
        fun `required properties in body are marked required`() {
            val addPet = findToolFromSpec(minimalSpec, "addPet")!!
            val bodyParam = addPet.definition.inputSchema.parameters.find { it.name == "body" }!!
            val nameParam = bodyParam.properties!!.find { it.name == "name" }!!
            assertTrue(nameParam.required)
            val tagParam = bodyParam.properties!!.find { it.name == "tag" }!!
            assertFalse(tagParam.required)
        }

        @Test
        fun `body with enum property`() {
            val addPet = findToolFromSpec(extendedSpec, "addPet")!!
            val bodyParam = addPet.definition.inputSchema.parameters.find { it.name == "body" }!!
            val statusProp = bodyParam.properties!!.find { it.name == "status" }!!
            assertEquals(listOf("available", "pending", "sold"), statusProp.enumValues)
        }

        @Test
        fun `body with array property has itemType`() {
            val addPet = findToolFromSpec(extendedSpec, "addPet")!!
            val bodyParam = addPet.definition.inputSchema.parameters.find { it.name == "body" }!!
            val photoUrlsProp = bodyParam.properties!!.find { it.name == "photoUrls" }!!
            assertEquals(Tool.ParameterType.ARRAY, photoUrlsProp.type)
            assertEquals(Tool.ParameterType.STRING, photoUrlsProp.itemType)
        }

        @Test
        fun `body with nested object property`() {
            val addPet = findToolFromSpec(extendedSpec, "addPet")!!
            val bodyParam = addPet.definition.inputSchema.parameters.find { it.name == "body" }!!
            val categoryProp = bodyParam.properties!!.find { it.name == "category" }!!
            assertEquals(Tool.ParameterType.OBJECT, categoryProp.type)
            assertNotNull(categoryProp.properties)
            assertTrue(categoryProp.properties!!.any { it.name == "id" })
            assertTrue(categoryProp.properties!!.any { it.name == "name" })
        }

        @Test
        fun `body with boolean property`() {
            val placeOrder = findToolFromSpec(extendedSpec, "placeOrder")!!
            val bodyParam = placeOrder.definition.inputSchema.parameters.find { it.name == "body" }!!
            val completeProp = bodyParam.properties!!.find { it.name == "complete" }!!
            assertEquals(Tool.ParameterType.BOOLEAN, completeProp.type)
        }

        @Test
        fun `body with integer property`() {
            val placeOrder = findToolFromSpec(extendedSpec, "placeOrder")!!
            val bodyParam = placeOrder.definition.inputSchema.parameters.find { it.name == "body" }!!
            val petIdProp = bodyParam.properties!!.find { it.name == "petId" }!!
            assertEquals(Tool.ParameterType.INTEGER, petIdProp.type)
        }

        // --- Operations with both path params and body ---

        @Test
        fun `PUT with path param and body`() {
            val updatePet = findToolFromSpec(extendedSpec, "updatePet")!!
            val params = updatePet.definition.inputSchema.parameters
            val petIdParam = params.find { it.name == "petId" }
            assertNotNull(petIdParam)
            assertTrue(petIdParam!!.required)
            val bodyParam = params.find { it.name == "body" }
            assertNotNull(bodyParam)
            assertEquals(Tool.ParameterType.OBJECT, bodyParam!!.type)
        }

        @Test
        fun `PUT user with string path param and body`() {
            val updateUser = findToolFromSpec(extendedSpec, "updateUser")!!
            val params = updateUser.definition.inputSchema.parameters
            val usernameParam = params.find { it.name == "username" }
            assertNotNull(usernameParam)
            assertEquals(Tool.ParameterType.STRING, usernameParam!!.type)
            assertTrue(usernameParam.required)
            val bodyParam = params.find { it.name == "body" }
            assertNotNull(bodyParam)
        }

        // --- Operation with no parameters ---

        @Test
        fun `operation with no params has empty schema`() {
            val getInventory = findToolFromSpec(minimalSpec, "getInventory")!!
            assertTrue(getInventory.definition.inputSchema.parameters.isEmpty())
        }

        // --- Programmatic edge cases ---

        @Test
        fun `array parameter without ArraySchema still gets itemType`() {
            // Simulate a parameter that's typed "array" but not an ArraySchema instance
            val schema = Schema<Any>().apply { type = "array" }
            val param = Parameter().apply {
                name = "ids"
                `in` = "query"
                this.schema = schema
            }
            val op = Operation().apply { parameters = listOf(param) }
            val inputSchema = OpenApiOperationTool.buildInputSchema(op)
            val idsParam = inputSchema.parameters.find { it.name == "ids" }!!
            assertEquals(Tool.ParameterType.ARRAY, idsParam.type)
            assertEquals(Tool.ParameterType.STRING, idsParam.itemType, "Should default to STRING")
        }

        @Test
        fun `array parameter with integer items`() {
            val schema = ArraySchema().apply {
                items = IntegerSchema()
            }
            val param = Parameter().apply {
                name = "ids"
                `in` = "query"
                this.schema = schema
            }
            val op = Operation().apply { parameters = listOf(param) }
            val inputSchema = OpenApiOperationTool.buildInputSchema(op)
            val idsParam = inputSchema.parameters.find { it.name == "ids" }!!
            assertEquals(Tool.ParameterType.ARRAY, idsParam.type)
            assertEquals(Tool.ParameterType.INTEGER, idsParam.itemType)
        }
    }

    // ======================================================================
    // HTTP execution — GET requests
    // ======================================================================

    @Nested
    inner class GetRequestTests {

        @Test
        fun `GET with no parameters`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/store/inventory",
                operation = Operation().apply { operationId = "getInventory" },
            )
            server.expect(requestTo("https://api.example.com/store/inventory"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""{"sold": 5}""", MediaType.APPLICATION_JSON))

            val result = tool.call("")
            assertIsText(result, """{"sold": 5}""")
            server.verify()
        }

        @Test
        fun `GET with string query parameter`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                operation = Operation().apply {
                    operationId = "listPets"
                    parameters = listOf(
                        Parameter().apply {
                            name = "name"
                            `in` = "query"
                            schema = StringSchema()
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/pets?name=Fido"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"name": "Fido"}""")
            assertIsText(result, "[]")
            server.verify()
        }

        @Test
        fun `GET with integer query parameter`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                operation = Operation().apply {
                    operationId = "listPets"
                    parameters = listOf(
                        Parameter().apply {
                            name = "limit"
                            `in` = "query"
                            schema = IntegerSchema()
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/pets?limit=10"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"limit": 10}""")
            assertIsText(result, "[]")
            server.verify()
        }

        @Test
        fun `GET with multiple query parameters`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                operation = Operation().apply {
                    operationId = "listPets"
                    parameters = listOf(
                        Parameter().apply {
                            name = "name"
                            `in` = "query"
                            schema = StringSchema()
                        },
                        Parameter().apply {
                            name = "limit"
                            `in` = "query"
                            schema = IntegerSchema()
                        },
                    )
                },
            )
            // Order may vary, so match with Hamcrest
            server.expect(requestTo(allOf(
                containsString("name=Fido"),
                containsString("limit=5"),
            )))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"name": "Fido", "limit": 5}""")
            assertIsText(result, "[]")
            server.verify()
        }

        @Test
        fun `GET with path parameter`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets/{petId}",
                operation = Operation().apply {
                    operationId = "getPetById"
                    parameters = listOf(
                        Parameter().apply {
                            name = "petId"
                            `in` = "path"
                            required = true
                            schema = IntegerSchema()
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/pets/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""{"id": 42, "name": "Rex"}""", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"petId": 42}""")
            assertIsText(result, """{"id": 42, "name": "Rex"}""")
            server.verify()
        }

        @Test
        fun `GET with string path parameter`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/users/{username}",
                operation = Operation().apply {
                    operationId = "getUserByName"
                    parameters = listOf(
                        Parameter().apply {
                            name = "username"
                            `in` = "path"
                            required = true
                            schema = StringSchema()
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/users/johndoe"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""{"username": "johndoe"}""", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"username": "johndoe"}""")
            assertIsText(result, """{"username": "johndoe"}""")
            server.verify()
        }

        @Test
        fun `GET with multiple path parameters`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/owners/{ownerId}/pets/{petId}",
                operation = Operation().apply {
                    operationId = "getOwnerPet"
                    parameters = listOf(
                        Parameter().apply {
                            name = "ownerId"
                            `in` = "path"
                            required = true
                            schema = IntegerSchema()
                        },
                        Parameter().apply {
                            name = "petId"
                            `in` = "path"
                            required = true
                            schema = IntegerSchema()
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/owners/1/pets/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"ownerId": 1, "petId": 42}""")
            assertIsText(result, "{}")
            server.verify()
        }

        @Test
        fun `GET with path and query parameters`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/owners/{ownerId}/pets",
                operation = Operation().apply {
                    operationId = "listOwnerPets"
                    parameters = listOf(
                        Parameter().apply {
                            name = "ownerId"
                            `in` = "path"
                            required = true
                            schema = IntegerSchema()
                        },
                        Parameter().apply {
                            name = "status"
                            `in` = "query"
                            schema = StringSchema()
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/owners/1/pets?status=available"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"ownerId": 1, "status": "available"}""")
            assertIsText(result, "[]")
            server.verify()
        }

        @Test
        fun `GET ignores unknown parameters - they are not added as query params`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                operation = Operation().apply {
                    operationId = "listPets"
                    parameters = listOf(
                        Parameter().apply {
                            name = "limit"
                            `in` = "query"
                            schema = IntegerSchema()
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/pets?limit=10"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            // "unknown" should not appear in query string
            val result = tool.call("""{"limit": 10, "unknown": "value"}""")
            assertIsText(result, "[]")
            server.verify()
        }

        @Test
        fun `GET with null query param value omits it`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                operation = Operation().apply {
                    operationId = "listPets"
                    parameters = listOf(
                        Parameter().apply {
                            name = "limit"
                            `in` = "query"
                            schema = IntegerSchema()
                        },
                        Parameter().apply {
                            name = "name"
                            `in` = "query"
                            schema = StringSchema()
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/pets?limit=10"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"limit": 10, "name": null}""")
            assertIsText(result, "[]")
            server.verify()
        }

        @Test
        fun `GET with empty input`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/store/inventory",
                operation = Operation().apply { operationId = "getInventory" },
            )
            server.expect(requestTo("https://api.example.com/store/inventory"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

            val result = tool.call("")
            assertIsText(result, "{}")
            server.verify()
        }
    }

    // ======================================================================
    // Array query parameter serialization
    // ======================================================================

    @Nested
    inner class ArrayQueryParamTests {

        @Test
        fun `array query param with single value`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                operation = Operation().apply {
                    operationId = "listPets"
                    parameters = listOf(
                        Parameter().apply {
                            name = "status"
                            `in` = "query"
                            schema = ArraySchema().apply {
                                items = StringSchema()
                            }
                        },
                    )
                },
            )
            // Should produce ?status=available, NOT ?status=[available]
            server.expect(requestTo("https://api.example.com/pets?status=available"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"status": ["available"]}""")
            assertIsText(result, "[]")
            server.verify()
        }

        @Test
        fun `array query param with multiple values`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                operation = Operation().apply {
                    operationId = "listPets"
                    parameters = listOf(
                        Parameter().apply {
                            name = "status"
                            `in` = "query"
                            schema = ArraySchema().apply {
                                items = StringSchema()
                            }
                        },
                    )
                },
            )
            // Should produce repeated params: ?status=available&status=pending
            server.expect(requestTo(allOf(
                containsString("status=available"),
                containsString("status=pending"),
            )))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"status": ["available", "pending"]}""")
            assertIsText(result, "[]")
            server.verify()
        }

        @Test
        fun `array query param passed as single string value works`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                operation = Operation().apply {
                    operationId = "listPets"
                    parameters = listOf(
                        Parameter().apply {
                            name = "status"
                            `in` = "query"
                            schema = ArraySchema().apply {
                                items = StringSchema()
                            }
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/pets?status=available"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            // LLM might send a single string even though schema says array
            val result = tool.call("""{"status": "available"}""")
            assertIsText(result, "[]")
            server.verify()
        }

        @Test
        fun `array and scalar query params together`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                operation = Operation().apply {
                    operationId = "listPets"
                    parameters = listOf(
                        Parameter().apply {
                            name = "status"
                            `in` = "query"
                            schema = ArraySchema().apply {
                                items = StringSchema()
                            }
                        },
                        Parameter().apply {
                            name = "limit"
                            `in` = "query"
                            schema = IntegerSchema()
                        },
                    )
                },
            )
            server.expect(requestTo(allOf(
                containsString("status=available"),
                containsString("limit=10"),
            )))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"status": ["available"], "limit": 10}""")
            assertIsText(result, "[]")
            server.verify()
        }
    }

    // ======================================================================
    // HTTP execution — POST, PUT, PATCH, DELETE
    // ======================================================================

    @Nested
    inner class WriteRequestTests {

        @Test
        fun `POST with JSON body`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.POST, "/pets",
                operation = Operation().apply {
                    operationId = "addPet"
                    requestBody = RequestBody().apply {
                        content = Content().apply {
                            addMediaType("application/json", io.swagger.v3.oas.models.media.MediaType().apply {
                                schema = ObjectSchema().apply {
                                    addProperty("name", StringSchema())
                                }
                            })
                        }
                    }
                },
            )
            server.expect(requestTo("https://api.example.com/pets"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""{"id": 1, "name": "Rex"}""", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"body": {"name": "Rex"}}""")
            assertIsText(result, """{"id": 1, "name": "Rex"}""")
            server.verify()
        }

        @Test
        fun `POST with no body`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.POST, "/pets",
                operation = Operation().apply { operationId = "addPet" },
            )
            server.expect(requestTo("https://api.example.com/pets"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

            val result = tool.call("")
            assertIsText(result, "{}")
            server.verify()
        }

        @Test
        fun `PUT with path param and body`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.PUT, "/pets/{petId}",
                operation = Operation().apply {
                    operationId = "updatePet"
                    parameters = listOf(
                        Parameter().apply {
                            name = "petId"
                            `in` = "path"
                            required = true
                            schema = IntegerSchema()
                        },
                    )
                    requestBody = RequestBody().apply {
                        content = Content().apply {
                            addMediaType("application/json", io.swagger.v3.oas.models.media.MediaType().apply {
                                schema = ObjectSchema().apply {
                                    addProperty("name", StringSchema())
                                    addProperty("status", StringSchema())
                                }
                            })
                        }
                    }
                },
            )
            server.expect(requestTo("https://api.example.com/pets/42"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""{"id": 42}""", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"petId": 42, "body": {"name": "Rex", "status": "sold"}}""")
            assertIsText(result, """{"id": 42}""")
            server.verify()
        }

        @Test
        fun `PATCH request`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.PATCH, "/pets/{petId}",
                operation = Operation().apply {
                    operationId = "patchPet"
                    parameters = listOf(
                        Parameter().apply {
                            name = "petId"
                            `in` = "path"
                            required = true
                            schema = IntegerSchema()
                        },
                    )
                    requestBody = RequestBody().apply {
                        content = Content().apply {
                            addMediaType("application/json", io.swagger.v3.oas.models.media.MediaType().apply {
                                schema = ObjectSchema()
                            })
                        }
                    }
                },
            )
            server.expect(requestTo("https://api.example.com/pets/42"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"petId": 42, "body": {"status": "sold"}}""")
            assertIsText(result, "{}")
            server.verify()
        }

        @Test
        fun `DELETE with path parameter`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.DELETE, "/pets/{petId}",
                operation = Operation().apply {
                    operationId = "deletePet"
                    parameters = listOf(
                        Parameter().apply {
                            name = "petId"
                            `in` = "path"
                            required = true
                            schema = IntegerSchema()
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/pets/42"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON))

            val result = tool.call("""{"petId": 42}""")
            assertInstanceOf(Tool.Result.Text::class.java, result)
            server.verify()
        }
    }

    // ======================================================================
    // Error handling
    // ======================================================================

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `HTTP 404 returns error result`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets/{petId}",
                operation = Operation().apply {
                    operationId = "getPetById"
                    parameters = listOf(
                        Parameter().apply {
                            name = "petId"
                            `in` = "path"
                            required = true
                            schema = IntegerSchema()
                        },
                    )
                },
            )
            server.expect(requestTo("https://api.example.com/pets/999"))
                .andRespond(withResourceNotFound().body("Pet not found"))

            val result = tool.call("""{"petId": 999}""")
            assertInstanceOf(Tool.Result.Error::class.java, result)
            val error = result as Tool.Result.Error
            assertTrue(error.message.contains("404"))
        }

        @Test
        fun `HTTP 500 returns error result`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/store/inventory",
                operation = Operation().apply { operationId = "getInventory" },
            )
            server.expect(requestTo("https://api.example.com/store/inventory"))
                .andRespond(withServerError().body("Internal error"))

            val result = tool.call("")
            assertInstanceOf(Tool.Result.Error::class.java, result)
            val error = result as Tool.Result.Error
            assertTrue(error.message.contains("500"))
        }
    }

    // ======================================================================
    // URI building
    // ======================================================================

    @Nested
    inner class UriBuildingTests {

        @Test
        fun `base URL trailing slash is normalized`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                baseUrl = "https://api.example.com/",
                operation = Operation().apply { operationId = "listPets" },
            )
            server.expect(requestTo("https://api.example.com/pets"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            tool.call("")
            server.verify()
        }

        @Test
        fun `base URL without trailing slash works`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                baseUrl = "https://api.example.com",
                operation = Operation().apply { operationId = "listPets" },
            )
            server.expect(requestTo("https://api.example.com/pets"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            tool.call("")
            server.verify()
        }

        @Test
        fun `base URL with path prefix`() {
            val (tool, server) = createToolWithMock(
                PathItem.HttpMethod.GET, "/pets",
                baseUrl = "https://api.example.com/api/v2",
                operation = Operation().apply { operationId = "listPets" },
            )
            server.expect(requestTo("https://api.example.com/api/v2/pets"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

            tool.call("")
            server.verify()
        }
    }

    // ======================================================================
    // Spec-based integration tests (petstore-minimal.json)
    // ======================================================================

    @Nested
    inner class PetstoreMinimalIntegrationTests {

        @Test
        fun `all operations materialize correctly`() {
            val tools = materializeFromSpec(minimalSpec)
            assertEquals(7, tools.size)
        }

        @Test
        fun `listPets has expected params`() {
            val tool = findToolFromSpec(minimalSpec, "listPets")!!
            val paramNames = tool.definition.inputSchema.parameters.map { it.name }.toSet()
            assertEquals(setOf("status", "limit"), paramNames)
        }

        @Test
        fun `addPet has body with required name`() {
            val tool = findToolFromSpec(minimalSpec, "addPet")!!
            val body = tool.definition.inputSchema.parameters.find { it.name == "body" }!!
            val nameProp = body.properties!!.find { it.name == "name" }!!
            assertTrue(nameProp.required)
            assertEquals(Tool.ParameterType.STRING, nameProp.type)
        }

        @Test
        fun `getPetById has required integer path param`() {
            val tool = findToolFromSpec(minimalSpec, "getPetById")!!
            val param = tool.definition.inputSchema.parameters.single()
            assertEquals("petId", param.name)
            assertEquals(Tool.ParameterType.INTEGER, param.type)
            assertTrue(param.required)
        }

        @Test
        fun `deletePet has required integer path param`() {
            val tool = findToolFromSpec(minimalSpec, "deletePet")!!
            val param = tool.definition.inputSchema.parameters.single()
            assertEquals("petId", param.name)
            assertTrue(param.required)
        }

        @Test
        fun `getInventory has no params`() {
            val tool = findToolFromSpec(minimalSpec, "getInventory")!!
            assertTrue(tool.definition.inputSchema.parameters.isEmpty())
        }

        @Test
        fun `createUser has body with required username`() {
            val tool = findToolFromSpec(minimalSpec, "createUser")!!
            val body = tool.definition.inputSchema.parameters.find { it.name == "body" }!!
            val usernameProp = body.properties!!.find { it.name == "username" }!!
            assertTrue(usernameProp.required)
        }
    }

    // ======================================================================
    // Spec-based integration tests (petstore-extended.json)
    // ======================================================================

    @Nested
    inner class PetstoreExtendedIntegrationTests {

        @Test
        fun `all operations materialize correctly`() {
            val tools = materializeFromSpec(extendedSpec)
            // listPets, addPet, getPetById, updatePet, deletePet, uploadPetImage,
            // getInventory, placeOrder, getOrderById, deleteOrder,
            // createUser, getUserByName, updateUser, deleteUser,
            // no-tags-or-id, findPetsByStatus
            assertEquals(16, tools.size)
        }

        @Test
        fun `findPetsByStatus has required array param`() {
            val tool = findToolFromSpec(extendedSpec, "findPetsByStatus")!!
            val param = tool.definition.inputSchema.parameters.find { it.name == "status" }!!
            assertEquals(Tool.ParameterType.ARRAY, param.type)
            assertEquals(Tool.ParameterType.STRING, param.itemType)
            assertTrue(param.required)
        }

        @Test
        fun `listPets has array and scalar params`() {
            val tool = findToolFromSpec(extendedSpec, "listPets")!!
            val params = tool.definition.inputSchema.parameters
            assertEquals(4, params.size)

            val statusParam = params.find { it.name == "status" }!!
            assertEquals(Tool.ParameterType.ARRAY, statusParam.type)

            val tagsParam = params.find { it.name == "tags" }!!
            assertEquals(Tool.ParameterType.ARRAY, tagsParam.type)

            val limitParam = params.find { it.name == "limit" }!!
            assertEquals(Tool.ParameterType.INTEGER, limitParam.type)

            val nameParam = params.find { it.name == "name" }!!
            assertEquals(Tool.ParameterType.STRING, nameParam.type)
        }

        @Test
        fun `addPet body has nested object and array properties`() {
            val tool = findToolFromSpec(extendedSpec, "addPet")!!
            val body = tool.definition.inputSchema.parameters.find { it.name == "body" }!!
            assertNotNull(body.properties)

            val photoUrls = body.properties!!.find { it.name == "photoUrls" }!!
            assertEquals(Tool.ParameterType.ARRAY, photoUrls.type)
            assertEquals(Tool.ParameterType.STRING, photoUrls.itemType)

            val category = body.properties!!.find { it.name == "category" }!!
            assertEquals(Tool.ParameterType.OBJECT, category.type)
            val catId = category.properties!!.find { it.name == "id" }!!
            assertEquals(Tool.ParameterType.INTEGER, catId.type)
        }

        @Test
        fun `placeOrder body has multiple types`() {
            val tool = findToolFromSpec(extendedSpec, "placeOrder")!!
            val body = tool.definition.inputSchema.parameters.find { it.name == "body" }!!
            val props = body.properties!!.associate { it.name to it.type }
            assertEquals(Tool.ParameterType.INTEGER, props["petId"])
            assertEquals(Tool.ParameterType.INTEGER, props["quantity"])
            assertEquals(Tool.ParameterType.STRING, props["shipDate"])
            assertEquals(Tool.ParameterType.STRING, props["status"])
            assertEquals(Tool.ParameterType.BOOLEAN, props["complete"])
        }

        @Test
        fun `placeOrder status has enum values`() {
            val tool = findToolFromSpec(extendedSpec, "placeOrder")!!
            val body = tool.definition.inputSchema.parameters.find { it.name == "body" }!!
            val statusProp = body.properties!!.find { it.name == "status" }!!
            assertEquals(listOf("placed", "approved", "delivered"), statusProp.enumValues)
        }

        @Test
        fun `updatePet has both path param and body`() {
            val tool = findToolFromSpec(extendedSpec, "updatePet")!!
            val params = tool.definition.inputSchema.parameters
            assertTrue(params.any { it.name == "petId" && it.required })
            assertTrue(params.any { it.name == "body" && it.type == Tool.ParameterType.OBJECT })
        }

        @Test
        fun `uploadPetImage has path param and body`() {
            val tool = findToolFromSpec(extendedSpec, "uploadPetImage")!!
            val params = tool.definition.inputSchema.parameters
            val petIdParam = params.find { it.name == "petId" }!!
            assertTrue(petIdParam.required)
            val bodyParam = params.find { it.name == "body" }!!
            assertNotNull(bodyParam.properties!!.find { it.name == "url" })
        }

        @Test
        fun `getUserByName has string path param`() {
            val tool = findToolFromSpec(extendedSpec, "getUserByName")!!
            val param = tool.definition.inputSchema.parameters.single()
            assertEquals("username", param.name)
            assertEquals(Tool.ParameterType.STRING, param.type)
            assertTrue(param.required)
        }

        @Test
        fun `updateUser has string path param and body`() {
            val tool = findToolFromSpec(extendedSpec, "updateUser")!!
            val params = tool.definition.inputSchema.parameters
            val usernameParam = params.find { it.name == "username" }!!
            assertEquals(Tool.ParameterType.STRING, usernameParam.type)
            assertTrue(usernameParam.required)
            val bodyParam = params.find { it.name == "body" }!!
            assertEquals(Tool.ParameterType.OBJECT, bodyParam.type)
        }

        @Test
        fun `deleteOrder has integer path param`() {
            val tool = findToolFromSpec(extendedSpec, "deleteOrder")!!
            val param = tool.definition.inputSchema.parameters.single()
            assertEquals("orderId", param.name)
            assertEquals(Tool.ParameterType.INTEGER, param.type)
            assertTrue(param.required)
        }

        @Test
        fun `createUser body has required username and optional fields`() {
            val tool = findToolFromSpec(extendedSpec, "createUser")!!
            val body = tool.definition.inputSchema.parameters.find { it.name == "body" }!!
            val props = body.properties!!
            val usernameProp = props.find { it.name == "username" }!!
            assertTrue(usernameProp.required)
            val emailProp = props.find { it.name == "email" }!!
            assertFalse(emailProp.required)
            val phoneProp = props.find { it.name == "phone" }!!
            assertFalse(phoneProp.required)
            val statusProp = props.find { it.name == "userStatus" }!!
            assertEquals(Tool.ParameterType.INTEGER, statusProp.type)
        }

        @Test
        fun `no-tags-or-id operation gets synthesized name`() {
            val tools = materializeFromSpec(extendedSpec)
            val names = tools.map { it.definition.name }.toSet()
            assertTrue(names.any { it.contains("no_tags_or_id") }, "Expected synthesized name: $names")
        }
    }

    // ======================================================================
    // Spec-based integration tests (petstore-with-auth.json)
    // ======================================================================

    @Nested
    inner class PetstoreAuthIntegrationTests {

        @Test
        fun `auth spec materializes tools`() {
            val tools = materializeFromSpec(authSpec)
            assertEquals(1, tools.size)
            assertEquals("listPets", tools[0].definition.name)
        }

        @Test
        fun `auth spec listPets has no params`() {
            val tool = findToolFromSpec(authSpec, "listPets")!!
            assertTrue(tool.definition.inputSchema.parameters.isEmpty())
        }

        @Test
        fun `auth spec listPets has correct description`() {
            val tool = findToolFromSpec(authSpec, "listPets")!!
            assertEquals("List all pets", tool.definition.description)
        }
    }

    // ======================================================================
    // Learner integration tests across all specs
    // ======================================================================

    @Nested
    inner class LearnerIntegrationTests {

        private val learner = OpenApiLearner()
        private val minimalUrl = javaClass.classLoader.getResource("petstore-minimal.json")!!.toString()
        private val extendedUrl = javaClass.classLoader.getResource("petstore-extended.json")!!.toString()
        private val authUrl = javaClass.classLoader.getResource("petstore-with-auth.json")!!.toString()

        @Test
        fun `minimal spec creates UnfoldingTool with all operations`() {
            val tool = learner.learn(minimalUrl).create()
            assertInstanceOf(ProgressiveTool::class.java, tool)
            val allTools = collectAllLeafTools(tool)
            assertEquals(7, allTools.size)
        }

        @Test
        fun `extended spec creates UnfoldingTool with all operations`() {
            val tool = learner.learn(extendedUrl).create()
            assertInstanceOf(ProgressiveTool::class.java, tool)
            val allTools = collectAllLeafTools(tool)
            assertEquals(16, allTools.size)
        }

        @Test
        fun `auth spec creates tool`() {
            val tool = learner.learn(authUrl).create()
            assertInstanceOf(ProgressiveTool::class.java, tool)
            val allTools = collectAllLeafTools(tool)
            assertEquals(1, allTools.size)
        }

        @Test
        fun `extended spec groups by tag`() {
            val learned = learner.learn(extendedUrl)
            val tool = learned.create()
            // Extended spec has pets, store, user tags + one untagged
            // With multiple tags, it should create category-based progressive tool
            assertInstanceOf(ProgressiveTool::class.java, tool)
        }

        @Test
        fun `minimal spec has correct name`() {
            assertEquals("petstore", learner.learn(minimalUrl).name)
        }

        @Test
        fun `extended spec has correct name`() {
            assertEquals("petstore_extended", learner.learn(extendedUrl).name)
        }

        @Test
        fun `minimal spec has correct description`() {
            assertEquals("A minimal petstore API for testing", learner.learn(minimalUrl).description)
        }

        @Test
        fun `extended spec has correct description`() {
            assertEquals("Extended petstore API for thorough testing", learner.learn(extendedUrl).description)
        }

        @Test
        fun `minimal spec has no auth requirements`() {
            val reqs = learner.learn(minimalUrl).authRequirements
            assertEquals(1, reqs.size)
            assertInstanceOf(com.embabel.agent.api.client.AuthRequirement.None::class.java, reqs[0])
        }

        @Test
        fun `auth spec has api key and bearer requirements`() {
            val reqs = learner.learn(authUrl).authRequirements
            assertTrue(reqs.any { it is com.embabel.agent.api.client.AuthRequirement.ApiKey })
            assertTrue(reqs.any { it is com.embabel.agent.api.client.AuthRequirement.Bearer })
        }

        @Test
        fun `tool tree can be formatted for all specs`() {
            listOf(minimalUrl, extendedUrl, authUrl).forEach { url ->
                val tool = learner.learn(url).create()
                val tree = Tool.formatToolTree("test", listOf(tool))
                assertTrue(tree.isNotBlank())
            }
        }
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private fun createToolWithMock(
        method: PathItem.HttpMethod,
        path: String,
        operation: Operation,
        baseUrl: String = "https://api.example.com",
    ): Pair<OpenApiOperationTool, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val tool = OpenApiOperationTool(
            baseUrl = baseUrl,
            path = path,
            httpMethod = method,
            operation = operation,
            restClient = builder.build(),
        )
        return tool to server
    }

    private fun materializeFromSpec(spec: OpenAPI): List<Tool> {
        val baseUrl = spec.servers?.firstOrNull()?.url ?: "https://example.com"
        val restClient = RestClient.builder().build()
        val tools = mutableListOf<Tool>()
        spec.paths?.forEach { (path, pathItem) ->
            pathItem.readOperationsMap()?.forEach { (method, operation) ->
                val pathParams = pathItem.parameters ?: emptyList()
                val opParams = operation.parameters ?: emptyList()
                val existingNames = opParams.map { it.name }.toSet()
                operation.parameters = opParams + pathParams.filter { it.name !in existingNames }
                tools.add(
                    OpenApiOperationTool(
                        baseUrl = baseUrl,
                        path = path,
                        httpMethod = method,
                        operation = operation,
                        restClient = restClient,
                    )
                )
            }
        }
        return tools
    }

    private fun findToolFromSpec(spec: OpenAPI, name: String): Tool? =
        materializeFromSpec(spec).find { it.definition.name == name }

    private fun assertIsText(result: Tool.Result, expected: String) {
        assertInstanceOf(Tool.Result.Text::class.java, result)
        assertEquals(expected, (result as Tool.Result.Text).content)
    }

    private fun collectAllLeafTools(tool: Tool): List<Tool> {
        return when (tool) {
            is ProgressiveTool -> {
                val inner = tool.innerTools(
                    org.mockito.Mockito.mock(com.embabel.agent.core.AgentProcess::class.java),
                )
                inner.flatMap { collectAllLeafTools(it) }
            }
            else -> listOf(tool)
        }
    }
}
