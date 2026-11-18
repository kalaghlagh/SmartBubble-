package com.example.thesis

import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Waits for the soft keyboard to stop moving before a test reads the semantics tree. Compose
 * tests read semantics on the instrumentation thread once the UI is idle, but the keyboard's
 * insets animation is driven by the platform frame by frame and is not pending work for Compose:
 * a read between two of its frames can overlap the main thread laying out the next one ("Detected
 * multithreaded access to SnapshotStateObserver", run #83). Animations are counted on the main
 * thread by a WindowInsetsAnimation callback on the root view of every window of the app (the
 * activity and its full-screen dialogs, which are edge to edge; dispatch continues to the Compose
 * views below, which animate their insets as before). Only main-thread values are read.
 */
class InsetsAnimationWatcher(private val compose: AndroidComposeTestRule<*, out ComponentActivity>) {
    private val running = AtomicInteger()
    private val watched: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    /** Root views of the app's windows (activity and dialogs), on the main thread. */
    private fun roots(): List<View> =
        if (Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }
        else listOf(compose.activity.window.decorView)

    private fun watch() = compose.runOnUiThread {
        for (root in roots()) {
            if (!watched.add(root)) continue
            ViewCompat.setWindowInsetsAnimationCallback(root, object : WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) { running.incrementAndGet() }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>) = insets
                override fun onEnd(animation: WindowInsetsAnimationCompat) { running.updateAndGet { maxOf(0, it - 1) } }
            })
        }
    }

    /** Bottom insets in pixels, largest over the app's windows: navigation bar and keyboard. */
    data class Bottoms(val navigation: Int, val ime: Int)

    fun bottoms(): Bottoms {
        var nav = 0; var ime = 0
        compose.runOnUiThread {
            for (root in roots()) {
                val i = ViewCompat.getRootWindowInsets(root) ?: continue
                nav = maxOf(nav, i.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                if (i.isVisible(WindowInsetsCompat.Type.ime())) ime = maxOf(ime, i.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            }
        }
        return Bottoms(nav, ime)
    }

    fun imeShown(): Boolean = bottoms().ime > 0

    /** No insets animation running (and the keyboard shown, when [shown]); then Compose idle. */
    fun awaitSettled(step: String, shown: Boolean = false) {
        watch()
        try {
            compose.waitUntil(20_000) { watch(); running.get() == 0 && (!shown || imeShown()) }
        } catch (e: Throwable) {
            throw AssertionError("Keyboard still moving: $step (animations=${running.get()}, ime=${imeShown()})", e)
        }
        compose.waitForIdle()
    }
}
