package com.example

import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import com.example.ai.providers.AiHttp
import com.example.ai.providers.AiMessage
import com.example.ai.providers.AiProviderType
import com.example.ai.providers.AiRequest
import com.example.ai.providers.AiRole
import com.example.ai.providers.AnthropicClient
import com.example.ai.providers.GeminiClient
import com.example.ai.providers.Json
import com.example.ai.providers.OpenAiClient
import com.example.ai.providers.ProviderField
import com.example.ai.providers.ProviderInput
import com.example.ai.providers.ProviderValidator
import com.example.ai.providers.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure JVM tests of request construction, response parsing and error mapping (no network). */
class AiProviderClientsTest {

    private val request = AiRequest(
        system = "You are helpful.",
        messages = listOf(
            AiMessage(AiRole.ASSISTANT, "leading assistant turn is dropped"),
            AiMessage(AiRole.USER, "مرحبا"),
            AiMessage(AiRole.USER, "second user line"),
            AiMessage(AiRole.ASSISTANT, "Hi!"),
            AiMessage(AiRole.USER, "Write a report")
        ),
        expectJson = true,
        maxOutputTokens = 4000
    )

    private inline fun expectError(kind: AiErrorKind, block: () -> Unit) {
        try {
            block()
            fail("Expected AiException($kind)")
        } catch (e: AiException) {
            assertEquals(kind, e.kind)
        }
    }

    // ---------------------------------------------------------------- normalization

    @Test
    fun conversationIsNormalizedToStartWithUserAndAlternate() {
        val normalized = AiHttp.normalize(request.messages)
        assertEquals(listOf(AiRole.USER, AiRole.ASSISTANT, AiRole.USER), normalized.map { it.role })
        assertEquals("مرحبا\n\nsecond user line", normalized.first().text)
    }

    @Test
    fun conversationWithoutUserMessageIsRejected() {
        expectError(AiErrorKind.BAD_REQUEST) { AiHttp.normalize(listOf(AiMessage(AiRole.ASSISTANT, "x"))) }
    }

    // ---------------------------------------------------------------- OpenAI

