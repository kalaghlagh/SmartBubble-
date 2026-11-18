package com.example.agent.routing

import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import com.example.ai.providers.AiProviderClient
import com.example.ai.providers.AiRequest
import com.example.ai.providers.AiResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Sequential AI provider routing for the Agent.
 *
 * The user arranges the configured models in a fixed priority order (Settings > Agent). A request
 * goes to the first enabled, compatible model; when it fails with a failure that another provider
 * could avoid, the next model in the list is tried, strictly in order. After the last model the
 * list starts again from the first one, up to [RoutingPolicy.maxCycles] passes (default 2), with a
 * bounded pause between passes. Nothing is random and nothing retries forever.
 *
 * Pure Kotlin (no Android types): every rule is unit-tested with fake clients.
 */

/** What a request needs from a model. */
enum class Capability { TEXT, STRUCTURED_OUTPUT, IMAGE_INPUT, LONG_CONTEXT, TOOL_CALLING }

/**
 * What a model is known to support. A null value means unknown: the provider did not report it,
 * and the model is tried (a real "unsupported" answer then moves on to the next model).
 */
data class ModelSupport(
    val structuredOutput: Boolean? = null,
    val imageInput: Boolean? = null,
    val toolCalling: Boolean? = null,
    /** Context window in tokens, when the provider reports it. */
    val contextTokens: Int? = null
) {
    companion object {
        val UNKNOWN = ModelSupport()
    }
}

/**
 * One entry of the user's priority list. [id] is the provider id; credentials are never part of a
 * candidate: the client is created per attempt by the router's client factory.
 */
data class ProviderCandidate(
    val id: String,
    val name: String,
    val model: String,
    val support: ModelSupport = ModelSupport.UNKNOWN,
    /** Stored provider type (ProviderEntity.providerType); informational only. */
    val providerType: String = ""
)

/** Routing settings. [maxCycles] passes over the list; never unbounded. */
data class RoutingPolicy(
    val maxCycles: Int = DEFAULT_CYCLES,
    /** Pause before another pass over the list (ms); a provider's Retry-After can lengthen it. */
    val cycleBackoffMs: Long = 2_000,
    /** Upper bound for the pause between passes, whatever a provider asks. */
    val maxBackoffMs: Long = 30_000
) {
    init {
        require(maxCycles in 1..MAX_CYCLES) { "maxCycles must be in 1..$MAX_CYCLES" }
    }

    companion object {
        const val DEFAULT_CYCLES = 2
        const val MAX_CYCLES = 3
    }
}

/** What the router does after a failure of one model. */
enum class FailoverDecision {
    /** Transient or provider-side failure: try the next model; this model stays eligible in later passes. */
    NEXT,

    /**
     * The failure belongs to this model's configuration or account (bad key, no quota, model not
     * found, request too large for it): try the next model and do not use this one again in this task.
     */
    NEXT_AND_EXCLUDE,

    /** The request itself is the problem (or the user stopped it): another model would not help. */
    STOP
}

/**
 * The deliberate policy for each error category.
 *
 * - Connection problems (network, DNS, timeout), 5xx, rate limits, empty or malformed answers:
 *   provider-side or transient, so the next model is tried. AiHttp has already waited once for a
 *   rate limit (honouring Retry-After); the pause between passes honours it again, bounded.
 * - Invalid or missing key, permission, quota/billing, unknown model, configuration problems and
 *   context-window overflow: recorded and that model is not tried again in this task (an invalid
 *   credential is never retried).
 * - Bad request, blocked content, truncated output, unknown errors: the request is the problem;
 *   routing stops and the real error is shown. Never disguised as a connection failure.
 * - User cancellation never reaches this policy: it is rethrown at once and no other model starts.
 */
object FailurePolicy {
    fun decide(kind: AiErrorKind): FailoverDecision = when (kind) {
        AiErrorKind.NETWORK_UNAVAILABLE,
        AiErrorKind.TIMEOUT,
        AiErrorKind.PROVIDER_UNAVAILABLE,
        AiErrorKind.RATE_LIMITED,
        AiErrorKind.EMPTY_RESPONSE,
        AiErrorKind.MALFORMED_RESPONSE -> FailoverDecision.NEXT

        AiErrorKind.INVALID_API_KEY,
        AiErrorKind.MISSING_API_KEY,
        AiErrorKind.CREDENTIAL_UNAVAILABLE,
        AiErrorKind.PERMISSION_DENIED,
        AiErrorKind.QUOTA_OR_BILLING,
        AiErrorKind.MODEL_NOT_FOUND,
        AiErrorKind.INVALID_CONFIGURATION,
        AiErrorKind.NO_DEFAULT_PROVIDER,
        AiErrorKind.REQUEST_TOO_LARGE -> FailoverDecision.NEXT_AND_EXCLUDE

        AiErrorKind.BAD_REQUEST,
        AiErrorKind.CONTENT_BLOCKED,
        AiErrorKind.TRUNCATED_RESPONSE,
        AiErrorKind.NO_COMPATIBLE_MODEL,
        AiErrorKind.UNKNOWN -> FailoverDecision.STOP
    }
}

