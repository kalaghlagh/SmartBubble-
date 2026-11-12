package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.horizontalScroll
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.TemplateEntity
import com.example.document.model.ScannedDocumentReviewState
import com.example.document.model.ScannedPageData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrReviewDialog(
    reviewState: ScannedDocumentReviewState,
    templates: List<TemplateEntity>,
    onUpdateTitle: (String) -> Unit,
    onUpdatePageText: (pageIndex: Int, text: String) -> Unit,
    onSelectPageIndex: (Int) -> Unit,
    onRemovePage: (pageIndex: Int) -> Unit,
    onAddPage: () -> Unit,
    onSelectTemplate: (String?) -> Unit,
    onGenerateDocx: () -> Unit,
    onDismiss: () -> Unit,
    /** Current on-device OCR language: "auto", "ar", "en" or "fr". */
    ocrLanguage: String = "auto",
    onChangeOcrLanguage: (String) -> Unit = {},
    /** Name of the default AI provider, or blank when none is configured. */
    aiProviderName: String = "",
    /** Called only after the user confirmed sending the recognized text to the AI provider. */
    onEnhanceWithAi: () -> Unit = {}
) {
    val context = LocalContext.current
    val pages = reviewState.pages
    val activeIndex = reviewState.activePageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val currentPage: ScannedPageData? = pages.getOrNull(activeIndex)

    var docTitle by remember(reviewState.documentTitle) { mutableStateOf(reviewState.documentTitle) }
    var currentText by remember(activeIndex, currentPage?.rawText) {
        mutableStateOf(currentPage?.rawText ?: "")
    }

    var showTemplateMenu by remember { mutableStateOf(false) }
    var showAiConsent by remember { mutableStateOf(false) }

    if (showAiConsent) {
        AlertDialog(
            onDismissRequest = { showAiConsent = false },
            title = { Text(stringResource(R.string.scan_consent_title)) },
            text = { Text(stringResource(R.string.scan_consent_desc, aiProviderName)) },
            confirmButton = {
                TextButton(onClick = {
                    showAiConsent = false
                    onEnhanceWithAi()
                }) { Text(stringResource(R.string.scan_consent_agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showAiConsent = false }) {
                    Text(stringResource(R.string.scan_consent_local_only))
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.review_extracted_text),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (pages.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.scan_page_indicator, activeIndex + 1, pages.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("ocr_review_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_close)
                            )
                        }
                    },
                    actions = {
                        if (pages.size > 1) {
                            IconButton(
                                onClick = { onRemovePage(activeIndex) },
                                modifier = Modifier.testTag("ocr_remove_page_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = stringResource(R.string.scan_remove_page),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(
                            onClick = onAddPage,
                            modifier = Modifier.testTag("ocr_add_page_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = stringResource(R.string.scan_add_page),
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = onGenerateDocx,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("ocr_generate_docx_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.generate_word_doc),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Document Title field
                OutlinedTextField(
                    value = docTitle,
                    onValueChange = {
                        docTitle = it
                        onUpdateTitle(it)
                    },
                    label = { Text(stringResource(R.string.scan_doc_title_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ocr_doc_title_input"),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // On-device OCR language (changing it reads all pages again)
                Text(
                    text = stringResource(R.string.scan_ocr_language),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "auto" to stringResource(R.string.scan_ocr_language_auto),
                        "ar" to "العربية",
                        "en" to "English",
                        "fr" to "Français"
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = ocrLanguage == code,
                            onClick = { onChangeOcrLanguage(code) },
                            label = { Text(label, fontSize = 12.sp) },
                            modifier = Modifier.testTag("ocr_language_$code")
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.scan_ocr_on_device_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (aiProviderName.isNotBlank()) {
                    TextButton(
                        onClick = { showAiConsent = true },
                        modifier = Modifier.testTag("ocr_ai_cleanup_button")
                    ) {
                        Text(
                            stringResource(
                                if (reviewState.isAiEnhanced) R.string.scan_ai_cleanup_done else R.string.scan_ai_cleanup
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Template / Formatting Style Selector Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ocr_template_selector_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Style,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.scan_template_option),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Formatting Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = reviewState.selectedTemplateId == null,
                                onClick = { onSelectTemplate(null) },
                                label = { Text(stringResource(R.string.scan_template_original), fontSize = 12.sp) },
                                leadingIcon = if (reviewState.selectedTemplateId == null) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )

                            FilterChip(
                                selected = reviewState.selectedTemplateId == "default",
                                onClick = { onSelectTemplate("default") },
                                label = { Text(stringResource(R.string.scan_template_default), fontSize = 12.sp) },
                                leadingIcon = if (reviewState.selectedTemplateId == "default") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }

                        // Uploaded custom templates if any
                        val docxTemplates = templates.filter { it.fileType == "DOCX" }
                        if (docxTemplates.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Box {
                                TextButton(
                                    onClick = { showTemplateMenu = true },
                                    modifier = Modifier.testTag("ocr_custom_template_dropdown")
                                ) {
                                    val currentCustom = docxTemplates.find { it.id == reviewState.selectedTemplateId }
                                    Text(
                                        text = if (currentCustom != null) stringResource(R.string.scan_template_custom, currentCustom.name)
                                        else "+ Choose Custom Template...",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = showTemplateMenu,
                                    onDismissRequest = { showTemplateMenu = false }
                                ) {
                                    docxTemplates.forEach { tmpl ->
                                        DropdownMenuItem(
                                            text = { Text(tmpl.name) },
                                            onClick = {
                                                onSelectTemplate(tmpl.id)
                                                showTemplateMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Low confidence or empty warning banner
                if (currentPage != null && (currentPage.confidenceScore < 0.6f || currentPage.rawText.isBlank())) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.scan_warning_low_confidence),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Page Navigation Bar (Previous / Next / Page Counter)
                if (pages.size > 1) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onSelectPageIndex(activeIndex - 1) },
                                enabled = activeIndex > 0,
                                modifier = Modifier.testTag("ocr_prev_page_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_previous_page))
                            }

                            Text(
                                text = stringResource(R.string.scan_page_indicator, activeIndex + 1, pages.size),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )

                            IconButton(
                                onClick = { onSelectPageIndex(activeIndex + 1) },
                                enabled = activeIndex < pages.size - 1,
                                modifier = Modifier.testTag("ocr_next_page_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.action_next_page))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Split / Preview Section
                Text(
                    text = stringResource(R.string.scan_original_page),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Image preview card
                if (currentPage != null && currentPage.imageUriString.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = currentPage.imageUriString,
                            contentDescription = stringResource(R.string.scan_original_page),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Detected Table summary badge if present
                if (currentPage?.table != null) {
                    val table = currentPage.table
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.TableChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.scan_table_detected, table.rows.size),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Extracted Text Editor
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.scan_extracted_content),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (currentPage?.isArabicDetected == true) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "العربية (RTL)",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = currentText,
                    onValueChange = {
                        currentText = it
                        onUpdatePageText(activeIndex, it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp)
                        .testTag("ocr_extracted_text_editor"),
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text(stringResource(R.string.scan_text_placeholder)) }
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
