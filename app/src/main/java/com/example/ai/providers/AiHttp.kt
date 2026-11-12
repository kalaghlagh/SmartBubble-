package com.example.ai.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Result of an HTTP exchange. For a plain exchange [body] is the full response body. For a
 * streaming exchange that the server answered with Server-Sent Events, [streamed] is true, the
 * events were already delivered to the caller and [body] is empty; an error response or a
 * server that ignored the stream request is returned with its full body and [streamed] false.
 */
data class HttpResult(val code: Int, val body: String, val retryAfterSeconds: Long?, val streamed: Boolean = false)

/**
 * Shared networking for all provider clients (the project's existing OkHttp stack; no logging
 * interceptor is installed, so keys and request contents are never logged).
 */
object AiHttp {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** LLM responses (especially documents) can take minutes; keep timeouts generous but bounded. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(330, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Same connection pool and dispatcher as [client] (newBuilder shares them), but a longer
     * overall call limit: a streamed answer keeps arriving in pieces, and the read timeout still
     * stops a stream that goes silent.
     */
    val streamingClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(15, TimeUnit.MINUTES).build()
    }

    fun parseBaseUrl(raw: String): HttpUrl {
        val url = raw.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw AiException(AiErrorKind.INVALID_CONFIGURATION, "Invalid base URL")
        if (!url.isHttps) throw AiException(AiErrorKind.INVALID_CONFIGURATION, "Base URL must use https")
        return url
    }

