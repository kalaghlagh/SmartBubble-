package com.example.agent

import com.example.agent.routing.Capability
import com.example.agent.routing.CapabilityMatcher
import com.example.agent.routing.FailoverDecision
import com.example.agent.routing.FailurePolicy
import com.example.agent.routing.ModelSupport
import com.example.agent.routing.PriorityList
import com.example.agent.routing.ProviderCandidate
import com.example.agent.routing.RoutedAiClient
import com.example.agent.routing.RoutingEvent
import com.example.agent.routing.RoutingPolicy
import com.example.agent.routing.RoutingSession
import com.example.agent.routing.SkipReason
import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import com.example.ai.providers.AiMessage
import com.example.ai.providers.AiProviderClient
import com.example.ai.providers.AiProviderType
import com.example.ai.providers.AiRequest
import com.example.ai.providers.AiResponse
import com.example.ai.providers.AiRole
import com.example.data.model.ProviderEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Sequential provider routing: order, failover, cycles, error policy, capabilities, cancellation. */
class ProviderRoutingTest {

    /** A fake provider: answers from [script] in order (an AiException is thrown, a String answered). */
    private class FakeClient(val id: String, private val script: MutableList<Any>, val seen: MutableList<Pair<String, AiRequest>>) : AiProviderClient {
        override val type = AiProviderType.OPENAI
        override suspend fun complete(request: AiRequest): AiResponse {
            seen += id to request
            val next = if (script.size > 1) script.removeAt(0) else script.first()
            if (next is AiException) throw next
            if (next == "HANG") awaitCancellation()
            return AiResponse(next as String)
        }

        override suspend fun stream(request: AiRequest, onText: (String) -> Unit): AiResponse {
            seen += id to request
            val next = if (script.size > 1) script.removeAt(0) else script.first()
            if (next is Pair<*, *>) { // partial text, then failure
                onText(next.first as String)
                throw next.second as AiException
            }
            if (next is AiException) throw next
            onText(next as String)
            return AiResponse(next)
        }

        override suspend fun testConnection() = 1L
    }

    private val request = AiRequest("system", listOf(AiMessage(AiRole.USER, "Write a report in Arabic")), expectJson = true, maxOutputTokens = 1000)
    private val seen = mutableListOf<Pair<String, AiRequest>>()
    private val events = mutableListOf<RoutingEvent>()
    private val sleeps = mutableListOf<Long>()

    private fun candidate(id: String, support: ModelSupport = ModelSupport.UNKNOWN) = ProviderCandidate(id, "Provider $id", "model-$id", support)

    private fun session(
        scripts: Map<String, List<Any>>,
        order: List<String> = scripts.keys.toList(),
        policy: RoutingPolicy = RoutingPolicy(),
        support: Map<String, ModelSupport> = emptyMap()
    ): RoutingSession {
        val clients = scripts.mapValues { (id, s) -> FakeClient(id, s.toMutableList(), seen) }
        return RoutingSession(
            candidates = order.map { candidate(it, support[it] ?: ModelSupport.UNKNOWN) },
            policy = policy,
            clientFor = { clients.getValue(it.id) },
            onEvent = { events += it },
            sleep = { sleeps += it }
        )
    }

    private fun AiErrorKind.ex(retryAfter: Long? = null) = AiException(this, retryAfterSeconds = retryAfter)
    private suspend fun RoutingSession.ask() = RoutedAiClient(this).complete(request).text
    private fun order() = seen.map { it.first }

    @Test
    fun firstHealthyModelAnswers_noOtherModelIsCalled() = runBlocking {
        val s = session(mapOf("A" to listOf("from A"), "B" to listOf("from B")))
        assertEquals("from A", s.ask())
        assertEquals(listOf("A"), order())
        assertEquals("A", s.serving?.id)
    }

