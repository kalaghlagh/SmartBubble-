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
 * on the next launch without a computer or adb. The report holds the exception, the build and the
 * Android version only: no user text, no keys. It never leaves the device unless the user copies it.
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
        append(error.stackTraceToString())
    }

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)

    fun write(context: Context, text: String) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun read(context: Context): String? = try { file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }

    fun clear(context: Context) { try { file(context).delete() } catch (_: Exception) { } }
}
