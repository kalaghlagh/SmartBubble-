package com.example.diagnostics

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash in the app's private files, so it can be shown and copied
 * on the next launch without a computer or adb. The report holds the exception (class, message,
 * stack frames), the build, the Android version and the device model. Key-looking tokens are
 * removed (see [redact]); an exception message can still quote a few words of the content that
 * was being processed, which the dialog says. It never leaves the device unless the user copies it.
 */
object CrashLog {
    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { write(app, report(thread.name, error)) } catch (_: Throwable) { /* never hide the original crash */ }
            previous?.uncaughtException(thread, error)
        }
    }

    fun report(threadName: String, error: Throwable): String = buildString {
        append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())).append('\n')
        append("App: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(") commit ").append(BuildConfig.SOURCE_COMMIT).append('\n')
        append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("Thread: ").append(threadName).append("\n\n")
        append(redact(error.stackTraceToString()).take(MAX_TRACE))
    }

    private const val MAX_TRACE = 24_000

    /**
     * Exception messages can quote what was being processed (an HTTP error body, a file name, a
     * line of text), so key-looking tokens and key/token parameters are replaced before the report
     * is stored or shown. Class names, messages and stack frames stay: they locate the defect.
     */
    fun redact(text: String): String = text
        .replace(Regex("""(?i)\bbearer\s+[^\s"',;]{6,}"""), "Bearer ***")
        .replace(Regex("""(?i)\b(key|api[_-]?key|token|access_token|authorization|bearer|password|secret)(["']?\s*[:=]\s*["']?|\s+)[^\s&"',;]{6,}"""), "$1$2***")
        .replace(Regex("""\b(sk-[A-Za-z0-9_\-]{8,}|sk-ant-[A-Za-z0-9_\-]{8,}|AIza[0-9A-Za-z_\-]{20,}|gsk_[A-Za-z0-9]{16,}|xai-[A-Za-z0-9]{16,}|hf_[A-Za-z0-9]{16,})"""), "***")

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)

    fun write(context: Context, text: String) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun read(context: Context): String? = try { file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    fun clear(context: Context) { try { file(context).delete() } catch (_: Exception) { } }
}