/** Why a model was not used for a request. */
enum class SkipReason { DISABLED, NO_STRUCTURED_OUTPUT, NO_IMAGE_INPUT, NO_TOOL_CALLING, CONTEXT_TOO_SMALL, EXCLUDED_EARLIER }

/** Observable routing events, for the Agent's status line and the task record. No secrets. */
sealed class RoutingEvent {
    data class Attempt(val candidate: ProviderCandidate, val cycle: Int) : RoutingEvent()
    data class Failed(val candidate: ProviderCandidate, val cycle: Int, val kind: AiErrorKind, val decision: FailoverDecision) : RoutingEvent()
    data class Skipped(val candidate: ProviderCandidate, val reason: SkipReason) : RoutingEvent()
    data class Waiting(val nextCycle: Int, val delayMs: Long) : RoutingEvent()
    data class Served(val candidate: ProviderCandidate, val cycle: Int) : RoutingEvent()
}

/** One provider attempt, kept for the task record. */
data class ProviderAttempt(
    val providerId: String,
    val providerName: String,
    val model: String,
    val cycle: Int,
    val error: AiErrorKind?,
    val decision: FailoverDecision?
)

/** Pure capability check (shared by the router and the Settings screen). */
object CapabilityMatcher {
    /** Approximate tokens of a request: ~3 characters per token, a safe over-estimate for mixed scripts. */
    fun estimateTokens(request: AiRequest): Int =
        (request.system.length + request.messages.sumOf { it.text.length }) / 3 + request.maxOutputTokens.coerceAtMost(8_000)

    /** Null when [support] fits [required], else the reason it does not. */
    fun mismatch(support: ModelSupport, required: Set<Capability>, estimatedTokens: Int): SkipReason? = when {
        Capability.STRUCTURED_OUTPUT in required && support.structuredOutput == false -> SkipReason.NO_STRUCTURED_OUTPUT
        Capability.IMAGE_INPUT in required && support.imageInput == false -> SkipReason.NO_IMAGE_INPUT
        Capability.TOOL_CALLING in required && support.toolCalling == false -> SkipReason.NO_TOOL_CALLING
        support.contextTokens != null && estimatedTokens > support.contextTokens -> SkipReason.CONTEXT_TOO_SMALL
        else -> null
    }

    /** Capabilities an [AiRequest] needs. */
    fun required(request: AiRequest): Set<Capability> = buildSet {
        add(Capability.TEXT)
        if (request.expectJson) add(Capability.STRUCTURED_OUTPUT)
    }
}

/**
 * Routing state of ONE task: which models failed for good, which one is serving, every attempt.
 * Calls of the same task share it, so a model that failed for good is not called again, and a
 * later call continues with the model that last answered (the earlier ones already failed in this
 * task, which is the documented reason they are not tried first again).
 *
 * The full request (original user message, conversation, plan, completed tool results, language
 * and format constraints) is built by the caller and sent unchanged to each model, so a model that
 * takes over continues the same task. Credentials stay with their own provider's client.
 */
