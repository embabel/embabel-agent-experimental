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
package com.embabel.agent.eval.support

import com.embabel.agent.eval.client.SessionCreationRequest
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.entity
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions

internal val SCORES_EXAMPLE = SubjectiveScores(
    tone = 0.5,
    tasks = listOf(
        Score("What is the capital of France?", 0.9),
        Score("Who was the first President of France", 0.4),
        Score("Tell me a joke", 0.7),
        Score("Tell me a story in 50 words", 0.6),
    ),
)

/**
 * Scores a conversation transcript using an LLM-as-judge pattern.
 * Reusable independently of [com.embabel.agent.eval.runner.DefaultEvaluationRunner]
 * for in-process evaluation scenarios.
 */
class TranscriptScorer(
    private val scoringChatModel: ChatModel,
    private val templateRenderer: TemplateRenderer,
) {

    fun scoreTranscript(evaluationRun: EvaluationRun): SubjectiveScores {
        val scoringChatOptions = ChatOptions.builder()
            .temperature(evaluationRun.job.scorer.temperature)
            .build()
        val prompt = templateRenderer.renderLoadedTemplate(
            evaluationRun.job.scorer.prompt,
            mapOf(
                "config" to evaluationRun.job,
                "transcript" to evaluationRun.transcript,
                "example" to jacksonObjectMapper().registerModule(JavaTimeModule()).writerWithDefaultPrettyPrinter()
                    .writeValueAsString(SCORES_EXAMPLE),
            )
        )
        val chatClient = ChatClient
            .builder(scoringChatModel)
            .defaultOptions(scoringChatOptions)
            .build()
        return chatClient.prompt(prompt).call()
            .entity<SubjectiveScores>()
    }

    /**
     * Java-friendly entry point for in-process evaluation.
     * Builds a minimal [EvaluationJob] and scores the transcript.
     */
    @JvmOverloads
    fun scoreConversation(
        tasks: List<Task>,
        facts: List<String>,
        transcript: List<TimedOpenAiCompatibleMessage>,
        scorer: Scorer = Scorer(),
    ): SubjectiveScores {
        val job = EvaluationJob(
            evaluator = Evaluator(),
            aspirationalAverage = 5000,
            target = SessionCreationRequest("in-process", "in-process"),
            scorer = scorer,
            tasks = tasks,
            facts = facts,
        )
        val run = object : EvaluationRun {
            override val job = job
            override val transcript = transcript
        }
        return scoreTranscript(run)
    }
}
