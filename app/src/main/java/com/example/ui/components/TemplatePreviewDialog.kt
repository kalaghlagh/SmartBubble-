package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.TemplateEntity
import com.example.template.preview.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatePreviewDialog(
    template: TemplateEntity,
    previewResult: TemplatePreviewResult?,
    isLoading: Boolean,
    onClose: () -> Unit,
    onUseTemplate: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleDefault: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("template_preview_dialog"),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = template.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.template_preview_title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("template_preview_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_close)
                            )
                        }
                    },
                    actions = {
                        // Quick duplicate button in top bar
                        IconButton(
                            onClick = onDuplicate,
                            modifier = Modifier.testTag("template_preview_top_duplicate_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.template_duplicate),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDuplicate,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("template_preview_duplicate_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.template_duplicate), maxLines = 1)
                        }

                        Button(
                            onClick = onUseTemplate,
                            modifier = Modifier
                                .weight(1.4f)
                                .height(48.dp)
                                .testTag("template_preview_use_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.template_preview_use_template),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (isLoading) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.template_preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val previewData = when (previewResult) {
                        is TemplatePreviewResult.Success -> previewResult.data
                        is TemplatePreviewResult.Fallback -> previewResult.data
                        else -> null
                    }

                    val fallbackWarning = (previewResult as? TemplatePreviewResult.Fallback)?.warningMessage
                    val errorMessage = (previewResult as? TemplatePreviewResult.Error)?.errorMessage

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Header metadata card
                        TemplateMetadataHeaderCard(
                            template = template,
                            onToggleDefault = onToggleDefault
                        )

                        // Fallback warning notice if any
                        if (fallbackWarning != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.template_preview_structure_only),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }

                        if (errorMessage != null && previewData == null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.template_preview_error),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // Document-Specific Visual Preview
                        when (previewData) {
                            is TemplatePreviewData.DocxPreview -> {
                                DocxRealisticPreview(docx = previewData)
                            }
                            is TemplatePreviewData.PptxPreview -> {
                                PptxRealisticPreview(pptx = previewData)
                            }
                            is TemplatePreviewData.XlsxPreview -> {
                                XlsxRealisticPreview(xlsx = previewData)
                            }
                            null -> {
                                // Empty state placeholder
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.template_preview_error),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Extra bottom spacing so content doesn't tuck under the bottom bar
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TemplateMetadataHeaderCard(
    template: TemplateEntity,
    onToggleDefault: () -> Unit
) {
    val isDefault = template.isDefaultWord || template.isDefaultPpt || template.isDefaultExcel
    val typeColor = when (template.fileType.uppercase()) {
        "DOCX" -> Color(0xFF1D52D8)
        "PPTX" -> Color(0xFFE05D26)
        "XLSX" -> Color(0xFF107C41)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Type & size badges
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = typeColor,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = template.fileType.uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    val formattedSize = if (template.fileSize >= 1024 * 1024) {
                        String.format("%.1f MB", template.fileSize / (1024.0 * 1024.0))
                    } else {
                        "${template.fileSize / 1024} KB"
                    }

                    Text(
                        text = formattedSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Default status pill
                if (isDefault) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("preview_default_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.template_default_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Original file source path indicator
            val sourceFileName = template.originalFilePath.substringAfterLast("/")
            Text(
                text = stringResource(R.string.template_info_original_file, sourceFileName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/* =======================================================================
   DOCX REALISTIC PREVIEW
   ======================================================================= */
@Composable
fun DocxRealisticPreview(docx: TemplatePreviewData.DocxPreview) {
    val brandColor = parseHexColor(docx.primaryColorHex, Color(0xFF1D52D8))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Formatting summary header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${docx.orientation} • ${docx.headingFont} / ${docx.bodyFont}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.template_preview_margins, "${docx.marginTopPt}pt / ${docx.marginLeftPt}pt"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        // Realistic Page Sheet Card (simulating an 8.5x11 page)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("docx_preview_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header if present
                if (docx.headerText.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = docx.headerText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = stringResource(R.string.template_preview_header),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // Document Title
                Text(
                    text = docx.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = brandColor
                )

                // Subtitle if available
                if (docx.subtitle.isNotBlank()) {
                    Text(
                        text = docx.subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = brandColor.copy(alpha = 0.3f), thickness = 1.5.dp)

                // Sections
                docx.sections.forEach { section ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (section.heading.isNotBlank()) {
                            Text(
                                text = section.heading,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = brandColor
                            )
                        }

                        section.paragraphs.forEach { paragraph ->
                            Text(
                                text = paragraph,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 20.sp
                            )
                        }

                        section.bulletPoints.forEach { bullet ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .size(6.dp)
                                        .background(brandColor, CircleShape)
                                )
                                Text(
                                    text = bullet,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Table if available
                if (docx.table != null && docx.table.headers.isNotEmpty()) {
                    DocxTablePreview(table = docx.table, headerBg = brandColor)
                }

                // Footer if present
                if (docx.footerText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = docx.footerText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = stringResource(R.string.template_preview_footer),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DocxTablePreview(
    table: DocxPreviewTable,
    headerBg: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                table.headers.forEach { header ->
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = headerBg,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Rows
            table.rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (index % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    row.forEach { cell ->
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (index < table.rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}

/* =======================================================================
   PPTX REALISTIC PREVIEW (Slide Carousel)
   ======================================================================= */
@Composable
fun PptxRealisticPreview(pptx: TemplatePreviewData.PptxPreview) {
    var currentSlideIndex by remember { mutableStateOf(0) }
    val totalSlides = pptx.slides.size.coerceAtLeast(1)
    val activeSlide = pptx.slides.getOrNull(currentSlideIndex) ?: PptxPreviewSlide(
        slideNumber = 1,
        title = "Slide Title",
        subtitle = "Presentation Theme Slide Master"
    )

    val primaryBrand = parseHexColor(pptx.primaryColorHex, Color(0xFFE05D26))
    val secondaryBrand = parseHexColor(pptx.secondaryColorHex, Color(0xFF8665F7))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Theme and slide counter header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${pptx.themeName} • ${pptx.font}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.template_preview_slide_counter, currentSlideIndex + 1, totalSlides),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 16:9 Realistic Slide Canvas
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, primaryBrand.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .testTag("pptx_slide_canvas")
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Decorative slide master accent bars
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(primaryBrand)
                        .align(Alignment.TopStart)
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = activeSlide.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = primaryBrand,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (activeSlide.subtitle.isNotBlank()) {
                            Text(
                                text = activeSlide.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        // Bullets
                        activeSlide.bulletPoints.take(3).forEach { bullet ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(6.dp)
                                        .background(secondaryBrand, CircleShape)
                                )
                                Text(
                                    text = bullet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Callout metric if present
                    if (!activeSlide.calloutMetric.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = primaryBrand.copy(alpha = 0.1f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, primaryBrand.copy(alpha = 0.3f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = activeSlide.calloutMetric,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryBrand
                                    )
                                    if (!activeSlide.calloutLabel.isNullOrBlank()) {
                                        Text(
                                            text = activeSlide.calloutLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Slide Navigation Controls (< Previous, Slide X of Y, Next >)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { if (currentSlideIndex > 0) currentSlideIndex-- },
                enabled = currentSlideIndex > 0,
                modifier = Modifier.testTag("pptx_prev_slide_btn"),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.template_preview_prev_slide),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.template_preview_prev_slide))
            }

            // Dot indicators
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(totalSlides.coerceAtMost(8)) { idx ->
                    Box(
                        modifier = Modifier
                            .size(if (idx == currentSlideIndex) 8.dp else 6.dp)
                            .background(
                                if (idx == currentSlideIndex) primaryBrand
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape
                            )
                    )
                }
            }

            OutlinedButton(
                onClick = { if (currentSlideIndex < totalSlides - 1) currentSlideIndex++ },
                enabled = currentSlideIndex < totalSlides - 1,
                modifier = Modifier.testTag("pptx_next_slide_btn"),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.template_preview_next_slide))
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.template_preview_next_slide),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/* =======================================================================
   XLSX REALISTIC PREVIEW (Worksheet Grid)
   ======================================================================= */
@Composable
fun XlsxRealisticPreview(xlsx: TemplatePreviewData.XlsxPreview) {
    var selectedSheetIndex by remember { mutableStateOf(0) }
    val sheets = xlsx.sheets
    val activeSheet = sheets.getOrNull(selectedSheetIndex)

    val excelBrand = parseHexColor(xlsx.primaryColorHex, Color(0xFF107C41))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Workbook and font info
        Text(
            text = "${xlsx.workbookName} • ${xlsx.font}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Sheet Tabs if multiple
        if (sheets.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = selectedSheetIndex,
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                sheets.forEachIndexed { index, sheet ->
                    Tab(
                        selected = selectedSheetIndex == index,
                        onClick = { selectedSheetIndex = index },
                        text = {
                            Text(
                                text = sheet.name,
                                fontWeight = if (selectedSheetIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        if (activeSheet == null || activeSheet.headers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.template_preview_empty_sheet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Spreadsheet Table Grid with Horizontal and Vertical scrolling
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("xlsx_sheet_grid")
            ) {
                val horizontalScroll = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontalScroll)
                ) {
                    // Header Row with column index letters & headers
                    Row(
                        modifier = Modifier
                            .background(excelBrand.copy(alpha = 0.12f))
                            .padding(vertical = 8.dp)
                    ) {
                        // Row index header corner
                        Text(
                            text = "#",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = excelBrand,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .width(36.dp)
                                .padding(horizontal = 4.dp)
                        )

                        activeSheet.headers.forEachIndexed { colIndex, header ->
                            val colLetter = ('A' + colIndex).toString()
                            Column(
                                modifier = Modifier
                                    .width(130.dp)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = colLetter,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = excelBrand.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = header,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = excelBrand,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = excelBrand.copy(alpha = 0.3f), thickness = 1.5.dp)

                    // Data Rows
                    activeSheet.rows.forEachIndexed { rowIndex, row ->
                        Row(
                            modifier = Modifier
                                .background(
                                    if (rowIndex % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                    else Color.Transparent
                                )
                                .padding(vertical = 6.dp)
                        ) {
                            // Row number
                            Text(
                                text = "${rowIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .width(36.dp)
                                    .padding(horizontal = 4.dp)
                            )

                            // Row cells
                            activeSheet.headers.indices.forEach { colIndex ->
                                val cellVal = row.getOrNull(colIndex) ?: ""
                                val isNumeric = cellVal.toDoubleOrNull() != null || cellVal.startsWith("$") || cellVal.endsWith("%")
                                Text(
                                    text = cellVal,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = if (isNumeric) FontFamily.Monospace else FontFamily.Default,
                                    textAlign = if (isNumeric) TextAlign.End else TextAlign.Start,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .width(130.dp)
                                        .padding(horizontal = 8.dp)
                                )
                            }
                        }
                        if (rowIndex < activeSheet.rows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Safely parses a hex color string into a Compose Color with fallback.
 */
private fun parseHexColor(hex: String, fallback: Color): Color {
    return try {
        val clean = hex.removePrefix("#")
        when (clean.length) {
            6 -> Color(android.graphics.Color.parseColor("#$clean"))
            8 -> Color(android.graphics.Color.parseColor("#$clean"))
            else -> fallback
        }
    } catch (e: Exception) {
        fallback
    }
}
