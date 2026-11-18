package com.example.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Window width / height classes (Material breakpoints: 600 and 840 dp wide, 480 and 900 dp high). */
enum class WindowClass { COMPACT, MEDIUM, EXPANDED }

/**
 * Layout rules of the app, from the space actually available to a layout (window or container
 * size in dp, after system bars, cutouts and keyboard) and the user's font scale; never from a
 * device model. Pure functions: tested on the JVM.
 */
object AdaptiveRules {
    fun widthClass(widthDp: Float): WindowClass = when {
        widthDp < 600f -> WindowClass.COMPACT
        widthDp < 840f -> WindowClass.MEDIUM
        else -> WindowClass.EXPANDED
    }

    fun heightClass(heightDp: Float): WindowClass = when {
        heightDp < 480f -> WindowClass.COMPACT
        heightDp < 900f -> WindowClass.MEDIUM
        else -> WindowClass.EXPANDED
    }

    /** Narrowest cell of an action grid at font scale 1 (its label wraps on up to three lines). */
    const val MIN_ACTION_CELL_DP = 90f
    const val ACTION_GAP_DP = 6f

    /**
     * Columns of a grid of [count] actions in [availableWidthDp]: as many cells of at least
     * [MIN_ACTION_CELL_DP] (wider with larger text, up to twice) as fit, at least 1, at most [count].
     */
    fun actionColumns(availableWidthDp: Float, fontScale: Float, count: Int): Int {
        if (count <= 0) return 1
        val cell = MIN_ACTION_CELL_DP * fontScale.coerceIn(1f, 2f)
        val fit = ((availableWidthDp + ACTION_GAP_DP) / (cell + ACTION_GAP_DP)).toInt()
        return fit.coerceIn(1, count)
    }

    /**
     * Content beside its actions (not above them) when the space is short and wide enough for
     * both side by side: a phone in landscape, a short window.
     */
    fun actionsBeside(widthDp: Float, heightDp: Float): Boolean = heightDp < 480f && widthDp >= 560f

    /**
     * A one-line action bar (a label and two buttons) needs about 340 dp at font scale 1; with
     * less width for its text (narrow window or larger text) the buttons go on their own line.
     */
    fun actionBarStacked(widthDp: Float, fontScale: Float): Boolean = widthDp / fontScale.coerceAtLeast(1f) < 340f

    /** Even side by side the two buttons would be too narrow for their labels: one per line, full width. */
    fun actionBarButtonsInColumn(widthDp: Float, fontScale: Float): Boolean = widthDp / fontScale.coerceAtLeast(1f) < 240f

    /**
     * Height the content keeps under (or beside) an action area: a third of the space, at least
     * 120 dp. The actions get the rest and scroll inside it when they need more.
     */
    fun minContentHeightDp(heightDp: Float): Float = maxOf(120f, heightDp * 0.35f).coerceAtMost(heightDp)
}

/** One action of an [AdaptiveActionGrid]. */
data class AdaptiveAction(
    val tag: String,
    val label: String,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Actions as a grid that adapts to the width it gets: equal-width cells, as many per row as fit
 * ([AdaptiveRules.actionColumns]), labels wrapping (never cut, never pushed off screen), 48 dp
 * minimum touch height. Never a row that scrolls sideways.
 */
@Composable
fun AdaptiveActionGrid(actions: List<AdaptiveAction>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = AdaptiveRules.actionColumns(maxWidth.value, LocalDensity.current.fontScale, actions.size)
        Column(verticalArrangement = Arrangement.spacedBy(AdaptiveRules.ACTION_GAP_DP.dp)) {
            actions.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(AdaptiveRules.ACTION_GAP_DP.dp)) {
                    row.forEach { a ->
                        val m = Modifier.weight(1f).heightIn(min = 48.dp).testTag(a.tag)
                        val pad = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        val label: @Composable () -> Unit = {
                            Text(a.label, style = MaterialTheme.typography.labelLarge, maxLines = 3, textAlign = TextAlign.Center)
                        }
                        if (a.primary) Button(onClick = a.onClick, enabled = a.enabled, modifier = m, contentPadding = pad) { label() }
                        else OutlinedButton(onClick = a.onClick, enabled = a.enabled, modifier = m, contentPadding = pad) { label() }
                    }
                    // Cells keep the same width on a last, shorter row.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Content with its actions, sharing the space deliberately: the content takes what is left
 * (it scrolls itself, e.g. a LazyColumn) and always keeps [AdaptiveRules.minContentHeightDp];
 * the actions sit under it, or beside it when the space is short and wide
 * ([AdaptiveRules.actionsBeside]), and scroll vertically inside their area when they need more
 * room (large text) so that every action stays reachable.
 */
@Composable
fun ContentWithActions(
    modifier: Modifier = Modifier,
    actionsTag: String = "adaptive_actions",
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val h = maxHeight
        val w = maxWidth
        val panelColor = MaterialTheme.colorScheme.surfaceContainer
        if (AdaptiveRules.actionsBeside(w.value, h.value)) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                Box(
                    Modifier.width((w * 0.4f).coerceAtMost(360.dp)).fillMaxHeight().background(panelColor).testTag(actionsTag)
                        .verticalScroll(rememberScrollState()).padding(8.dp)
                ) { actions() }
            }
        } else {
            val maxActions: Dp = h - AdaptiveRules.minContentHeightDp(h.value).dp
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                Box(
                    Modifier.fillMaxWidth().heightIn(max = maxActions).background(panelColor).testTag(actionsTag)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)
                ) { actions() }
            }
        }
    }
}

/**
 * A full-screen dialog laid out the same way on every Android version: edge to edge
 * (decorFitsSystemWindows = false, as Android 15+ enforces for apps targeting SDK 35+), its
 * content kept inside the safe area (status bar, navigation bar, display cutout and keyboard)
 * and the status bar area painted with [topColor] (the screen's top bar colour).
 */
@Composable
fun FullScreenDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnClickOutside: Boolean = true,
    topColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest, properties = fullScreenDialogProperties(dismissOnClickOutside)) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(topColor))
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                    content = content)
            }
        }
    }
}

/** Properties of the app's full-screen dialogs (also for Scaffold-based ones, with safeDrawing content insets). */
fun fullScreenDialogProperties(dismissOnClickOutside: Boolean = true) = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
    dismissOnClickOutside = dismissOnClickOutside
)
