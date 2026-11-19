package com.example.ui.screens

import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.smarttemplates.LibraryTemplate
import com.example.smarttemplates.OutputKind
import com.example.smarttemplates.TemplateCatalog
import com.example.smarttemplates.TemplateCategory
import com.example.template.domain.TemplateProfile
import com.example.ui.SmartBubbleViewModel
import com.example.ui.SmartTemplateStrings
import com.example.ui.publishOnMain
import java.util.Locale

/** Library filters of My Templates. */
enum class TemplateLibraryFilter(val label: Int) {
    ALL(R.string.st_filter_all), BUILT_IN(R.string.st_filter_builtin), UPLOADED(R.string.st_filter_uploaded),
    FAVORITES(R.string.st_filter_favorites), DEFAULTS(R.string.st_filter_defaults)
}

@Composable
fun templateName(t: LibraryTemplate): String =
    t.builtIn?.let { stringResource(SmartTemplateStrings.builtInName(it.id)) } ?: t.uploaded?.name.orEmpty()

@Composable
fun templateDescription(t: LibraryTemplate): String =
    t.builtIn?.let { stringResource(SmartTemplateStrings.builtInDescription(it.id)) }
        ?: t.uploaded?.let { u -> TemplateProfile.fromJson(u.profileJson).sourceFileName.ifBlank { u.name } }.orEmpty()

private fun profileOf(t: LibraryTemplate): TemplateProfile = t.builtIn?.profile ?: TemplateProfile.fromJson(t.uploaded?.profileJson.orEmpty())

private fun colorOf(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
} catch (e: Exception) { Color.Gray }

/** Filter chips of the library. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateLibraryFilterRow(selected: TemplateLibraryFilter, onSelect: (TemplateLibraryFilter) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TemplateLibraryFilter.values().forEach { f ->
            FilterChip(selected = selected == f, onClick = { onSelect(f) }, label = { Text(stringResource(f.label)) },
                modifier = Modifier.testTag("st_filter_${f.name.lowercase()}"))
        }
    }
}

/**
 * The library scope filter as a menu anchored to a filter (tune) button (Stitch): All, Built-in,
 * My uploads, Favorites, Defaults. The button shows a dot while a scope other than All is active.
 * The menu is a popup anchored to the button, so it stays on screen at either edge and in RTL.
 */
@Composable
fun TemplateScopeMenu(selected: TemplateLibraryFilter, onSelect: (TemplateLibraryFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        val label = stringResource(R.string.templates_scope_filter)
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .clip(CircleShape)
                .background(if (selected != TemplateLibraryFilter.ALL || open) colors.surfaceContainerHigh else Color.Transparent)
                .semantics { contentDescription = label }
                .testTag("templates_scope_button")
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = colors.primary)
        }
        if (selected != TemplateLibraryFilter.ALL) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(10.dp)
                .background(colors.surface, CircleShape).padding(2.dp).background(colors.primary, CircleShape).testTag("templates_scope_active"))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = colors.surfaceContainerLowest,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 192.dp).testTag("templates_scope_menu")
        ) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            TemplateLibraryFilter.values().forEach { f ->
                val isSelected = f == selected
                DropdownMenuItem(
                    text = { Text(stringResource(f.label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) colors.primary else colors.onSurface) },
                    leadingIcon = { Icon(scopeIcon(f), contentDescription = null, tint = if (isSelected) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                    trailingIcon = if (isSelected) { { Icon(Icons.Default.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp)) } } else null,
                    onClick = { open = false; onSelect(f) },
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) colors.primary.copy(alpha = 0.1f) else Color.Transparent)
                        .semantics { this.selected = isSelected }
                        .testTag("st_filter_${f.name.lowercase()}")
                )
            }
        }
    }
}

private fun scopeIcon(f: TemplateLibraryFilter) = when (f) {
    TemplateLibraryFilter.ALL -> Icons.Default.Apps
    TemplateLibraryFilter.BUILT_IN -> Icons.Default.AutoFixHigh
    TemplateLibraryFilter.UPLOADED -> Icons.Default.CloudUpload
    TemplateLibraryFilter.FAVORITES -> Icons.Default.Star
    TemplateLibraryFilter.DEFAULTS -> Icons.Default.CheckCircle
}

