package com.example

import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import com.example.ai.providers.AiHttp
import com.example.ai.providers.AiMessage
import com.example.ai.providers.AiProviderClient
import com.example.ai.providers.AiProviderFactory
import com.example.ai.providers.AiProviderType
import com.example.ai.providers.AiRequest
import com.example.ai.providers.AiRole
import com.example.ai.providers.DeepSeekClient
import com.example.ai.providers.GroqClient
import com.example.ai.providers.Json
import com.example.ai.providers.MistralClient
import com.example.ai.providers.ModelCatalogCache
import com.example.ai.providers.ModelListParsers
import com.example.ai.providers.OpenAiClient
import com.example.ai.providers.OpenRouterClient
import com.example.ai.providers.XaiClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MOCK tests of the five added providers (DeepSeek, Mistral AI, Groq, xAI, OpenRouter): each
 * client talks to a local HTTP server that imitates the provider's documented API (paths,
 * headers, model lists, SSE streams, errors). They verify the app's requests and parsing; they
 * are NOT authenticated tests against the real services.
 */
class ProviderEcosystemTest {

    private data class Recorded(val method: String, val path: String, val auth: String?, val accept: String?, val body: String)

    private lateinit var server: HttpServer
    private val requests = Collections.synchronizedList(mutableListOf<Recorded>())
    private val routes = Collections.synchronizedMap(mutableMapOf<String, (HttpExchange) -> Unit>())
    private val key = "test-key-1234567890"
    private val base get() = "http://127.0.0.1:${server.address.port}"

    private val request = AiRequest(
        system = "Answer in JSON.",
        messages = listOf(AiMessage(AiRole.USER, "مرحبا")),
        expectJson = true
    )

    @Before
    fun start() {
        AiHttp.allowLoopbackHttpForTests = true
        ModelCatalogCache.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Recorded(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestHeaders.getFirst("Accept"), body)
            val route = routes[ex.requestURI.path]
            if (route == null) send(ex, 404, """{"error":{"message":"Not found"}}""") else route(ex)
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        AiHttp.allowLoopbackHttpForTests = false
        ModelCatalogCache.clear()
    }