class RoutingSession(
    candidates: List<ProviderCandidate>,
    private val policy: RoutingPolicy = RoutingPolicy(),
    private val clientFor: (ProviderCandidate) -> AiProviderClient,
    private val onEvent: (RoutingEvent) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    /** The priority list, duplicates removed (a model is never tried twice in one pass). */
    val candidates: List<ProviderCandidate> = candidates.distinctBy { it.id }

    private val excluded = LinkedHashMap<String, AiErrorKind>()
    private val reportedSkips = HashSet<Pair<String, SkipReason>>()
    private val _attempts = ArrayList<ProviderAttempt>()

    /** Every attempt of this task, in order. */
    val attempts: List<ProviderAttempt> get() = synchronized(_attempts) { _attempts.toList() }

    /** The model that answered the last successful call, if any. */
    @Volatile
    var serving: ProviderCandidate? = null
        private set

    /** Models excluded from this task, with the failure that excluded them. */
    val exclusions: Map<String, AiErrorKind> get() = synchronized(excluded) { LinkedHashMap(excluded) }

    /**
     * Runs [call] on the first compatible model, failing over in priority order.
     * @throws AiException the last real error when every pass failed, the error itself when it is
     *   not a failover error, or [AiErrorKind.NO_COMPATIBLE_MODEL]
     * @throws CancellationException at once when the caller is cancelled (no other model starts)
     */
    suspend fun <T> run(
        required: Set<Capability>,
        estimatedTokens: Int,
        call: suspend (AiProviderClient, ProviderCandidate) -> T
    ): T {
        val compatible = candidates.filter { c ->
            val reason = CapabilityMatcher.mismatch(c.support, required, estimatedTokens)
            if (reason != null && reportedSkips.add(c.id to reason)) onEvent(RoutingEvent.Skipped(c, reason))
            reason == null
        }
        if (compatible.isEmpty()) throw AiException(AiErrorKind.NO_COMPATIBLE_MODEL)

        val start = serving?.let { s -> compatible.indexOfFirst { it.id == s.id } }?.takeIf { it >= 0 } ?: 0
        var lastError: AiException? = null
        for (cycle in 1..policy.maxCycles) {
            if (cycle > 1) {
                val asked = lastError?.retryAfterSeconds?.times(1000) ?: 0L
                val wait = maxOf(policy.cycleBackoffMs, asked).coerceAtMost(policy.maxBackoffMs)
                onEvent(RoutingEvent.Waiting(cycle, wait))
                sleep(wait)
            }
            val pass = if (cycle == 1) compatible.drop(start) else compatible
            var tried = 0
            for (candidate in pass) {
                val exclusion = synchronized(excluded) { excluded[candidate.id] }
                if (exclusion != null) {
                    if (reportedSkips.add(candidate.id to SkipReason.EXCLUDED_EARLIER)) {
                        onEvent(RoutingEvent.Skipped(candidate, SkipReason.EXCLUDED_EARLIER))
                    }
                    continue
                }
                tried++
                onEvent(RoutingEvent.Attempt(candidate, cycle))
                try {
                    val result = call(clientFor(candidate), candidate)
                    record(candidate, cycle, null, null)
                    serving = candidate
                    onEvent(RoutingEvent.Served(candidate, cycle))
                    return result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    val decision = FailurePolicy.decide(e.kind)
                    record(candidate, cycle, e.kind, decision)
                    onEvent(RoutingEvent.Failed(candidate, cycle, e.kind, decision))
                    if (decision == FailoverDecision.STOP) throw e
                    if (decision == FailoverDecision.NEXT_AND_EXCLUDE) synchronized(excluded) { excluded[candidate.id] = e.kind }
                    if (serving?.id == candidate.id) serving = null
                    lastError = e
                }
            }
            if (tried == 0 && cycle > 1) break // every model is excluded: another pass cannot help
            if (synchronized(excluded) { compatible.all { it.id in excluded } }) break
        }
        throw lastError ?: AiException(AiErrorKind.NO_COMPATIBLE_MODEL)
    }

    private fun record(c: ProviderCandidate, cycle: Int, kind: AiErrorKind?, decision: FailoverDecision?) {
        synchronized(_attempts) { _attempts += ProviderAttempt(c.id, c.name, c.model, cycle, kind, decision) }
    }
}

/**
 * An [AiProviderClient] that sends every request through a [RoutingSession]. The orchestrator
 * uses it exactly like the default provider's client, so the existing chat, Smart Templates and
 * document pipeline run unchanged on top of routing.
 *
 * Model calls have no side effects: documents are written by the orchestrator only after a
 * complete, validated answer, so failing over a model call can never create a duplicate file.
 *
 * Streaming: when a model fails after part of its answer was shown, [onRestart] is called before
 * the next model starts, so the partial text of the failed model can be discarded by the UI.
 */
class RoutedAiClient(
    private val session: RoutingSession,
    private val onRestart: () -> Unit = {}
) : AiProviderClient {
    /** The type of the model serving this task (the first in the list before any answer). */
    override val type: com.example.ai.providers.AiProviderType
        get() = com.example.ai.providers.AiProviderType.fromStorageKey(session.serving?.providerType ?: session.candidates.firstOrNull()?.providerType ?: "")

    override suspend fun complete(request: AiRequest): AiResponse =
        session.run(CapabilityMatcher.required(request), CapabilityMatcher.estimateTokens(request)) { client, _ ->
            client.complete(request)
        }

    override suspend fun stream(request: AiRequest, onText: (String) -> Unit): AiResponse {
        var streamedBefore = false
        return session.run(CapabilityMatcher.required(request), CapabilityMatcher.estimateTokens(request)) { client, _ ->
            if (streamedBefore) onRestart()
            streamedBefore = false
            client.stream(request) { piece ->
                streamedBefore = true
                onText(piece)
            }
        }
    }

    override suspend fun testConnection(): Long =
        session.run(setOf(Capability.TEXT), 0) { client, _ -> client.testConnection() }
}
