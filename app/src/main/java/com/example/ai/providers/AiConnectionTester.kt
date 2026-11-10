package com.example.ai.providers

import com.example.core.security.KeystoreHelper
import com.example.data.model.ProviderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ConnectionResult(
    val success: Boolean,
    val latencyMs: Long = 0,
    val message: String
)

class AiConnectionTester {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(provider: ProviderEntity): ConnectionResult = withContext(Dispatchers.IO) {
        val apiKey = KeystoreHelper.decrypt(provider.encryptedApiKey)
        if (apiKey.isBlank()) {
            return@withContext ConnectionResult(
                success = false,
                message = "Missing API Key: Configure key in settings"
            )
        }

        val startTime = System.currentTimeMillis()
        try {
            val request = when (provider.providerType) {
                "Google Gemini" -> {
                    val url = if (provider.baseUrl.contains("googleapis.com")) {
                        "${provider.baseUrl.trimEnd('/')}/models?key=$apiKey"
                    } else {
                        "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                    }
                    Request.Builder().url(url).get().build()
                }
                "Anthropic Claude" -> {
                    val url = if (provider.baseUrl.isNotBlank()) provider.baseUrl else "https://api.anthropic.com/v1/messages"
                    Request.Builder()
                        .url(url)
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .head()
                        .build()
                }
                else -> {
                    // OpenAI or OpenAI-compatible
                    val url = if (provider.baseUrl.isNotBlank()) {
                        "${provider.baseUrl.trimEnd('/')}/models"
                    } else {
                        "https://api.openai.com/v1/models"
                    }
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $apiKey")
                        .get()
                        .build()
                }
            }

            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    ConnectionResult(
                        success = true,
                        latencyMs = latency,
                        message = "Connected (${latency}ms)"
                    )
                } else {
                    val code = response.code
                    val errMsg = when (code) {
                        401 -> "Unauthorized (401): Invalid API Key"
                        403 -> "Forbidden (403): Access denied"
                        404 -> "Endpoint not found (404)"
                        429 -> "Rate Limited (429): Quota exceeded"
                        else -> "HTTP Error $code"
                    }
                    ConnectionResult(
                        success = false,
                        latencyMs = latency,
                        message = errMsg
                    )
                }
            }
        } catch (e: Exception) {
            ConnectionResult(
                success = false,
                message = "Connection failed: ${e.message?.take(50) ?: "Network unreachable"}"
            )
        }
    }
}
