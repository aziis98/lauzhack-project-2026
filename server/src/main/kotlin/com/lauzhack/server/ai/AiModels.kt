package com.lauzhack.server.ai

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * The models the app uses, behind one Koog executor:
 *  - [ocrModel]: light vision model on **Together AI** (OpenAI-compatible API),
 *    transcribes chunk images;
 *  - [agentModel]: the page agent, one structured call per settled page.
 *
 * Each is null when the API key is missing, so the app still runs without AI.
 */
class AiModels(
    togetherApiKey: String?,
    ocrModelId: String,
    agentModelId: String,
) {
    /** Together speaks the OpenAI Chat Completions protocol, so Koog's OpenAI client is pointed at it. */
    val ocrModel: LLModel? = togetherApiKey?.let { togetherModel(ocrModelId, vision = true) }

    /** The page agent (one call per changed page, structured actions out). */
    val agentModel: LLModel? = togetherApiKey?.let { togetherModel(agentModelId, vision = false) }

    val executor: PromptExecutor = MultiLLMPromptExecutor(
        buildMap {
            if (togetherApiKey != null) {
                put(
                    LLMProvider.OpenAI,
                    OpenAILLMClient(apiKey = togetherApiKey, settings = OpenAIClientSettings(baseUrl = TOGETHER_BASE_URL)),
                )
            }
        },
    )

    companion object {
        const val TOGETHER_BASE_URL = "https://api.together.xyz"

        private fun togetherModel(id: String, vision: Boolean) = LLModel(
            provider = LLMProvider.OpenAI,
            id = id,
            capabilities = listOfNotNull(
                LLMCapability.Completion,
                LLMCapability.Vision.Image.takeIf { vision },
                LLMCapability.OpenAIEndpoint.Completions,
                // No Schema.JSON capability on purpose: Koog then asks for JSON in the
                // prompt and parses it, which works with any Together model.
            ),
            contextLength = 128_000,
            maxOutputTokens = 4_096,
        )
    }
}
