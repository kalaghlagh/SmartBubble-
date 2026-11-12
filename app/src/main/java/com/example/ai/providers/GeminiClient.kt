package com.example.ai.providers

import okhttp3.HttpUrl

/**
 * Google Gemini API: POST {base}/v1beta/models/{model}:generateContent. The key is sent in the
 * `x-goog-api-key` header (never in the URL). Connection test: GET {base}/v1beta/models/{model}.
 */
class GeminiClient(
    baseUrl: String,
    private val apiKey: String,
    model: String
) : AiProviderClient {

    override val type: AiProviderType = AiProviderType.GEMINI

    private val model: String = normalizeModel(model)
    private val base: HttpUrl = AiHttp.parseBaseUrl(normalizeBaseUrl(baseUrl))
    private val headers = mapOf("x-goog-api-key" to apiKey, "Accept" to "application/json")
    private val streamHeaders = headers + ("Accept" to "text/event-stream")

    override suspend fun complete(request: AiRequest): AiResponse {
        val url = base.newBuilder().addPathSegments("v1beta/models").addPathSegment("$model:generateContent").build()
        val result = AiHttp.execute(AiHttp.jsonPost(url, headers, buildGenerateBody(request)), apiKey)
        if (result.code !in 200..299) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
        return parseGenerateResponse(result.body)
    }

    override suspend fun stream(request: AiRequest, onText: (String) -> Unit): AiResponse {
        val url = base.newBuilder().addPathSegments("v1beta/models").addPathSegment("$model:streamGenerateContent")
            .addQueryParameter("alt", "sse").build()
        val acc = StreamAccumulator(onText)
        val result = AiHttp.executeStream(AiHttp.jsonPost(url, streamHeaders, buildGenerateBody(request)), apiKey) { acc.accept(it) }
        if (result.code !in 200..299) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
        if (!result.streamed) return parseGenerateResponse(result.body).also { onText(it.text) }
        return acc.finish()
    }

    override suspend fun testConnection(): Long {
        val start = System.currentTimeMillis()
        val url = base.newBuilder().addPathSegments("v1beta/models").addPathSegment(model).build()
        val result = AiHttp.execute(AiHttp.get(url, headers), apiKey)
        if (result.code !in 200..299) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
        return System.currentTimeMillis() - start
    }

    /**
     * Collects a streamed answer (`streamGenerateContent?alt=sse`): every event is a partial
     * GenerateContentResponse. Thought summaries are skipped. Pure Kotlin.
     */
    class StreamAccumulator(private val onText: (String) -> Unit) {
        private val text = StringBuilder()
        private var finish: String? = null

        fun accept(data: String) {
            val root = Json.obj(Json.parse(data))
                ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Invalid stream event")
            root["error"]?.let { throw AiHttp.mapHttpError(500, data, null) }
            val blockReason = Json.str(Json.obj(root["promptFeedback"])?.get("blockReason"))
            if (blockReason != null) throw AiException(AiErrorKind.CONTENT_BLOCKED, blockReason)
            val candidate = Json.obj(Json.arr(root["candidates"])?.firstOrNull()) ?: return
            Json.str(candidate["finishReason"])?.let { finish = it }
            val piece = (Json.arr(Json.obj(candidate["content"])?.get("parts")) ?: emptyList())
                .mapNotNull { Json.obj(it) }
                .filter { it["thought"] != true }
                .mapNotNull { Json.str(it["text"]) }
                .joinToString("")
            if (piece.isNotEmpty()) {
                text.append(piece)
                onText(piece)
            }
        }

        fun finish(): AiResponse {
            if (text.isBlank()) {
                if (finish in BLOCK_REASONS) throw AiException(AiErrorKind.CONTENT_BLOCKED, finish)
                if (finish == "MAX_TOKENS") throw AiException(AiErrorKind.TRUNCATED_RESPONSE)
                throw AiException(AiErrorKind.EMPTY_RESPONSE)
            }
            return AiResponse(text.toString(), truncated = finish == "MAX_TOKENS")
        }
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String =
            raw.trim().trimEnd('/').removeSuffix("/v1beta").removeSuffix("/v1").trimEnd('/')

        fun normalizeModel(raw: String): String = raw.trim().removePrefix("models/")

        fun buildGenerateBody(request: AiRequest): Map<String, Any?> {
            val body = linkedMapOf<String, Any?>(
                "contents" to AiHttp.normalize(request.messages).map { m ->
                    mapOf(
                        "role" to if (m.role == AiRole.USER) "user" else "model",
                        "parts" to listOf(mapOf("text" to m.text))
                    )
                }
            )
            if (request.system.isNotBlank()) {
                body["systemInstruction"] = mapOf("parts" to listOf(mapOf("text" to request.system)))
            }
            if (request.expectJson) {
                body["generationConfig"] = mapOf("responseMimeType" to "application/json")
            }
            return body
        }

        internal val BLOCK_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")

        fun parseGenerateResponse(body: String): AiResponse {
            val root = Json.obj(Json.parse(body))
                ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Response is not JSON")
            val blockReason = Json.str(Json.obj(root["promptFeedback"])?.get("blockReason"))
            if (blockReason != null) throw AiException(AiErrorKind.CONTENT_BLOCKED, blockReason)
            val candidate = Json.obj(Json.arr(root["candidates"])?.firstOrNull())
                ?: throw AiException(AiErrorKind.EMPTY_RESPONSE, "No candidates returned")
            val finish = Json.str(candidate["finishReason"])
            val text = (Json.arr(Json.obj(candidate["content"])?.get("parts")) ?: emptyList())
                .mapNotNull { Json.obj(it) }
                .filter { it["thought"] != true } // skip thought summaries
                .mapNotNull { Json.str(it["text"]) }
                .joinToString("")
            if (text.isBlank()) {
                if (finish in BLOCK_REASONS) throw AiException(AiErrorKind.CONTENT_BLOCKED, finish)
                if (finish == "MAX_TOKENS") throw AiException(AiErrorKind.TRUNCATED_RESPONSE)
                throw AiException(AiErrorKind.EMPTY_RESPONSE)
            }
            return AiResponse(text, truncated = finish == "MAX_TOKENS")
        }
    }
}
