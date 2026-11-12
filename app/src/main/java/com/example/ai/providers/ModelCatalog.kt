package com.example.ai.providers

import java.util.concurrent.ConcurrentHashMap

/**
 * What a model can do, as reported by the provider's model list. `null` means the provider does
 * not say; the app then does not assume the capability exists (and does not hide the model).
 */
data class ModelCapabilities(
    val vision: Boolean? = null,
    val jsonOutput: Boolean? = null,
    val toolCalling: Boolean? = null,
    val reasoning: Boolean? = null
) {
    companion object {
        val UNKNOWN = ModelCapabilities()
    }
}

/** One model offered by a provider. [id] is exactly what the API expects in requests. */
data class ModelInfo(
    val id: String,
    val displayName: String? = null,
    val contextLength: Int? = null,
    val capabilities: ModelCapabilities = ModelCapabilities.UNKNOWN,
    /** Other ids the provider accepts for the same model (e.g. "-latest" names). */
    val aliases: List<String> = emptyList()
) {
    fun answersTo(modelId: String): Boolean = id == modelId || modelId in aliases
}

/**
 * Models fetched during this app session, per provider endpoint. The chat client reads it to
 * avoid options a model is known not to support (e.g. JSON mode); unknown models are treated as
 * "capability not known". Nothing here is persisted.
 */
object ModelCatalogCache {
    private val models = ConcurrentHashMap<String, ModelInfo>()

    private fun key(baseUrl: String, modelId: String) = "${baseUrl.trimEnd('/')}|$modelId"

    fun store(baseUrl: String, list: List<ModelInfo>) {
        list.forEach { info -> (listOf(info.id) + info.aliases).forEach { models[key(baseUrl, it)] = info } }
    }

    fun get(baseUrl: String, modelId: String): ModelInfo? = models[key(baseUrl, modelId)]

    fun clear() = models.clear()
}

/** Parsers for the model-list responses of each provider. Pure Kotlin, unit-tested. */
object ModelListParsers {

    private fun num(value: Any?): Int? = (value as? Number)?.toInt()

    private fun strings(value: Any?): List<String> = Json.arr(value)?.mapNotNull { it as? String }.orEmpty()

    private fun data(body: String, field: String = "data"): List<Map<String, Any?>> {
        val root = Json.obj(Json.parse(body)) ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Model list is not JSON")
        return Json.arr(root[field])?.mapNotNull { Json.obj(it) }
            ?: throw AiException(AiErrorKind.MALFORMED_RESPONSE, "Model list has no '$field'")
    }

    /** OpenAI-style `{"data":[{"id":...}]}` without capability information (OpenAI, DeepSeek, xAI /models). */
    fun plain(body: String): List<ModelInfo> =
        data(body).mapNotNull { Json.str(it["id"])?.let { id -> ModelInfo(id) } }

    /** DeepSeek: plain list; the "reasoner" model is the thinking model. */
    fun deepSeek(body: String): List<ModelInfo> = plain(body).map {
        if (it.id.contains("reasoner")) it.copy(capabilities = ModelCapabilities(reasoning = true)) else it
    }

    /**
     * Mistral `GET /v1/models`: `capabilities` object (completion_chat, function_calling, vision,
     * ...) and `max_context_length`. Only chat-completion models are returned.
     */
    fun mistral(body: String): List<ModelInfo> = data(body).mapNotNull { m ->
        val id = Json.str(m["id"]) ?: return@mapNotNull null
        val caps = Json.obj(m["capabilities"])
        if (caps != null && caps["completion_chat"] == false) return@mapNotNull null
        ModelInfo(
            id = id,
            displayName = Json.str(m["name"])?.takeIf { it != id },
            contextLength = num(m["max_context_length"]),
            capabilities = ModelCapabilities(
                vision = caps?.get("vision") as? Boolean,
                toolCalling = caps?.get("function_calling") as? Boolean,
                reasoning = caps?.get("reasoning") as? Boolean
            ),
            aliases = strings(m["aliases"])
        )
    }.distinctBy { it.id }

    /**
     * Groq `GET /openai/v1/models`: `active` and `context_window`. Inactive models and the
     * speech models served on the same list (Whisper transcription, text-to-speech) are left out
     * because they cannot answer chat requests.
     */
    fun groq(body: String): List<ModelInfo> = data(body).mapNotNull { m ->
        val id = Json.str(m["id"]) ?: return@mapNotNull null
        if (m["active"] == false) return@mapNotNull null
        val lower = id.lowercase()
        if ("whisper" in lower || "tts" in lower) return@mapNotNull null
        ModelInfo(id = id, contextLength = num(m["context_window"]))
    }

    /**
     * xAI `GET /v1/language-models`: `{"models":[{"id", "input_modalities", "output_modalities",
     * "aliases"}]}`. Only models that output text are returned; image input means vision.
     */
    fun xaiLanguageModels(body: String): List<ModelInfo> = data(body, "models").mapNotNull { m ->
        val id = Json.str(m["id"]) ?: return@mapNotNull null
        val output = strings(m["output_modalities"])
        if (output.isNotEmpty() && "text" !in output) return@mapNotNull null
        val input = strings(m["input_modalities"])
        ModelInfo(
            id = id,
            capabilities = ModelCapabilities(vision = if (input.isEmpty()) null else "image" in input),
            aliases = strings(m["aliases"])
        )
    }

    /**
     * OpenRouter `GET /api/v1/models`: per-model `supported_parameters`, `architecture` modalities
     * and `context_length`. Ids are OpenRouter's ("vendor/model"). Models that do not output
     * text are left out. A capability is true only when the model lists the parameter.
     */
    fun openRouter(body: String): List<ModelInfo> = data(body).mapNotNull { m ->
        val id = Json.str(m["id"]) ?: return@mapNotNull null
        val arch = Json.obj(m["architecture"])
        val output = strings(arch?.get("output_modalities"))
        if (output.isNotEmpty() && "text" !in output) return@mapNotNull null
        val input = strings(arch?.get("input_modalities"))
        val params = Json.arr(m["supported_parameters"])?.mapNotNull { it as? String }
        ModelInfo(
            id = id,
            displayName = Json.str(m["name"]),
            contextLength = num(m["context_length"]),
            capabilities = ModelCapabilities(
                vision = if (input.isEmpty()) null else "image" in input,
                jsonOutput = params?.let { "response_format" in it || "structured_outputs" in it },
                toolCalling = params?.let { "tools" in it },
                reasoning = params?.let { "reasoning" in it || "include_reasoning" in it }
            )
        )
    }
}
