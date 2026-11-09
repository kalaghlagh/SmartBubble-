package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.DocumentEntity
import com.example.ui.SmartBubbleViewModel
import com.example.ui.theme.*

@Composable
fun LibraryScreen(
    viewModel: SmartBubbleViewModel,
    modifier: Modifier = Modifier
) {
    val documents by viewModel.allDocuments.collectAsState()
    val inspectedDoc by viewModel.selectedDocumentForInspection.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "DOCX", "PPTX", "XLSX", "PDF", "FAVORITES"

    val filteredDocs = documents.filter { doc ->
        val matchesSearch = doc.title.contains(searchQuery, ignoreCase = true) ||
                doc.summary.contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (selectedFilter) {
            "ALL" -> true
            "FAVORITES" -> doc.isFavorite
            else -> doc.fileType == selectedFilter
        }
        matchesSearch && matchesFilter
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Document Library",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${documents.size} verified files in local executive storage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search documents, contracts, reports...", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("library_search_input"),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Filter Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterPill("All Files", isSelected = selectedFilter == "ALL") { selectedFilter = "ALL" }
            FilterPill("Word (.docx)", isSelected = selectedFilter == "DOCX") { selectedFilter = "DOCX" }
            FilterPill("PowerPoint (.pptx)", isSelected = selectedFilter == "PPTX") { selectedFilter = "PPTX" }
            FilterPill("Excel (.xlsx)", isSelected = selectedFilter == "XLSX") { selectedFilter = "XLSX" }
            FilterPill("PDF Docs", isSelected = selectedFilter == "PDF") { selectedFilter = "PDF" }
            FilterPill("Favorites", isSelected = selectedFilter == "FAVORITES") { selectedFilter = "FAVORITES" }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Document Cards List
        if (filteredDocs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No documents found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredDocs, key = { it.id }) { doc ->
                    LibraryDocCard(
                        doc = doc,
                        onOpen = { viewModel.openDocument(doc) },
                        onExportPdf = { viewModel.exportDocumentAsPdf(doc) },
                        onShare = { viewModel.shareDocument(doc) },
                        onInspect = { viewModel.inspectDocument(doc) },
                        onToggleFavorite = { viewModel.toggleFavorite(doc) },
                        onDelete = { viewModel.deleteDocument(doc) }
                    )
                }
            }
        }
    }

    // Inspect Raw Text Modal
    if (inspectedDoc != null) {
        val currentDoc = inspectedDoc!!
        AlertDialog(
            onDismissRequest = { viewModel.clearInspection() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = currentDoc.title, style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                ) {
                    Text(
                        text = "Metadata: ${currentDoc.fileType} • ${currentDoc.fileSize / 1024} KB • Orchestrator: ${currentDoc.providerUsed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = currentDoc.textContent.ifBlank { "(Binary document created and verified)" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.deliveryManager.deliverBoth(
                        file = java.io.File(currentDoc.filePath),
                        textContent = currentDoc.textContent,
                        mimeType = "text/plain",
                        title = currentDoc.title
                    )
                    viewModel.clearInspection()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy Text")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearInspection() }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun FilterPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.height(32.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun LibraryDocCard(
    doc: DocumentEntity,
    onOpen: () -> Unit,
    onExportPdf: () -> Unit,
    onShare: () -> Unit,
    onInspect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    val (icon, color) = when (doc.fileType) {
        "DOCX" -> Icons.Default.Description to WordBlue
        "PPTX" -> Icons.Default.Slideshow to PptOrange
        "XLSX" -> Icons.Default.TableChart to ExcelGreen
        else -> Icons.Default.PictureAsPdf to PdfRed
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library_doc_${doc.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = doc.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${doc.fileSize / 1024} KB • ${doc.pageOrSlideCount} Pages • ${doc.providerUsed}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (doc.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (doc.isFavorite) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onInspect, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Inspect", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onShare, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onExportPdf, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(14.dp), tint = PdfRed)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PDF", style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = onOpen,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Open", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
