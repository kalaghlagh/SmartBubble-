package com.example.thesis.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.pdf.PdfPageRenderer

/** What is known of a page once its text was extracted. */
enum class PdfPageMark { EXTRACTED, CHECK }

/**
 * The PDF at [uri] opened for viewing: null while it opens, a failure when it cannot be
 * displayed. The renderer is closed when the caller leaves the composition (or [uri] changes).
 */
@Composable
internal fun rememberPdfPages(uri: Uri): Result<PdfPageRenderer>? {
    val context = LocalContext.current
    val opened by produceState<Result<PdfPageRenderer>?>(null, uri) {
        val r = PdfPageRenderer.open(context, uri)
        value = if (r != null) Result.success(r) else Result.failure(IllegalStateException("unreadable"))
    }
    val renderer = opened?.getOrNull()
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    return opened
}

/** File name of [uri] as the picker shows it, or null. */
@Composable
private fun rememberDisplayName(uri: Uri): String? {
    val context = LocalContext.current
    val name by produceState<String?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (uri.scheme == "file") uri.lastPathSegment
                else context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (e: Exception) { null }
        }
    }
    return name
}

/**
 * The PDF page viewer on its own (as in the Thesis editor's extraction layer): the real pages of
 * [uri], several of which can be selected ([selected], [onToggle]); the floating bar's Next
 * ([onNext]) goes on with them, its Cancel ([onCancel]) clears the selection.
 */
@Composable
fun PdfPageViewer(uri: Uri, title: String, marks: Map<Int, PdfPageMark>, selected: Set<Int>, onToggle: (Int) -> Unit, onNext: () -> Unit, onClose: () -> Unit, onCancel: () -> Unit = {}) {
    val opened = rememberPdfPages(uri)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        PdfPagesPane(opened, title, marks, selected, onToggle, onNext, onClose, onCancel)
    }
}

/**
 * Step 1 of a PDF extraction: the PDF itself, page after page (in order, at the screen's width,
 * their proportions kept), drawn only when shown. Tapping a page selects it, tapping it again
 * deselects it; any pages can be selected, consecutive or not. Nothing is extracted on a tap.
 * Once a page is selected, a floating bar appears at the bottom, over the pages and never inside
 * the scrolling list: how many pages are selected, Cancel ([onCancel]: clears the selection,
 * nothing is extracted, the viewer stays) and Next ([onNext]: goes on with them). It disappears
 * again when no page is selected. The top bar's back arrow ([onClose]) leaves the viewer.
 * [marks] puts the extraction state of pages already extracted on them.
 */
@Composable
internal fun PdfPagesPane(
    opened: Result<PdfPageRenderer>?, title: String, marks: Map<Int, PdfPageMark>, selected: Set<Int>,
    onToggle: (Int) -> Unit, onNext: () -> Unit, onClose: () -> Unit, onCancel: () -> Unit
) {
    Column(Modifier.fillMaxSize().testTag("pdf_viewer")) {
        BlueTopBar(title, onBack = onClose, backTag = "pdf_back")
        val renderer = opened?.getOrNull()
        when {
            opened == null -> Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.testTag("pdf_viewer_loading"))
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.dx_pdf_loading), style = MaterialTheme.typography.bodyMedium)
            }
            renderer == null -> Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.dx_pdf_open_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("pdf_viewer_error"))
            }
            else -> {
                val count = renderer.pageCount
                val chosen = selected.filter { it in 0 until count }
                Text(pluralStringResource(R.plurals.dx_pdf_page_count, count, count) + " · " + stringResource(R.string.dx_pdf_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("pdf_info"))
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val density = LocalDensity.current
                    val pageWidthPx = with(density) { (maxWidth - 32.dp).roundToPx() }
                    // Room under the last page for the floating bar (its measured height, one line
                    // or stacked), so no page stays under it.
                    var barHeight by remember { mutableStateOf(0.dp) }
                    LazyColumn(Modifier.fillMaxSize().testTag("pdf_pages"), state = rememberLazyListState(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + barHeight), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(count, key = { it }) { index ->
                            PdfPageItem(renderer, index, count, pageWidthPx, index in selected, marks[index]) { onToggle(index) }
                        }
                    }
                    PdfSelectionBar(chosen.size, onCancel, onNext,
                        Modifier.align(Alignment.BottomCenter).onSizeChanged { barHeight = with(density) { it.height.toDp() } })
                }
            }
        }
    }
}

