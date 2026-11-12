package com.example.ai.providers

/** Whether a provider accepts `response_format: {"type":"json_object"}`. */
enum class JsonModePolicy {
    /** Never sent (unknown OpenAI-compatible endpoints). */
    NEVER,

    /**
     * Sent unless the model list says the model lacks it or the model rejected it once; the
     * prompt asks for JSON in any case.
     */
    WHEN_SUPPORTED
}

enum class ConnectionTest {
    /** GET {base}/models/{model}: authenticated and checks the model. */
    RETRIEVE_MODEL,

    /** Optional key check, then the model must appear in the provider's model list. */
    LIST_MODELS
}

/** Where a provider lists its models and how to read that list. */
class ModelSource(val path: String, val parse: (String) -> List<ModelInfo>)

/**
 * How one provider differs inside the OpenAI Chat Completions protocol. Request and stream
 * formats are identical for all of them (POST {base}/chat/completions, Bearer key, SSE with
 * `data: [DONE]`), so only these points are configured.
 */
data class ChatDialect(
    val jsonMode: JsonModePolicy,
    val connectionTest: ConnectionTest,
    /** Tried in order; a 404/405 moves to the next one. Empty = the provider has no model list. */
    val modelSources: List<ModelSource>,
    /** Authenticated endpoints checked before the model list (needed when that list is public). */
    val authCheckPaths: List<String> = emptyList(),
    /** OpenRouter ids may end in ":variant" (":free", ":nitro", ...). */
    val allowsVariantSuffix: Boolean = false
) {
    companion object {
        private val OPENAI = ChatDialect(
            jsonMode = JsonModePolicy.WHEN_SUPPORTED,
            connectionTest = ConnectionTest.RETRIEVE_MODEL,
            modelSources = listOf(ModelSource("models", ModelListParsers::plain))
        )

        private val COMPATIBLE = ChatDialect(
            jsonMode = JsonModePolicy.NEVER,
            connectionTest = ConnectionTest.RETRIEVE_MODEL,
            modelSources = listOf(ModelSource("models", ModelListParsers::plain))
        )

        /** DeepSeek: https://api.deepseek.com, GET /models, JSON output via response_format. */
        private val DEEPSEEK = ChatDialect(
            jsonMode = JsonModePolicy.WHEN_SUPPORTED,
            connectionTest = ConnectionTest.LIST_MODELS,
            modelSources = listOf(ModelSource("models", ModelListParsers::deepSeek))
        )

        /** Mistral: https://api.mistral.ai/v1, GET /v1/models with capability flags. */
        private val MISTRAL = ChatDialect(
            jsonMode = JsonModePolicy.WHEN_SUPPORTED,
            connectionTest = ConnectionTest.LIST_MODELS,
            modelSources = listOf(ModelSource("models", ModelListParsers::mistral))
        )

        /** Groq: https://api.groq.com/openai/v1, GET /models with active flag and context window. */
        private val GROQ = ChatDialect(
            jsonMode = JsonModePolicy.WHEN_SUPPORTED,
            connectionTest = ConnectionTest.LIST_MODELS,
            modelSources = listOf(ModelSource("models", ModelListParsers::groq))
        )

        /** xAI: https://api.x.ai/v1, GET /language-models (modalities), else GET /models. */
        private val XAI = ChatDialect(
            jsonMode = JsonModePolicy.WHEN_SUPPORTED,
            connectionTest = ConnectionTest.LIST_MODELS,
            modelSources = listOf(
                ModelSource("language-models", ModelListParsers::xaiLanguageModels),
                ModelSource("models", ModelListParsers::plain)
            )
        )

        /**
         * OpenRouter: https://openrouter.ai/api/v1. The model catalog is public, so the key is
         * checked with GET /key (older path /auth/key as a fallback).
         */
        private val OPENROUTER = ChatDialect(
            jsonMode = JsonModePolicy.WHEN_SUPPORTED,
            connectionTest = ConnectionTest.LIST_MODELS,
            modelSources = listOf(ModelSource("models", ModelListParsers::openRouter)),
            authCheckPaths = listOf("key", "auth/key"),
            allowsVariantSuffix = true
        )

        fun forType(type: AiProviderType): ChatDialect = when (type) {
            AiProviderType.OPENAI -> OPENAI
            AiProviderType.DEEPSEEK -> DEEPSEEK
            AiProviderType.MISTRAL -> MISTRAL
            AiProviderType.GROQ -> GROQ
            AiProviderType.XAI -> XAI
            AiProviderType.OPENROUTER -> OPENROUTER
            AiProviderType.OPENAI_COMPATIBLE, AiProviderType.ANTHROPIC, AiProviderType.GEMINI -> COMPATIBLE
        }
    }
}
