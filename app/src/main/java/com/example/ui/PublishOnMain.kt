package com.example.ui

import androidx.compose.runtime.ProduceStateScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sets a produceState value on the main thread. A load that switches to Dispatchers.IO resumes
 * where the composition's effect dispatcher puts it; under the Compose test harness that was a
 * background worker (ThesisAndroidTest run #43: "returned ... on DefaultDispatcher-worker-5"), the
 * state write came from that thread and the dialog intermittently stayed on its spinner although
 * the data had arrived. On a device the write already happens on the main thread, so
 * Main.immediate adds no dispatch there.
 */
suspend fun <T> ProduceStateScope<T>.publishOnMain(result: T) {
    withContext(Dispatchers.Main.immediate) { value = result }
}
