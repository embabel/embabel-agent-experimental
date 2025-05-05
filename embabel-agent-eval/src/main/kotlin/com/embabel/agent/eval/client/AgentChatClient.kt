/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.eval.client

import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

data class KnowledgeContext(
    val name: String,
    val description: String,
    val schemaName: String = "personal",
    val id: String = name,
)

data class SessionCreationRequest(
    val user: String,
    val chatbot: String,
)

data class SessionCreationResponse(
    val sessionId: String,
)

/**
 * Simple client to Agent chat
 */
@Service
class AgentChatClient(
    private val restTemplate: RestTemplate = RestTemplate(),
    private val agentHost: String = "http://localhost:8081",
    private val agentChatPath: String = "/api/v1/chat",
    private val boogieHost: String = "http://localhost:8080",
    private val boogieContextPath: String = "/api/v1/graphs",
    private val apiKey: String = "treehorn",
) {

    // TODO share with the BoogieClient
    val defaultHeaders = HttpHeaders().apply {
        set("Content-Type", "application/json")
        set("X-API-KEY", apiKey)
    }

    fun createKnowledgeContext(knowledgeContext: KnowledgeContext): String {
        val entity = HttpEntity(knowledgeContext, defaultHeaders)
        return restTemplate.exchange(
            "${boogieHost}/${boogieContextPath}",
            HttpMethod.PUT,
            entity,
            String::class.java,
        ).body ?: throw IllegalStateException("No response body")
    }

    fun createSession(sessionCreationRequest: SessionCreationRequest): SessionCreationResponse {
        val url = "${agentHost}/${agentChatPath}/sessions"
        val entity = HttpEntity(sessionCreationRequest, defaultHeaders)
        val re = restTemplate.exchange(
            url,
            HttpMethod.PUT,
            entity,
            SessionCreationResponse::class.java,
        )
        return re.body ?: throw IllegalStateException("No response body")
    }

//    fun ingestDocument(knowledgeContext: KnowledgeContext): String {
//        val entity = HttpEntity(knowledgeContext, defaultHeaders)
//        return restTemplate.exchange(
//            "${boogieHost}/${boogieContextPath}",
//            HttpMethod.PUT,
//            entity,
//            String::class.java,
//        ).body ?: throw IllegalStateException("No response body")
//    }

    fun getObjectContext(id: String): ObjectContext {
        return restTemplate.getForObject(
            "${agentHost}/${agentChatPath}/objectContexts/{id}",
            ObjectContext::class.java,
            id,
        ) ?: throw IllegalStateException("No response body")
    }

    fun respond(chatRequest: ChatRequest): MessageResponse {
        val entity = HttpEntity(chatRequest)
        return restTemplate.exchange(
            "${agentHost}/${agentChatPath}/messages",
            HttpMethod.PUT,
            entity,
            MessageResponse::class.java,
        ).body ?: throw IllegalStateException("No response body")
    }

}
