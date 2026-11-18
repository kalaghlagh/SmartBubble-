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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * The PDF page viewer in its own window: the real pages of [uri], several of which can be
 * selected ([selected], [onToggle]); [onNext] goes on with the selected pages.
 */
@Composable
fun PdfPageViewer(uri: Uri, title: String, marks: Map<Int, PdfPageMark>, selected: Set<Int>, onToggle: (Int) -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val opened = rememberPdfPages(uri)
    com.example.ui.adaptive.FullScreenDialog(onDismissRequest = onClose) {
        PdfPagesPane(opened, title, marks, selected, onToggle, onNext, onClose)
    }
}

/**
 * Step 1 of a PDF extraction: the PDF itself, page after page (in order, at the screen's width,
 * their proportions kept), drawn only when shown. Tapping a page selects it, tapping it again
 * deselects it; any pages can be selected, consecutive or not. Nothing is extracted on a tap:
 * the bar at the bottom shows how many pages are selected and "Next" (enabled once one page is
 * selected) goes on with them. [marks] puts the extraction state of pages already extracted on them.
 */
@Composable
internal fun PdfPagesPane(
    opened: Result<PdfPageRenderer>?, title: String, marks: Map<Int, PdfPageMark>, selected: Set<Int>,
    onToggle: (Int) -> Unit, onNext: () -> Unit, onClose: () -> Unit
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
                    val pageWidthPx = with(LocalDensity.current) { (maxWidth - 32.dp).roundToPx() }
                    LazyColumn(Modifier.fillMaxSize().testTag("pdf_pages"), state = rememberLazyListState(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(count, key = { it }) { index ->
                            PdfPageItem(renderer, index, count, pageWidthPx, index in selected, marks[index]) { onToggle(index) }
                        }
                    }
                }
                HorizontalDivider()
                // Always in view below the pages: how many are selected, and Next.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (chosen.isEmpty()) stringResource(R.string.dx_pdf_none_selected) else stringResource(R.string.dx_pdf_selected_count, chosen.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (chosen.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.testTag("pdf_selected_label")
                    )
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("pdf_close")) { Text(stringResource(R.string.action_close)) }
                    Button(onClick = onNext, enabled = chosen.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("pdf_next")) {
                        Text(stringResource(R.string.dx_pdf_next))
                    }
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
internal fun PdfExtractionFlow(host: ThesisHost, uri: Uri, cursor: () -> Int, sync: () -> Unit) {
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
        sync()
        controller.startExtraction(listOf(com.example.extraction.ExtractionSource(uri, isPdf = true, pages = selected.toSortedSet())),
            host.ocrLanguageCode, cursor(), host.extractor())
        reviewing = true
    }
    // Edge to edge on every Android version, content inside the safe area (system bars, cutout,
    // keyboard): the same layout as Android 15+ gives apps targeting SDK 35+.
    com.example.ui.adaptive.FullScreenDialog(onDismissRequest = { if (reviewing && x != null) back() else controller.closePdfViewer() },
        modifier = Modifier.testTag("pdf_flow")) {
        if (reviewing && x != null) ExtractionReviewContent(host, onBack = back)
        else PdfPagesPane(opened, fileName ?: stringResource(R.string.dx_pdf_viewer_title), marks, selected,
            onToggle = { i -> selection = if (i in selected) selection.filter { it != i }.toIntArray() else selection + i },
            onNext = next, onClose = { controller.closePdfViewer() })
    }
}
