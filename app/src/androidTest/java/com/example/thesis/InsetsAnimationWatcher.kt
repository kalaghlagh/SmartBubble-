package com.example.thesis

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Waits for the soft keyboard to stop moving before a test reads the semantics tree. Compose
 * tests read semantics on the instrumentation thread once the UI is idle, but the keyboard's
 * insets animation is driven by the platform frame by frame and is not pending work for Compose:
 * a read between two of its frames can overlap the main thread laying out the next one ("Detected
 * multithreaded access to SnapshotStateObserver", run #83). Animations are counted on the main
 * thread by a WindowInsetsAnimation callback on the content view (dispatch continues to the
 * Compose view, which animates WindowInsets.ime as before); only main-thread values are read.
 */
class InsetsAnimationWatcher(private val compose: AndroidComposeTestRule<*, out ComponentActivity>) {
    private val running = AtomicInteger()
    private var watched: View? = null

    private fun watch() = compose.runOnUiThread {
        val content = compose.activity.findViewById<View>(android.R.id.content)
        if (watched === content) return@runOnUiThread
        watched = content
        running.set(0)
        ViewCompat.setWindowInsetsAnimationCallback(content, object : WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) { running.incrementAndGet() }
            override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>) = insets
            override fun onEnd(animation: WindowInsetsAnimationCompat) { running.updateAndGet { maxOf(0, it - 1) } }
        })
    }

    fun imeShown(): Boolean {
        var shown = false
        compose.runOnUiThread {
            shown = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    /** No insets animation running (and the keyboard shown, when [shown]); then Compose idle. */
    fun awaitSettled(step: String, shown: Boolean = false) {
        watch()
        try {
            compose.waitUntil(20_000) { running.get() == 0 && (!shown || imeShown()) }
        } catch (e: Throwable) {
            throw AssertionError("Keyboard still moving: $step (animations=${running.get()}, ime=${imeShown()})", e)
        }
        compose.waitForIdle()
    }
}
