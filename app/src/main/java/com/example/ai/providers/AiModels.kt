package com.example.ai.providers

/**
 * Provider types supported by the Central AI Orchestrator. [storageKey] is the value stored in
 * ProviderEntity.providerType (unchanged from earlier versions so existing rows keep working).
 */
enum class AiProviderType(val storageKey: String, val defaultBaseUrl: String) {
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com"),
    OPENAI_COMPATIBLE("OpenAI-compatible", "");

    companion object {
        /** Maps stored values (including legacy "Custom") to a type; unknown values are OpenAI-compatible. */
        fun fromStorageKey(value: String): AiProviderType = when (value.trim()) {
            OPENAI.storageKey -> OPENAI
            ANTHROPIC.storageKey, "Anthropic", "Claude" -> ANTHROPIC
            GEMINI.storageKey, "Gemini" -> GEMINI
            else -> OPENAI_COMPATIBLE
        }
    }
}

enum class AiRole { USER, ASSISTANT }

data class AiMessage(val role: AiRole, val text: String)

/**
 * A provider-neutral completion request.
 * @property system system instructions
 * @property messages conversation, oldest first (normalized by the client: starts with USER,
 *   alternating roles)
 * @property expectJson ask the provider for a JSON object response where it supports that
 * @property maxOutputTokens upper bound for generated tokens (required by some providers)
 */
data class AiRequest(
    val system: String,
    val messages: List<AiMessage>,
    val expectJson: Boolean = false,
    val maxOutputTokens: Int = 16_000
)

data class AiResponse(val text: String, val truncated: Boolean = false)

/** Error categories, mapped to localized messages in the UI. */
enum class AiErrorKind {
    NO_DEFAULT_PROVIDER,
    MISSING_API_KEY,
    CREDENTIAL_UNAVAILABLE,
    INVALID_API_KEY,
    PERMISSION_DENIED,
    MODEL_NOT_FOUND,
    RATE_LIMITED,
    QUOTA_OR_BILLING,
    BAD_REQUEST,
    REQUEST_TOO_LARGE,
    PROVIDER_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    EMPTY_RESPONSE,
    MALFORMED_RESPONSE,
    TRUNCATED_RESPONSE,
    CONTENT_BLOCKED,
    INVALID_CONFIGURATION,
    UNKNOWN
}

/**
 * A provider failure. [detail] is a short, already-redacted diagnostic (never contains the API
 * key or request contents) and may be shown to the user next to the localized message.
 */
class AiException(
    val kind: AiErrorKind,
    val detail: String? = null,
    val httpCode: Int? = null,
    cause: Throwable? = null
) : Exception("${kind.name}${httpCode?.let { " (HTTP $it)" } ?: ""}${detail?.let { ": $it" } ?: ""}", cause)

/** Removes secrets from text that might be shown or logged. */
object Redactor {
    fun redact(text: String?, vararg secrets: String?): String {
        if (text == null) return ""
        var out: String = text
        secrets.filterNotNull().filter { it.length >= 4 }.forEach { out = out.replace(it, "***") }
        // Generic key-looking tokens and key query parameters.
        out = out.replace(Regex("""(?i)(key=)[^&\s"]+"""), "$1***")
        out = out.replace(Regex("""\b(sk-[A-Za-z0-9_\-]{8,}|AIza[0-9A-Za-z_\-]{20,})"""), "***")
        return out.take(300)
    }
}
