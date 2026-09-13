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

/**
 * Cross-cutting hook around a learned-API call.
 *
 * Because every learned-API invocation is routed through this client rather than the model calling
 * the remote service directly, there is exactly ONE place to observe and improve those calls. This
 * is that seam: argument diagnosis today, and the natural home for metrics, audit, redaction, and
 * rate-limit backoff later, without threading any of it through [openapi.OpenApiOperationTool].
 *
 * ## The problem this exists to solve
 *
 * When a caller supplies the wrong argument shape, the request is forwarded to the remote service
 * and the caller gets back whatever THAT service says. Quality then varies wildly and is outside
 * our control: OpenAlex helpfully replies "searchTerm is not a valid parameter", while arXiv
 * returns an XML feed that names nothing. Meanwhile the client already KNOWS the declared
 * parameters — they are in the OpenAPI operation — and simply never says so at the moment of
 * failure.
 *
 * Measured with a small model (gpt-oss-20b) driving the surface: asked for papers, it burned four
 * attempts on one method — `{query, maxResults}`, `{searchTerm, maxResults}`, `{q, per_page}`,
 * `{q, per_page:100}` — because nothing local ever told it the real shape. Telling it in the
 * DESCRIPTION did not help either; small models guess rather than look things up. The error is
 * where the model actually is when it needs this, so the error is where the answer belongs.
 */
interface ApiCallInterceptor {

    /**
     * Called BEFORE the remote request. Return null to proceed, or an [ApiCallError] to reject the
     * call without dialling out.
     *
     * Preferred over [onError] for anything decidable locally. Callers reach these tools from
     * inside a code-mode SCRIPT, where a failed call throws and ABORTS THE WHOLE SCRIPT — so every
     * badly-shaped call costs a full round-trip and discards the work the script had already done.
     * Rejecting pre-flight makes that cost as small as possible: no network latency, and — since
     * the request never leaves the process — no consumption of the remote's rate limit, which
     * otherwise punishes a caller for a mistake we could see coming.
     *
     * Fail-fast on the first bad call remains correct: continuing past a failed fetch just feeds
     * `undefined` into the rest of the script. The goal is to make each failure cheap and
     * self-explanatory, not to soldier on.
     */
    fun beforeCall(call: ApiCall): ApiCallError? = null

    /**
     * Called when an invocation fails, before the failure reaches the caller. Return [error]
     * unchanged to pass it through, or a copy with a more useful message. Use this for what only
     * the REMOTE can tell us (a bad value, a rejected enum, an auth problem) — anything knowable
     * from the spec belongs in [beforeCall].
     *
     * MUST NOT throw: an interceptor that fails would replace a real API error with an unrelated
     * one, which is strictly worse than the error it was trying to improve.
     */
    fun onError(call: ApiCall, error: ApiCallError): ApiCallError = error

    companion object {
        /** Applies [interceptors] in order, skipping any that throw. */
        fun chain(interceptors: List<ApiCallInterceptor>): ApiCallInterceptor =
            object : ApiCallInterceptor {
                override fun beforeCall(call: ApiCall): ApiCallError? =
                    interceptors.firstNotNullOfOrNull {
                        runCatching { it.beforeCall(call) }.getOrNull()
                    }

                override fun onError(call: ApiCall, error: ApiCallError): ApiCallError =
                    interceptors.fold(error) { acc, interceptor ->
                        runCatching { interceptor.onError(call, acc) }.getOrDefault(acc)
                    }
            }
    }
}

/**
 * What was invoked and with what — enough for an interceptor to diagnose the call without
 * reaching back into the tool.
 *
 * @param suppliedArgumentNames the top-level argument names the caller actually passed.
 * @param declaredParameterNames every parameter name the operation declares (path + query + body).
 */
data class ApiCall(
    val operationName: String,
    val httpMethod: String,
    val url: String,
    val suppliedArgumentNames: Set<String>,
    val declaredParameterNames: Set<String>,
) {
    /** Supplied names the operation does not declare — the usual cause of a 4xx. */
    val unknownArguments: Set<String> get() = suppliedArgumentNames - declaredParameterNames
}

/** A failed invocation. [message] is what the caller will see. */
data class ApiCallError(
    val status: Int?,
    val message: String,
)

/**
 * Turns an opaque remote 4xx into an actionable one by naming the arguments the operation actually
 * declares — the information the client had all along.
 *
 * Deliberately conservative:
 *  - only 4xx (a 5xx is the remote service's problem; the caller's arguments are not the fix);
 *  - appends, never replaces, so the remote's own diagnostic is preserved;
 *  - names the unknown arguments explicitly when there are any, because "you passed X, which is not
 *    declared" is far more actionable than a bare list of valid names.
 *
 * Parameter lists are capped so a large operation cannot flood the caller's context.
 */
class DeclaredParameterDiagnosingInterceptor(
    private val maxParametersListed: Int = 25,
) : ApiCallInterceptor {

    /**
     * Reject an argument name the operation does not declare WITHOUT calling the remote — the
     * mistake is already provable from the spec, so there is nothing to learn by asking the
     * service, and asking costs latency plus a slice of the caller's rate limit.
     *
     * Only fires when the operation declares parameters AND at least one supplied name is unknown;
     * an empty declaration set means the spec told us nothing, so we must not guess.
     */
    override fun beforeCall(call: ApiCall): ApiCallError? {
        if (call.declaredParameterNames.isEmpty()) return null
        val unknown = call.unknownArguments
        if (unknown.isEmpty()) return null
        return ApiCallError(
            status = null,
            message = "${call.operationName} was called with " +
                describeUnknown(unknown) + " ${declaredList(call)} " +
                "The call was NOT sent — fix the argument names and retry.",
        )
    }

    override fun onError(call: ApiCall, error: ApiCallError): ApiCallError {
        val status = error.status ?: return error
        if (status !in 400..499) return error
        if (call.declaredParameterNames.isEmpty()) return error
        // Unknown names are already rejected pre-flight, so a 4xx here means the NAMES were fine
        // and something else was wrong (a value, a type, a missing required field). List what the
        // operation declares so the caller can check types/required-ness against the remote's own
        // complaint, which is preserved above.
        return error.copy(
            message = "${error.message}\n\n${declaredList(call)} Check value types and required fields.",
        )
    }

    private fun describeUnknown(unknown: Set<String>): String =
        unknown.sorted().joinToString(", ") + (if (unknown.size == 1) " — not a parameter." else " — not parameters.")

    private fun declaredList(call: ApiCall): String {
        val declared = call.declaredParameterNames.sorted()
        val shown = declared.take(maxParametersListed).joinToString(", ")
        val overflow = (declared.size - maxParametersListed).takeIf { it > 0 }?.let { " (+$it more)" } ?: ""
        return "${call.operationName} declares: $shown$overflow."
    }
}
