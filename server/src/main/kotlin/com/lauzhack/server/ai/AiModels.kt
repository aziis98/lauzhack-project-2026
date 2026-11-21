package com.lauzhack.server.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * The two models the app uses, behind one Koog executor:
 *  - [ocrModel]: light vision model on **Together AI** (OpenAI-compatible API),
 *    transcribes chunk images;
 *  - [reasoningModel]: heavy model on **Anthropic**, does the math (hints, checks, recall).
 *
 * Each is null when its API key is missing, so the app still runs without AI.
 */
class AiModels(
    togetherApiKey: String?,
    ocrModelId: String,
    anthropicApiKey: String?,
    reasoningModelId: String,
) {
    /** Together speaks the OpenAI Chat Completions protocol, so Koog's OpenAI client is pointed at it. */
    val ocrModel: LLModel? = togetherApiKey?.let {
        LLModel(
            provider = LLMProvider.OpenAI,
            id = ocrModelId,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Vision.Image,
                LLMCapability.OpenAIEndpoint.Completions,
                // No Schema.JSON capability on purpose: Koog then asks for JSON in the
                // prompt and parses it, which works with any Together model.
            ),
            contextLength = 128_000,
            maxOutputTokens = 4_096,
        )
    }

    /**
     * Koog 1.3.0 has no constants for the newest Claude models, so the model is
     * declared here and registered in the client's version map (which is how the
     * Anthropic client resolves model ids).
     */
    val reasoningModel: LLModel? = anthropicApiKey?.let {
        LLModel(
            provider = LLMProvider.Anthropic,
            id = reasoningModelId,
            capabilities = listOf(
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Vision.Image,
                LLMCapability.Completion,
                LLMCapability.Schema.JSON.Basic,
                LLMCapability.Schema.JSON.Standard,
            ),
            contextLength = 200_000,
            maxOutputTokens = 32_000,
        )
    }

    val executor: PromptExecutor = MultiLLMPromptExecutor(
        buildMap {
            if (togetherApiKey != null) {
                put(
                    LLMProvider.OpenAI,
                    OpenAILLMClient(apiKey = togetherApiKey, settings = OpenAIClientSettings(baseUrl = TOGETHER_BASE_URL)),
                )
            }
            if (anthropicApiKey != null && reasoningModel != null) {
                put(
                    LLMProvider.Anthropic,
                    AnthropicLLMClient(
                        apiKey = anthropicApiKey,
                        settings = AnthropicClientSettings(modelVersionsMap = mapOf(reasoningModel to reasoningModelId)),
                    ),
                )
            }
        },
    )

    companion object {
        const val TOGETHER_BASE_URL = "https://api.together.xyz"
    }
}