    @Test
    fun openAiBodyHasSystemMessagesAndJsonMode() {
        val body = OpenAiClient.buildChatBody("gpt-test", request, useJsonMode = true)
        assertEquals("gpt-test", body["model"])
        @Suppress("UNCHECKED_CAST")
        val messages = body["messages"] as List<Map<String, Any?>>
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it["role"] })
        assertEquals(mapOf("type" to "json_object"), body["response_format"])
        assertFalse("no sampling params", body.containsKey("temperature"))
        // Serializes to valid JSON with Arabic preserved.
        val json = Json.write(body)
        assertTrue(json.contains("مرحبا"))
    }

    @Test
    fun openAiResponseIsParsed() {
        val r = OpenAiClient.parseChatResponse(
            """{"choices":[{"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}]}"""
        )
        assertEquals("Hello", r.text)
        assertFalse(r.truncated)
        assertTrue(OpenAiClient.parseChatResponse(
            """{"choices":[{"message":{"content":"Partial"},"finish_reason":"length"}]}"""
        ).truncated)
    }

    @Test
    fun openAiEmptyRefusalAndMalformedAreErrors() {
        expectError(AiErrorKind.EMPTY_RESPONSE) { OpenAiClient.parseChatResponse("""{"choices":[]}""") }
        expectError(AiErrorKind.CONTENT_BLOCKED) {
            OpenAiClient.parseChatResponse("""{"choices":[{"message":{"content":null,"refusal":"no"},"finish_reason":"stop"}]}""")
        }
        expectError(AiErrorKind.MALFORMED_RESPONSE) { OpenAiClient.parseChatResponse("<html>") }
    }

    @Test
    fun openAiBaseUrlNormalization() {
        assertEquals("https://api.openai.com/v1", OpenAiClient.normalizeBaseUrl("https://api.openai.com/v1/chat/completions/"))
    }

    // ---------------------------------------------------------------- Anthropic

    @Test
    fun anthropicBodyUsesSystemFieldMaxTokensAndNoSampling() {
        val body = AnthropicClient.buildMessagesBody("claude-test", request)
        assertEquals("claude-test", body["model"])
        assertEquals(4000, body["max_tokens"])
        assertEquals("You are helpful.", body["system"])
        @Suppress("UNCHECKED_CAST")
        val messages = body["messages"] as List<Map<String, Any?>>
        assertEquals("user", messages.first()["role"])
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        assertEquals("2026-10-21", AnthropicClient.API_VERSION)
    }

    @Test
    fun anthropicResponseSkipsThinkingBlocks() {
        val r = AnthropicClient.parseMessagesResponse(
            """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"Bonjour"}],"stop_reason":"end_turn"}"""
        )
        assertEquals("Bonjour", r.text)
        assertTrue(AnthropicClient.parseMessagesResponse(
            """{"content":[{"type":"text","text":"cut"}],"stop_reason":"max_tokens"}"""
        ).truncated)
    }

    @Test
    fun anthropicRefusalAndEmptyAreErrors() {
        expectError(AiErrorKind.CONTENT_BLOCKED) {
            AnthropicClient.parseMessagesResponse("""{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","explanation":"x"}}""")
        }
        expectError(AiErrorKind.TRUNCATED_RESPONSE) {
            AnthropicClient.parseMessagesResponse("""{"content":[{"type":"thinking","thinking":""}],"stop_reason":"max_tokens"}""")
        }
    }

    @Test
    fun anthropicBaseUrlNormalization() {
        assertEquals("https://api.anthropic.com", AnthropicClient.normalizeBaseUrl("https://api.anthropic.com/v1"))
        assertEquals("https://api.anthropic.com", AnthropicClient.normalizeBaseUrl("https://api.anthropic.com/v1/messages"))
    }

    // ---------------------------------------------------------------- Gemini

    @Test
    fun geminiBodyUsesContentsSystemInstructionAndJsonMime() {
        val body = GeminiClient.buildGenerateBody(request)
        @Suppress("UNCHECKED_CAST")
        val contents = body["contents"] as List<Map<String, Any?>>
        assertEquals(listOf("user", "model", "user"), contents.map { it["role"] })
        assertTrue(body.containsKey("systemInstruction"))
        assertEquals(mapOf("responseMimeType" to "application/json"), body["generationConfig"])
        assertEquals("gemini-x", GeminiClient.normalizeModel("models/gemini-x"))
        assertEquals("https://generativelanguage.googleapis.com", GeminiClient.normalizeBaseUrl("https://generativelanguage.googleapis.com/v1beta/"))
    }

    @Test
    fun geminiResponseParsingAndBlocking() {
        val r = GeminiClient.parseGenerateResponse(
            """{"candidates":[{"content":{"parts":[{"text":"think","thought":true},{"text":"Answer"}]},"finishReason":"STOP"}]}"""
        )
        assertEquals("Answer", r.text)
        expectError(AiErrorKind.CONTENT_BLOCKED) {
            GeminiClient.parseGenerateResponse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        }
        expectError(AiErrorKind.CONTENT_BLOCKED) {
            GeminiClient.parseGenerateResponse("""{"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}""")
        }
    }

    // ---------------------------------------------------------------- error mapping / security

    @Test
    fun httpErrorsMapToKinds() {
        assertEquals(AiErrorKind.INVALID_API_KEY, AiHttp.mapHttpError(401, """{"error":{"message":"bad key"}}""", null).kind)
        assertEquals(AiErrorKind.INVALID_API_KEY, AiHttp.mapHttpError(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""", null).kind)
        assertEquals(AiErrorKind.MODEL_NOT_FOUND, AiHttp.mapHttpError(404, """{"type":"error","error":{"type":"not_found_error","message":"model: x"}}""", null).kind)
        assertEquals(AiErrorKind.RATE_LIMITED, AiHttp.mapHttpError(429, "{}", null).kind)
        assertEquals(AiErrorKind.QUOTA_OR_BILLING, AiHttp.mapHttpError(429, """{"error":{"message":"You exceeded your current quota","code":"insufficient_quota"}}""", null).kind)
        assertEquals(AiErrorKind.PROVIDER_UNAVAILABLE, AiHttp.mapHttpError(529, "{}", null).kind)
        assertEquals(AiErrorKind.PERMISSION_DENIED, AiHttp.mapHttpError(403, "{}", null).kind)
    }

    @Test
    fun errorDetailsNeverContainTheApiKey() {
        val key = "sk-secret-1234567890abcdef"
        val e = AiHttp.mapHttpError(400, """{"error":{"message":"Invalid key $key for url ?key=$key"}}""", key)
        assertFalse(e.detail!!.contains(key))
        assertFalse(e.message!!.contains(key))
        assertFalse(Redactor.redact("AIzaSyA1234567890abcdefghijklmnop leaked").contains("AIzaSyA1234567890"))
    }

    @Test
    fun providerValidation() {
        val ok = ProviderInput("Mine", AiProviderType.ANTHROPIC, "", "claude-x", "key")
        assertTrue(ProviderValidator.validate(ok, requireKey = true).isEmpty())
        val bad = ProviderInput(" ", AiProviderType.OPENAI_COMPATIBLE, "http://insecure.example", "", "")
        assertEquals(
            setOf(ProviderField.NAME, ProviderField.MODEL, ProviderField.BASE_URL, ProviderField.API_KEY),
            ProviderValidator.validate(bad, requireKey = true)
        )
        // Editing with a stored key: an empty key field is allowed (keeps the stored key).
        assertTrue(ProviderValidator.validate(ok.copy(apiKey = ""), requireKey = false).isEmpty())
    }

    @Test
    fun legacyProviderTypesMap() {
        assertEquals(AiProviderType.OPENAI_COMPATIBLE, AiProviderType.fromStorageKey("Custom"))
        assertEquals(AiProviderType.ANTHROPIC, AiProviderType.fromStorageKey("Anthropic Claude"))
        assertNull(Json.extractObject("no json here"))
    }
}
