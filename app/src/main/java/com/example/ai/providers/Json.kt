package com.example.ai.providers

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi

/**
 * Minimal untyped JSON helpers on top of Moshi (already a project dependency). Objects are
 * Map<String, Any?>, arrays are List<Any?>, numbers are Double. Pure JVM, so request builders
 * and response parsers are unit-testable without Android.
 */
object Json {
    private val adapter: JsonAdapter<Any> = Moshi.Builder().build().adapter(Any::class.java)

    fun write(value: Any): String = adapter.toJson(value)

    /** Parses [text]; returns null when it is not valid JSON. */
    fun parse(text: String): Any? = try {
        adapter.fromJson(text)
    } catch (e: Exception) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(value: Any?): List<Any?>? = value as? List<Any?>

    fun str(value: Any?): String? = value as? String

    /** Extracts the first JSON object from model output (handles ``` fences and surrounding prose). */
    fun extractObject(text: String): Map<String, Any?>? {
        val trimmed = text.trim()
        obj(parse(trimmed))?.let { return it }
        val fence = Regex("""```(?:json)?\s*([\s\S]*?)```""").find(trimmed)
        if (fence != null) obj(parse(fence.groupValues[1].trim()))?.let { return it }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start in 0 until end) obj(parse(trimmed.substring(start, end + 1)))?.let { return it }
        return null
    }
}
