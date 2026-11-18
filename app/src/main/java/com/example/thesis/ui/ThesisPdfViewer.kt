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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ui.pdf.PdfPageRenderer

/** What is known of a page once its text was extracted. */
enum class PdfPageMark { EXTRACTED, CHECK }

/**
 * The selected PDF itself, page after page, to choose the page whose text is extracted: the real
 * pages are drawn (in order, at the screen's width, their proportions kept), the tapped page is
 * selected and outlined, and "Extract page N" sends exactly that page to the shared extraction
 * ([onExtract] gets its 0-based index). Pages are drawn only when shown and the renderer is
 * released when the viewer closes. [marks] puts the extraction state of pages already extracted
 * (done, or to check) on them.
 */
@Composable
fun PdfPageViewer(uri: Uri, title: String, marks: Map<Int, PdfPageMark>, onExtract: (Int) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    // null while opening; Result.failure when the PDF cannot be displayed.
    val opened by produceState<Result<PdfPageRenderer>?>(null, uri) {
        val r = PdfPageRenderer.open(context, uri)
        value = if (r != null) Result.success(r) else Result.failure(IllegalStateException("unreadable"))
    }
    val renderer = opened?.getOrNull()
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    var selected by rememberSaveable(uri) { mutableStateOf(0) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("pdf_viewer"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                BlueTopBar(title, onBack = onClose)
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
                        if (selected !in 0 until count) selected = 0
                        Text(stringResource(R.string.dx_pdf_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            val pageWidthPx = with(LocalDensity.current) { (maxWidth - 32.dp).roundToPx() }
                            LazyColumn(Modifier.fillMaxSize().testTag("pdf_pages"), state = rememberLazyListState(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                items((0 until count).toList(), key = { it }) { index ->
                                    PdfPageItem(renderer, index, count, pageWidthPx, selected == index, marks[index]) { selected = index }
                                }
                            }
                        }
                        HorizontalDivider()
                        // Compact actions: the page stays in view; the extracted text opens in the review.
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.dx_pdf_selected_of, selected + 1, count), style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f).testTag("pdf_selected_label"))
                            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("pdf_close")) { Text(stringResource(R.string.action_close)) }
                            Button(onClick = { onExtract(selected) }, modifier = Modifier.heightIn(min = 48.dp).testTag("pdf_extract")) {
                                Text(stringResource(R.string.dx_pdf_extract_page, selected + 1))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(renderer: PdfPageRenderer, index: Int, count: Int, widthPx: Int, isSelected: Boolean, mark: PdfPageMark?, onSelect: () -> Unit) {
    val (pw, ph) = renderer.pageSizes[index]
    // Drawn only while the page is composed (on screen), at the width it is shown; kept in the
    // renderer's bounded cache, so scrolling back does not draw it again.
    val bitmap by produceState<Result<Bitmap>?>(null, renderer, index, widthPx) {
        val b = renderer.render(index, widthPx)
        value = if (b != null) Result.success(b) else Result.failure(IllegalStateException())
    }
    val description = if (isSelected) stringResource(R.string.dx_pdf_page_selected_desc, index + 1, count) else stringResource(R.string.dx_pdf_page_desc, index + 1, count)
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
                .semantics { selected = isSelected; contentDescription = description }
                .clickable(role = Role.Button, onClick = onSelect)
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
        }
    }
}
