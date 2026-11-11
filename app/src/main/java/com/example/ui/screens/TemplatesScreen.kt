package com.example.ui.screens

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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.ui.SmartBubbleViewModel
import com.example.ui.components.TemplatePreviewDialog
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
    var templateNameInput by remember { mutableStateOf("") }
    var showUploadDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingUploadUri = uri
            templateNameInput = "My Custom Template"
            showUploadDialog = true
        }
    }

    val filteredTemplates = templates.filter { tmpl ->
        val matchesType = when (selectedFilter) {
            "Docs" -> tmpl.fileType.equals("DOCX", ignoreCase = true)
            "PPTX" -> tmpl.fileType.equals("PPTX", ignoreCase = true)
            "XLSX" -> tmpl.fileType.equals("XLSX", ignoreCase = true)
            else -> true
        }
        val matchesSearch = tmpl.name.contains(searchQuery, ignoreCase = true)
        matchesType && matchesSearch
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
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    IconButton(
                        onClick = { showSearchBar = !showSearchBar },
                        modifier = Modifier.testTag("templates_search_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "Search",
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
                                    Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Large prominent Upload Template Button matching mockup
        Button(
            onClick = {
                try {
                    filePickerLauncher.launch(
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "*/*"
                        )
                    )
                } catch (ignored: Exception) {
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(52.dp)
                .testTag("upload_template_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.templates_upload),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Filter Pills Row [All] [Docs] [PPTX] [XLSX]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filters = listOf(
                "All" to stringResource(R.string.filter_all),
                "Docs" to stringResource(R.string.filter_docs),
                "PPTX" to stringResource(R.string.filter_pptx),
                "XLSX" to stringResource(R.string.filter_xlsx)
            )

            filters.forEach { (key, label) ->
                val isSelected = selectedFilter == key
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = key },
                    label = {
                        Text(
                            text = label,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("filter_$key")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Templates List
        if (filteredTemplates.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredTemplates, key = { it.id }) { template ->
                    TemplateItemCard(
                        template = template,
                        onPreview = { viewModel.openTemplatePreview(template) },
                        onDuplicate = {
                            templateToDuplicate = template
                            duplicateNameInput = context.getString(R.string.template_duplicate_name_format, template.name)
                        },
                        onSetDefault = { viewModel.setDefaultTemplate(template) },
                        onRemoveDefault = { viewModel.removeDefaultTemplate(template) },
                        onDelete = { viewModel.deleteTemplate(template) },
                        onSelectForChat = {
                            viewModel.selectTemplateForChat(template)
                            viewModel.setNavTab(com.example.ui.AppNavTab.CHAT)
                        }
                    )
                }
            }
        }
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
        TemplatePreviewDialog(
            template = tmpl,
            previewResult = previewResult,
            isLoading = isPreviewLoading,
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
                val isDef = tmpl.isDefaultWord || tmpl.isDefaultPpt || tmpl.isDefaultExcel
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
                            viewModel.importTemplateFromUri(uri, name)
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
    onPreview: () -> Unit,
    onDuplicate: () -> Unit,
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
        else -> Color(0xFF1D52D8)
    }

    val badgeLetter = when (template.fileType.uppercase()) {
        "DOCX" -> "W"
        "PPTX" -> "P"
        "XLSX" -> "X"
        else -> "D"
    }

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
        else -> template.isDefaultWord
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPreview)
            .testTag("template_card_${template.id}"),
        shape = RoundedCornerShape(14.dp),
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
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeLetter,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
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
