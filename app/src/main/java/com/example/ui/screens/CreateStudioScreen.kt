package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.TemplateEntity
import com.example.template.domain.TemplateMode
import com.example.template.domain.TemplateProfile
import com.example.ui.AppNavTab
import com.example.ui.SmartBubbleViewModel
import com.example.ui.theme.*

@Composable
fun CreateStudioScreen(
    viewModel: SmartBubbleViewModel,
    modifier: Modifier = Modifier
) {
    val templates by viewModel.allTemplates.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val taskStatus by viewModel.taskStatusMessage.collectAsState()

    var selectedFormat by remember { mutableStateOf("DOCX") }
    var selectedTemplateEntity by remember { mutableStateOf<TemplateEntity?>(null) }
    var selectedMode by remember { mutableStateOf(TemplateMode.SMART_ADAPTATION) }
    var promptText by remember { mutableStateOf("") }
    var attachKnowledgeSources by remember { mutableStateOf(true) }

    LaunchedEffect(templates) {
        if (selectedTemplateEntity == null && templates.isNotEmpty()) {
            selectedTemplateEntity = templates.firstOrNull()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Generative Document Studio",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Compile native Office & PDF documents with your personal templates",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 1. Format Selector (4 tabs)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Select Document Architecture",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FormatSelectorButton(
                            label = "Word (.docx)",
                            format = "DOCX",
                            isSelected = selectedFormat == "DOCX",
                            color = WordBlue,
                            modifier = Modifier.weight(1f)
                        ) { selectedFormat = "DOCX" }

                        FormatSelectorButton(
                            label = "PowerPoint",
                            format = "PPTX",
                            isSelected = selectedFormat == "PPTX",
                            color = PptOrange,
                            modifier = Modifier.weight(1f)
                        ) { selectedFormat = "PPTX" }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FormatSelectorButton(
                            label = "Excel (.xlsx)",
                            format = "XLSX",
                            isSelected = selectedFormat == "XLSX",
                            color = ExcelGreen,
                            modifier = Modifier.weight(1f)
                        ) { selectedFormat = "XLSX" }

                        FormatSelectorButton(
                            label = "PDF Studio",
                            format = "PDF",
                            isSelected = selectedFormat == "PDF",
                            color = PdfRed,
                            modifier = Modifier.weight(1f)
                        ) { selectedFormat = "PDF" }
                    }
                }
            }
        }

        // 2. Personal Template Selector
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "My Templates Profile",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(
                            onClick = { viewModel.setNavTab(AppNavTab.SETTINGS) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Manage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(templates, key = { it.id }) { t ->
                            val isSelected = (selectedTemplateEntity?.id == t.id)
                            Surface(
                                onClick = { selectedTemplateEntity = t },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainer,
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .width(180.dp)
                                    .padding(vertical = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                        Text(
                                            text = t.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = t.compatibilityStatus,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Template Application Mode
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Template Application Mode",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ModeTabButton(
                            title = stringResource(R.string.mode_smart),
                            isSelected = selectedMode == TemplateMode.SMART_ADAPTATION,
                            modifier = Modifier.weight(1f)
                        ) { selectedMode = TemplateMode.SMART_ADAPTATION }

                        ModeTabButton(
                            title = stringResource(R.string.mode_strict),
                            isSelected = selectedMode == TemplateMode.STRICT_MATCH,
                            modifier = Modifier.weight(1f)
                        ) { selectedMode = TemplateMode.STRICT_MATCH }

                        ModeTabButton(
                            title = stringResource(R.string.mode_style),
                            isSelected = selectedMode == TemplateMode.STYLE_ONLY,
                            modifier = Modifier.weight(1f)
                        ) { selectedMode = TemplateMode.STYLE_ONLY }
                    }
                }
            }
        }

        // 4. Content Instructions & Prompt Input
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Document Instructions & Topic",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        placeholder = {
                            Text(
                                text = "e.g., Draft an enterprise SLA partnership agreement for 500 seats with tiered pricing, 99.9% uptime guarantees, and 6-week milestones...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 110.dp)
                            .testTag("create_prompt_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Attach NotebookLM Knowledge Checkbox
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = attachKnowledgeSources,
                                onCheckedChange = { attachKnowledgeSources = it }
                            )
                            Text(
                                text = "Ground with NotebookLM Knowledge Vault",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 5. Generate & Compile Action Button
        item {
            Button(
                onClick = {
                    val prompt = promptText.ifBlank {
                        "Generate a comprehensive $selectedFormat document with executive summary, structured data tables, and SLA terms"
                    }
                    val profile = selectedTemplateEntity?.let { TemplateProfile.fromJson(it.profileJson) }
                    viewModel.executePrompt(prompt, selectedFormat)
                    viewModel.setNavTab(AppNavTab.ASSISTANT)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("generate_and_compile_btn"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                enabled = !isGenerating
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = taskStatus,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Compile & Deliver Both ($selectedFormat + Clipboard)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatSelectorButton(
    label: String,
    format: String,
    isSelected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainer,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, color) else null,
        modifier = modifier.height(44.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ModeTabButton(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
