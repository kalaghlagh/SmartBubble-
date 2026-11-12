package com.example

import com.example.ai.orchestrator.PlannerReplyExtractor
import com.example.ai.orchestrator.StreamThrottle
import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import com.example.ai.providers.AiHttp
import com.example.ai.providers.AnthropicClient
import com.example.ai.providers.GeminiClient
import com.example.ai.providers.OpenAiClient
import com.example.ai.providers.SseParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Streaming parsers, the planner reply extractor and real HTTP cancellation. Pure JVM. */
class StreamingAndCancellationTest {

    private var server: HttpServer? = null

    @After
    fun stopServer() {
        server?.stop(0)
    }

    // ------------------------------------------------------------------ SSE

    @Test
    fun sseParser_joinsDataLinesAndIgnoresOtherFields() {
        val events = mutableListOf<String>()
        val parser = SseParser { events += it }
        listOf(
            ": keep-alive comment",
            "event: content_block_delta",
            "data: {\"a\":1}",
            "",
            "data: line1",
            "data: line2\r",
            "",
            "id: 7",
            "data: [DONE]"
        ).forEach(parser::feedLine)
        parser.finish()
        assertEquals(listOf("{\"a\":1}", "line1\nline2", "[DONE]"), events)
    }

    // ------------------------------------------------------------------ provider stream formats

    @Test
    fun openAiStream_collectsDeltasAndFinishReason() {
        val pieces = mutableListOf<String>()
        val acc = OpenAiClient.StreamAccumulator { pieces += it }
        acc.accept("""{"choices":[{"delta":{"role":"assistant"}}]}""")
        acc.accept("""{"choices":[{"delta":{"content":"Bon"}}]}""")
        acc.accept("""{"choices":[{"delta":{"content":"jour"},"finish_reason":"length"}]}""")
        acc.accept("""{"choices":[],"usage":{"total_tokens":5}}""")
        acc.accept("[DONE]")
        val response = acc.finish()
        assertEquals(listOf("Bon", "jour"), pieces)
        assertEquals("Bonjour", response.text)
        assertTrue(response.truncated)
    }