/**
 * The floating bar of the page viewer: shown only while pages are selected ([count] > 0), at the
 * bottom of the viewer above the system navigation bar and the keyboard (safe-drawing insets not
 * already consumed by the screen around it), with the selected count, Cancel and Next. It adapts
 * to the width it gets and the font scale: one line; or the count on top and the two buttons
 * sharing the width below ([AdaptiveRules.actionBarStacked]); or, with very little width for
 * the text, each button on its own line ([AdaptiveRules.actionBarButtonsInColumn]).
 */
@Composable
internal fun PdfSelectionBar(count: Int, onCancel: () -> Unit, onNext: () -> Unit, modifier: Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = count > 0,
        modifier = modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut()
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth().testTag("pdf_action_bar")) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fontScale = LocalDensity.current.fontScale
                val stacked = com.example.ui.adaptive.AdaptiveRules.actionBarStacked(maxWidth.value, fontScale)
                val inColumn = com.example.ui.adaptive.AdaptiveRules.actionBarButtonsInColumn(maxWidth.value, fontScale)
                val label: @Composable (Modifier) -> Unit = { m ->
                    Text(stringResource(R.string.dx_pdf_selected_count, count), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = m.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.testTag("pdf_selected_label"))
                }
                val cancel: @Composable (Modifier) -> Unit = { m ->
                    OutlinedButton(onClick = onCancel, modifier = m.heightIn(min = 48.dp).testTag("pdf_cancel")) {
                        Text(stringResource(R.string.action_cancel), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                val next: @Composable (Modifier) -> Unit = { m ->
                    Button(onClick = onNext, modifier = m.heightIn(min = 48.dp).testTag("pdf_next")) {
                        Text(stringResource(R.string.dx_pdf_next), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                if (stacked) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    label(Modifier.padding(horizontal = 4.dp))
                    if (inColumn) { cancel(Modifier.fillMaxWidth()); next(Modifier.fillMaxWidth()) }
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cancel(Modifier.weight(1f)); next(Modifier.weight(1f))
                    }
                } else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    label(Modifier.weight(1f)); cancel(Modifier); next(Modifier)
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(renderer: PdfPageRenderer, index: Int, count: Int, widthPx: Int, isSelected: Boolean, mark: PdfPageMark?, onToggle: () -> Unit) {
    val (pw, ph) = renderer.pageSizes[index]
    // Drawn only while the page is composed (on screen), at the width it is shown; kept in the
    // renderer's bounded cache, so scrolling back (or selecting it) does not draw it again.
    val bitmap by produceState<Result<Bitmap>?>(null, renderer, index, widthPx) {
        val b = renderer.render(index, widthPx)
        value = if (b != null) Result.success(b) else Result.failure(IllegalStateException())
    }
    val description = if (isSelected) stringResource(R.string.dx_pdf_page_selected_desc, index + 1, count) else stringResource(R.string.dx_pdf_page_desc, index + 1, count)
    val actionLabel = stringResource(if (isSelected) R.string.dx_pdf_deselect_page else R.string.dx_pdf_select_page)
    val stateLabel = stringResource(if (isSelected) R.string.dx_pdf_selected else R.string.dx_pdf_not_selected)
    val outline = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Text(stringResource(R.string.dx_page_n, index + 1), style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (isSelected) {
                Spacer(Modifier.size(8.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(stringResource(R.string.dx_pdf_selected), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).testTag("pdf_selected_badge_$index"))
                }
            }
            Spacer(Modifier.weight(1f))
            when (mark) {
                PdfPageMark.EXTRACTED -> Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.dx_pdf_extracted),
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).testTag("pdf_mark_done_$index"))
                PdfPageMark.CHECK -> Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.dx_pdf_needs_review),
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp).testTag("pdf_mark_check_$index"))
                null -> {}
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(pw.toFloat() / ph.toFloat())
                .border(if (isSelected) 3.dp else 1.dp, outline, RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { selected = isSelected; stateDescription = stateLabel; contentDescription = description }
                .clickable(onClickLabel = actionLabel, role = Role.Checkbox, onClick = onToggle)
                .testTag("pdf_page_$index"),
            contentAlignment = Alignment.Center
        ) {
            val b = bitmap
            when {
                b == null -> CircularProgressIndicator(Modifier.size(28.dp))
                // Fit, never FillBounds: the outline padding makes the inner area a little off the
                // page's proportions, and the page must not be stretched to it.
                b.isSuccess -> Image(b.getOrThrow().asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(if (isSelected) 3.dp else 1.dp).testTag("pdf_page_image_$index"))
                else -> Text(stringResource(R.string.dx_pdf_page_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp).testTag("pdf_page_error_$index"))
            }
            // Selection mark in the page's corner (the outline alone is easy to miss).
            if (isSelected) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).testTag("pdf_check_$index")) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(4.dp))
            }
        }
    }
}