    @Test
    fun connectionFailures_moveStrictlyToTheNextModelInTheConfiguredOrder() = runBlocking {
        val s = session(
            mapOf("C" to listOf("from C"), "A" to listOf(AiErrorKind.TIMEOUT.ex()), "B" to listOf(AiErrorKind.NETWORK_UNAVAILABLE.ex())),
            order = listOf("A", "B", "C")
        )
        assertEquals("from C", s.ask())
        assertEquals(listOf("A", "B", "C"), order())
        assertEquals(listOf(AiErrorKind.TIMEOUT, AiErrorKind.NETWORK_UNAVAILABLE, null), s.attempts.map { it.error })
    }

    @Test
    fun serviceErrorsAndRateLimits_failOver_andTheFullContextIsSentToEachModel() = runBlocking {
        val s = session(mapOf("A" to listOf(AiErrorKind.PROVIDER_UNAVAILABLE.ex()), "B" to listOf(AiErrorKind.RATE_LIMITED.ex()), "C" to listOf("ok")))
        assertEquals("ok", s.ask())
        // Same request object (system prompt, conversation, format constraints) to every model.
        seen.forEach { assertSame(request, it.second) }
    }

    @Test
    fun afterTheLastModel_anotherPassStartsFromTheFirst_withABoundedPause() = runBlocking {
        val s = session(mapOf("A" to listOf(AiErrorKind.TIMEOUT.ex(), "A second pass"), "B" to listOf(AiErrorKind.PROVIDER_UNAVAILABLE.ex())))
        assertEquals("A second pass", s.ask())
        assertEquals(listOf("A", "B", "A"), order())
        assertEquals(listOf(2_000L), sleeps)
        assertTrue(events.any { it is RoutingEvent.Waiting && it.nextCycle == 2 })
        assertEquals(listOf(1, 1, 2), s.attempts.map { it.cycle })
    }