    private fun send(ex: HttpExchange, code: Int, body: String, type: String = "application/json", headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sse(vararg events: String): (HttpExchange) -> Unit = { ex ->
        send(ex, 200, events.joinToString("") { if (it.startsWith(":")) "$it\n\n" else "data: $it\n\n" }, "text/event-stream")
    }

    private fun chunk(text: String, finish: String? = null) =
        """{"choices":[{"index":0,"delta":{"content":${Json.write(text)}}${finish?.let { ",\"finish_reason\":\"$it\"" } ?: ""}}]}"""

    private fun bodyOf(r: Recorded): Map<String, Any?> = Json.obj(Json.parse(r.body))!!

    private fun chatCalls() = requests.filter { it.path.endsWith("/chat/completions") }

    // ------------------------------------------------------------------ catalog of each provider

    private val deepSeekModels = """{"object":"list","data":[{"id":"deepseek-chat","object":"model","owned_by":"deepseek"},{"id":"deepseek-reasoner","object":"model","owned_by":"deepseek"}]}"""
    private val mistralModels = """{"object":"list","data":[
        {"id":"mistral-large-2411","name":"Mistral Large","max_context_length":131072,"aliases":["mistral-large-latest"],
         "capabilities":{"completion_chat":true,"function_calling":true,"vision":false}},
        {"id":"mistral-embed","capabilities":{"completion_chat":false,"function_calling":false}}]}"""
    private val groqModels = """{"object":"list","data":[
        {"id":"llama-3.3-70b-versatile","active":true,"context_window":131072},
        {"id":"whisper-large-v3","active":true,"context_window":448},
        {"id":"retired-model","active":false,"context_window":8192}]}"""
    private val xaiLanguageModels = """{"models":[
        {"id":"grok-4-0709","aliases":["grok-4"],"input_modalities":["text","image"],"output_modalities":["text"]},
        {"id":"grok-image","input_modalities":["text"],"output_modalities":["image"]}]}"""
    private val openRouterModels = """{"data":[
        {"id":"openai/gpt-4o","name":"OpenAI: GPT-4o","context_length":128000,
         "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
         "supported_parameters":["max_tokens","response_format","tools"]},
        {"id":"vendor/plain-model","name":"Plain","context_length":8192,
         "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
         "supported_parameters":["max_tokens","reasoning"]},
        {"id":"vendor/image-maker","architecture":{"input_modalities":["text"],"output_modalities":["image"]},"supported_parameters":[]}]}"""

    // ------------------------------------------------------------------ factory / types

    @Test
    fun factoryCreatesDedicatedClientsAndTypesRoundTrip() {
        val expected = mapOf(
            AiProviderType.DEEPSEEK to DeepSeekClient::class,
            AiProviderType.MISTRAL to MistralClient::class,
            AiProviderType.GROQ to GroqClient::class,
            AiProviderType.XAI to XaiClient::class,
            AiProviderType.OPENROUTER to OpenRouterClient::class
        )
        for ((type, cls) in expected) {
            assertEquals(type, AiProviderType.fromStorageKey(type.storageKey))
            assertTrue(type.defaultBaseUrl.startsWith("https://"))
            assertTrue(type.listsModels)
            assertEquals(cls, AiProviderFactory.createClient(type, "", key, "m")::class)
        }
        assertEquals("https://api.deepseek.com", AiProviderType.DEEPSEEK.defaultBaseUrl)
        assertEquals("https://api.mistral.ai/v1", AiProviderType.MISTRAL.defaultBaseUrl)
        assertEquals("https://api.groq.com/openai/v1", AiProviderType.GROQ.defaultBaseUrl)
        assertEquals("https://api.x.ai/v1", AiProviderType.XAI.defaultBaseUrl)
        assertEquals("https://openrouter.ai/api/v1", AiProviderType.OPENROUTER.defaultBaseUrl)
        // Existing providers are unchanged.
        assertEquals(AiProviderType.OPENAI_COMPATIBLE, AiProviderType.fromStorageKey("Custom"))
        assertFalse(AiProviderType.ANTHROPIC.listsModels)
        assertFalse(AiProviderType.OPENAI_COMPATIBLE.listsModels)
        try {
            AiProviderFactory.createClient(AiProviderType.GROQ, "", "", "m")
            fail("a blank key must be rejected")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.MISSING_API_KEY, e.kind)
        }
    }