    fun jsonPost(url: HttpUrl, headers: Map<String, String>, body: Map<String, Any?>): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(Json.write(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

    fun get(url: HttpUrl, headers: Map<String, String>): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()

    /**
     * Executes [request] with coroutine cancellation (the OkHttp call is cancelled when the
     * coroutine is) and one bounded retry for 429 / transient 5xx responses.
     */
    suspend fun execute(request: Request, secret: String?): HttpResult {
        var attempt = 0
        while (true) {
            val result = executeOnce(request, secret)
            val retryable = result.code == 429 || result.code in setOf(500, 502, 503, 504, 529)
            if (!retryable || attempt >= 1) return result
            val wait = (result.retryAfterSeconds ?: 2L)
            if (wait > 20L) return result // don't block the user for long; report rate limit instead
            delay(wait * 1000L)
            attempt++
        }
    }

    /**
     * Executes a streaming [request]. Each Server-Sent Event's data is passed to [onEvent] (on an
     * OkHttp thread, in order) as it arrives. Cancelling the coroutine cancels the HTTP call and
     * closes the stream; no event is delivered after that. Same single retry as [execute] for
     * 429 / transient 5xx answers (only possible before any event was delivered).
     */
    suspend fun executeStream(request: Request, secret: String?, onEvent: (String) -> Unit): HttpResult {
        var attempt = 0
        while (true) {
            val result = streamOnce(request, secret, onEvent)
            val retryable = !result.streamed && (result.code == 429 || result.code in setOf(500, 502, 503, 504, 529))
            if (!retryable || attempt >= 1) return result
            val wait = (result.retryAfterSeconds ?: 2L)
            if (wait > 20L) return result
            delay(wait * 1000L)
            attempt++
        }
    }

    private suspend fun streamOnce(request: Request, secret: String?, onEvent: (String) -> Unit): HttpResult =
        suspendCancellableCoroutine { cont ->
            val call = streamingClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(mapIoException(e, secret))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            val retryAfter = r.header("retry-after")?.trim()?.toLongOrNull()
                            val isEventStream = r.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)
                            if (!r.isSuccessful || !isEventStream) {
                                val body = r.body?.string().orEmpty()
                                if (cont.isActive) cont.resume(HttpResult(r.code, body, retryAfter, streamed = false))
                                return
                            }
                            val source = r.body?.source()
                                ?: throw AiException(AiErrorKind.EMPTY_RESPONSE, "Empty stream")
                            val parser = SseParser { data -> if (cont.isActive) onEvent(data) }
                            while (cont.isActive) {
                                val line = source.readUtf8Line() ?: break
                                parser.feedLine(line)
                            }
                            parser.finish()
                            if (cont.isActive) cont.resume(HttpResult(r.code, "", retryAfter, streamed = true))
                        }
                    } catch (e: AiException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(mapIoException(e, secret))
                    } catch (e: RuntimeException) {
                        if (cont.isActive) {
                            cont.resumeWithException(AiException(AiErrorKind.MALFORMED_RESPONSE, Redactor.redact(e.message, secret), cause = e))
                        }
                    }
                }
            })
        }

    private suspend fun executeOnce(request: Request, secret: String?): HttpResult =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(mapIoException(e, secret))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use { it.body?.string().orEmpty() }
                        val retryAfter = response.header("retry-after")?.trim()?.toLongOrNull()
                        if (cont.isActive) cont.resume(HttpResult(response.code, body, retryAfter))
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(mapIoException(e, secret))
                    }
                }
            })
        }

    fun mapIoException(e: IOException, secret: String?): AiException = when (e) {
        is UnknownHostException, is ConnectException ->
            AiException(AiErrorKind.NETWORK_UNAVAILABLE, Redactor.redact(e.javaClass.simpleName, secret))
        is SocketTimeoutException ->
            AiException(AiErrorKind.TIMEOUT, null)
        is InterruptedIOException ->
            // OkHttp reports callTimeout as InterruptedIOException("timeout"); cancellation also lands here.
            AiException(AiErrorKind.TIMEOUT, null)
        else -> AiException(AiErrorKind.NETWORK_UNAVAILABLE, Redactor.redact(e.message, secret))
    }

    /** Extracts the provider's error message from common error envelopes. */
    fun providerErrorMessage(body: String): String? {
        val root = Json.obj(Json.parse(body)) ?: return null
        val error = root["error"]
        return when (error) {
            is Map<*, *> -> error["message"] as? String
            is String -> error
            else -> root["message"] as? String
        }
    }

    /** Provider-neutral mapping of an unsuccessful HTTP response. */
    fun mapHttpError(code: Int, body: String, secret: String?): AiException {
        val message = providerErrorMessage(body)
        val detail = Redactor.redact(message, secret).ifBlank { null }
        val lower = (message ?: "").lowercase()
        val kind = when {
            code == 401 -> AiErrorKind.INVALID_API_KEY
            // Gemini reports a bad key as 400 INVALID_ARGUMENT / API_KEY_INVALID.
            code == 400 && (lower.contains("api key not valid") || body.contains("API_KEY_INVALID")) ->
                AiErrorKind.INVALID_API_KEY
            code == 402 -> AiErrorKind.QUOTA_OR_BILLING
            code == 403 -> AiErrorKind.PERMISSION_DENIED
            code == 404 -> AiErrorKind.MODEL_NOT_FOUND
            code == 408 -> AiErrorKind.TIMEOUT
            code == 413 -> AiErrorKind.REQUEST_TOO_LARGE
            code == 429 && (lower.contains("quota") || body.contains("insufficient_quota")) ->
                AiErrorKind.QUOTA_OR_BILLING
            code == 429 -> AiErrorKind.RATE_LIMITED
            code == 400 && (lower.contains("model") && (lower.contains("not found") ||
                lower.contains("does not exist") || lower.contains("not supported") || lower.contains("invalid model"))) ->
                AiErrorKind.MODEL_NOT_FOUND
            code == 400 || code == 422 -> AiErrorKind.BAD_REQUEST
            code >= 500 -> AiErrorKind.PROVIDER_UNAVAILABLE
            else -> AiErrorKind.UNKNOWN
        }
        return AiException(kind, detail, code)
    }

    /**
     * Normalizes a conversation for providers that require it to start with a user turn and
     * alternate roles: drops blank messages and leading assistant turns, merges consecutive
     * messages of the same role. Throws when no user message remains.
     */
    fun normalize(messages: List<AiMessage>): List<AiMessage> {
        val out = mutableListOf<AiMessage>()
        for (m in messages) {
            if (m.text.isBlank()) continue
            if (out.isEmpty() && m.role == AiRole.ASSISTANT) continue
            val last = out.lastOrNull()
            if (last != null && last.role == m.role) {
                out[out.lastIndex] = last.copy(text = last.text + "\n\n" + m.text)
            } else {
                out += m
            }
        }
        if (out.none { it.role == AiRole.USER }) {
            throw AiException(AiErrorKind.BAD_REQUEST, "Empty conversation")
        }
        return out
    }
}

/**
 * Minimal Server-Sent Events line parser: collects the `data:` lines of one event and emits the
 * joined data when the event ends (blank line). `event:`, `id:`, `retry:` and comment lines are
 * ignored (the providers repeat the event type inside the JSON data). Pure Kotlin.
 */
class SseParser(private val onData: (String) -> Unit) {
    private val data = StringBuilder()
    private var hasData = false

    fun feedLine(rawLine: String) {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(":")) return
        if (line == "data" || line.startsWith("data:")) {
            val value = line.removePrefix("data").removePrefix(":").removePrefix(" ")
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
    }

    /** Emits a last event that was not followed by a blank line. */
    fun finish() = dispatch()

    private fun dispatch() {
        if (!hasData) return
        val value = data.toString()
        data.setLength(0)
        hasData = false
        onData(value)
    }
}
