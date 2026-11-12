package com.example.ai.orchestrator

/**
 * Real processing stages of one AI operation, reported by [CentralAiOrchestrator] when the
 * stage actually starts. The UI shows them as localized progress text.
 */
enum class AiProgressStage {
    /** The request to the default provider is being sent; no answer has arrived yet. */
    CONNECTING,

    /** The provider has started answering; it is deciding how to handle the request. */
    PROCESSING,

    /** Content is being written: chat reply text is arriving, or the document content was requested. */
    GENERATING,

    /** The generated document content is being checked (and repaired once if needed). */
    VALIDATING,

    /** The document file is being rendered, checked and saved. */
    CREATING_DOCUMENT
}

/**
 * Receives progress of one orchestrator operation. Calls can arrive on background threads;
 * implementations must be thread-safe and must ignore calls for an operation that was cancelled.
 */
interface AiProgressListener {
    fun onStage(stage: AiProgressStage) {}

    /** The chat reply received so far (the whole text, not only the newest piece). */
    fun onPartialReply(text: String) {}

    companion object {
        val NONE = object : AiProgressListener {}
    }
}

/**
 * Extracts the chat reply from the planner's JSON answer while it is still being streamed,
 * so it can be shown as it arrives. The planner answers
 * `{"intent":"chat","reply":"..."}` or `{"intent":"document",...}`; only a "chat" answer
 * whose first key is "intent" is shown progressively. Anything else (a document plan, plain
 * text, an unexpected shape) is not shown until the complete answer has been parsed.
 *
 * Feed the streamed pieces in order with [append]. Pure Kotlin, linear in the input size.
 */
class PlannerReplyExtractor {
    private enum class Phase { SEEK_INTENT, SEEK_REPLY, IN_REPLY, DONE, NOT_CHAT }

    private val raw = StringBuilder()
    private val reply = StringBuilder()
    private var phase = Phase.SEEK_INTENT
    private var position = 0 // next raw index to decode while IN_REPLY

    /** True once the answer is known to be a chat reply (its text may still be empty). */
    val isChatReply: Boolean get() = phase == Phase.SEEK_REPLY || phase == Phase.IN_REPLY || phase == Phase.DONE

    /** The reply decoded so far, or null if there is none to show. */
    fun currentReply(): String? = if (isChatReply && reply.isNotEmpty()) reply.toString() else null

    /** True when there is reply text to show. */
    val hasReply: Boolean get() = isChatReply && reply.isNotEmpty()

    /** Adds the next streamed piece. Returns the reply decoded so far, or null if there is none to show. */
    fun append(piece: String): String? {
        feed(piece)
        return currentReply()
    }

    /** Adds the next streamed piece without building the reply string. */
    fun feed(piece: String) {
        raw.append(piece)
        when (phase) {
            Phase.SEEK_INTENT -> seekIntent()
            Phase.SEEK_REPLY -> seekReply()
            else -> Unit
        }
        if (phase == Phase.SEEK_REPLY) seekReply()
        if (phase == Phase.IN_REPLY) decode()
    }

    private fun seekIntent() {
        val match = INTENT.find(raw)
        if (match != null) {
            phase = if (match.groupValues[1] == "chat") Phase.SEEK_REPLY else Phase.NOT_CHAT
            position = match.range.last + 1
            return
        }
        val start = raw.trimStart()
        if (start.isNotEmpty() && start[0] != '{' && start[0] != '`') phase = Phase.NOT_CHAT
        else if (raw.length > SEEK_LIMIT) phase = Phase.NOT_CHAT
    }

    private fun seekReply() {
        // Only the short text between the intent and the reply key is searched (anchored).
        val match = REPLY.find(raw.substring(position))
        if (match != null) {
            phase = Phase.IN_REPLY
            position += match.range.last + 1
        } else if (raw.length - position > SEEK_LIMIT) {
            phase = Phase.NOT_CHAT
        }
    }

    /** Decodes JSON string characters up to the closing quote; an incomplete escape waits for more input. */
    private fun decode() {
        while (position < raw.length) {
            val c = raw[position]
            when {
                c == '"' -> {
                    phase = Phase.DONE
                    position++
                    return
                }
                c != '\\' -> {
                    reply.append(c)
                    position++
                }
                else -> {
                    if (position + 1 >= raw.length) return
                    when (val e = raw[position + 1]) {
                        'n' -> reply.append('\n')
                        't' -> reply.append('\t')
                        'r' -> reply.append('\r')
                        'b' -> reply.append('\b')
                        'f' -> reply.append('\u000C')
                        'u' -> {
                            if (position + 6 > raw.length) return
                            val code = raw.substring(position + 2, position + 6).toIntOrNull(16)
                            if (code == null) {
                                phase = Phase.NOT_CHAT
                                return
                            }
                            reply.append(code.toChar())
                            position += 6
                            continue
                        }
                        else -> reply.append(e) // \" \\ \/
                    }
                    position += 2
                }
            }
        }
    }

    private companion object {
        const val SEEK_LIMIT = 2_000
        val INTENT = Regex("""^\s*(?:```(?:json)?\s*)?\{\s*"intent"\s*:\s*"([A-Za-z]+)"""")
        val REPLY = Regex("""^\s*,\s*"reply"\s*:\s*"""")
    }
}

/**
 * Limits how often streamed text is handed to the UI (building the text and redrawing it for
 * every small piece is wasted work). The complete text always arrives with the final result.
 */
class StreamThrottle(private val intervalMs: Long = 80, private val clock: () -> Long = System::currentTimeMillis) {
    private var last = Long.MIN_VALUE

    fun ready(): Boolean {
        val now = clock()
        if (last != Long.MIN_VALUE && now - last < intervalMs) return false
        last = now
        return true
    }
}