/**
 * Extraction from a PDF into the open section, in one window with two steps sharing the open
 * PDF: (1) the page viewer, where pages are selected; Next extracts them (in document order,
 * whatever the order they were selected in) with the shared extraction ([startExtraction]:
 * text pages read directly, scanned ones by PaddleOCR); (2) the review of the extracted text with
 * every insertion action. Back from the review returns to the pages as they were selected (a
 * running extraction is stopped; a finished one is kept, so Next with the same pages shows it
 * again without extracting again). Inserting closes the whole flow.
 */
@Composable
internal fun PdfExtractionFlow(host: ThesisHost, uri: Uri, cursor: Int) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val opened = rememberPdfPages(uri)
    val fileName = rememberDisplayName(uri)
    var selection by rememberSaveable(uri) { mutableStateOf(intArrayOf()) }
    val selected = remember(selection) { selection.toSet() }
    var reviewing by rememberSaveable(uri) { mutableStateOf(false) }
    var marks by remember(uri) { mutableStateOf(emptyMap<Int, PdfPageMark>()) }
    val x = state.extraction
    // The extraction of this PDF, if the current one is (never one of another file).
    val ours = x?.sources?.singleOrNull()?.takeIf { it.isPdf && it.uri == uri }
    LaunchedEffect(x?.document) {
        val doc = x?.document
        if (ours != null && doc != null) {
            marks = marks + doc.pages.mapNotNull { p -> p.sourcePage?.let { it to if (p.reviewRecommended) PdfPageMark.CHECK else PdfPageMark.EXTRACTED } }
        }
    }
    LaunchedEffect(x == null) { if (x == null) reviewing = false }
    val back: () -> Unit = {
        reviewing = false
        if (x?.running == true) controller.cancelExtraction()
    }
    val next: () -> Unit = next@{
        if (selected.isEmpty()) return@next
        if (x != null && ours != null && ours.pages == selected && (x.running || x.document != null)) {
            // Same pages as the extraction already made (or running): shown again, not repeated.
            reviewing = true
            return@next
        }
        controller.startExtraction(listOf(com.example.extraction.ExtractionSource(uri, isPdf = true, pages = selected.toSortedSet())),
            host.ocrLanguageCode, cursor, host.extractor())
        reviewing = true
    }
    // Drawn in the app's window over the editor (ExtractionLayer), not in a dialog window: on
    // Android 16 (Samsung S22) the full-screen dialog's frame did not match the area its content
    // was laid out for, and the bottom of the viewer (with Next) was cut off.
    ExtractionSurface(Modifier.testTag("pdf_flow"), onBack = { if (reviewing && x != null) back() else controller.closePdfViewer() }) {
        if (reviewing && x != null) ExtractionReviewContent(host, onBack = back)
        else PdfPagesPane(opened, fileName ?: stringResource(R.string.dx_pdf_viewer_title), marks, selected,
            onToggle = { i -> selection = if (i in selected) selection.filter { it != i }.toIntArray() else selection + i },
            onNext = next, onClose = { controller.closePdfViewer() },
            // Cancel: the selection is cleared; nothing is extracted and the viewer stays open.
            onCancel = { selection = intArrayOf() })
    }
}

/**
 * Extraction into the open section, drawn over the section editor in the app's own window (whose
 * insets the screens around it already handle): the PDF flow ([PdfExtractionFlow]) or, for
 * images, the review of their extracted content.
 */
@Composable
internal fun ExtractionLayer(host: ThesisHost) {
    val state by host.thesis.state.collectAsState()
    val shown = state.draft != null && (state.pdfToExtract != null || state.extraction != null)
    // The section editor under the layer loses the focus when the layer opens, which closes
    // its keyboard: in the same window, the keyboard opened for the editor would otherwise
    // stay up over the PDF pages and the review (taking half of the screen). Fields of the
    // review can take the focus afterwards.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(shown) { if (shown) { focus.clearFocus(force = true); keyboard?.hide() } }
    if (state.draft == null) return
    val uri = state.pdfToExtract
    if (uri != null) PdfExtractionFlow(host, uri, state.pdfCursor)
    else if (state.extraction != null) ExtractionSurface(Modifier.testTag("dx_review_layer"), onBack = { host.thesis.cancelExtraction() }) {
        ExtractionReviewContent(host, onBack = { host.thesis.cancelExtraction() })
    }
}

/** A full-size opaque layer (taps do not reach the screen below); system Back runs [onBack]. */
@Composable
private fun ExtractionSurface(modifier: Modifier, onBack: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), content = content)
    }
}
