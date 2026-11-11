package com.example.ai.providers

import okhttp3.HttpUrl

/**
 * OpenAI Chat Completions API (POST {base}/chat/completions, Bearer auth). Also used for
 * OpenAI-compatible endpoints (e.g. DeepSeek, OpenRouter, Azure-style gateways) that implement
 * the same request/response shape.
 */
class OpenAiClient(
    override val type: AiProviderType,
    baseUrl: String,
    private val apiKey: String,
    private val model: String
) : AiProviderClient {

    private val base: HttpUrl = AiHttp.parseBaseUrl(normalizeBaseUrl(baseUrl))
    private val headers = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json")

    override suspend fun complete(request: AiRequest): AiResponse {
        val url = base.newBuilder().addPathSegments("chat/completions").build()
        val jsonMode = request.expectJson && type == AiProviderType.OPENAI
        var result = AiHttp.execute(AiHttp.jsonPost(url, headers, buildChatBody(model, request, jsonMode)), apiKey)
        if (jsonMode && result.code == 400 &&
            AiHttp.providerErrorMessage(result.body).orEmpty().contains("response_format")
        ) {
            // Some models don't support JSON mode; the JSON is then requested via instructions only.
            result = AiHttp.execute(AiHttp.jsonPost(url, headers, buildChatBody(model, request, useJsonMode = false)), apiKey)
        }
        if (result.code !in 200..299) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
        return parseChatResponse(result.body)
    }

    override suspend fun testConnection(): Long {
        val start = System.currentTimeMillis()
        // Retrieve the configured model: authenticates the key and verifies the model exists.
        val modelUrl = base.newBuilder().addPathSegment("models").addPathSegment(model).build()
        val result = AiHttp.execute(AiHttp.get(modelUrl, headers), apiKey)
        if (result.code in 200..299) return System.currentTimeMillis() - start
        if (type == AiProviderType.OPENAI_COMPATIBLE && result.code in setOf(404, 405)) {
            // Many compatible gateways don't implement GET /models/{id}: send a minimal completion.
            val ping = AiRequest(system = "", messages = listOf(AiMessage(AiRole.USER, "ping")), maxOutputTokens = 1)
            val url = base.newBuilder().addPathSegments("chat/completions").build()
            val body = buildChatBody(model, ping, useJsonMode = false) + ("max_tokens" to 1)
            val r2 = AiHttp.execute(AiHttp.jsonPost(url, headers, body), apiKey)
            if (r2.code in 200..299) return System.currentTimeMillis() - start
            throw AiHttp.mapHttpError(r2.code, r2.body, apiKey)
        }
        throw AiHttp.mapHttpError(result.code, result.body, apiKey)
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String =
            raw.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')

        /**
         * Builds the request body. No sampling parameters or token limits are sent: newer OpenAI
         * models reject `max_tokens`/non-default `temperature`, and the model default is used.
         */
        fun buildChatBody(model: String, request: AiRequest, useJsonMode: Boolean): Map<String, Any?> {
            val messages = mutableListOf<Map<String, Any?>>()
            if (request.system.isNotBlank()) messages += mapOf("role" to "system", "content" to request.system)
            AiHttp.normalize(request.messages).forEach { m ->
                messages += mapOf(
                    "role" to if (m.role == AiRole.USER) "user" else "assistant",
                    "content" to m.text
                )
            }
            val body = linkedMapOf<String, Any?>("model" to model, "messages" to messages)
            if (useJsonMode) body["response_format"] = mapOf("type" to "json_object")
            return body
        }

        fun parseChatResponse(body: String): AiResponse {
            val root = Json.obj(Json.parse(body))
                ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Response is not JSON")
            val choice = Json.obj(Json.arr(root["choices"])?.firstOrNull())
                ?: throw AiException(AiErrorKind.EMPTY_RESPONSE, "No choices returned")
            val message = Json.obj(choice["message"])
                ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Missing message")
            val finish = Json.str(choice["finish_reason"])
            val refusal = Json.str(message["refusal"])
            val text = when (val content = message["content"]) {
                is String -> content
                is List<*> -> content.mapNotNull { Json.obj(it)?.let { part -> Json.str(part["text"]) } }.joinToString("")
                else -> ""
            }
            if (text.isBlank()) {
                if (!refusal.isNullOrBlank() || finish == "content_filter") {
                    throw AiException(AiErrorKind.CONTENT_BLOCKED, refusal?.take(200))
                }
                throw AiException(AiErrorKind.EMPTY_RESPONSE)
            }
            return AiResponse(text, truncated = finish == "length")
        }
    }
}