    @Test
    fun openAiStream_errorAndRefusalAreReported() {
        val acc = OpenAiClient.StreamAccumulator {}
        try {
            acc.accept("""{"error":{"message":"overloaded"}}""")
            fail("expected an error")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.PROVIDER_UNAVAILABLE, e.kind)
        }
        val refused = OpenAiClient.StreamAccumulator {}
        refused.accept("""{"choices":[{"delta":{"refusal":"I can't help"},"finish_reason":"stop"}]}""")
        try {
            refused.finish()
            fail("expected CONTENT_BLOCKED")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.CONTENT_BLOCKED, e.kind)
        }
    }

    @Test
    fun anthropicStream_keepsOnlyTextDeltas() {
        val pieces = mutableListOf<String>()
        val acc = AnthropicClient.StreamAccumulator { pieces += it }
        acc.accept("""{"type":"message_start","message":{"id":"m"}}""")
        acc.accept("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""")
        acc.accept("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""")
        acc.accept("""{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"مرحبا"}}""")
        acc.accept("""{"type":"ping"}""")
        acc.accept("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        acc.accept("""{"type":"message_stop"}""")
        assertEquals(listOf("مرحبا"), pieces)
        val response = acc.finish()
        assertEquals("مرحبا", response.text)
        assertFalse(response.truncated)
    }

    @Test
    fun anthropicStream_errorEventAndRefusal() {
        try {
            AnthropicClient.StreamAccumulator {}.accept("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
            fail("expected an error")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.PROVIDER_UNAVAILABLE, e.kind)
        }
        val acc = AnthropicClient.StreamAccumulator {}
        acc.accept("""{"type":"message_delta","delta":{"stop_reason":"refusal"}}""")
        try {
            acc.finish()
            fail("expected CONTENT_BLOCKED")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.CONTENT_BLOCKED, e.kind)
        }
    }

    @Test
    fun geminiStream_skipsThoughtsAndReportsBlocking() {
        val pieces = mutableListOf<String>()
        val acc = GeminiClient.StreamAccumulator { pieces += it }
        acc.accept("""{"candidates":[{"content":{"parts":[{"text":"plan","thought":true}]}}]}""")
        acc.accept("""{"candidates":[{"content":{"parts":[{"text":"Hello "}]}}]}""")
        acc.accept("""{"candidates":[{"content":{"parts":[{"text":"world"}]},"finishReason":"STOP"}]}""")
        assertEquals(listOf("Hello ", "world"), pieces)
        assertEquals("Hello world", acc.finish().text)

        try {
            GeminiClient.StreamAccumulator {}.accept("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
            fail("expected CONTENT_BLOCKED")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.CONTENT_BLOCKED, e.kind)
        }
        val empty = GeminiClient.StreamAccumulator {}
        empty.accept("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")
        try {
            empty.finish()
            fail("expected TRUNCATED_RESPONSE")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.TRUNCATED_RESPONSE, e.kind)
        }
    }

    // ------------------------------------------------------------------ planner reply extraction

    @Test
    fun plannerExtractor_streamsChatReplyAcrossArbitraryChunks() {
        val json = """{"intent":"chat","reply":"Line 1\nQuote: \"ok\" é – done"}"""
        // Feed one character at a time: escapes split across pieces must wait for the rest.
        val extractor = PlannerReplyExtractor()
        val seen = mutableListOf<String>()
        json.forEach { c -> extractor.append(c.toString())?.let { seen += it } }
        assertEquals("Line 1\nQuote: \"ok\" é – done", extractor.currentReply())
        // Every intermediate value is a prefix of the final reply (nothing wrong is ever shown).
        assertTrue(seen.all { "Line 1\nQuote: \"ok\" é – done".startsWith(it) })
        assertTrue(extractor.isChatReply)
    }

    @Test
    fun plannerExtractor_showsNothingForDocumentsOrOtherShapes() {
        val doc = PlannerReplyExtractor()
        assertNull(doc.append("""{"intent":"document","format":"DOCX","title":"T","reply":"Preparing"}"""))
        assertFalse(doc.isChatReply)

        val plain = PlannerReplyExtractor()
        assertNull(plain.append("Sure, here is the answer"))

        val fenced = PlannerReplyExtractor()
        assertEquals("Hi", fenced.append("```json\n{\"intent\": \"chat\", \"reply\": \"Hi\"}\n```"))

        // A reply key that is not right after the intent is not streamed (shown when complete).
        val otherOrder = PlannerReplyExtractor()
        assertNull(otherOrder.append("""{"intent":"chat","note":"x","reply":"Hi"}"""))
    }

    @Test
    fun streamThrottle_limitsUpdates() {
        var now = 0L
        val throttle = StreamThrottle(intervalMs = 100) { now }
        assertTrue(throttle.ready())
        now = 50
        assertFalse(throttle.ready())
        now = 100
        assertTrue(throttle.ready())
    }

    // ------------------------------------------------------------------ real HTTP streaming + cancellation

    /** Starts a local server that sends one SSE event, then keeps the response open. */
    private fun startSlowStreamServer(closed: CountDownLatch, sentAfterCancel: AtomicBoolean, cancelled: AtomicBoolean): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/stream") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            try {
                out.write("data: {\"n\":1}\n\n".toByteArray())
                out.flush()
                // Keep writing until the client goes away.
                repeat(200) {
                    Thread.sleep(50)
                    if (cancelled.get()) sentAfterCancel.set(true)
                    out.write(": keep-alive\n\n".toByteArray())
                    out.flush()
                }
            } catch (e: IOException) {
                closed.countDown() // the client closed the connection
            } finally {
                exchange.close()
            }
        }
        s.executor = java.util.concurrent.Executors.newCachedThreadPool()
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/stream"
    }

    @Test
    fun executeStream_cancellationClosesTheConnectionAndStopsEvents() = runBlocking {
        val closed = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val url = startSlowStreamServer(closed, AtomicBoolean(false), cancelled)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstEvent = CountDownLatch(1)

        val call = async(Dispatchers.IO) {
            AiHttp.executeStream(Request.Builder().url(url).build(), secret = null) {
                events += it
                firstEvent.countDown()
            }
        }
        assertTrue("first event not received", firstEvent.await(5, TimeUnit.SECONDS))
        cancelled.set(true)
        call.cancel()
        try {
            call.await()
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected: a user cancellation is not reported as a network error
        }
        // The HTTP connection is really closed: the server's next write fails.
        assertTrue("server did not see the connection close", closed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("{\"n\":1}"), events.toList())
    }

    @Test
    fun executeStream_deliversEventsAndCompletes() = runBlocking {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/ok") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n".toByteArray())
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"B\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
            }
        }
        s.start()
        server = s
        val acc = OpenAiClient.StreamAccumulator {}
        val result = withTimeout(10_000) {
            AiHttp.executeStream(Request.Builder().url("http://127.0.0.1:${s.address.port}/ok").build(), null) { acc.accept(it) }
        }
        assertTrue(result.streamed)
        assertEquals(200, result.code)
        assertEquals("AB", acc.finish().text)
    }

    @Test
    fun executeStream_errorResponseIsReturnedWithBody() = runBlocking {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/err") { exchange ->
            val body = "{\"error\":{\"message\":\"Invalid API key\"}}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.start()
        server = s
        val result = AiHttp.executeStream(Request.Builder().url("http://127.0.0.1:${s.address.port}/err").build(), null) {
            fail("no events expected")
        }
        assertFalse(result.streamed)
        assertEquals(401, result.code)
        assertEquals(AiErrorKind.INVALID_API_KEY, AiHttp.mapHttpError(result.code, result.body, null).kind)
    }
}