    @Test
    fun defaultTwoPasses_thenTheRealLastErrorIsReported_neverInfinite() = runBlocking {
        val s = session(mapOf("A" to listOf(AiErrorKind.TIMEOUT.ex()), "B" to listOf(AiErrorKind.NETWORK_UNAVAILABLE.ex())))
        try {
            s.ask(); fail("expected failure")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NETWORK_UNAVAILABLE, e.kind)
        }
        assertEquals(listOf("A", "B", "A", "B"), order())
        assertEquals(1, sleeps.size)
    }

    @Test
    fun configuredCycles_areRespected() = runBlocking {
        val one = session(mapOf("A" to listOf(AiErrorKind.TIMEOUT.ex())), policy = RoutingPolicy(maxCycles = 1))
        runCatching { one.ask() }
        assertEquals(listOf("A"), order())
        seen.clear()
        val three = session(mapOf("A" to listOf(AiErrorKind.TIMEOUT.ex())), policy = RoutingPolicy(maxCycles = 3))
        runCatching { three.ask() }
        assertEquals(listOf("A", "A", "A"), order())
        assertTrue(runCatching { RoutingPolicy(maxCycles = 0) }.isFailure)
        assertTrue(runCatching { RoutingPolicy(maxCycles = 99) }.isFailure)
    }

    @Test
    fun invalidKeyAndQuota_areRecordedAndNeverRetried() = runBlocking {
        val s = session(mapOf(
            "A" to listOf(AiErrorKind.INVALID_API_KEY.ex()),
            "B" to listOf(AiErrorKind.QUOTA_OR_BILLING.ex()),
            "C" to listOf(AiErrorKind.TIMEOUT.ex())
        ))
        runCatching { s.ask() }
        // A and B are excluded after one attempt; only C is tried again in pass 2.
        assertEquals(listOf("A", "B", "C", "C"), order())
        assertEquals(mapOf("A" to AiErrorKind.INVALID_API_KEY, "B" to AiErrorKind.QUOTA_OR_BILLING), s.exclusions)
    }

    @Test
    fun everyModelExcluded_noSecondPassAndNoPause() = runBlocking {
        val s = session(mapOf("A" to listOf(AiErrorKind.INVALID_API_KEY.ex()), "B" to listOf(AiErrorKind.PERMISSION_DENIED.ex())))
        try {
            s.ask(); fail()
        } catch (e: AiException) {
            assertEquals(AiErrorKind.PERMISSION_DENIED, e.kind)
        }
        assertEquals(listOf("A", "B"), order())
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun badRequest_isNotDisguisedAsAConnectionFailure_andStopsRouting() = runBlocking {
        val s = session(mapOf("A" to listOf(AiErrorKind.BAD_REQUEST.ex()), "B" to listOf("never")))
        try {
            s.ask(); fail()
        } catch (e: AiException) {
            assertEquals(AiErrorKind.BAD_REQUEST, e.kind)
        }
        assertEquals(listOf("A"), order())
    }

    @Test
    fun rateLimit_retryAfterLengthensThePause_butItIsCapped() = runBlocking {
        val s = session(mapOf("A" to listOf(AiErrorKind.RATE_LIMITED.ex(retryAfter = 7), "ok")))
        assertEquals("ok", s.ask())
        assertEquals(listOf(7_000L), sleeps)
        sleeps.clear(); seen.clear()
        val capped = session(mapOf("A" to listOf(AiErrorKind.RATE_LIMITED.ex(retryAfter = 3600), "ok")))
        assertEquals("ok", capped.ask())
        assertEquals(listOf(30_000L), sleeps)
    }

    @Test
    fun incompatibleModels_areSkippedTransparently_withTheReason() = runBlocking {
        val s = session(
            mapOf("A" to listOf("A"), "B" to listOf("B"), "C" to listOf("C")),
            support = mapOf("A" to ModelSupport(structuredOutput = false), "B" to ModelSupport(contextTokens = 10))
        )
        assertEquals("C", s.ask())
        assertEquals(listOf("C"), order())
        val skipped = events.filterIsInstance<RoutingEvent.Skipped>().map { it.candidate.id to it.reason }
        assertEquals(listOf("A" to SkipReason.NO_STRUCTURED_OUTPUT, "B" to SkipReason.CONTEXT_TOO_SMALL), skipped)
    }

    @Test
    fun noCompatibleModel_isReportedAndNothingIsCalled() = runBlocking {
        val s = session(mapOf("A" to listOf("A")), support = mapOf("A" to ModelSupport(structuredOutput = false)))
        try {
            s.ask(); fail()
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NO_COMPATIBLE_MODEL, e.kind)
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun userCancellation_neverStartsAnotherModel() = runBlocking {
        val s = session(mapOf("A" to listOf("HANG"), "B" to listOf("never")))
        val job = async { s.ask() }
        while (seen.isEmpty()) yield()
        job.cancel()
        try {
            job.await(); fail()
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals(listOf("A"), order())
        assertTrue(events.none { it is RoutingEvent.Failed })
    }

    @Test
    fun laterCallsOfTheSameTask_continueWithTheModelThatAnswered() = runBlocking {
        val s = session(mapOf("A" to listOf(AiErrorKind.TIMEOUT.ex(), "A again"), "B" to listOf("B1", "B2")))
        assertEquals("B1", s.ask())
        assertEquals("B2", s.ask())
        assertEquals(listOf("A", "B", "B"), order())
    }

    @Test
    fun duplicateEntries_areNotTriedTwiceInOnePass() = runBlocking {
        val s = session(mapOf("A" to listOf(AiErrorKind.TIMEOUT.ex())), order = listOf("A", "A"), policy = RoutingPolicy(maxCycles = 1))
        runCatching { s.ask() }
        assertEquals(listOf("A"), order())
    }

    @Test
    fun streaming_failureAfterPartialText_signalsARestartBeforeTheNextModel() = runBlocking {
        val restarts = CompletableDeferred<Int>()
        var count = 0
        val s = session(mapOf("A" to listOf("half" to AiErrorKind.TIMEOUT.ex()), "B" to listOf("whole answer")))
        val shown = StringBuilder()
        val client = RoutedAiClient(s) { count++; shown.clear() }
        val r = client.stream(request) { shown.append(it) }
        restarts.complete(count)
        assertEquals("whole answer", r.text)
        assertEquals("whole answer", shown.toString())
        assertEquals(1, restarts.await())
    }

    @Test
    fun failurePolicy_coversEveryErrorKind() {
        AiErrorKind.values().forEach { FailurePolicy.decide(it) } // exhaustive, no exception
        assertEquals(FailoverDecision.NEXT, FailurePolicy.decide(AiErrorKind.TIMEOUT))
        assertEquals(FailoverDecision.NEXT, FailurePolicy.decide(AiErrorKind.NETWORK_UNAVAILABLE))
        assertEquals(FailoverDecision.NEXT, FailurePolicy.decide(AiErrorKind.PROVIDER_UNAVAILABLE))
        assertEquals(FailoverDecision.NEXT, FailurePolicy.decide(AiErrorKind.RATE_LIMITED))
        assertEquals(FailoverDecision.NEXT_AND_EXCLUDE, FailurePolicy.decide(AiErrorKind.INVALID_API_KEY))
        assertEquals(FailoverDecision.NEXT_AND_EXCLUDE, FailurePolicy.decide(AiErrorKind.QUOTA_OR_BILLING))
        assertEquals(FailoverDecision.NEXT_AND_EXCLUDE, FailurePolicy.decide(AiErrorKind.REQUEST_TOO_LARGE))
        assertEquals(FailoverDecision.STOP, FailurePolicy.decide(AiErrorKind.BAD_REQUEST))
        assertEquals(FailoverDecision.STOP, FailurePolicy.decide(AiErrorKind.CONTENT_BLOCKED))
    }

    @Test
    fun capabilityMatcher_requiresStructuredOutputOnlyForJsonRequests() {
        assertEquals(setOf(Capability.TEXT, Capability.STRUCTURED_OUTPUT), CapabilityMatcher.required(request))
        assertEquals(setOf(Capability.TEXT), CapabilityMatcher.required(request.copy(expectJson = false)))
        assertEquals(null, CapabilityMatcher.mismatch(ModelSupport.UNKNOWN, setOf(Capability.IMAGE_INPUT), 100))
        assertEquals(SkipReason.NO_IMAGE_INPUT, CapabilityMatcher.mismatch(ModelSupport(imageInput = false), setOf(Capability.IMAGE_INPUT), 100))
    }

    // ------------------------------------------------------------------ priority list

    private fun provider(id: String, default: Boolean = false, created: Long = 0) =
        ProviderEntity(id, "P$id", "OpenAI", "https://x", "", "m$id", isDefault = default, status = "", createdAt = created)

    @Test
    fun priorityList_userOrderFirst_newProvidersAfter_defaultFirstBeforeAnyOrder() {
        val ps = listOf(provider("a", created = 1), provider("b", default = true, created = 2), provider("c", created = 3))
        assertEquals(listOf("b", "a", "c"), PriorityList.arrange(ps, emptyList(), emptySet()) { true }.map { it.provider.id })
        assertEquals(listOf("c", "a", "b"), PriorityList.arrange(ps, listOf("c", "a"), emptySet()) { true }.map { it.provider.id })
        val entries = PriorityList.arrange(ps, listOf("c", "a", "gone"), setOf("a")) { it != "b" }
        assertEquals(listOf("c"), PriorityList.candidates(entries) { ModelSupport.UNKNOWN }.map { it.id })
        assertFalse(entries.first { it.provider.id == "a" }.enabled)
    }

    @Test
    fun priorityList_moveAndCodec() {
        assertEquals(listOf("b", "a", "c"), PriorityList.move(listOf("a", "b", "c"), 1, 0))
        assertEquals(listOf("a", "b", "c"), PriorityList.move(listOf("a", "b", "c"), 0, -1))
        assertEquals(listOf("x", "y"), PriorityList.decode(PriorityList.encode(listOf("x", "y"))))
        assertEquals(emptyList<String>(), PriorityList.decode(null))
    }
}
