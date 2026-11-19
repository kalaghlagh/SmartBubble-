package com.example.ui.screens

import com.example.ui.publishOnMain

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.TemplateEntity
import com.example.template.domain.TemplateProfile
import com.example.smarttemplates.TemplateCatalog
import com.example.ui.SmartBubbleViewModel
import com.example.ui.components.FilterChipRow
import com.example.ui.components.FilterOption
import com.example.ui.components.TemplatePreviewDialog
import android.content.Context
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    viewModel: SmartBubbleViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val templates by viewModel.allTemplates.collectAsState()
    var selectedFilter by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    // Template preview state from ViewModel
    val selectedPreviewTemplate by viewModel.selectedPreviewTemplate.collectAsState()
    val previewResult by viewModel.previewResult.collectAsState()
    val isPreviewLoading by viewModel.isPreviewLoading.collectAsState()

    // Duplicate dialog state
    var templateToDuplicate by remember { mutableStateOf<TemplateEntity?>(null) }
    var duplicateNameInput by remember { mutableStateOf("") }

    // Upload dialog state
    var pendingUploadUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDisplayName by remember { mutableStateOf<String?>(null) }
    var templateNameInput by remember { mutableStateOf("") }
    var showUploadDialog by remember { mutableStateOf(false) }

    var templateForOptions by remember { mutableStateOf<TemplateEntity?>(null) }

    // Smart Templates library
    val library by viewModel.templateLibrary.collectAsState()
    val pendingClassification by viewModel.pendingTemplateClassification.collectAsState()
    var libraryFilter by remember { mutableStateOf(TemplateLibraryFilter.ALL) }
    var openCategory by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var detailsFor by remember { mutableStateOf<com.example.smarttemplates.LibraryTemplate?>(null) }
    // Names in the app language, for the search.
    val libraryNames = library.associate { t -> t.id to (t.builtIn?.let { context.getString(com.example.ui.SmartTemplateStrings.builtInName(it.id)) } ?: t.uploaded?.name.orEmpty()) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingUploadUri = uri
            // The picked file's real name (Storage Access Framework); the template name starts as it.
            pendingDisplayName = displayNameOf(context, uri)
            templateNameInput = pendingDisplayName?.substringBeforeLast('.')?.ifBlank { null } ?: "My Custom Template"
            showUploadDialog = true
        }
    }

    val filteredTemplates = templates.filter { tmpl ->
        val matchesType = when (selectedFilter) {
            "Docs" -> tmpl.fileType.equals("DOCX", ignoreCase = true)
            "PPTX" -> tmpl.fileType.equals("PPTX", ignoreCase = true)
            "XLSX" -> tmpl.fileType.equals("XLSX", ignoreCase = true)
            "PDF" -> tmpl.fileType.equals("PDF", ignoreCase = true)
            else -> true
        }
        val matchesSearch = tmpl.name.contains(searchQuery, ignoreCase = true)
        matchesType && matchesSearch
    }
    /** The file-type chips also narrow the library results (built-in and uploaded). */
    fun typeMatches(fileType: String) = when (selectedFilter) {
        "Docs" -> fileType.equals("DOCX", ignoreCase = true)
        "PPTX" -> fileType.equals("PPTX", ignoreCase = true)
        "XLSX" -> fileType.equals("XLSX", ignoreCase = true)
        "PDF" -> fileType.equals("PDF", ignoreCase = true)
        else -> true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.templates_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = { showSearchBar = !showSearchBar },
                        modifier = Modifier.testTag("templates_search_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.action_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (showSearchBar) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.template_search_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("templates_search_field"),
                        singleLine = true,
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(imageVector = Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                                }
                            }
                        }
                    )
                }
            }
        }

        // Library row (Stitch): title, number of files and the scope filter menu.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.st_library_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
            Spacer(Modifier.width(8.dp))
            Text(
                pluralStringResource(R.plurals.templates_files_count, templates.size, templates.size),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp).testTag("templates_count")
            )
            Spacer(Modifier.weight(1f))
            TemplateScopeMenu(libraryFilter) { libraryFilter = it }
        }

        // Upload Template: full-width pill (Stitch).
        Button(
            onClick = {
                try {
                    filePickerLauncher.launch(
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/pdf",
                            "*/*"
                        )
                    )
                } catch (ignored: Exception) {
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 48.dp)
                .testTag("upload_template_button"),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = stringResource(R.string.templates_upload), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // File-type chips [All] [Docs] [PPTX] [XLSX] [PDF]: one horizontally scrollable, non-wrapping row.
        FilterChipRow(
            options = listOf(
                FilterOption("All", stringResource(R.string.filter_all)),
                FilterOption("Docs", stringResource(R.string.filter_docs)),
                FilterOption("PPTX", stringResource(R.string.filter_pptx)),
                FilterOption("XLSX", stringResource(R.string.filter_xlsx)),
                FilterOption("PDF", stringResource(R.string.filter_pdf))
            ),
            selectedKey = selectedFilter,
            onSelect = { selectedFilter = it },
            testTagPrefix = "filter_"
        )

        Spacer(modifier = Modifier.height(16.dp))

        val openCategoryEntity = openCategory?.let { TemplateCatalog.category(it) }
        if (openCategoryEntity != null) {
            // Smart Templates: the subcategories of one category with their templates and defaults.
            Box(Modifier.weight(1f)) {
                CategoryContent(viewModel, openCategoryEntity, library, onBack = { openCategory = null }) { t ->
                    if (t.uploaded != null) viewModel.openTemplatePreview(t.uploaded) else detailsFor = t
                }
            }
        } else LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("templates_list"),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            pendingClassification?.let { pending -> item(key = "classify") { ClassificationCard(viewModel, pending) } }
            val query = searchQuery.trim()
            if ((libraryFilter != TemplateLibraryFilter.ALL && libraryFilter != TemplateLibraryFilter.UPLOADED) ||
                (libraryFilter == TemplateLibraryFilter.ALL && query.isNotEmpty())) {
                val matches = library.filter { t ->
                    val byFilter = when (libraryFilter) {
                        TemplateLibraryFilter.BUILT_IN -> t.isBuiltIn
                        TemplateLibraryFilter.FAVORITES -> t.favorite
                        TemplateLibraryFilter.DEFAULTS -> t.defaultFor.isNotEmpty()
                        else -> true
                    }
                    byFilter && typeMatches(t.fileType) && (query.isEmpty() || libraryNames[t.id].orEmpty().contains(query, ignoreCase = true))
                }
                items(matches, key = { "lib_" + it.id }) { t ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        LibraryTemplateRow(
                            template = t,
                            defaultBadgeFor = t.defaultFor.firstOrNull()?.let { scope -> context.getString(com.example.ui.SmartTemplateStrings.subcategory(scope)) },
                            onFavorite = { viewModel.setTemplateFavorite(t.id, it) },
                            onPreview = { if (t.uploaded != null) viewModel.openTemplatePreview(t.uploaded) else detailsFor = t },
                            onSetDefault = null
                        )
                    }
                }
            }
            if (libraryFilter == TemplateLibraryFilter.ALL || libraryFilter == TemplateLibraryFilter.UPLOADED) {
                item(key = "uploaded_header") {
                    // "Recent files" (your uploaded templates), newest first.
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Text(stringResource(R.string.templates_recent_files), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).semantics { heading() })
                        Text(stringResource(R.string.templates_sorted_recent), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (filteredTemplates.isEmpty()) item(key = "uploaded_empty") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.template_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.template_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
                items(filteredTemplates, key = { it.id }) { template ->
                    // First-page image of PDF templates (rendered on the device, cached).
                    val thumbnail by produceState<ImageBitmap?>(null, template.id, template.originalFilePath) {
                        publishOnMain(viewModel.templateThumbnail(template)?.asImageBitmap())
                    }
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        TemplateItemCard(
                            template = template,
                            thumbnail = thumbnail,
                            onPreview = { viewModel.openTemplatePreview(template) },
                            onDuplicate = {
                                templateToDuplicate = template
                                duplicateNameInput = context.getString(R.string.template_duplicate_name_format, template.name)
                            },
                            onSetDefault = { viewModel.setDefaultTemplate(template) },
                            onRemoveDefault = { viewModel.removeDefaultTemplate(template) },
                            onDelete = { viewModel.deleteTemplate(template) },
                            onOptions = { templateForOptions = template },
                            onSelectForChat = {
                                viewModel.selectTemplateForChat(template)
                                viewModel.setNavTab(com.example.ui.AppNavTab.CHAT)
                            }
                        )
                    }
                }
            }
            // Categories of the library (built-in and uploaded templates by kind of document).
            if (libraryFilter == TemplateLibraryFilter.ALL && query.isEmpty()) {
                item(key = "categories_header") {
                    Text(stringResource(R.string.st_categories), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                }
                items(TemplateCatalog.categories, key = { "cat_" + it.id }) { c ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        CategoryCard(c, library.count { t -> c.subcategories.any { t.appliesTo(it.id) } }) { openCategory = c.id }
                    }
                }
            }
        }
    }

    detailsFor?.let { t -> TemplateDetailsDialog(viewModel, t) { detailsFor = null } }

    // Template generation settings dialog
    templateForOptions?.let { target ->
        TemplateOptionsDialog(
            template = target,
            onDismiss = { templateForOptions = null },
            onSave = { useLayout, keepContent ->
                viewModel.updateTemplateOptions(target, useLayout, keepContent)
                templateForOptions = null
            }
        )
    }

    // Duplicate Template naming dialog
    if (templateToDuplicate != null) {
        val targetTemplate = templateToDuplicate!!
        AlertDialog(
            onDismissRequest = { templateToDuplicate = null },
            title = { Text(stringResource(R.string.template_duplicate_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.template_duplicate_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = duplicateNameInput,
                        onValueChange = { duplicateNameInput = it },
                        label = { Text(stringResource(R.string.template_duplicate_name_label)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("duplicate_template_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = duplicateNameInput.trim().ifBlank {
                            context.getString(R.string.template_duplicate_name_format, targetTemplate.name)
                        }
                        viewModel.duplicateTemplate(targetTemplate, name)
                        templateToDuplicate = null
                    },
                    modifier = Modifier.testTag("confirm_duplicate_template_btn")
                ) {
                    Text(stringResource(R.string.template_duplicate_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { templateToDuplicate = null }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Template Preview Dialog
    if (selectedPreviewTemplate != null) {
        val tmpl = selectedPreviewTemplate!!
        val previewThumbnail by produceState<ImageBitmap?>(null, tmpl.id) {
            publishOnMain(viewModel.templateThumbnail(tmpl)?.asImageBitmap())
        }
        TemplatePreviewDialog(
            template = tmpl,
            previewResult = previewResult,
            isLoading = isPreviewLoading,
            pageImage = previewThumbnail,
            onOpenOriginal = if (tmpl.fileType.equals("PDF", ignoreCase = true)) {
                { viewModel.openTemplateOriginal(tmpl) }
            } else null,
            onClose = { viewModel.closeTemplatePreview() },
            onUseTemplate = {
                viewModel.selectTemplateForChat(tmpl)
                viewModel.setNavTab(com.example.ui.AppNavTab.CHAT)
                viewModel.closeTemplatePreview()
            },
            onDuplicate = {
                templateToDuplicate = tmpl
                duplicateNameInput = context.getString(R.string.template_duplicate_name_format, tmpl.name)
            },
            onToggleDefault = {
                val isDef = tmpl.isDefaultWord || tmpl.isDefaultPpt || tmpl.isDefaultExcel || tmpl.isDefaultPdf
                if (isDef) viewModel.removeDefaultTemplate(tmpl) else viewModel.setDefaultTemplate(tmpl)
            }
        )
    }

    // Upload naming dialog
    if (showUploadDialog && pendingUploadUri != null) {
        AlertDialog(
            onDismissRequest = {
                showUploadDialog = false
                pendingUploadUri = null
            },
            title = { Text(stringResource(R.string.template_name_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.template_name_dialog_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = templateNameInput,
                        onValueChange = { templateNameInput = it },
                        label = { Text(stringResource(R.string.template_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingUploadUri
                        val name = templateNameInput.trim().ifBlank { "Custom Template" }
                        if (uri != null) {
                            viewModel.importTemplateFromUri(uri, name, pendingDisplayName)
                        }
                        showUploadDialog = false
                        pendingUploadUri = null
                    }
                ) {
                    Text(stringResource(R.string.template_analyze_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUploadDialog = false
                        pendingUploadUri = null
                    }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun TemplateItemCard(
    template: TemplateEntity,
    thumbnail: ImageBitmap? = null,
    onPreview: () -> Unit,
    onDuplicate: () -> Unit,
    onOptions: () -> Unit = {},
    onSetDefault: () -> Unit,
    onRemoveDefault: () -> Unit,
    onDelete: () -> Unit,
    onSelectForChat: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val formattedDate = remember(template.createdAt) {
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        sdf.format(Date(template.createdAt))
    }

    val badgeColor = when (template.fileType.uppercase()) {
        "DOCX" -> Color(0xFF1D52D8)
        "PPTX" -> Color(0xFFE05D26)
        "XLSX" -> Color(0xFF107C41)
        "PDF" -> Color(0xFFD83B01)
        else -> Color(0xFF1D52D8)
    }

    val badgeLetter = when (template.fileType.uppercase()) {
        "DOCX" -> "W"
        "PPTX" -> "P"
        "XLSX" -> "X"
        "PDF" -> "PDF"
        else -> "D"
    }
    val isPdf = template.fileType.equals("PDF", ignoreCase = true)
    val profile = remember(template.id, template.profileJson) { TemplateProfile.fromJson(template.profileJson) }

    val sizeFormatted = remember(template.fileSize) {
        if (template.fileSize >= 1024 * 1024) {
            String.format(Locale.US, "%.1f MB", template.fileSize / (1024.0 * 1024.0))
        } else {
            "${template.fileSize / 1024} KB"
        }
    }

    val isDefault = when (template.fileType.uppercase()) {
        "DOCX" -> template.isDefaultWord
        "PPTX" -> template.isDefaultPpt
        "XLSX" -> template.isDefaultExcel
        "PDF" -> template.isDefaultPdf
        else -> template.isDefaultWord
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPreview)
            .testTag("template_card_${template.id}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Square Badge Icon matching mockup
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (thumbnail != null) Color.White else badgeColor)
                    .testTag("template_badge_${template.id}"),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = stringResource(R.string.template_pdf_first_page),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = badgeLetter,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (badgeLetter.length > 1) 13.sp else 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                // 1. Template title on its own line
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 2. Default badge directly below the template title, on its own line, before metadata
                if (isDefault) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.testTag("default_badge_${template.id}")
                    ) {
                        Text(
                            text = stringResource(R.string.template_default_badge),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 3. File metadata (DOCX/PPTX/XLSX, file size)
                Text(
                    text = "${template.fileType} • $sizeFormatted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Original file name (when known) and scanned-PDF status.
                if (profile.sourceFileName.isNotBlank()) {
                    Text(
                        text = profile.sourceFileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("template_filename_${template.id}")
                    )
                }
                if (profile.layoutSource == "DOCX") TemplateFormatDetails(template.id, profile)
                if (isPdf && profile.isImageOnlyPdf) {
                    Text(
                        text = stringResource(R.string.template_pdf_no_text_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 2
                    )
                }

                // 4. Added date
                Text(
                    text = stringResource(R.string.template_added_on, formattedDate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 3-dots Menu
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("template_menu_${template.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.library_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.template_preview_title)) },
                        leadingIcon = { Icon(Icons.Outlined.Visibility, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onPreview()
                        },
                        modifier = Modifier.testTag("menu_preview_${template.id}")
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.template_use_in_chat)) },
                        leadingIcon = { Icon(Icons.Default.Chat, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onSelectForChat()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.template_duplicate)) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDuplicate()
                        },
                        modifier = Modifier.testTag("menu_duplicate_${template.id}")
                    )

                    // Generation settings concern Office template files; a PDF template is applied
                    // through its measured style only, so the setting does not exist for it.
                    if (!isPdf) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.template_options_title)) },
                            leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onOptions()
                            },
                            modifier = Modifier.testTag("menu_options_${template.id}")
                        )
                    }

                    if (!isDefault) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.template_set_default)) },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onSetDefault()
                            },
                            modifier = Modifier.testTag("menu_set_default_${template.id}")
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.template_remove_default)) },
                            leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRemoveDefault()
                            },
                            modifier = Modifier.testTag("menu_remove_default_${template.id}")
                        )
                    }

                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.template_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        modifier = Modifier.testTag("menu_delete_${template.id}")
                    )
                }
            }
        }
    }
}

