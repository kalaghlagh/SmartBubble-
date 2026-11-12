package com.example.ai.providers

import com.example.data.model.ProviderEntity

/** Common interface the Central AI Orchestrator uses to talk to every provider. */
interface AiProviderClient {
    val type: AiProviderType

    /** Sends [request] and returns the generated text. Throws [AiException] on any failure. */
    suspend fun complete(request: AiRequest): AiResponse

    /**
     * Like [complete], but the provider streams its answer: [onText] receives each new piece of
     * text as it arrives (on a background thread). The returned response holds the whole text.
     * Cancelling the coroutine cancels the HTTP request and closes the stream.
     *
     * The default implementation does not stream: it delivers the complete answer once, when it
     * has arrived.
     */
    suspend fun stream(request: AiRequest, onText: (String) -> Unit): AiResponse =
        complete(request).also { onText(it.text) }

    /**
     * Performs a lightweight authenticated request that also checks the configured model.
     * Returns the latency in milliseconds; throws [AiException] on failure.
     */
    suspend fun testConnection(): Long
}

object AiProviderFactory {
    /** Creates a client for [provider] using the decrypted [apiKey] (never stored on the client beyond its lifetime). */
    fun create(provider: ProviderEntity, apiKey: String): AiProviderClient {
        if (apiKey.isBlank()) throw AiException(AiErrorKind.MISSING_API_KEY)
        if (provider.modelName.isBlank()) throw AiException(AiErrorKind.INVALID_CONFIGURATION, "Model is not set")
        val type = AiProviderType.fromStorageKey(provider.providerType)
        val baseUrl = provider.baseUrl.ifBlank { type.defaultBaseUrl }
        return when (type) {
            AiProviderType.OPENAI, AiProviderType.OPENAI_COMPATIBLE ->
                OpenAiClient(type, baseUrl, apiKey, provider.modelName.trim())
            AiProviderType.ANTHROPIC -> AnthropicClient(baseUrl, apiKey, provider.modelName.trim())
            AiProviderType.GEMINI -> GeminiClient(baseUrl, apiKey, provider.modelName.trim())
        }
    }
}