    @Test
    fun cleartextUrlsAreRejectedOutsideTests() {
        AiHttp.allowLoopbackHttpForTests = false
        try {
            DeepSeekClient("http://127.0.0.1:1", key, "deepseek-chat")
            fail("http must be rejected")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.INVALID_CONFIGURATION, e.kind)
        }
    }

    // ------------------------------------------------------------------ model lists (parsers)

    @Test
    fun modelListParsersReadProviderCapabilities() {
        val ds = ModelListParsers.deepSeek(deepSeekModels)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), ds.map { it.id })
        assertEquals(true, ds[1].capabilities.reasoning)
        assertNull(ds[0].capabilities.jsonOutput) // not reported: unknown, not assumed

        val mistral = ModelListParsers.mistral(mistralModels)
        assertEquals(listOf("mistral-large-2411"), mistral.map { it.id }) // embeddings model left out
        assertEquals(131072, mistral[0].contextLength)
        assertEquals(true, mistral[0].capabilities.toolCalling)
        assertEquals(false, mistral[0].capabilities.vision)
        assertTrue(mistral[0].answersTo("mistral-large-latest"))

        val groq = ModelListParsers.groq(groqModels)
        assertEquals(listOf("llama-3.3-70b-versatile"), groq.map { it.id }) // speech + inactive left out
        assertEquals(131072, groq[0].contextLength)

        val xai = ModelListParsers.xaiLanguageModels(xaiLanguageModels)
        assertEquals(listOf("grok-4-0709"), xai.map { it.id }) // image-output model left out
        assertEquals(true, xai[0].capabilities.vision)
        assertTrue(xai[0].answersTo("grok-4"))

        val or = ModelListParsers.openRouter(openRouterModels)
        assertEquals(listOf("openai/gpt-4o", "vendor/plain-model"), or.map { it.id })
        assertEquals(true, or[0].capabilities.jsonOutput)
        assertEquals(true, or[0].capabilities.toolCalling)
        assertEquals(true, or[0].capabilities.vision)
        assertEquals(false, or[1].capabilities.jsonOutput)
        assertEquals(false, or[1].capabilities.toolCalling)
        assertEquals(true, or[1].capabilities.reasoning)
    }

    @Test
    fun modelListErrorsAreReported() {
        try {
            ModelListParsers.groq("<html>")
            fail()
        } catch (e: AiException) {
            assertEquals(AiErrorKind.MALFORMED_RESPONSE, e.kind)
        }
    }

    // ------------------------------------------------------------------ streaming per provider

    private fun streamWith(client: AiProviderClient): Pair<String, List<String>> = runBlocking {
        val pieces = Collections.synchronizedList(mutableListOf<String>())
        val response = client.stream(request) { pieces += it }
        response.text to pieces.toList()
    }

    private fun assertChatRequest(path: String, model: String, json: Boolean) {
        val call = chatCalls().last()
        assertEquals("POST", call.method)
        assertEquals(path, call.path)
        assertEquals("Bearer $key", call.auth)
        assertEquals("text/event-stream", call.accept)
        val body = bodyOf(call)
        assertEquals(model, body["model"])
        assertEquals(true, body["stream"])
        assertEquals(json, body.containsKey("response_format"))
        assertFalse(body.containsKey("temperature"))
        assertTrue(call.body.contains("مرحبا"))
    }

    @Test
    fun deepSeekStreamsContentAndSkipsReasoning() {
        routes["/deepseek/chat/completions"] = sse(
            """{"choices":[{"delta":{"role":"assistant","reasoning_content":"thinking..."}}]}""",
            chunk("Hel"), chunk("lo", "stop"), "[DONE]"
        )
        val (text, pieces) = streamWith(DeepSeekClient("$base/deepseek", key, "deepseek-reasoner"))
        assertEquals("Hello", text)
        assertEquals(listOf("Hel", "lo"), pieces)
        assertChatRequest("/deepseek/chat/completions", "deepseek-reasoner", json = true)
    }

    @Test
    fun mistralStreamsTextPartsAndSkipsThinkingParts() {
        routes["/mistral/v1/chat/completions"] = sse(
            """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"hidden"}]}]}}]}""",
            """{"choices":[{"delta":{"content":[{"type":"text","text":"Bon"}]}}]}""",
            chunk("jour", "stop"), "[DONE]"
        )
        val (text, pieces) = streamWith(MistralClient("$base/mistral/v1", key, "mistral-large-latest"))
        assertEquals("Bonjour", text)
        assertEquals(listOf("Bon", "jour"), pieces)
        assertChatRequest("/mistral/v1/chat/completions", "mistral-large-latest", json = true)
    }

    @Test
    fun groqStreams() {
        routes["/groq/openai/v1/chat/completions"] = sse(
            chunk("A"), chunk("B"),
            """{"choices":[{"delta":{},"finish_reason":"stop"}],"x_groq":{"usage":{"total_tokens":3}}}""", "[DONE]"
        )
        val (text, pieces) = streamWith(GroqClient("$base/groq/openai/v1", key, "llama-3.3-70b-versatile"))
        assertEquals("AB", text)
        assertEquals(listOf("A", "B"), pieces)
        assertChatRequest("/groq/openai/v1/chat/completions", "llama-3.3-70b-versatile", json = true)
    }

    @Test
    fun xaiStreamsAndSkipsReasoningContent() {
        routes["/xai/v1/chat/completions"] = sse(
            """{"choices":[{"delta":{"reasoning_content":"…"}}]}""", chunk("Grok"), chunk(" ok", "stop"), "[DONE]"
        )
        val (text, pieces) = streamWith(XaiClient("$base/xai/v1", key, "grok-4"))
        assertEquals("Grok ok", text)
        assertEquals(listOf("Grok", " ok"), pieces)
        assertChatRequest("/xai/v1/chat/completions", "grok-4", json = true)
    }

    @Test
    fun openRouterStreamsIgnoringKeepAliveComments() {
        routes["/openrouter/api/v1/chat/completions"] = sse(
            ": OPENROUTER PROCESSING", chunk("x"), """{"choices":[{"delta":{"reasoning":"r"}}]}""", chunk("y", "stop"), "[DONE]"
        )
        val (text, pieces) = streamWith(OpenRouterClient("$base/openrouter/api/v1", key, "openai/gpt-4o"))
        assertEquals("xy", text)
        assertEquals(listOf("x", "y"), pieces)
        assertChatRequest("/openrouter/api/v1/chat/completions", "openai/gpt-4o", json = true)
    }

    // ------------------------------------------------------------------ capabilities

    @Test
    fun openRouterModelWithoutJsonSupportGetsNoResponseFormat() = runBlocking {
        routes["/openrouter/api/v1/models"] = { send(it, 200, openRouterModels) }
        routes["/openrouter/api/v1/chat/completions"] = sse(chunk("{}", "stop"), "[DONE]")
        val client = OpenRouterClient("$base/openrouter/api/v1", key, "vendor/plain-model")
        client.listModels()
        client.stream(request) {}
        assertChatRequest("/openrouter/api/v1/chat/completions", "vendor/plain-model", json = false)
        // A model that does support it still gets JSON mode.
        OpenRouterClient("$base/openrouter/api/v1", key, "openai/gpt-4o").stream(request) {}
        assertChatRequest("/openrouter/api/v1/chat/completions", "openai/gpt-4o", json = true)
    }

    @Test
    fun jsonModeRejectionIsRetriedOnceWithTheSameModelAndRemembered() = runBlocking {
        var calls = 0
        routes["/groq/openai/v1/chat/completions"] = { ex ->
            calls++
            if (calls == 1) send(ex, 400, """{"error":{"message":"json mode is not supported for this model","type":"invalid_request_error","code":"json_validate_failed"}}""")
            else sse(chunk("ok", "stop"), "[DONE]")(ex)
        }
        val client = GroqClient("$base/groq/openai/v1", key, "model-without-json")
        assertEquals("ok", client.stream(request) {}.text)
        val bodies = chatCalls().map { bodyOf(it) }
        assertEquals(2, bodies.size)
        assertTrue(bodies[0].containsKey("response_format"))
        assertFalse(bodies[1].containsKey("response_format"))
        assertEquals(listOf("model-without-json", "model-without-json"), bodies.map { it["model"] })
        // Remembered: the next request does not repeat the failing call.
        client.stream(request) {}
        assertEquals(3, chatCalls().size)
        assertFalse(bodyOf(chatCalls().last()).containsKey("response_format"))
    }

    // ------------------------------------------------------------------ model listing + connection test over HTTP

    @Test
    fun listModelsUsesEachProvidersEndpoint() = runBlocking {
        routes["/deepseek/models"] = { send(it, 200, deepSeekModels) }
        routes["/mistral/v1/models"] = { send(it, 200, mistralModels) }
        routes["/groq/openai/v1/models"] = { send(it, 200, groqModels) }
        routes["/xai/v1/language-models"] = { send(it, 200, xaiLanguageModels) }
        routes["/openrouter/api/v1/models"] = { send(it, 200, openRouterModels) }
        assertEquals(2, DeepSeekClient("$base/deepseek", key, "").listModels()!!.size)
        assertEquals(1, MistralClient("$base/mistral/v1", key, "").listModels()!!.size)
        assertEquals(1, GroqClient("$base/groq/openai/v1", key, "").listModels()!!.size)
        assertEquals(listOf("grok-4-0709"), XaiClient("$base/xai/v1", key, "").listModels()!!.map { it.id })
        assertEquals(2, OpenRouterClient("$base/openrouter/api/v1", key, "").listModels()!!.size)
        assertTrue(requests.filter { it.path.endsWith("models") }.all { it.method == "GET" && it.auth == "Bearer $key" })
    }

    @Test
    fun xaiFallsBackToPlainModelListWhenLanguageModelsIsMissing() = runBlocking {
        routes["/xai/v1/models"] = { send(it, 200, """{"data":[{"id":"grok-3"}]}""") }
        assertEquals(listOf("grok-3"), XaiClient("$base/xai/v1", key, "").listModels()!!.map { it.id })
    }

    @Test
    fun connectionTestChecksTheModelIsOffered() = runBlocking {
        routes["/mistral/v1/models"] = { send(it, 200, mistralModels) }
        assertTrue(MistralClient("$base/mistral/v1", key, "mistral-large-latest").testConnection() >= 0)
        try {
            MistralClient("$base/mistral/v1", key, "invented-model").testConnection()
            fail("unknown model must fail")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.MODEL_NOT_FOUND, e.kind)
        }
        // Chat requests are never sent by a connection test.
        assertTrue(chatCalls().isEmpty())
    }

    @Test
    fun openRouterConnectionTestAuthenticatesBeforeReadingThePublicCatalog() = runBlocking {
        routes["/openrouter/api/v1/key"] = { send(it, 200, """{"data":{"label":"k","usage":0}}""") }
        routes["/openrouter/api/v1/models"] = { send(it, 200, openRouterModels) }
        OpenRouterClient("$base/openrouter/api/v1", key, "openai/gpt-4o:free").testConnection() // variant of a listed id
        assertEquals(listOf("/openrouter/api/v1/key", "/openrouter/api/v1/models"), requests.map { it.path })

        routes["/openrouter/api/v1/key"] = { send(it, 401, """{"error":{"message":"No auth credentials found","code":401}}""") }
        try {
            OpenRouterClient("$base/openrouter/api/v1", key, "openai/gpt-4o").testConnection()
            fail("bad key must fail")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.INVALID_API_KEY, e.kind)
        }
    }

    // ------------------------------------------------------------------ errors

    @Test
    fun providerErrorsAreMappedAndNeverContainTheKey() = runBlocking {
        routes["/groq/openai/v1/chat/completions"] = { ex ->
            send(ex, 429, """{"error":{"message":"Rate limit reached for $key","type":"tokens"}}""", headers = mapOf("retry-after" to "120"))
        }
        try {
            GroqClient("$base/groq/openai/v1", key, "llama-3.3-70b-versatile").stream(request) {}
            fail()
        } catch (e: AiException) {
            assertEquals(AiErrorKind.RATE_LIMITED, e.kind)
            assertFalse(e.message!!.contains(key))
        }
        // A retry-after longer than 20 s is not waited for: exactly one request.
        assertEquals(1, chatCalls().size)

        routes["/deepseek/chat/completions"] = { send(it, 402, """{"error":{"message":"Insufficient Balance"}}""") }
        try {
            DeepSeekClient("$base/deepseek", key, "deepseek-chat").stream(request) {}
            fail()
        } catch (e: AiException) {
            assertEquals(AiErrorKind.QUOTA_OR_BILLING, e.kind)
        }

        routes["/openrouter/api/v1/chat/completions"] = { send(it, 403, """{"error":{"message":"Input was flagged by moderation","code":403}}""") }
        try {
            OpenRouterClient("$base/openrouter/api/v1", key, "openai/gpt-4o").stream(request) {}
            fail()
        } catch (e: AiException) {
            assertEquals(AiErrorKind.CONTENT_BLOCKED, e.kind)
        }
    }

    @Test
    fun midStreamProviderErrorEndsTheAnswer() {
        routes["/openrouter/api/v1/chat/completions"] = sse(
            chunk("partial"),
            """{"error":{"code":502,"message":"Upstream provider error"},"choices":[{"delta":{"content":""},"finish_reason":"error"}]}"""
        )
        val pieces = mutableListOf<String>()
        try {
            runBlocking { OpenRouterClient("$base/openrouter/api/v1", key, "openai/gpt-4o").stream(request) { pieces += it } }
            fail()
        } catch (e: AiException) {
            assertEquals(AiErrorKind.PROVIDER_UNAVAILABLE, e.kind)
        }
        assertEquals(listOf("partial"), pieces)
    }

    @Test
    fun reasoningOnlyAnswerCutByTheLimitIsReportedAsTruncated() {
        routes["/deepseek/chat/completions"] = sse(
            """{"choices":[{"delta":{"reasoning_content":"long thoughts"},"finish_reason":"length"}]}""", "[DONE]"
        )
        try {
            runBlocking { DeepSeekClient("$base/deepseek", key, "deepseek-reasoner").stream(request) {} }
            fail()
        } catch (e: AiException) {
            assertEquals(AiErrorKind.TRUNCATED_RESPONSE, e.kind)
        }
    }

    // ------------------------------------------------------------------ cancellation (Stop button)

    @Test
    fun cancellingStopsEveryNewProviderAndSendsNoFurtherRequest() = runBlocking {
        val prefixes = mapOf(
            AiProviderType.DEEPSEEK to "/deepseek",
            AiProviderType.MISTRAL to "/mistral/v1",
            AiProviderType.GROQ to "/groq/openai/v1",
            AiProviderType.XAI to "/xai/v1",
            AiProviderType.OPENROUTER to "/openrouter/api/v1"
        )
        for ((type, prefix) in prefixes) {
            val closed = CountDownLatch(1)
            routes["$prefix/chat/completions"] = { ex ->
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                val out = ex.responseBody
                try {
                    out.write("data: ${chunk("first")}\n\n".toByteArray()); out.flush()
                    repeat(200) { Thread.sleep(50); out.write(": keep-alive\n\n".toByteArray()); out.flush() }
                } catch (e: IOException) {
                    closed.countDown()
                } finally {
                    ex.close()
                }
            }
            val before = requests.size
            val pieces = Collections.synchronizedList(mutableListOf<String>())
            val first = CountDownLatch(1)
            val client = AiProviderFactory.createClient(type, "$base$prefix", key, "m")
            val job = async(Dispatchers.IO) { client.stream(request) { pieces += it; first.countDown() } }
            assertTrue("$type: first piece", first.await(5, TimeUnit.SECONDS))
            job.cancel()
            try {
                job.await()
                fail("$type: expected cancellation")
            } catch (e: CancellationException) {
                // a user stop, not a network error
            }
            assertTrue("$type: connection not closed", closed.await(5, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertEquals("$type: pieces after cancel", listOf("first"), pieces.toList())
            assertEquals("$type: extra requests after cancel", before + 1, requests.size)
        }
    }

    // ------------------------------------------------------------------ existing providers unchanged

    @Test
    fun openAiRegressionStillRetrievesTheModelAndUsesJsonMode() = runBlocking {
        routes["/openai/v1/models/gpt-test"] = { send(it, 200, """{"id":"gpt-test"}""") }
        routes["/openai/v1/chat/completions"] = sse(chunk("ok", "stop"), "[DONE]")
        val client = OpenAiClient(AiProviderType.OPENAI, "$base/openai/v1", key, "gpt-test")
        client.testConnection()
        assertEquals("/openai/v1/models/gpt-test", requests.last().path)
        client.stream(request) {}
        assertChatRequest("/openai/v1/chat/completions", "gpt-test", json = true)
        // Generic OpenAI-compatible endpoints still never get JSON mode.
        routes["/compat/v1/chat/completions"] = sse(chunk("ok", "stop"), "[DONE]")
        OpenAiClient(AiProviderType.OPENAI_COMPATIBLE, "$base/compat/v1", key, "m").stream(request) {}
        assertChatRequest("/compat/v1/chat/completions", "m", json = false)
    }
}
