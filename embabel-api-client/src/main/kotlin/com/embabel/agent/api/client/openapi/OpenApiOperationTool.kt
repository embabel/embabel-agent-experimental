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

import com.embabel.agent.api.client.ToolNames
import com.embabel.agent.api.tool.Tool
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder

/**
 * A [Tool] that wraps a single OpenAPI operation, executing it via [RestClient].
 *
 * Each instance represents one API operation (e.g., `GET /pets/{petId}`)
 * materialized at runtime from an OpenAPI spec — no code generation needed.
 */
class OpenApiOperationTool(
    private val baseUrl: String,
    private val path: String,
    private val httpMethod: PathItem.HttpMethod,
    private val operation: Operation,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    // Map of named OpenAPI component schemas (`#/components/schemas/*`)
    // keyed by name. Used to follow `$ref` nodes when the spec was parsed
    // via OpenApiLearner.parseSpecPreservingRefs (the default for the
    // tool path since v8 of the schema-emission pipeline). Empty when the
    // spec was parsed fully-resolved or has no component schemas — the
    // tool then walks the inline structure as before.
    private val componentsSchemas: Map<String, Schema<*>> = emptyMap(),
    // Pre-serialised per-source named-types JSON, supplied by
    // [OpenApiLearner.materializeTools] so every sibling tool from the
    // same spec shares the same String reference rather than each
    // re-serialising the registry in its constructor — that re-
    // serialisation cost was O(operations × component-graph-size), which
    // for Google's specs meant ~1.8 MB of identical JSON per source
    // sitting in `Tool.Definition.metadata`. With this in place the
    // duplicated copies collapse to one.
    private val namedTypesJson: String? = null,
) : Tool {

    override val definition: Tool.Definition = Tool.Definition(
        name = operationName(httpMethod, path, operation),
        description = operationDescription(operation),
        inputSchema = buildInputSchema(operation, componentsSchemas),
    ).let { def ->
        var withMeta = def
        extractOutputSchema(operation, componentsSchemas)?.let {
            withMeta = withMeta.withMetadata(OUTPUT_SCHEMA_KEY, it)
        }
        // Per-source named-types JSON pre-computed by `materializeTools`
        // and shared by reference across every sibling tool (one
        // serialisation per spec instead of one per operation). See
        // `[namedTypesJson]` for context.
        namedTypesJson?.let {
            withMeta = withMeta.withMetadata(NAMED_TYPES_KEY, it)
        }
        withMeta
    }

    override fun call(input: String): Tool.Result {
        val started = System.currentTimeMillis()
        var uriForLog: String? = null
        return try {
            @Suppress("UNCHECKED_CAST")
            val params: Map<String, Any?> = if (input.isBlank()) {
                emptyMap()
            } else {
                objectMapper.readValue(input, Map::class.java) as Map<String, Any?>
            }

            val resolvedPath = resolvePath(path, params)
            val queryParams = resolveQueryParams(params)
            val body = resolveBody(params)

            val uri = buildUri(resolvedPath, queryParams)
            uriForLog = uri

            logger.info("Calling {} {} (baseUrl={})", httpMethod, uri, baseUrl)

            val response = executeRequest(uri, body)
            val elapsed = System.currentTimeMillis() - started
            val status = response.statusCode.value()
            logger.info("Completed {} {} -> {} in {}ms", httpMethod, uri, status, elapsed)
            val responseBody = response.body
            if (responseBody.isNullOrBlank()) {
                // Empty body handling differs by method:
                //  - GET with empty body is degenerate — the model can't act
                //    on nothing. Common cause: a synthesized OpenAPI spec
                //    omits required query params, so they get silently
                //    dropped and the API returns an empty 200. Without an
                //    error, the model retries the same call in a loop.
                //  - DELETE/POST/PUT/PATCH legitimately return 204 No
                //    Content (or 200 with empty body) on success. Return a
                //    non-empty status indicator so the caller knows the
                //    operation succeeded — and so we never produce
                //    Tool.Result.text("") (which downstream wraps in a
                //    TextPart that rejects empty content).
                return if (httpMethod == PathItem.HttpMethod.GET) {
                    Tool.Result.error(
                        "Empty response body from GET $uri (HTTP $status with no content). " +
                            "If this operation requires query parameters, they may not be declared in the OpenAPI spec.",
                    )
                } else {
                    Tool.Result.text("HTTP $status (no response body)")
                }
            }
            Tool.Result.text(responseBody)
        } catch (e: RestClientResponseException) {
            val errorBody = e.responseBodyAsString
            val message = "HTTP ${e.statusCode.value()} from $httpMethod $baseUrl$path after ${System.currentTimeMillis() - started}ms: ${errorBody.take(200)}"
            logger.warn(message)
            Tool.Result.error(message, e)
        } catch (e: Exception) {
            logger.warn(
                "Error calling {} {} at {} after {}ms: {} ({})",
                httpMethod, uriForLog ?: path, baseUrl,
                System.currentTimeMillis() - started,
                e.javaClass.simpleName, e.message,
            )
            Tool.Result.error("Error calling $httpMethod $path at $baseUrl: ${e.message}", e)
        }
    }

    private fun resolvePath(path: String, params: Map<String, Any?>): String {
        var resolved = path
        pathParameterNames().forEach { paramName ->
            val value = params[paramName]
            if (value != null) {
                resolved = resolved.replace("{$paramName}", value.toString())
            }
        }
        return resolved
    }

    /**
     * Resolve query parameters from the caller's argument map.
     *
     * Behaviour:
     *  - Declared query params (from the spec) always pass through.
     *  - For GET and DELETE, **undeclared** scalar/array params are also
     *    forwarded as query strings. This is the permissive mode: real APIs
     *    have many query parameters, and OpenAPI specs in the wild — and
     *    especially synthesized specs — frequently omit them. Silently
     *    dropping the LLM's args produces an empty/unfiltered request and
     *    sends the model into a retry loop with no diagnostic. Forwarding
     *    them lets the call succeed; if the server rejects unknown params
     *    that's a real signal the LLM can act on.
     *  - Path params, the reserved `body` key, and request-body properties
     *    (for methods with bodies) are excluded from the query string.
     */
    private fun resolveQueryParams(params: Map<String, Any?>): Map<String, List<String>> {
        val pathParams = pathParameterNames().toSet()
        val queryParamNames = (operation.parameters ?: emptyList())
            .filter { it.`in` == "query" }
            .map { it.name }
            .toSet()

        val isReadOnly = httpMethod == PathItem.HttpMethod.GET ||
            httpMethod == PathItem.HttpMethod.DELETE
        val bodyPropNames: Set<String> = if (isReadOnly) {
            emptySet()
        } else {
            // Body schema may be a bare `$ref` node when the spec was
            // parsed via [OpenApiLearner.parseSpecPreservingRefs]; deref
            // before reading `.properties` to recover the target type's
            // declared body fields. Without this the request would forward
            // those fields as query params, which most APIs reject.
            operation.requestBody?.content?.values?.firstOrNull()?.schema
                ?.deref(componentsSchemas)
                ?.properties?.keys?.toSet().orEmpty()
        }

        return params
            .filter { (k, _) ->
                k != "body" && k !in pathParams && k !in bodyPropNames &&
                    (k in queryParamNames || isReadOnly)
            }
            .filter { it.value != null }
            .mapValues { entry ->
                when (val v = entry.value) {
                    is Collection<*> -> v.mapNotNull { it?.toString() }
                    else -> listOf(v.toString())
                }
            }
    }

    private fun pathParameterNames(): List<String> {
        val regex = "\\{([^}]+)}".toRegex()
        return regex.findAll(path).map { it.groupValues[1] }.toList()
    }

    /**
     * Build the request body from the caller's flat argument map.
     *
     * Inputs are accepted in two shapes:
     *   1. **Flat** (preferred — matches the typed surface):
     *      `{owner, repo, title, body, labels}` — body fields appear at
     *      the top level alongside path/query params. The tool gathers
     *      every property the request-body schema declares (minus path/
     *      query names, since those win on collision).
     *   2. **Wrapper** (legacy / non-object body):
     *      `{owner, repo, body: {title, body}}` — body nested under a
     *      reserved `body` key. Used when the request body is non-object
     *      (raw string, array) or when the caller chose to send a wrapper.
     *
     * If both shapes are present (legacy `body: {...}` plus flat fields),
     * the flat fields override matching keys in the wrapper.
     *
     * The flat shape eliminates the body-name-collision bug for ops whose
     * request body has a property named `body` (e.g. GitHub `issues/create`,
     * `issues/create-comment`, `pulls/create`).
     */
    private fun resolveBody(params: Map<String, Any?>): Any? {
        // Same deref reasoning as `resolveQueryParams`: the body schema
        // may arrive as a bare ref under the ref-preserving parse.
        val bodySchema = operation.requestBody?.content?.values?.firstOrNull()?.schema
            ?.deref(componentsSchemas)
            ?: return null

        val bodyProps = bodySchema.properties?.keys?.toSet().orEmpty()
        val isObjectBody = (bodySchema.type == "object" || bodyProps.isNotEmpty())

        if (!isObjectBody) {
            // Non-object body — use the wrapper key directly.
            return params["body"]
        }

        val pathParams = pathParameterNames().toSet()
        val queryParamNames = (operation.parameters ?: emptyList())
            .filter { it.`in` == "query" }
            .map { it.name }
            .toSet()
        val flat = params.filter { (k, _) ->
            k in bodyProps && k !in pathParams && k !in queryParamNames
        }

        @Suppress("UNCHECKED_CAST")
        val wrapper = params["body"] as? Map<String, Any?>
        return when {
            wrapper != null && flat.isNotEmpty() -> wrapper + flat
            wrapper != null -> wrapper
            flat.isNotEmpty() -> flat
            else -> null
        }
    }

    private fun buildUri(resolvedPath: String, queryParams: Map<String, List<String>>): String {
        val builder = UriComponentsBuilder
            .fromUriString(baseUrl.trimEnd('/') + resolvedPath)

        queryParams.forEach { (key, values) ->
            values.forEach { value ->
                builder.queryParam(key, value)
            }
        }

        return builder.build().toUriString()
    }

    private fun executeRequest(uri: String, body: Any?): ResponseEntity<String> {
        return when (httpMethod) {
            PathItem.HttpMethod.GET -> restClient.get().uri(uri)
                .retrieve().toEntity(String::class.java)

            PathItem.HttpMethod.DELETE -> restClient.delete().uri(uri)
                .retrieve().toEntity(String::class.java)

            PathItem.HttpMethod.POST -> executeWithBody(restClient.post().uri(uri), body)
            PathItem.HttpMethod.PUT -> executeWithBody(restClient.put().uri(uri), body)
            PathItem.HttpMethod.PATCH -> executeWithBody(restClient.patch().uri(uri), body)

            else -> throw UnsupportedOperationException("HTTP method $httpMethod not supported")
        }
    }

    private fun executeWithBody(
        spec: RestClient.RequestBodySpec,
        body: Any?,
    ): ResponseEntity<String> {
        if (body != null) {
            if (preferFormUrlEncoded()) {
                spec.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(toFormBody(body))
            } else {
                spec.contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
            }
        }
        return spec.retrieve().toEntity(String::class.java)
    }

    // True when the operation's requestBody declares
    // `application/x-www-form-urlencoded` AND not `application/json`.
    // Conservative on purpose — JSON wins when a spec lists both, so
    // every existing pack keeps its current wire format.
    private fun preferFormUrlEncoded(): Boolean {
        val content = operation.requestBody?.content ?: return false
        val keys = content.keys
        if (keys.any { it.equals(MediaType.APPLICATION_JSON_VALUE, ignoreCase = true) }) return false
        return keys.any { it.equals(MediaType.APPLICATION_FORM_URLENCODED_VALUE, ignoreCase = true) }
    }

    // Flatten an arbitrary body graph into a `MultiValueMap<String,String>`
    // using Stripe / Rails bracket syntax: `metadata[key]=v`, `items[0][price]=p`.
    // Booleans render `true`/`false`; nulls are dropped (Stripe treats a
    // missing key as "leave unchanged"). Non-map/list scalars at the root
    // are coerced to a single `body=<toString>` pair as a last resort —
    // shouldn't happen for any reasonable form-encoded spec.
    private fun toFormBody(body: Any): MultiValueMap<String, String> {
        val out = LinkedMultiValueMap<String, String>()
        when (body) {
            is Map<*, *> -> body.forEach { (k, v) -> appendFormPair(out, k.toString(), v) }
            is Collection<*> -> body.forEachIndexed { i, v -> appendFormPair(out, "[$i]", v) }
            else -> out.add("body", body.toString())
        }
        return out
    }

    private fun appendFormPair(out: MultiValueMap<String, String>, key: String, value: Any?) {
        when (value) {
            null -> Unit
            is Map<*, *> -> value.forEach { (k, v) -> appendFormPair(out, "$key[${k}]", v) }
            is Collection<*> -> value.forEachIndexed { i, v -> appendFormPair(out, "$key[$i]", v) }
            is Boolean -> out.add(key, value.toString())
            is Number -> out.add(key, value.toString())
            else -> out.add(key, value.toString())
        }
    }

    companion object {

        /**
         * Metadata key for the JSON Schema string describing the tool's
         * response. Populated from the OpenAPI spec's `responses.2xx`
         * content schema when available.
         */
        const val OUTPUT_SCHEMA_KEY: String = "outputSchema"

        // Metadata key for the per-source named-types JSON Schema map.
        //
        // Value is a JSON string mapping each TypeName to its JSON Schema —
        // one entry per `#/components/schemas/*` in the source spec, with
        // inter-type references kept as JSON Schema $ref pointers (into a
        // `#/$defs/X` namespace) rather than inlined so the graph stays
        // compact. Same string value on every tool from the same source;
        // downstream surface generators (e.g. JavaScriptCodeSurfaceBuilder)
        // read it once and emit one `interface TypeName { ... }` per entry.
        const val NAMED_TYPES_KEY: String = "namedTypes"

        private val logger = LoggerFactory.getLogger(OpenApiOperationTool::class.java)

        // Serialize the named-types registry (`components/schemas`) reachable
        // from [entryPointOperations] into a single JSON object mapping
        // TypeName to JSON Schema, suitable for shipping on
        // `Tool.Definition.metadata[NAMED_TYPES_KEY]`. Refs between named
        // types are preserved as JSON Schema $ref pointers (into a
        // `#/$defs/X` namespace) rather than inlined.
        //
        // Reachability is computed from [entryPointOperations]'s
        // request/response/parameter schemas, following $refs transitively.
        // Types not reachable from a curated operation are dropped — for a
        // pack like Sheets that exposes 9 of 17 operations, this strips
        // hundreds of unreferenced sub-types from the JSON the LLM has to
        // load.
        //
        // Returns `null` when the spec has no component schemas (or when
        // none are reachable from the entry points), so the metadata key
        // is simply absent.
        internal fun namedTypesAsJson(
            componentsSchemas: Map<String, Schema<*>>,
            entryPointOperations: Iterable<Operation>,
        ): String? {
            if (componentsSchemas.isEmpty()) return null
            val reachable = computeReachableTypeNames(componentsSchemas, entryPointOperations)
            if (reachable.isEmpty()) return null
            return try {
                val mapped = reachable.associateWith { name ->
                    schemaToMap(componentsSchemas.getValue(name), componentsSchemas)
                }
                jacksonObjectMapper().writeValueAsString(mapped)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Walk every schema reachable from [entryPointOperations]'s
         * parameters / request bodies / responses, following `$ref`s
         * transitively into [componentsSchemas]. Returns the set of named
         * types actually used by the curated operation surface — the
         * inverse "everything in the spec" wastes context on schemas no
         * curated method ever returns or accepts.
         */
        private fun computeReachableTypeNames(
            componentsSchemas: Map<String, Schema<*>>,
            entryPointOperations: Iterable<Operation>,
        ): Set<String> {
            val frontier = ArrayDeque<Schema<*>>()
            for (op in entryPointOperations) {
                op.parameters?.forEach { it.schema?.let(frontier::add) }
                op.requestBody?.content?.values?.forEach { it.schema?.let(frontier::add) }
                op.responses?.values?.forEach { resp ->
                    resp.content?.values?.forEach { it.schema?.let(frontier::add) }
                }
            }
            val seen = LinkedHashSet<String>()
            while (frontier.isNotEmpty()) {
                val schema = frontier.removeFirst()
                schema.`$ref`?.let { ref ->
                    val name = extractRefName(ref)
                    if (name != null && seen.add(name)) {
                        componentsSchemas[name]?.let(frontier::add)
                    }
                }
                schema.properties?.values?.forEach(frontier::add)
                (schema as? ArraySchema)?.items?.let(frontier::add)
                if (schema !is ArraySchema) schema.items?.let(frontier::add)
                schema.allOf?.forEach(frontier::add)
                schema.oneOf?.forEach(frontier::add)
                schema.anyOf?.forEach(frontier::add)
                (schema.additionalProperties as? Schema<*>)?.let(frontier::add)
            }
            return seen
        }

        // Extract the JSON Schema of the success response (first 2xx with
        // a JSON content schema) from an OpenAPI operation. Returns the
        // schema as a JSON string, or `null` if unavailable.
        //
        // The result preserves $ref markers (pointing into the
        // `#/$defs/X` namespace) for any property whose original schema
        // was a named-component reference, so downstream code-surface
        // generators can emit one `interface Foo { ... }` block instead
        // of inlining Foo's shape at every call site.
        private fun extractOutputSchema(
            operation: Operation,
            componentsSchemas: Map<String, Schema<*>>,
        ): String? {
            val responses = operation.responses ?: return null
            // Try 200, 201, then any 2xx
            val successResponse = responses["200"]
                ?: responses["201"]
                ?: responses.entries.firstOrNull { it.key.startsWith("2") }?.value
                ?: return null
            val mediaType = successResponse.content
                ?.get("application/json")
                ?: successResponse.content?.values?.firstOrNull()
                ?: return null
            val schema = mediaType.schema ?: return null
            return try {
                jacksonObjectMapper().writeValueAsString(schemaToMap(schema, componentsSchemas))
            } catch (_: Exception) {
                null
            }
        }

        // Convert a Swagger [Schema] to a Map suitable for JSON
        // serialization. Handles object, array, primitive, oneOf/anyOf
        // combinators, and nullable types.
        //
        // When [schema] is a bare $ref node (the spec was parsed via
        // [OpenApiLearner.parseSpecPreservingRefs]), emits a JSON Schema
        // $ref marker pointing into a `#/$defs/TypeName` namespace
        // rather than inlining the referenced shape. Downstream surface
        // generators emit one named interface and reference it from every
        // method that returns the type — what shrinks Sheets/Docs from
        // 200KB+ of inlined types per namespace down to the registry
        // size + a few bytes per signature.
        //
        // The output is consumed by `JavaScriptCodeSurfaceBuilder` /
        // `PythonCodeSurfaceBuilder` to emit typed interfaces; missing
        // fields here become `unknown` in the generated TypeScript.
        private fun schemaToMap(
            schema: Schema<*>,
            componentsSchemas: Map<String, Schema<*>>,
        ): Map<String, Any?> {
            // Bare `$ref`: emit a JSON-Schema reference into the `$defs`
            // namespace. Don't inline the target — the named-types map
            // ships separately and the surface builder splices it in once
            // per namespace.
            schema.`$ref`?.let { ref ->
                val name = extractRefName(ref)
                if (name != null) {
                    return buildMap<String, Any?> {
                        put("\$ref", "#/\$defs/$name")
                        schema.description?.let { put("description", it) }
                    }
                }
                // External ref or malformed — fall through to the inline
                // walk below; without a registry to dereference against,
                // it'll come out as `unknown` rather than crashing.
            }
            return buildMap {
                // Type inference: explicit `type` wins; otherwise infer from
                // structure. A schema with `properties` but no `type` is
                // implicitly object; a schema with `items` is implicitly array.
                val inferredType = schema.type ?: when {
                    schema is ArraySchema || schema.items != null -> "array"
                    schema.properties != null -> "object"
                    schema.allOf != null || schema.oneOf != null || schema.anyOf != null -> "object"
                    else -> null
                }
                inferredType?.let { put("type", it) }

                schema.description?.let { put("description", it) }
                schema.format?.let { put("format", it) }
                // OpenAPI 3.0 nullable + 3.1 type:["x","null"] both map here.
                if (schema.nullable == true) put("nullable", true)

                if (schema.properties != null) {
                    put(
                        "properties",
                        schema.properties.mapValues { (_, v) -> schemaToMap(v, componentsSchemas) },
                    )
                }
                if (schema.required != null) {
                    put("required", schema.required)
                }
                // Array items — handle both ArraySchema.items (typed) and
                // generic Schema.items (post-resolveFully sometimes lands here).
                val items = (schema as? ArraySchema)?.items ?: schema.items
                if (items != null) {
                    put("items", schemaToMap(items, componentsSchemas))
                }

                // allOf / oneOf / anyOf — emit a union or merged shape.
                // Downstream TS emitter treats these as union types when the
                // shape isn't an object merge.
                schema.allOf?.let { branches ->
                    if (branches.isNotEmpty()) put("allOf", branches.map { schemaToMap(it, componentsSchemas) })
                }
                schema.oneOf?.let { branches ->
                    if (branches.isNotEmpty()) put("oneOf", branches.map { schemaToMap(it, componentsSchemas) })
                }
                schema.anyOf?.let { branches ->
                    if (branches.isNotEmpty()) put("anyOf", branches.map { schemaToMap(it, componentsSchemas) })
                }

                schema.enum?.let { put("enum", it) }
                // Additional properties — `Record<string, T>`-like maps.
                when (val ap = schema.additionalProperties) {
                    is Schema<*> -> put("additionalProperties", schemaToMap(ap, componentsSchemas))
                    is Boolean -> put("additionalProperties", ap)
                    else -> {}
                }
            }
        }

        internal fun operationName(
            httpMethod: PathItem.HttpMethod,
            path: String,
            operation: Operation,
        ): String {
            // Prefer operationId if available
            if (!operation.operationId.isNullOrBlank()) {
                return ToolNames.sanitize(operation.operationId)
            }
            // Synthesize from method + path: GET /pets/{petId} → get_pets_by_petId
            val synthesized = path
                .replace("{", "by_")
                .replace("}", "")
                .replace("/", "_")
                .replace("-", "_")
                .trimStart('_')
                .trimEnd('_')
                .replace("__", "_")
            return ToolNames.sanitize("${httpMethod.name.lowercase()}_$synthesized")
        }

        internal fun operationDescription(operation: Operation): String {
            return listOfNotNull(
                operation.summary,
                operation.description?.takeIf { it != operation.summary },
            ).joinToString(". ").ifBlank { "No description available" }
        }

        internal fun buildInputSchema(
            operation: Operation,
            componentsSchemas: Map<String, Schema<*>>,
        ): Tool.InputSchema {
            val parameters = mutableListOf<Tool.Parameter>()

            // Path and query parameters
            operation.parameters?.forEach { param ->
                parameters.add(mapParameter(param, componentsSchemas))
            }
            val pathQueryNames = parameters.map { it.name }.toSet()

            // Request body — INLINE the body's properties at the top level
            // so the typed surface presents a flat callable shape:
            //   issuesCreate({owner, repo, title, body, labels})
            // not the legacy nested wrapper:
            //   issuesCreate({owner, repo, body: {title, body, labels}})
            // The wrapper form created a name-collision footgun (a request
            // body containing a property named `body` — every GitHub
            // `issues/create`, `issues/create-comment`, `pulls/create` — got
            // reduced to a string by the LLM and rejected with HTTP 422).
            //
            // Body schema may itself be a $ref node when the spec was
            // parsed via `parseSpecPreservingRefs`; deref so the property
            // expansion below still finds the referenced shape's fields.
            operation.requestBody?.content?.values?.firstOrNull()?.schema?.let { rawSchema ->
                val schema = rawSchema.deref(componentsSchemas)
                val bodyRequiredOverall = operation.requestBody?.required ?: false
                @Suppress("UNCHECKED_CAST")
                val properties = schema.properties as? Map<String, Schema<*>>
                if (!properties.isNullOrEmpty()) {
                    val bodyRequired = (schema.required as? List<*>)?.map { it.toString() }?.toSet().orEmpty()
                    for ((propName, propSchema) in properties) {
                        if (propName in pathQueryNames) continue // path/query wins on collision
                        parameters.add(
                            mapSchemaToParameter(
                                name = propName,
                                schema = propSchema,
                                description = propSchema.description ?: propName,
                                required = bodyRequiredOverall && propName in bodyRequired,
                                componentsSchemas = componentsSchemas,
                            ),
                        )
                    }
                } else {
                    // Non-object body (raw string, array, etc.) — keep the
                    // legacy `body` wrapper since there's nothing to flatten.
                    parameters.add(
                        mapSchemaToParameter(
                            name = "body",
                            schema = schema,
                            description = "Request body",
                            required = true,
                            componentsSchemas = componentsSchemas,
                        ),
                    )
                }
            }

            return Tool.InputSchema.of(*parameters.toTypedArray())
        }

        private fun mapParameter(
            param: Parameter,
            componentsSchemas: Map<String, Schema<*>>,
        ): Tool.Parameter {
            // Param schemas are typically primitive but specs in the wild
            // sometimes ref out to a named primitive alias (e.g.
            // `#/components/schemas/PetStatus` for an enum). Deref so the
            // type/enum extraction below sees the underlying shape.
            val schema = param.schema?.deref(componentsSchemas)
            val type = mapSchemaType(schema)
            val itemType = if (type == Tool.ParameterType.ARRAY && schema != null) {
                if (schema is ArraySchema) {
                    schema.items?.deref(componentsSchemas)?.let { mapSchemaType(it) }
                        ?: Tool.ParameterType.STRING
                } else {
                    Tool.ParameterType.STRING
                }
            } else null
            return Tool.Parameter(
                name = param.name,
                type = type,
                description = param.description ?: param.name,
                required = param.required ?: (param.`in` == "path"),
                // OpenAPI 3.1 nullable enums commonly include explicit null
                // (the GitHub spec uses this for state filters). Drop nulls
                // rather than NPE on `.toString()`. Same fix as
                // `OpenApiModelBuilder.convertSchema`.
                enumValues = schema?.enum?.mapNotNull { it?.toString() },
                itemType = itemType,
            )
        }

        private fun mapSchemaToParameter(
            name: String,
            schema: Schema<*>,
            description: String,
            required: Boolean,
            componentsSchemas: Map<String, Schema<*>>,
        ): Tool.Parameter {
            // Deref the property schema so a bare $ref node (under the
            // ref-preserving parse) resolves to its target shape before we
            // read `.type`, `.properties`, etc. Without this, every $ref-
            // typed body field would land here as STRING (the fallback in
            // `mapSchemaType` for unknown type), losing structure.
            val derefed = schema.deref(componentsSchemas)
            val type = mapSchemaType(derefed)

            val properties = if (type == Tool.ParameterType.OBJECT && derefed.properties != null) {
                val requiredProps = derefed.required?.toSet() ?: emptySet()
                derefed.properties.map { (propName, propSchema) ->
                    mapSchemaToParameter(
                        name = propName,
                        schema = propSchema,
                        description = propSchema.description ?: propName,
                        required = propName in requiredProps,
                        componentsSchemas = componentsSchemas,
                    )
                }
            } else null

            val itemType = if (type == Tool.ParameterType.ARRAY) {
                if (derefed is ArraySchema) {
                    derefed.items?.deref(componentsSchemas)?.let { mapSchemaType(it) }
                        ?: Tool.ParameterType.STRING
                } else {
                    // Fallback for array params without ArraySchema (e.g. Swagger 2.0 conversion)
                    Tool.ParameterType.STRING
                }
            } else null

            return Tool.Parameter(
                name = name,
                type = type,
                description = derefed.description ?: description,
                required = required,
                enumValues = derefed.enum?.mapNotNull { it?.toString() },
                properties = properties,
                itemType = itemType,
            )
        }

        private fun mapSchemaType(schema: Schema<*>?): Tool.ParameterType {
            return when (schema?.type) {
                "string" -> Tool.ParameterType.STRING
                "integer" -> Tool.ParameterType.INTEGER
                "number" -> Tool.ParameterType.NUMBER
                "boolean" -> Tool.ParameterType.BOOLEAN
                "array" -> Tool.ParameterType.ARRAY
                "object" -> Tool.ParameterType.OBJECT
                else -> Tool.ParameterType.STRING
            }
        }
    }
}

/**
 * Follow a single level of `$ref` against [componentsSchemas]. Returns
 * [this] if there's no `$ref` or the referenced name isn't in the
 * registry. Used wherever the runtime walks properties or types and
 * expects a populated schema, not a bare reference (e.g. building the
 * request body, mapping property types into [Tool.Parameter]s).
 *
 * File-scoped so both the runtime instance methods and the
 * companion-object schema helpers can call it without re-importing.
 */
internal fun Schema<*>.deref(
    componentsSchemas: Map<String, Schema<*>>,
): Schema<*> {
    val ref = this.`$ref` ?: return this
    val name = extractRefName(ref) ?: return this
    return componentsSchemas[name] ?: this
}

internal fun extractRefName(ref: String): String? {
    val prefix = "#/components/schemas/"
    return if (ref.startsWith(prefix)) ref.removePrefix(prefix).takeIf { it.isNotBlank() } else null
}