/** A category tile: its name, kind of file and number of templates. */
@Composable
fun CategoryCard(category: TemplateCategory, count: Int, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("st_category_${category.id}"),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text(when (category.kind) { OutputKind.SPREADSHEET -> "XLS"; OutputKind.PRESENTATION -> "PPT"; else -> "DOC" },
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(SmartTemplateStrings.category(category.id)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.st_templates_count, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One template in the library: name, source, file type, description, default and favorite. */
@Composable
fun LibraryTemplateRow(
    template: LibraryTemplate,
    defaultBadgeFor: String?,
    onFavorite: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onSetDefault: (() -> Unit)?,
    onSelectForChat: (() -> Unit)? = null,
    /** Distinguishes the same template listed under several subcategories (test tags). */
    tagScope: String = ""
) {
    val profile = remember(template.id) { profileOf(template) }
    val key = if (tagScope.isEmpty()) template.id else "${tagScope}_${template.id}"
    Card(Modifier.fillMaxWidth().testTag("st_template_$key"), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Color swatch of the template (its main and secondary colors).
                Row(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))) {
                    Box(Modifier.size(width = 18.dp, height = 36.dp).background(colorOf(profile.primaryColorHex), RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)))
                    Box(Modifier.size(width = 18.dp, height = 36.dp).background(colorOf(profile.tableHeaderBgHex), RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(templateName(template), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val category = template.categoryId?.let { stringResource(SmartTemplateStrings.category(it)) }
                    val sub = template.subcategoryId?.let { stringResource(SmartTemplateStrings.subcategory(it)) }
                    Text(listOfNotNull(
                        stringResource(if (template.isBuiltIn) R.string.st_builtin else R.string.st_uploaded),
                        template.fileType, category, sub
                    ).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                IconButton(onClick = { onFavorite(!template.favorite) }, modifier = Modifier.testTag("st_favorite_$key")) {
                    Icon(if (template.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline, contentDescription = stringResource(R.string.st_favorite),
                        tint = if (template.favorite) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(templateDescription(template), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (defaultBadgeFor != null) {
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.padding(top = 6.dp).testTag("st_default_badge_$key")) {
                    Text(stringResource(R.string.st_default_for, defaultBadgeFor), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onPreview, modifier = Modifier.testTag("st_preview_$key")) { Text(stringResource(R.string.st_preview)) }
                if (onSetDefault != null) TextButton(onClick = onSetDefault, modifier = Modifier.testTag("st_set_default_$key")) { Text(stringResource(R.string.st_set_default)) }
                if (onSelectForChat != null) TextButton(onClick = onSelectForChat, modifier = Modifier.testTag("st_use_$key")) { Text(stringResource(R.string.st_select_for_chat)) }
            }
        }
    }
}

/**
 * The subcategories of a category, each with the templates that can format it, its default
 * (marked) and a "Set as default" action that applies to that subcategory only.
 */
@Composable
fun CategoryContent(
    viewModel: SmartBubbleViewModel,
    category: TemplateCategory,
    library: List<LibraryTemplate>,
    onBack: () -> Unit,
    onPreview: (LibraryTemplate) -> Unit
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("st_category_screen")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("st_category_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
            Text(stringResource(SmartTemplateStrings.category(category.id)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        for (sub in category.subcategories) {
            val templates = library.filter { it.appliesTo(sub.id) }
            val subName = stringResource(SmartTemplateStrings.subcategory(sub.id))
            val explicitDefault = templates.firstOrNull { sub.id in it.defaultFor }
            val categoryDefault = templates.firstOrNull { category.id in it.defaultFor }
            Text(subName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp).testTag("st_sub_${sub.id}"))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in templates) {
                    val isDefault = t == explicitDefault || (explicitDefault == null && t == categoryDefault)
                    LibraryTemplateRow(
                        template = t,
                        defaultBadgeFor = if (isDefault) subName else null,
                        onFavorite = { viewModel.setTemplateFavorite(t.id, it) },
                        onPreview = { onPreview(t) },
                        onSetDefault = if (t == explicitDefault) null else ({ viewModel.setDefaultTemplateFor(sub.id, t.id) }),
                        tagScope = sub.id
                    )
                }
            }
        }
    }
}

/** Details of a template: page, fonts, margins, spacing, header/footer, border, colors and a sample. */
@Composable
fun TemplateDetailsDialog(viewModel: SmartBubbleViewModel, template: LibraryTemplate, onDismiss: () -> Unit) {
    val p = remember(template.id) { profileOf(template) }
    fun cm(pt: Int) = String.format(Locale.getDefault(), "%.1f", pt * 2.54 / 72)
    val sample by produceState<ImageBitmap?>(null, template.id) {
        publishOnMain(template.builtIn?.let { viewModel.builtInSamplePage(it.id, 600)?.asImageBitmap() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("st_details_dialog"),
        title = { Text(templateName(template), maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(templateDescription(template), style = MaterialTheme.typography.bodySmall)
                val kind = OutputKind.ofFormat(template.fileType.let { if (it == "PDF") "DOCX" else it })
                if (kind == OutputKind.DOCUMENT) {
                    val w = if (p.pageWidthPt > 0) p.pageWidthPt else 595
                    val h = if (p.pageHeightPt > 0) p.pageHeightPt else 842
                    Text(stringResource(R.string.st_page, "${cm(w)} × ${cm(h)} cm"), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.st_margins, listOf(p.marginTopPt, p.marginBottomPt, p.marginLeftPt, p.marginRightPt).joinToString("/") { cm(it) }), style = MaterialTheme.typography.bodySmall)
                    if (p.lineSpacing > 0f) Text(stringResource(R.string.st_spacing, String.format(Locale.getDefault(), "%.2f", p.lineSpacing)), style = MaterialTheme.typography.bodySmall)
                    val hf = listOfNotNull(p.headerText.takeIf { it.isNotBlank() }, p.footerText.takeIf { it.isNotBlank() },
                        stringResource(R.string.st_page_numbers).takeIf { p.pageNumberPosition.isNotBlank() }).joinToString(", ").ifEmpty { stringResource(R.string.st_none) }
                    Text(stringResource(R.string.st_header_footer, hf), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.st_border, if (p.pageBorders.isBlank()) stringResource(R.string.st_none) else p.pageBorders.split(';').joinToString(", ") { it.substringBefore(':') }),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.st_fonts, p.headingFont, p.bodyFont), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.st_colors) + "  ", style = MaterialTheme.typography.bodySmall)
                    listOf(p.primaryColorHex, p.secondaryColorHex, p.tableHeaderBgHex, p.textColorHex).distinct().forEach {
                        Box(Modifier.padding(end = 4.dp).size(18.dp).background(colorOf(it), CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
                }
                if (template.isBuiltIn && kind == OutputKind.DOCUMENT) {
                    Text(stringResource(R.string.st_example), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    val b = sample
                    if (b == null) Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    else Image(b, contentDescription = stringResource(R.string.st_example), contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant).testTag("st_sample_page"))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("st_details_close")) { Text(stringResource(R.string.action_close)) } },
        dismissButton = if (template.builtIn != null) {
            { TextButton(onClick = { viewModel.openBuiltInSample(template.builtIn.id) }, modifier = Modifier.testTag("st_open_sample")) { Text(stringResource(R.string.st_open_sample)) } }
        } else null
    )
}

/**
 * Non-blocking card asking where a newly uploaded template belongs (category and kind of
 * document), pre-filled with a suggestion from its file name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClassificationCard(viewModel: SmartBubbleViewModel, pending: SmartBubbleViewModel.PendingClassification) {
    val kind = OutputKind.ofFormat(pending.template.fileType.uppercase().let { if (it == "PDF") "DOCX" else it })
    val categories = TemplateCatalog.categories.filter { it.kind == kind }
    var category by remember(pending.template.id) { mutableStateOf(pending.categoryId) }
    var sub by remember(pending.template.id) { mutableStateOf(pending.subcategoryId) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("st_classify_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.st_classify_title, pending.template.name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.st_classify_category), style = MaterialTheme.typography.labelMedium)
            // One scrolling line each, so the card stays small above the list. (Wrapping these
            // rows made the card fill the screen and hid the templates below it: run #89.)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                categories.forEach { c ->
                    FilterChip(selected = category == c.id, onClick = { category = c.id; sub = null }, label = { Text(stringResource(SmartTemplateStrings.category(c.id))) },
                        modifier = Modifier.testTag("st_classify_cat_${c.id}"))
                }
            }
            Text(stringResource(R.string.st_classify_subcategory), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(selected = sub == null, onClick = { sub = null }, label = { Text(stringResource(R.string.st_whole_category)) })
                TemplateCatalog.category(category)?.subcategories?.forEach { s ->
                    FilterChip(selected = sub == s.id, onClick = { sub = s.id }, label = { Text(stringResource(SmartTemplateStrings.subcategory(s.id))) },
                        modifier = Modifier.testTag("st_classify_sub_${s.id}"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.classifyTemplate(pending.template.id, category, sub) }, modifier = Modifier.testTag("st_classify_save")) { Text(stringResource(R.string.st_save)) }
                OutlinedButton(onClick = { viewModel.dismissTemplateClassification() }, modifier = Modifier.testTag("st_classify_later")) { Text(stringResource(R.string.st_later)) }
            }
        }
    }
}

/** Templates that can format a generated document (same kind), for "Change template". */
@Composable
fun TemplatePickerDialog(viewModel: SmartBubbleViewModel, subcategoryId: String?, fileType: String, currentId: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val library by viewModel.templateLibrary.collectAsState()
    val kind = OutputKind.ofFormat(fileType)
    val options = library.filter { t ->
        if (subcategoryId != null) t.appliesTo(subcategoryId) else t.fileType.uppercase() in kind.templateFileTypes
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("st_picker_dialog"),
        title = { Text(stringResource(R.string.st_pick_template_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.st_pick_template_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                options.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(t.id) }.padding(vertical = 10.dp).testTag("st_pick_${t.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(colorOf(profileOf(t).primaryColorHex), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(templateName(t), fontWeight = if (t.id == currentId) FontWeight.Bold else FontWeight.Normal)
                            Text(stringResource(if (t.isBuiltIn) R.string.st_builtin else R.string.st_uploaded) + " · " + t.fileType,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Settings of Smart Templates: Auto Format, document language and format, default templates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartTemplatesSettingsCard(viewModel: SmartBubbleViewModel) {
    val auto by viewModel.autoFormat.collectAsState()
    val language by viewModel.documentLanguage.collectAsState()
    val format by viewModel.documentFormat.collectAsState()
    val library by viewModel.templateLibrary.collectAsState()
    // Collapsed by default; the state survives rotation, never the values (they are persisted
    // by the view model as before).
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val expandLabel = stringResource(if (expanded) R.string.st_settings_collapse else R.string.st_settings_expand)
    val stateLabel = stringResource(if (expanded) R.string.st_settings_expanded else R.string.st_settings_collapsed)
    Card(Modifier.fillMaxWidth().testTag("st_settings_card"), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth()) {
            // Header: the whole row toggles the section.
            Row(
                Modifier.fillMaxWidth()
                    .clickable(onClickLabel = expandLabel, role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = stateLabel }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("st_settings_header"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.st_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.st_settings_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val turn by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.rotate(turn).testTag("st_settings_chevron"))
            }
            androidx.compose.animation.AnimatedVisibility(visible = expanded,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag("st_settings_content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider()
                    // 1. Automatic formatting.
                    SettingsGroupTitle(R.string.st_group_automatic)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.st_auto_format), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.st_auto_format_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(checked = auto, onCheckedChange = { viewModel.setAutoFormat(it) }, modifier = Modifier.testTag("st_auto_format_switch"))
                    }
                    // 2. New documents: language and format.
                    SettingsGroupTitle(R.string.st_group_new_documents)
                    Text(stringResource(R.string.st_document_language), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("auto" to stringResource(R.string.st_language_auto), "ar" to "العربية", "fr" to "Français", "en" to "English").forEach { (code, label) ->
                            FilterChip(selected = language == code, onClick = { viewModel.setDocumentLanguage(code) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 40.dp).testTag("st_doc_lang_$code"))
                        }
                    }
                    Text(stringResource(R.string.st_document_format), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("DOCX" to "Word", "PDF" to "PDF").forEach { (code, label) ->
                            FilterChip(selected = format == code, onClick = { viewModel.setDocumentFormat(code) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 40.dp).testTag("st_doc_format_$code"))
                        }
                    }
                    // 3. Default templates.
                    SettingsGroupTitle(R.string.st_defaults_overview)
                    val defaults = library.flatMap { t -> t.defaultFor.map { scope -> scope to t } }.sortedBy { it.first }
                    if (defaults.isEmpty()) Text(stringResource(R.string.st_defaults_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    defaults.forEach { (scope, t) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("st_default_row_$scope")) {
                            Text(stringResource(SmartTemplateStrings.subcategory(scope)) + " → " + templateName(t), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.clearDefaultTemplateFor(scope) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.st_remove_default)) }
                        }
                    }
                    OutlinedButton(onClick = { viewModel.setNavTab(com.example.ui.AppNavTab.TEMPLATES) }, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("st_manage_templates")) {
                        Text(stringResource(R.string.st_manage_templates))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupTitle(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() })
}
