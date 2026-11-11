package com.example.ai.providers

import okhttp3.HttpUrl

/**
 * Anthropic Messages API: POST {base}/v1/messages with `x-api-key` and `anthropic-version`
 * headers. Connection test: GET {base}/v1/models/{model} (authenticated, verifies the model).
 */
class AnthropicClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String
) : AiProviderClient {

    override val type: AiProviderType = AiProviderType.ANTHROPIC

    private val base: HttpUrl = AiHttp.parseBaseUrl(normalizeBaseUrl(baseUrl))
    private val headers = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to API_VERSION,
        "Accept" to "application/json"
    )

    override suspend fun complete(request: AiRequest): AiResponse {
        val url = base.newBuilder().addPathSegments("v1/messages").build()
        var result = AiHttp.execute(AiHttp.jsonPost(url, headers, buildMessagesBody(model, request)), apiKey)
        if (result.code == 400 && request.maxOutputTokens > LEGACY_MAX_OUTPUT &&
            AiHttp.providerErrorMessage(result.body).orEmpty().contains("max_tokens")
        ) {
            // Older models have a smaller output limit: retry once with a limit they accept.
            val smaller = request.copy(maxOutputTokens = LEGACY_MAX_OUTPUT)
            result = AiHttp.execute(AiHttp.jsonPost(url, headers, buildMessagesBody(model, smaller)), apiKey)
        }
        if (result.code !in 200..299) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
        return parseMessagesResponse(result.body)
    }

    override suspend fun testConnection(): Long {
        val start = System.currentTimeMillis()
        val url = base.newBuilder().addPathSegments("v1/models").addPathSegment(model).build()
        val result = AiHttp.execute(AiHttp.get(url, headers), apiKey)
        if (result.code !in 200..299) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
        return System.currentTimeMillis() - start
    }

    companion object {
        /** Current API version. Sampling parameters (temperature/top_p/top_k) must not be sent with it. */
        const val API_VERSION = "2026-10-21"

        /** Output limit accepted by older Claude models (used only after a max_tokens rejection). */
        const val LEGACY_MAX_OUTPUT = 8_192

        fun normalizeBaseUrl(raw: String): String =
            raw.trim().trimEnd('/').removeSuffix("/messages").removeSuffix("/v1").trimEnd('/')

        fun buildMessagesBody(model: String, request: AiRequest): Map<String, Any?> {
            val body = linkedMapOf<String, Any?>(
                "model" to model,
                "max_tokens" to request.maxOutputTokens,
                "messages" to AiHttp.normalize(request.messages).map { m ->
                    mapOf("role" to if (m.role == AiRole.USER) "user" else "assistant", "content" to m.text)
                }
            )
            if (request.system.isNotBlank()) body["system"] = request.system
            return body
        }

        fun parseMessagesResponse(body: String): AiResponse {
            val root = Json.obj(Json.parse(body))
                ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Response is not JSON")
            val stopReason = Json.str(root["stop_reason"])
            // Only `text` blocks are the answer; `thinking` and other block types are skipped.
            val text = (Json.arr(root["content"]) ?: emptyList())
                .mapNotNull { Json.obj(it) }
                .filter { it["type"] == "text" }
                .mapNotNull { Json.str(it["text"]) }
                .joinToString("")
            if (stopReason == "refusal") {
                val explanation = Json.str(Json.obj(root["stop_details"])?.get("explanation"))
                throw AiException(AiErrorKind.CONTENT_BLOCKED, explanation?.take(200))
            }
            if (text.isBlank()) {
                if (stopReason == "max_tokens") throw AiException(AiErrorKind.TRUNCATED_RESPONSE)
                throw AiException(AiErrorKind.EMPTY_RESPONSE)
            }
            return AiResponse(text, truncated = stopReason == "max_tokens")
        }
    }
}
