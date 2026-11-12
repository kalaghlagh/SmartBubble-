package com.example.ai.providers

import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenAI Chat Completions API (POST {base}/chat/completions, Bearer auth, SSE streaming) and the
 * providers that implement the same API: DeepSeek, Mistral AI, Groq, xAI, OpenRouter and generic
 * OpenAI-compatible endpoints. What differs between them (JSON mode, how the connection is
 * tested, where and how models are listed) is described by a [ChatDialect]; networking,
 * streaming, cancellation and error mapping are shared ([AiHttp]).
 */
open class OpenAiClient(
    final override val type: AiProviderType,
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val dialect: ChatDialect = ChatDialect.forType(type)
) : AiProviderClient {

    private val base: HttpUrl = AiHttp.parseBaseUrl(normalizeBaseUrl(baseUrl))
    private val baseKey: String = base.toString().trimEnd('/')
    private val headers = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json")
    private val streamHeaders = headers + ("Accept" to "text/event-stream")

    private val chatUrl: HttpUrl get() = base.newBuilder().addPathSegments("chat/completions").build()
    private val modelKey: String get() = "$baseKey|$model"

    /**
     * JSON mode is requested only when the provider supports it, the model list does not say the
     * model lacks it, and the model has not rejected it before. The prompt asks for JSON anyway,
     * so leaving it out never changes provider or model.
     */
    internal fun useJsonMode(request: AiRequest): Boolean {
        if (!request.expectJson || dialect.jsonMode == JsonModePolicy.NEVER) return false
        if (modelKey in jsonModeRejected) return false
        return ModelCatalogCache.get(baseKey, model)?.capabilities?.jsonOutput != false
    }

    /** True when the model rejected JSON mode; remembered so the next requests skip the failing call. */
    private fun rejectedJsonMode(jsonMode: Boolean, result: HttpResult): Boolean {
        val message = AiHttp.providerErrorMessage(result.body).orEmpty()
        val rejected = jsonMode && !result.streamed && result.code in setOf(400, 422) &&
            (message.contains("response_format") || message.contains("json", ignoreCase = true))
        if (rejected) jsonModeRejected += modelKey
        return rejected
    }

    override suspend fun complete(request: AiRequest): AiResponse {
        val jsonMode = useJsonMode(request)
        var result = AiHttp.execute(AiHttp.jsonPost(chatUrl, headers, buildChatBody(model, request, jsonMode)), apiKey)
        if (rejectedJsonMode(jsonMode, result)) {
            // The JSON is then requested via instructions only (same provider, same model).
            result = AiHttp.execute(AiHttp.jsonPost(chatUrl, headers, buildChatBody(model, request, useJsonMode = false)), apiKey)
        }
        if (result.code !in 200..299) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
        return parseChatResponse(result.body)
    }

    override suspend fun stream(request: AiRequest, onText: (String) -> Unit): AiResponse {
        val jsonMode = useJsonMode(request)
        var acc = StreamAccumulator(onText)
        var result = AiHttp.executeStream(
            AiHttp.jsonPost(chatUrl, streamHeaders, buildChatBody(model, request, jsonMode) + ("stream" to true)), apiKey
        ) { acc.accept(it) }
        if (rejectedJsonMode(jsonMode, result)) {
            acc = StreamAccumulator(onText)
            result = AiHttp.executeStream(
                AiHttp.jsonPost(chatUrl, streamHeaders, buildChatBody(model, request, useJsonMode = false) + ("stream" to true)), apiKey
            ) { acc.accept(it) }
        }
        if (result.code !in 200..299) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
        if (!result.streamed) {
            // A server that ignores "stream" answers with one normal response.
            return parseChatResponse(result.body).also { onText(it.text) }
        }
        return acc.finish()
    }

    override suspend fun listModels(): List<ModelInfo>? {
        if (dialect.modelSources.isEmpty()) return null
        var last: HttpResult? = null
        for (source in dialect.modelSources) {
            val url = base.newBuilder().addPathSegments(source.path).build()
            val result = AiHttp.execute(AiHttp.get(url, headers), apiKey)
            if (result.code in 200..299) {
                val models = source.parse(result.body).sortedBy { it.id }
                ModelCatalogCache.store(baseKey, models)
                return models
            }
            // Only "this list does not exist here" moves on to the next source.
            if (result.code !in setOf(404, 405)) throw AiHttp.mapHttpError(result.code, result.body, apiKey)
            last = result
        }
        throw AiHttp.mapHttpError(last!!.code, last.body, apiKey)
    }

    override suspend fun testConnection(): Long {
        val start = System.currentTimeMillis()
        when (dialect.connectionTest) {
            ConnectionTest.RETRIEVE_MODEL -> {
                // Retrieve the configured model: authenticates the key and verifies the model exists.
                val modelUrl = base.newBuilder().addPathSegment("models").addPathSegment(model).build()
                val result = AiHttp.execute(AiHttp.get(modelUrl, headers), apiKey)
                if (result.code in 200..299) return System.currentTimeMillis() - start
                if (type == AiProviderType.OPENAI_COMPATIBLE && result.code in setOf(404, 405)) {
                    // Many compatible gateways don't implement GET /models/{id}: send a minimal completion.
                    val ping = AiRequest(system = "", messages = listOf(AiMessage(AiRole.USER, "ping")), maxOutputTokens = 1)
                    val body = buildChatBody(model, ping, useJsonMode = false) + ("max_tokens" to 1)
                    val r2 = AiHttp.execute(AiHttp.jsonPost(chatUrl, headers, body), apiKey)
                    if (r2.code in 200..299) return System.currentTimeMillis() - start
                    throw AiHttp.mapHttpError(r2.code, r2.body, apiKey)
                }
                throw AiHttp.mapHttpError(result.code, result.body, apiKey)
            }
            ConnectionTest.LIST_MODELS -> {
                // The model list is public on some providers: check the key on its own first.
                for (path in dialect.authCheckPaths) {
                    val url = base.newBuilder().addPathSegments(path).build()
                    val result = AiHttp.execute(AiHttp.get(url, headers), apiKey)
                    if (result.code in 200..299) break
                    if (result.code == 404 && path != dialect.authCheckPaths.last()) continue
                    throw AiHttp.mapHttpError(result.code, result.body, apiKey)
                }
                val models = listModels().orEmpty()
                if (!modelIsListed(model, models, dialect.allowsVariantSuffix)) {
                    throw AiException(AiErrorKind.MODEL_NOT_FOUND, "\"$model\" is not in this provider's model list")
                }
                return System.currentTimeMillis() - start
            }
        }
    }

    /** Collects a streamed Chat Completions answer (`data: {chunk}` events, then `data: [DONE]`). Pure Kotlin. */
    class StreamAccumulator(private val onText: (String) -> Unit) {
        private val text = StringBuilder()
        private val refusal = StringBuilder()
        private var finish: String? = null

        fun accept(data: String) {
            if (data.trim() == "[DONE]") return
            val root = Json.obj(Json.parse(data))
                ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Invalid stream event")
            root["error"]?.let { throw streamError(it, data) }
            // Chunks without choices (e.g. usage) carry no text.
            val choice = Json.obj(Json.arr(root["choices"])?.firstOrNull()) ?: return
            Json.str(choice["finish_reason"])?.let { finish = it }
            val delta = Json.obj(choice["delta"]) ?: return
            Json.str(delta["refusal"])?.let { refusal.append(it) }
            // Only "content" is the answer: reasoning fields (reasoning_content, reasoning,
            // thinking parts) are never shown or stored as the reply.
            val piece = contentText(delta["content"])
            if (piece.isNotEmpty()) {
                text.append(piece)
                onText(piece)
            }
        }

        fun finish(): AiResponse {
            if (finish == "error") throw AiException(AiErrorKind.PROVIDER_UNAVAILABLE, "The provider ended the stream with an error")
            if (text.isBlank()) {
                if (refusal.isNotBlank() || finish == "content_filter") {
                    throw AiException(AiErrorKind.CONTENT_BLOCKED, refusal.toString().take(200).ifBlank { null })
                }
                // Reasoning models can spend the whole output budget before answering.
                if (finish == "length") throw AiException(AiErrorKind.TRUNCATED_RESPONSE)
                throw AiException(AiErrorKind.EMPTY_RESPONSE)
            }
            return AiResponse(text.toString(), truncated = finish == "length")
        }
    }

    companion object {
        /** "base|model" of models that rejected JSON mode during this app session. */
        private val jsonModeRejected: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun normalizeBaseUrl(raw: String): String =
            raw.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')

        /**
         * True when [model] is offered: exact id or alias. With [allowsVariantSuffix]
         * (OpenRouter), "vendor/model:variant" also matches a listed "vendor/model".
         */
        fun modelIsListed(model: String, models: List<ModelInfo>, allowsVariantSuffix: Boolean): Boolean {
            if (models.any { it.answersTo(model) }) return true
            if (allowsVariantSuffix && ':' in model) {
                val baseId = model.substringBefore(':')
                return models.any { it.answersTo(baseId) }
            }
            return false
        }

        /** Error object inside a stream (OpenRouter, Groq, ...): uses its numeric code when present. */
        internal fun streamError(error: Any?, data: String): AiException {
            val code = (Json.obj(error)?.get("code") as? Number)?.toInt()?.takeIf { it in 400..599 } ?: 500
            return AiHttp.mapHttpError(code, data, null)
        }

        /** Text of a message `content`: a string, or the "text" parts of a part list (thinking parts skipped). */
        internal fun contentText(content: Any?): String = when (content) {
            is String -> content
            is List<*> -> content.mapNotNull { Json.obj(it) }
                .filter { part -> part["type"] == null || part["type"] == "text" }
                .mapNotNull { Json.str(it["text"]) }
                .joinToString("")
            else -> ""
        }

        /**
         * Builds the request body. No sampling parameters or token limits are sent: several
         * models (OpenAI reasoning models, DeepSeek's reasoner, xAI reasoning models) reject or
         * ignore them, and the provider's default is used.
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
            root["error"]?.let { if (root["choices"] == null) throw streamError(it, body) }
            val choice = Json.obj(Json.arr(root["choices"])?.firstOrNull())
                ?: throw AiException(AiErrorKind.EMPTY_RESPONSE, "No choices returned")
            val message = Json.obj(choice["message"])
                ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Missing message")
            val finish = Json.str(choice["finish_reason"])
            val refusal = Json.str(message["refusal"])
            val text = contentText(message["content"])
            if (text.isBlank()) {
                if (!refusal.isNullOrBlank() || finish == "content_filter") {
                    throw AiException(AiErrorKind.CONTENT_BLOCKED, refusal?.take(200))
                }
                if (finish == "length") throw AiException(AiErrorKind.TRUNCATED_RESPONSE)
                throw AiException(AiErrorKind.EMPTY_RESPONSE)
            }
            return AiResponse(text, truncated = finish == "length")
        }
    }
}

/** Dedicated clients: the OpenAI-compatible protocol with each provider's own dialect. */
class DeepSeekClient(baseUrl: String, apiKey: String, model: String) :
    OpenAiClient(AiProviderType.DEEPSEEK, baseUrl, apiKey, model)

class MistralClient(baseUrl: String, apiKey: String, model: String) :
    OpenAiClient(AiProviderType.MISTRAL, baseUrl, apiKey, model)

class GroqClient(baseUrl: String, apiKey: String, model: String) :
    OpenAiClient(AiProviderType.GROQ, baseUrl, apiKey, model)

class XaiClient(baseUrl: String, apiKey: String, model: String) :
    OpenAiClient(AiProviderType.XAI, baseUrl, apiKey, model)

class OpenRouterClient(baseUrl: String, apiKey: String, model: String) :
    OpenAiClient(AiProviderType.OPENROUTER, baseUrl, apiKey, model)
