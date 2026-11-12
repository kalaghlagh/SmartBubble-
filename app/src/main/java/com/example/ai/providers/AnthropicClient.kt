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
    private val streamHeaders = headers + ("Accept" to "text/event-stream")

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

    override suspend fun stream(request: AiRequest, onText: (String) -> Unit): AiResponse {
        val url = base.newBuilder().addPathSegments("v1/messages").build()
        var acc = StreamAccumulator(onText)
        var result = AiHttp.executeStream(
            AiHttp.jsonPost(url, streamHeaders, buildMessagesBody(model, request) + ("stream" to true)), apiKey
        ) { acc.accept(it) }
        if (!result.streamed && result.code == 400 && request.maxOutputTokens > LEGACY_MAX_OUTPUT &&
            AiHttp.providerErrorMessage(result.body).orEmpty().contains("max_tokens")
        ) {
            val smaller = request.copy(maxOutputTokens = LEGACY_MAX_OUTPUT)
            acc = StreamAccumulator(onText)
            result = AiHttp.executeStream(
                AiHttp.jsonPost(url, streamHeaders, buildMessagesBody(model, smaller) + ("stream" to true)), apiKey
            ) { acc.accept(it) }
        }
        if (result.code !in 200..299) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
        if (!result.streamed) return parseMessagesResponse(result.body).also { onText(it.text) }
        return acc.finish()
    }

    override suspend fun testConnection(): Long {
        val start = System.currentTimeMillis()
        val url = base.newBuilder().addPathSegments("v1/models").addPathSegment(model).build()
        val result = AiHttp.execute(AiHttp.get(url, headers), apiKey)
        if (result.code !in 200..299) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
        return System.currentTimeMillis() - start
    }

    /**
     * Collects a streamed Messages answer: `content_block_delta` events carry text (`text_delta`;
     * thinking deltas are skipped), `message_delta` carries the stop reason, `error` events end
     * the stream with a provider error. Pure Kotlin.
     */
    class StreamAccumulator(private val onText: (String) -> Unit) {
        private val text = StringBuilder()
        private var stopReason: String? = null
        private var refusalExplanation: String? = null

        fun accept(data: String) {
            val root = Json.obj(Json.parse(data))
                ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Invalid stream event")
            when (Json.str(root["type"])) {
                "content_block_delta" -> {
                    val delta = Json.obj(root["delta"]) ?: return
                    if (delta["type"] == "text_delta") {
                        val piece = Json.str(delta["text"]).orEmpty()
                        if (piece.isNotEmpty()) {
                            text.append(piece)
                            onText(piece)
                        }
                    }
                }
                "message_delta" -> {
                    val delta = Json.obj(root["delta"]) ?: return
                    Json.str(delta["stop_reason"])?.let { stopReason = it }
                    Json.str(Json.obj(delta["stop_details"])?.get("explanation"))?.let { refusalExplanation = it }
                }
                "error" -> {
                    val error = Json.obj(root["error"])
                    val message = Json.str(error?.get("message"))?.take(200)
                    val kind = when (Json.str(error?.get("type"))) {
                        "overloaded_error", "api_error" -> AiErrorKind.PROVIDER_UNAVAILABLE
                        "rate_limit_error" -> AiErrorKind.RATE_LIMITED
                        "invalid_request_error" -> AiErrorKind.BAD_REQUEST
                        "authentication_error" -> AiErrorKind.INVALID_API_KEY
                        "permission_error" -> AiErrorKind.PERMISSION_DENIED
                        "request_too_large" -> AiErrorKind.REQUEST_TOO_LARGE
                        else -> AiErrorKind.UNKNOWN
                    }
                    throw AiException(kind, message)
                }
            }
        }

        fun finish(): AiResponse {
            if (stopReason == "refusal") throw AiException(AiErrorKind.CONTENT_BLOCKED, refusalExplanation?.take(200))
            if (text.isBlank()) {
                if (stopReason == "max_tokens") throw AiException(AiErrorKind.TRUNCATED_RESPONSE)
                throw AiException(AiErrorKind.EMPTY_RESPONSE)
            }
            return AiResponse(text.toString(), truncated = stopReason == "max_tokens")
        }
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