/**
 * Generation settings of one template. Only options the document engines actually honor are
 * offered, with wording specific to the template's file type.
 */
@Composable
fun TemplateOptionsDialog(
    template: TemplateEntity,
    onDismiss: () -> Unit,
    onSave: (useTemplateLayout: Boolean, keepTemplateContent: Boolean) -> Unit
) {
    val profile = remember(template.id, template.profileJson) { TemplateProfile.fromJson(template.profileJson) }
    var useLayout by remember(template.id) { mutableStateOf(profile.useTemplateLayout) }
    var keepContent by remember(template.id) { mutableStateOf(profile.keepTemplateContent) }

    val (layoutDesc, keepDesc) = when (template.fileType.uppercase()) {
        "PPTX" -> R.string.template_opt_layout_pptx to R.string.template_opt_keep_pptx
        "XLSX" -> R.string.template_opt_layout_xlsx to R.string.template_opt_keep_xlsx
        else -> R.string.template_opt_layout_docx to R.string.template_opt_keep_docx
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_options_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.template_opt_layout), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(layoutDesc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = useLayout,
                        onCheckedChange = { useLayout = it },
                        modifier = Modifier.testTag("template_opt_layout_switch")
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.template_opt_keep), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(keepDesc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = keepContent && useLayout,
                        onCheckedChange = { keepContent = it },
                        // Existing content can only be kept when the template file itself is used.
                        enabled = useLayout,
                        modifier = Modifier.testTag("template_opt_keep_switch")
                    )
                }
                Text(
                    text = stringResource(R.string.template_opt_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(useLayout, keepContent && useLayout) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** The file name of a document picked with the Storage Access Framework, or null. */
private fun displayNameOf(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (e: Exception) {
    null
}

/**
 * What was read from a Word template (page, margins, font, header, footer, border, ...) and what
 * the PDF export and the preview cannot reproduce, so the user is never told more than is true.
 */
@Composable
private fun TemplateFormatDetails(id: String, p: TemplateProfile) {
    fun cm(pt: Int) = String.format(java.util.Locale.getDefault(), "%.1f", pt * 2.54 / 72)
    val parts = buildList {
        add(stringResource(R.string.template_fmt_page, cm(p.pageWidthPt), cm(p.pageHeightPt)))
        add(stringResource(R.string.template_fmt_margins, listOf(p.marginTopPt, p.marginBottomPt, p.marginLeftPt, p.marginRightPt).joinToString("/") { cm(it) }))
        add(stringResource(R.string.template_fmt_font, p.bodyFont, p.baseFontSizePt))
        if (p.headerText.isNotBlank()) add(stringResource(R.string.template_fmt_header))
        if (p.headerImage.isNotBlank()) add(stringResource(R.string.template_fmt_header_image))
        if (p.footerText.isNotBlank()) add(stringResource(R.string.template_fmt_footer))
        if (p.pageNumberPosition.isNotBlank()) add(stringResource(R.string.template_fmt_page_numbers))
        if (p.pageBorders.isNotBlank()) add(stringResource(R.string.template_fmt_border))
        if (p.differentFirstPage) add(stringResource(R.string.template_fmt_first_page))
        if (p.rtl) add(stringResource(R.string.template_fmt_rtl))
        if (p.templateTables > 0) add(stringResource(R.string.template_fmt_tables, p.templateTables))
        if (p.templateImages > 0) add(stringResource(R.string.template_fmt_images, p.templateImages))
    }
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("template_format_$id")
    )
    val warnings = p.extractionWarnings.split(';').filter { it.isNotBlank() }.map { w ->
        when (w.substringBefore(':')) {
            "odd_even_headers" -> stringResource(R.string.template_warn_odd_even)
            "header_shapes" -> stringResource(R.string.template_warn_shapes)
            "multiple_sections" -> stringResource(R.string.template_warn_sections, w.substringAfter(':').toIntOrNull() ?: 2)
            "body_content" -> stringResource(R.string.template_warn_body)
            "page_border_art" -> stringResource(R.string.template_warn_border_art)
            "footnotes" -> stringResource(R.string.template_warn_footnotes)
            else -> stringResource(R.string.template_warn_unreadable)
        }
    }
    if (warnings.isNotEmpty()) Text(
        text = stringResource(R.string.template_warn_title) + "\n" + warnings.joinToString("\n") { "• $it" },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.testTag("template_format_warnings_$id")
    )
}
