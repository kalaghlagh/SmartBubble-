package com.example.thesis.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.TemplateEntity
import com.example.data.model.ThesisRevisionEntity
import com.example.template.storage.TemplateThumbnails
import com.example.thesis.ProposalReview
import com.example.thesis.ThesisBusy
import com.example.thesis.ThesisController
import com.example.thesis.ThesisExportResult
import com.example.thesis.ThesisScreen
import com.example.thesis.core.ChangeKind
import com.example.thesis.core.EditMode
import com.example.thesis.core.ElementChange
import com.example.thesis.core.NewElement
import com.example.thesis.core.SegmentKind
import com.example.thesis.core.ThesisDiffResult
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.core.ThesisStructure
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ChangeSource
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.ProposalStatus
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisAsset
import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Content of the thesis follows its own direction (Arabic right to left) whatever the app language. */
@Composable
private fun contentStyle() = LocalTextStyle.current.copy(textDirection = TextDirection.Content)

@Composable
private fun levelLabel(level: String) = stringResource(
    when (level) { "LICENCE" -> R.string.thesis_level_licence; "DOCTORATE" -> R.string.thesis_level_doctorate; else -> R.string.thesis_level_master }
)

@Composable
private fun sourceLabel(source: String) = stringResource(
    when (ChangeSource.parse(source)) {
        ChangeSource.USER -> R.string.thesis_source_user
        ChangeSource.AI -> R.string.thesis_source_ai
        ChangeSource.FORMATTING -> R.string.thesis_source_formatting
        ChangeSource.IMAGE -> R.string.thesis_source_image
        ChangeSource.TABLE -> R.string.thesis_source_table
        ChangeSource.STRUCTURE -> R.string.thesis_source_structure
        ChangeSource.RESTORE -> R.string.thesis_source_restore
    }
)

private fun formatDate(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

/** Small image preview decoded at reduced size (the stored original is untouched). */
@Composable
fun ThesisImageThumb(path: String?, modifier: Modifier = Modifier, maxPx: Int = 480) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) {
                try {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(p, o)
                    var sample = 1
                    while (o.outWidth / (sample * 2) >= maxPx) sample *= 2
                    BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                } catch (e: Throwable) { null }
            }
        }
    }
    val b = bitmap
    if (b != null) Image(b, contentDescription = stringResource(R.string.thesis_image), modifier = modifier, contentScale = ContentScale.Fit)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(stringResource(R.string.thesis_image), style = MaterialTheme.typography.labelSmall) }
}

/** Entry dialog of the Thesis Builder (opened from Chat; no extra navigation tab). */
@Composable
fun ThesisWorkspaceDialog(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    if (!state.workspaceOpen) return
    Dialog(
        // System back goes one level up (section -> structure -> theses) before closing.
        onDismissRequest = { backTarget(state.screen)?.let { controller.show(it) } ?: controller.closeWorkspace() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        ThesisWorkspaceContent(host)
    }
    state.review?.let { ThesisReviewDialog(controller, it) }
}

/** Tabs of an open thesis (the workspace itself; the metadata form is only in Project settings). */
internal val projectTabs = listOf(ThesisScreen.PROJECT, ThesisScreen.STRUCTURE, ThesisScreen.CHANGES, ThesisScreen.HISTORY, ThesisScreen.PREVIEW)

internal fun backTarget(screen: ThesisScreen): ThesisScreen? = when (screen) {
    ThesisScreen.HOME -> null
    ThesisScreen.SECTION -> ThesisScreen.STRUCTURE
    ThesisScreen.SETTINGS -> ThesisScreen.PROJECT
    else -> ThesisScreen.HOME
}

/** The workspace window: top bar, project tabs, the current screen and its own snackbar. */
@Composable
fun ThesisWorkspaceContent(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val proposals by controller.openProposals.collectAsState()
    val feedback by host.feedback.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(feedback) {
        feedback?.let { message ->
            host.consumeFeedback()
            snackbar.showSnackbar(message)
        }
    }
    Surface(Modifier.fillMaxSize().testTag("thesis_workspace"), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                val back = backTarget(state.screen)
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (back != null) {
                        IconButton(onClick = { controller.show(back) }, modifier = Modifier.testTag("thesis_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.thesis_back))
                        }
                    } else {
                        Icon(Icons.Outlined.School, contentDescription = null, modifier = Modifier.padding(12.dp))
                    }
                    Text(
                        if (state.screen == ThesisScreen.HOME || state.screen == ThesisScreen.CREATE) stringResource(R.string.thesis_builder)
                        else state.snapshot?.meta?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() }.testTag("thesis_title_bar")
                    )
                    IconButton(onClick = { controller.closeWorkspace() }, modifier = Modifier.testTag("thesis_close")) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
                if (state.projectId != null && state.screen in projectTabs) {
                    ScrollableTabRow(selectedTabIndex = projectTabs.indexOf(state.screen), edgePadding = 8.dp) {
                        projectTabs.forEach { s ->
                            Tab(
                                selected = s == state.screen,
                                onClick = { controller.show(s) },
                                text = { Text(tabLabel(s, proposals.size), maxLines = 1) },
                                modifier = Modifier.testTag("thesis_tab_${s.name.lowercase()}")
                            )
                        }
                    }
                }
                state.busy?.let { busy -> BusyBar(controller, busy) }
                HorizontalDivider()
                Box(Modifier.weight(1f)) {
                    when (state.screen) {
                        ThesisScreen.HOME -> ThesisHome(controller)
                        ThesisScreen.CREATE -> ThesisCreateScreen(host)
                        ThesisScreen.PROJECT -> ThesisOverviewScreen(host)
                        ThesisScreen.STRUCTURE -> ThesisStructureScreen(controller)
                        ThesisScreen.SECTION -> ThesisSectionScreen(host)
                        ThesisScreen.CHANGES -> ThesisChangesScreen(controller)
                        ThesisScreen.HISTORY -> ThesisHistoryScreen(controller)
                        ThesisScreen.PREVIEW -> ThesisPreviewScreen(controller)
                        ThesisScreen.SETTINGS -> ThesisSettingsScreen(host)
                    }
                }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (state.screen == ThesisScreen.CREATE || state.screen == ThesisScreen.SETTINGS) 72.dp else 8.dp)
                    .testTag("thesis_snackbar")
            )
        }
    }
}

@Composable
private fun tabLabel(screen: ThesisScreen, pending: Int): String = when (screen) {
    ThesisScreen.PROJECT -> stringResource(R.string.thesis_tab_overview)
    ThesisScreen.STRUCTURE -> stringResource(R.string.thesis_tab_structure)
    ThesisScreen.CHANGES -> if (pending > 0) stringResource(R.string.thesis_tab_changes_n, pending) else stringResource(R.string.thesis_tab_changes)
    ThesisScreen.HISTORY -> stringResource(R.string.thesis_tab_history)
    else -> stringResource(R.string.thesis_tab_preview)
}

@Composable
fun BusyBar(controller: ThesisController, busy: ThesisBusy) {
    val label = stringResource(
        when (busy) {
            ThesisBusy.ANALYZING -> R.string.thesis_busy_analyzing
            ThesisBusy.ASKING_AI -> R.string.thesis_busy_ai
            ThesisBusy.EXPORTING -> R.string.thesis_busy_exporting
            ThesisBusy.PREVIEWING -> R.string.thesis_busy_preview
            ThesisBusy.SAVING -> R.string.thesis_busy_saving
        }
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { contentDescription = label }.testTag("thesis_busy"), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (busy != ThesisBusy.SAVING) TextButton(onClick = { controller.cancel() }, modifier = Modifier.testTag("thesis_stop")) { Text(stringResource(R.string.thesis_stop)) }
    }
}

// ------------------------------------------------------------------ home

@Composable
internal fun ThesisHome(controller: ThesisController) {
    val projects by controller.projects.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.thesis_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { controller.show(ThesisScreen.CREATE) }, modifier = Modifier.fillMaxWidth().testTag("thesis_new")) {
                Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.thesis_create_new))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.thesis_recent), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        if (projects.isEmpty()) item { Text(stringResource(R.string.thesis_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(projects, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth().clickable { controller.openProject(p.id) }.testTag("thesis_project_${p.id}")) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.SemiBold)
                    Text("${levelLabel(p.level)} · ${p.language.uppercase()} · " + stringResource(R.string.thesis_revision_short, p.currentRevisionNumber),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.thesis_last_saved, formatDate(p.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ create

/** New thesis: metadata form with a fixed Cancel / Create bar (the button never scrolls away). */
@Composable
private fun ThesisCreateScreen(host: ThesisHost) {
    val controller = host.thesis
    val projects by controller.projects.collectAsState()
    val state by controller.state.collectAsState()
    ThesisMetaForm(
        host = host,
        initial = null,
        busy = state.busy != null,
        saveLabel = R.string.thesis_create,
        onCancel = { if (projects.isEmpty()) controller.closeWorkspace() else controller.show(ThesisScreen.HOME) },
        onOpenExisting = if (projects.isEmpty()) null else ({ controller.show(ThesisScreen.HOME) })
    ) { meta, ai -> controller.createProject(meta, ai) }
}

// ------------------------------------------------------------------ metadata form (create / settings)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThesisMetaForm(
    host: ThesisHost,
    initial: ThesisMeta?,
    initialAi: Boolean = true,
    busy: Boolean,
    saveLabel: Int,
    onCancel: () -> Unit,
    onOpenExisting: (() -> Unit)? = null,
    header: @Composable () -> Unit = {},
    onSave: (ThesisMeta, Boolean) -> Unit
) {
    val templates by host.templateList.collectAsState()
    var meta by remember(initial) { mutableStateOf(initial ?: ThesisMeta("", language = host.defaultContentLanguage.takeIf { it in listOf("ar", "fr", "en") } ?: "en")) }
    var ai by remember(initial) { mutableStateOf(initialAi) }
    var titleError by remember { mutableStateOf(false) }
    val usable = templates.filter { it.fileType.equals("DOCX", true) || it.fileType.equals("PDF", true) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            header()
            onOpenExisting?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("thesis_open_existing")) { Text(stringResource(R.string.thesis_open_existing)) }
            }
            @Composable
            fun field(label: Int, value: String, tag: String, lines: Int = 1, onChange: (String) -> Unit) = OutlinedTextField(
                value = value, onValueChange = onChange, label = { Text(stringResource(label)) }, singleLine = lines == 1, minLines = lines,
                textStyle = contentStyle(), modifier = Modifier.fillMaxWidth().testTag(tag)
            )
            OutlinedTextField(
                value = meta.title, onValueChange = { meta = meta.copy(title = it); if (it.isNotBlank()) titleError = false },
                label = { Text(stringResource(R.string.thesis_field_title) + " *") }, isError = titleError,
                supportingText = { if (titleError) Text(stringResource(R.string.thesis_title_required), modifier = Modifier.testTag("thesis_title_error")) },
                textStyle = contentStyle(), modifier = Modifier.fillMaxWidth().testTag("thesis_field_title")
            )
            Text(stringResource(R.string.thesis_field_level), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AcademicLevel.values().forEach { l ->
                    FilterChip(selected = meta.level == l, onClick = { meta = meta.copy(level = l) }, label = { Text(levelLabel(l.name)) }, modifier = Modifier.testTag("thesis_level_${l.name}"))
                }
            }
            Text(stringResource(R.string.thesis_field_language), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ar" to "العربية", "fr" to "Français", "en" to "English").forEach { (code, name) ->
                    FilterChip(selected = meta.language == code, onClick = { meta = meta.copy(language = code) }, label = { Text(name) }, modifier = Modifier.testTag("thesis_lang_$code"))
                }
            }
            Text(stringResource(R.string.thesis_field_template), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = meta.templateId == null, onClick = { meta = meta.copy(templateId = null) }, label = { Text(stringResource(R.string.thesis_template_default)) })
                usable.forEach { t: TemplateEntity ->
                    FilterChip(selected = meta.templateId == t.id, onClick = { meta = meta.copy(templateId = t.id) },
                        label = { Text("${t.name} (${t.fileType})", maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.testTag("thesis_template_${t.id}"))
                }
            }
            Text(stringResource(R.string.thesis_template_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            field(R.string.thesis_field_student, meta.studentName, "thesis_field_student") { meta = meta.copy(studentName = it) }
            field(R.string.thesis_field_university, meta.university, "thesis_field_university") { meta = meta.copy(university = it) }
            field(R.string.thesis_field_faculty, meta.faculty, "thesis_field_faculty") { meta = meta.copy(faculty = it) }
            field(R.string.thesis_field_department, meta.department, "thesis_field_department") { meta = meta.copy(department = it) }
            field(R.string.thesis_field_specialization, meta.specialization, "thesis_field_specialization") { meta = meta.copy(specialization = it) }
            field(R.string.thesis_field_supervisor, meta.supervisor, "thesis_field_supervisor") { meta = meta.copy(supervisor = it) }
            field(R.string.thesis_field_cosupervisor, meta.coSupervisor, "thesis_field_cosupervisor") { meta = meta.copy(coSupervisor = it) }
            field(R.string.thesis_field_year, meta.academicYear, "thesis_field_year") { meta = meta.copy(academicYear = it) }
            field(R.string.thesis_field_jury, meta.juryMembers, "thesis_field_jury", 3) { meta = meta.copy(juryMembers = it) }
            field(R.string.thesis_field_extra, meta.extraMetadata, "thesis_field_extra", 2) { meta = meta.copy(extraMetadata = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.thesis_ai_assist), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.thesis_ai_assist_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ai, onCheckedChange = { ai = it }, modifier = Modifier.testTag("thesis_ai_switch"))
            }
        }
        HorizontalDivider()
        // Fixed action bar: Create stays visible however long the form is.
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("thesis_cancel_meta")) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = {
                    if (meta.title.isBlank()) titleError = true
                    else onSave(meta.copy(title = meta.title.trim()), ai)
                },
                // Disabled while saving: tapping again cannot create a second project.
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("thesis_save_meta")
            ) {
                if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(stringResource(saveLabel))
            }
        }
    }
}

// ------------------------------------------------------------------ overview (workspace home)

@Composable
private fun ThesisOverviewScreen(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val proposals by controller.openProposals.collectAsState()
    val projects by controller.projects.collectAsState()
    val templates by host.templateList.collectAsState()
    val snapshot = state.snapshot ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val project = projects.firstOrNull { it.id == state.projectId }
    val projectId = state.projectId ?: return
    val idle = state.busy == null
    var deleting by remember { mutableStateOf(false) }
    // "Edit selected section": the last opened section, else the first section that holds content.
    val editable = snapshot.section(state.selectedSectionId)?.takeIf { it.kind.writable }
        ?: snapshot.outline(includeDisabled = false).map { it.first }.firstOrNull { it.kind.writable }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth().testTag("thesis_overview")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(snapshot.meta.title, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.thesis_level_label, levelLabel(snapshot.meta.level.name)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.thesis_language_label, languageName(snapshot.meta.language)), style = MaterialTheme.typography.bodySmall)
                val template = templates.firstOrNull { it.id == snapshot.meta.templateId }
                Text(stringResource(R.string.thesis_template_label, template?.let { "${it.name} (${it.fileType})" } ?: stringResource(R.string.thesis_template_default)), style = MaterialTheme.typography.bodySmall)
                project?.let {
                    Text(stringResource(R.string.thesis_created_on, formatDate(it.createdAt)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.thesis_modified_on, formatDate(it.updatedAt)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.thesis_current_revision, it.currentRevisionNumber), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("thesis_current_revision"))
                }
                val completion = ThesisStructure.completion(snapshot)
                Text(stringResource(R.string.thesis_completion, completion), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { completion / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
        state.lastExport?.let { e -> ExportResultCard(host, e) }
        Text(stringResource(R.string.thesis_actions), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        ActionRow(
            { ActionButton(R.string.thesis_continue_in_chat, "thesis_continue_chat", primary = true) { host.continueThesisInChat(projectId, state.selectedSectionId) } },
            { ActionButton(R.string.thesis_edit_structure, "thesis_edit_structure") { controller.show(ThesisScreen.STRUCTURE) } }
        )
        ActionRow(
            { ActionButton(R.string.thesis_edit_section, "thesis_edit_section", enabled = editable != null) { editable?.let { controller.selectSection(it.id) } } },
            { ActionButton(R.string.thesis_preview, "thesis_preview", enabled = idle) { controller.buildPreview() } }
        )
        ActionRow(
            { ActionButton(stringResource(R.string.thesis_review_pending, proposals.size), "thesis_review_pending") { controller.show(ThesisScreen.CHANGES) } },
            { ActionButton(R.string.thesis_history, "thesis_history") { controller.show(ThesisScreen.HISTORY) } }
        )
        ActionRow(
            { ActionButton(R.string.thesis_export_docx, "thesis_export_docx", enabled = idle) { controller.export("DOCX") } },
            { ActionButton(R.string.thesis_export_pdf, "thesis_export_pdf", enabled = idle) { controller.export("PDF") } }
        )
        ActionRow(
            { ActionButton(R.string.thesis_project_settings, "thesis_settings") { controller.show(ThesisScreen.SETTINGS) } },
            { ActionButton(R.string.thesis_delete_project, "thesis_delete_project", enabled = idle, danger = true) { deleting = true } }
        )
        Text(stringResource(R.string.thesis_export_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (deleting) DeleteProjectDialog(onDismiss = { deleting = false }) { deleting = false; controller.deleteProject(projectId) }
}

@Composable
private fun ActionRow(first: @Composable RowScope.() -> Unit, second: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { first(); second() }
}

@Composable
private fun RowScope.ActionButton(label: Int, tag: String, enabled: Boolean = true, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) =
    ActionButton(stringResource(label), tag, enabled, primary, danger, onClick)

@Composable
private fun RowScope.ActionButton(label: String, tag: String, enabled: Boolean = true, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(tag)
    if (primary) Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label, textAlign = TextAlign.Center) }
    else OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        colors = if (danger) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonColors()
    ) { Text(label, textAlign = TextAlign.Center) }
}

/** Shown only after the file was written and saved to the Library. */
@Composable
private fun ExportResultCard(host: ThesisHost, e: ThesisExportResult) {
    Card(Modifier.fillMaxWidth().testTag("thesis_export_result"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.thesis_export_saved, e.format, e.document.title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            e.notes.distinct().forEach { Text(noteText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { host.openExportedDocument(e.document) }, modifier = Modifier.testTag("thesis_export_open")) { Text(stringResource(R.string.thesis_open_file)) }
                TextButton(onClick = { host.shareExportedDocument(e.document) }, modifier = Modifier.testTag("thesis_export_share")) { Text(stringResource(R.string.thesis_share_file)) }
            }
        }
    }
}

@Composable
private fun DeleteProjectDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thesis_delete_project_title)) },
        text = { Text(stringResource(R.string.thesis_delete_project_text)) },
        confirmButton = { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("thesis_delete_project_confirm")) { Text(stringResource(R.string.template_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

private fun languageName(code: String) = when (code) { "ar" -> "العربية"; "fr" -> "Français"; else -> "English" }

// ------------------------------------------------------------------ structure

@Composable
private fun ThesisStructureScreen(controller: ThesisController) {
    val state by controller.state.collectAsState()
    val snapshot = state.snapshot ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val numbering = remember(snapshot) { ThesisStructure.numbering(snapshot) }
    var renaming by remember { mutableStateOf<ThesisSection?>(null) }
    var deleting by remember { mutableStateOf<ThesisSection?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("thesis_structure"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(stringResource(R.string.thesis_structure_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                val last = snapshot.children(null).lastOrNull { it.kind == SectionKind.CHAPTER }
                controller.direct(listOf(ThesisOp.AddSection(ThesisSection(ThesisStructure.newId("sec"), null, SectionKind.CHAPTER, "", 0), last?.id)), ChangeSource.STRUCTURE, "chapter")
            }, enabled = state.busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("thesis_add_chapter")) {
                Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.thesis_add_chapter))
            }
        }
        items(snapshot.outline(includeDisabled = true), key = { it.first.id }) { (s, depth) ->
            SectionRow(controller, snapshot, s, depth, ThesisStructure.displayTitle(s, numbering, snapshot.meta.language), onRename = { renaming = s }, onDelete = { deleting = s })
        }
    }
    renaming?.let { s ->
        TextInputDialog(stringResource(R.string.thesis_rename), s.title, onDismiss = { renaming = null }) { title ->
            controller.direct(listOf(ThesisOp.UpdateSection(s.id, title = title)), ChangeSource.STRUCTURE, "rename"); renaming = null
        }
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.thesis_delete_section_title)) },
            text = { Text(stringResource(R.string.thesis_delete_section_text)) },
            confirmButton = { Button(onClick = { controller.direct(listOf(ThesisOp.DeleteSection(s.id)), ChangeSource.STRUCTURE, "delete section"); deleting = null },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("thesis_delete_section_confirm")) { Text(stringResource(R.string.template_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

// ------------------------------------------------------------------ pending changes

@Composable
private fun ThesisChangesScreen(controller: ThesisController) {
    val proposals by controller.openProposals.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("thesis_changes"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.thesis_changes_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (proposals.isEmpty()) item { Text(stringResource(R.string.thesis_no_pending), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("thesis_no_pending")) }
        items(proposals, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth().clickable { controller.openReview(p.id) }.testTag("thesis_pending_${p.id}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))) {
                Column(Modifier.padding(12.dp)) {
                    Text(sourceLabel(p.source) + " · " + statusLabel(p.status), style = MaterialTheme.typography.labelMedium)
                    Text(formatDate(p.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.request.take(160), style = contentStyle().copy(fontSize = 13.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { controller.openReview(p.id) }, modifier = Modifier.testTag("thesis_open_review_${p.id}")) { Text(stringResource(R.string.thesis_review_open)) }
                }
            }
        }
    }
}


@Composable
private fun statusLabel(status: String) = stringResource(
    when (ProposalStatus.parse(status)) {
        ProposalStatus.NEEDS_DESTINATION -> R.string.thesis_status_needs_destination
        ProposalStatus.CONFLICT -> R.string.thesis_status_conflict
        ProposalStatus.ACCEPTED -> R.string.thesis_status_accepted
        ProposalStatus.REJECTED -> R.string.thesis_status_rejected
        ProposalStatus.PENDING -> R.string.thesis_status_pending
    }
)

@Composable
private fun SectionRow(controller: ThesisController, snapshot: ThesisSnapshot, s: ThesisSection, depth: Int, title: String, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val siblings = snapshot.children(s.parentId)
    val index = siblings.indexOfFirst { it.id == s.id }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = s.kind.writable) { controller.selectSection(s.id) }
            .padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp).testTag("thesis_section_${s.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = contentStyle().copy(fontSize = 14.sp), fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (s.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textDecoration = if (s.enabled) null else TextDecoration.LineThrough)
            val count = snapshot.elementsOf(s.id).size
            Text(if (s.kind.generated) stringResource(R.string.thesis_generated) else stringResource(R.string.thesis_elements_count, count),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("thesis_section_menu_${s.id}")) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.library_more_options)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.thesis_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(if (s.enabled) R.string.thesis_disable else R.string.thesis_enable)) },
                    onClick = { menu = false; controller.direct(listOf(ThesisOp.UpdateSection(s.id, enabled = !s.enabled)), ChangeSource.STRUCTURE, "toggle") })
                if (index > 0) DropdownMenuItem(text = { Text(stringResource(R.string.thesis_move_up)) }, onClick = {
                    menu = false
                    controller.direct(listOf(ThesisOp.MoveSection(s.id, s.parentId, siblings.getOrNull(index - 2)?.id ?: ThesisOperations.AFTER_START)), ChangeSource.STRUCTURE, "reorder")
                })
                if (index in 0 until siblings.lastIndex) DropdownMenuItem(text = { Text(stringResource(R.string.thesis_move_down)) }, onClick = {
                    menu = false
                    controller.direct(listOf(ThesisOp.MoveSection(s.id, s.parentId, siblings[index + 1].id)), ChangeSource.STRUCTURE, "reorder")
                })
                if (s.kind.container) DropdownMenuItem(text = { Text(stringResource(R.string.thesis_add_section)) }, onClick = {
                    menu = false
                    val kind = if (s.kind == SectionKind.SECTION) SectionKind.SUBSECTION else SectionKind.SECTION
                    controller.direct(listOf(ThesisOp.AddSection(ThesisSection(ThesisStructure.newId("sec"), s.id, kind, "", 0), snapshot.children(s.id).lastOrNull()?.id)), ChangeSource.STRUCTURE, "section")
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.template_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun TextInputDialog(title: String, initial: String, lines: Int = 1, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = lines, textStyle = contentStyle(), modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("thesis_text_input")) },
        confirmButton = { Button(onClick = { onConfirm(text) }, modifier = Modifier.testTag("thesis_text_confirm")) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}


@Composable
private fun ElementCard(e: ThesisElement, snapshot: ThesisSnapshot, assets: List<ThesisAsset>, caption: String?, onEdit: () -> Unit, onDelete: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth().testTag("thesis_element_${e.id}")) {
        Column(Modifier.padding(10.dp)) {
            ElementBody(e, assets, caption)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                onUp?.let { IconButton(onClick = it) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.thesis_move_up)) } }
                onDown?.let { IconButton(onClick = it) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.thesis_move_down)) } }
                IconButton(onClick = onEdit, modifier = Modifier.testTag("thesis_edit_${e.id}")) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.thesis_edit)) }
                IconButton(onClick = onDelete, modifier = Modifier.testTag("thesis_delete_${e.id}")) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.template_delete)) }
            }
        }
    }
}

@Composable
fun ElementBody(e: ThesisElement, assets: List<ThesisAsset>, caption: String?) {
    when (e.type) {
        ElementType.HEADING -> Text(e.text, style = contentStyle(), fontWeight = FontWeight.Bold)
        ElementType.LIST_ITEM -> Text((if (e.data.ordered) e.data.marker else "•") + "  " + e.text, style = contentStyle().copy(fontSize = 14.sp))
        ElementType.IMAGE -> Column {
            ThesisImageThumb(assets.firstOrNull { it.id == e.data.assetId }?.path, Modifier.fillMaxWidth().height(160.dp))
            caption?.let { Text(it, style = contentStyle().copy(fontSize = 12.sp), fontWeight = FontWeight.Medium) }
        }
        ElementType.TABLE -> Column {
            caption?.let { Text(it, style = contentStyle().copy(fontSize = 12.sp), fontWeight = FontWeight.Medium) }
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                (listOf(e.data.tableHeader) + e.data.tableRows).forEachIndexed { r, row ->
                    Row(Modifier.background(if (r == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                        row.forEach { c -> Text(c, style = contentStyle().copy(fontSize = 12.sp), modifier = Modifier.width(110.dp).padding(4.dp), fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
            }
        }
        else -> Text(e.text, style = contentStyle().copy(fontSize = 14.sp))
    }
}

/** Caption, source, width and alignment of a figure (or caption of a table). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaDataDialog(data: ElementData, isImage: Boolean, onDismiss: () -> Unit, onSave: (ElementData) -> Unit) {
    var caption by remember { mutableStateOf(data.caption) }
    var source by remember { mutableStateOf(data.source) }
    var width by remember { mutableIntStateOf(data.widthPercent) }
    var align by remember { mutableStateOf(data.align) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isImage) R.string.thesis_figure_settings else R.string.thesis_table_caption)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(caption, { caption = it }, label = { Text(stringResource(R.string.thesis_caption)) }, textStyle = contentStyle(), modifier = Modifier.fillMaxWidth().testTag("thesis_caption_input"))
                if (isImage) {
                    OutlinedTextField(source, { source = it }, label = { Text(stringResource(R.string.thesis_image_source)) }, textStyle = contentStyle(), modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.thesis_image_width, width))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(40, 60, 80, 100).forEach { w -> FilterChip(selected = width == w, onClick = { width = w }, label = { Text("$w%") }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("start" to R.string.pdf_align_start, "center" to R.string.pdf_align_center, "end" to R.string.pdf_align_end).forEach { (a, l) ->
                            FilterChip(selected = align == a, onClick = { align = a }, label = { Text(stringResource(l)) })
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(data.copy(caption = caption.trim(), source = source.trim(), widthPercent = width, align = align)) }, modifier = Modifier.testTag("thesis_media_save")) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Table cells edited as lines of "cell | cell"; values are kept exactly as typed. */
@Composable
private fun TableEditDialog(e: ThesisElement, onDismiss: () -> Unit, onSave: (ElementData) -> Unit) {
    var caption by remember { mutableStateOf(e.data.caption) }
    var text by remember { mutableStateOf((listOf(e.data.tableHeader) + e.data.tableRows).joinToString("\n") { it.joinToString(" | ") }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thesis_edit_table)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(caption, { caption = it }, label = { Text(stringResource(R.string.thesis_caption)) }, textStyle = contentStyle(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, minLines = 4, textStyle = contentStyle(), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp))
                Text(stringResource(R.string.thesis_table_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val rows = text.lines().filter { it.isNotBlank() }.map { l -> l.split('|').map { it.trim() } }
                if (rows.isNotEmpty()) onSave(e.data.copy(caption = caption.trim(), tableHeader = rows.first(), tableRows = rows.drop(1)))
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

// ------------------------------------------------------------------ review

@Composable
private fun noteText(code: String): String {
    val parts = code.split(':')
    return when (parts[0]) {
        "pdf_profile" -> stringResource(R.string.thesis_tpl_pdf_profile, parts.getOrElse(1) { "" })
        "pdf_image_only" -> stringResource(R.string.offline_note_pdf_image_only, parts.getOrElse(1) { "" })
        "file_missing" -> stringResource(R.string.offline_note_file_missing, parts.getOrElse(1) { "" })
        "incompatible" -> stringResource(R.string.thesis_tpl_incompatible, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        "not_found" -> stringResource(R.string.offline_note_not_found)
        "builtin" -> stringResource(R.string.thesis_tpl_builtin)
        "template_footer_added" -> stringResource(R.string.thesis_tpl_footer_added)
        "template_unusable" -> stringResource(R.string.thesis_tpl_unusable)
        "image_missing" -> stringResource(R.string.thesis_warn_image_missing)
        "logo_missing" -> stringResource(R.string.thesis_warn_image_missing)
        "crossref_missing" -> stringResource(R.string.thesis_warn_crossref)
        "duplicate" -> stringResource(R.string.thesis_warn_duplicate)
        "citation_removed" -> stringResource(R.string.thesis_warn_citation_removed)
        "citation_added" -> stringResource(R.string.thesis_warn_citation_added)
        "number_changed" -> stringResource(R.string.thesis_warn_number_changed)
        "number_added" -> stringResource(R.string.thesis_warn_number_added)
        "to_verify" -> stringResource(R.string.thesis_warn_to_verify)
        "ignored" -> stringResource(R.string.thesis_warn_ignored)
        else -> code
    }
}

@Composable
private fun explanationText(code: String): String = when {
    code.startsWith("local:") -> stringResource(R.string.thesis_expl_local)
    code.startsWith("ai:") -> stringResource(R.string.thesis_expl_ai, code.removePrefix("ai:"))
    code.startsWith("ask:") -> code.removePrefix("ask:").ifBlank { stringResource(R.string.thesis_expl_ask) }
    else -> code
}

@Composable
fun ThesisReviewDialog(controller: ThesisController, review: ProposalReview) {
    val state by controller.state.collectAsState()
    val p = review.proposal
    val status = ProposalStatus.parse(p.status)
    val ops = remember(p.payload) { ThesisOperations.decode(p.payload) }
    val added = ops.filterIsInstance<ThesisOp.AddElements>().flatMap { it.elements }
    var choosing by remember { mutableStateOf(status == ProposalStatus.NEEDS_DESTINATION) }
    var editingMedia by remember { mutableStateOf<NewElement?>(null) }
    val numbering = remember(review.before) { ThesisStructure.numbering(review.before) }
    Dialog(onDismissRequest = { controller.closeReview() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("thesis_review"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller.closeReview() }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
                    Text(stringResource(R.string.thesis_review_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                }
                state.busy?.let { BusyBar(controller, it) }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(sourceLabel(p.source) + " · " + statusLabel(p.status), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("thesis_review_status"))
                        if (p.request.isNotBlank()) Text(p.request.take(300), style = contentStyle().copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text(explanationText(p.explanation), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.thesis_review_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (review.conflicts.isNotEmpty() || status == ProposalStatus.CONFLICT) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().testTag("thesis_conflict")) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(stringResource(R.string.thesis_conflict_text), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { controller.recheck(p.id) }, modifier = Modifier.testTag("thesis_recheck")) { Text(stringResource(R.string.thesis_recheck)) }
                                }
                            }
                        }
                        (p.warnings.lines().filter { it.isNotBlank() }.map { it.substringBefore(':') }.distinct()).forEach {
                            Text("⚠ " + noteText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        if (added.isNotEmpty()) {
                            val dest = ops.filterIsInstance<ThesisOp.AddElements>().firstOrNull()?.sectionId
                            val destTitle = review.before.section(dest)?.let { ThesisStructure.displayTitle(it, numbering, review.before.meta.language) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.thesis_destination, destTitle ?: stringResource(R.string.thesis_destination_none)),
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).testTag("thesis_destination"))
                                TextButton(onClick = { choosing = true }, modifier = Modifier.testTag("thesis_choose_destination")) { Text(stringResource(R.string.thesis_change_destination)) }
                            }
                        }
                    }
                    if (status == ProposalStatus.NEEDS_DESTINATION || review.after == null) {
                        items(added, key = { it.id }) { n ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    ElementBody(ThesisElement(n.id, "", n.type, 0, n.text, n.data), state.assets, null)
                                    if (n.type == ElementType.IMAGE || n.type == ElementType.TABLE) TextButton(onClick = { editingMedia = n }) { Text(stringResource(R.string.thesis_caption_edit)) }
                                }
                            }
                        }
                    } else {
                        val diff = review.diff
                        val after = review.after
                        if (diff != null) {
                            item { DiffSummary(diff) }
                            items(diff.elements, key = { it.elementId }) { c ->
                                ChangeCard(c, state.assets, c.after?.let { e -> captionFor(e, after) })
                                if (c.kind == ChangeKind.ADDED && (c.after?.type == ElementType.IMAGE || c.after?.type == ElementType.TABLE)) {
                                    TextButton(onClick = { editingMedia = added.firstOrNull { it.id == c.elementId } }, modifier = Modifier.testTag("thesis_caption_${c.elementId}")) { Text(stringResource(R.string.thesis_caption_edit)) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    OutlinedButton(onClick = { controller.reject(p.id) }, enabled = state.busy == null, modifier = Modifier.testTag("thesis_reject")) { Text(stringResource(R.string.thesis_reject)) }
                    Button(
                        onClick = { controller.accept(p.id) },
                        enabled = state.busy == null && status == ProposalStatus.PENDING && review.after != null && review.conflicts.isEmpty(),
                        modifier = Modifier.testTag("thesis_accept")
                    ) { Text(stringResource(R.string.thesis_accept)) }
                }
            }
        }
    }
    if (choosing) DestinationPicker(review.before, onDismiss = { choosing = false }) { sectionId -> choosing = false; controller.setDestination(p.id, sectionId, null) }
    editingMedia?.let { n ->
        MediaDataDialog(n.data, isImage = n.type == ElementType.IMAGE, onDismiss = { editingMedia = null }) { data -> editingMedia = null; controller.editProposalElement(p.id, n.id, data) }
    }
}

private fun captionFor(e: ThesisElement, snapshot: ThesisSnapshot): String? {
    val n = ThesisStructure.numbering(snapshot)
    return when (e.type) {
        ElementType.IMAGE -> ThesisStructure.figureCaption(e, n, snapshot.meta.language)
        ElementType.TABLE -> ThesisStructure.tableCaption(e, n, snapshot.meta.language)
        else -> null
    }
}

@Composable
private fun DestinationPicker(snapshot: ThesisSnapshot, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val numbering = remember(snapshot) { ThesisStructure.numbering(snapshot) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thesis_where)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(snapshot.outline(includeDisabled = false).filter { it.first.kind.writable }, key = { it.first.id }) { (s, depth) ->
                    Text(ThesisStructure.displayTitle(s, numbering, snapshot.meta.language), style = contentStyle(),
                        modifier = Modifier.fillMaxWidth().clickable { onPick(s.id) }.padding(start = (depth * 14).dp, top = 10.dp, bottom = 10.dp).testTag("thesis_pick_${s.id}"))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun DiffSummary(diff: ThesisDiffResult) {
    val c = diff.counts
    Text(
        stringResource(R.string.thesis_diff_summary, c[ChangeKind.ADDED] ?: 0, c[ChangeKind.DELETED] ?: 0, c[ChangeKind.MODIFIED] ?: 0, c[ChangeKind.MOVED] ?: 0, c[ChangeKind.FORMAT_ONLY] ?: 0) +
            if (diff.sections.isNotEmpty()) " · " + stringResource(R.string.thesis_diff_sections, diff.sections.size) else "",
        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("thesis_diff_summary")
    )
}

@Composable
private fun ChangeCard(c: ElementChange, assets: List<ThesisAsset>, caption: String?) {
    val added = Color(0x3334A853)
    val removed = Color(0x33EA4335)
    val label = stringResource(
        when (c.kind) {
            ChangeKind.ADDED -> R.string.thesis_change_added
            ChangeKind.DELETED -> R.string.thesis_change_deleted
            ChangeKind.MODIFIED -> R.string.thesis_change_modified
            ChangeKind.MOVED -> R.string.thesis_change_moved
            ChangeKind.FORMAT_ONLY -> R.string.thesis_change_format
        }
    )
    Card(Modifier.fillMaxWidth().testTag("thesis_change_${c.elementId}"),
        colors = CardDefaults.cardColors(containerColor = when (c.kind) { ChangeKind.ADDED -> added; ChangeKind.DELETED -> removed; else -> MaterialTheme.colorScheme.surfaceVariant })) {
        Column(Modifier.padding(10.dp)) {
            Text(label + if (c.moved && c.kind == ChangeKind.MODIFIED) " · " + stringResource(R.string.thesis_change_moved) else "", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            when {
                c.kind == ChangeKind.MODIFIED && c.segments.isNotEmpty() -> Text(buildAnnotatedString {
                    c.segments.forEachIndexed { i, s ->
                        if (i > 0) append(" ")
                        when (s.kind) {
                            SegmentKind.EQUAL -> append(s.text)
                            SegmentKind.INSERTED -> withStyle(SpanStyle(background = added, fontWeight = FontWeight.SemiBold)) { append(s.text) }
                            SegmentKind.DELETED -> withStyle(SpanStyle(background = removed, textDecoration = TextDecoration.LineThrough)) { append(s.text) }
                        }
                    }
                }, style = contentStyle().copy(fontSize = 14.sp))
                c.after != null -> ElementBody(c.after, assets, caption)
                c.before != null -> Text(c.before.text, style = contentStyle().copy(fontSize = 14.sp, textDecoration = TextDecoration.LineThrough))
            }
        }
    }
}

// ------------------------------------------------------------------ section content editor

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThesisSectionScreen(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val snapshot = state.snapshot ?: return
    val section = snapshot.section(state.selectedSectionId) ?: return
    val numbering = remember(snapshot) { ThesisStructure.numbering(snapshot) }
    val elements = snapshot.elementsOf(section.id)
    val idle = state.busy == null
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ThesisElement?>(null) }
    var addingText by remember { mutableStateOf(false) }
    var addingHeading by remember { mutableStateOf(false) }
    var addingTable by remember { mutableStateOf(false) }
    var aiMenu by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var deleting by remember { mutableStateOf<ThesisElement?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> pendingImage = uri }
    LazyColumn(Modifier.fillMaxSize().testTag("thesis_section_editor"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(ThesisStructure.displayTitle(section, numbering, snapshot.meta.language), style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }.testTag("thesis_section_title"))
            Text(stringResource(R.string.thesis_section_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { addingText = true }, enabled = idle, modifier = Modifier.testTag("thesis_add_paragraph")) { Text(stringResource(R.string.thesis_add_text)) }
                OutlinedButton(onClick = { addingHeading = true }, enabled = idle, modifier = Modifier.testTag("thesis_add_heading")) { Text(stringResource(R.string.thesis_add_heading)) }
                OutlinedButton(onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = idle, modifier = Modifier.testTag("thesis_insert_image")) {
                    Text(stringResource(R.string.thesis_insert_image))
                }
                OutlinedButton(onClick = { addingTable = true }, enabled = idle, modifier = Modifier.testTag("thesis_insert_table")) { Text(stringResource(R.string.thesis_insert_table)) }
                Box {
                    OutlinedButton(onClick = { aiMenu = true }, enabled = idle && elements.isNotEmpty(), modifier = Modifier.testTag("thesis_ai_review")) { Text(stringResource(R.string.thesis_ai_review)) }
                    DropdownMenu(expanded = aiMenu, onDismissRequest = { aiMenu = false }) {
                        listOf(EditMode.CORRECT to R.string.thesis_ai_correct, EditMode.IMPROVE to R.string.thesis_ai_improve, EditMode.SHORTEN to R.string.thesis_ai_shorten, EditMode.EXPAND to R.string.thesis_ai_expand)
                            .forEach { (mode, label) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { aiMenu = false; controller.requestEdit(mode, section.id) }, modifier = Modifier.testTag("thesis_ai_${mode.name}"))
                            }
                    }
                }
                OutlinedButton(onClick = { controller.formatSection(section.id) }, enabled = idle && elements.isNotEmpty(), modifier = Modifier.testTag("thesis_apply_formatting")) { Text(stringResource(R.string.thesis_apply_formatting)) }
                OutlinedButton(onClick = { controller.loadSectionHistory(section.id) }, enabled = idle, modifier = Modifier.testTag("thesis_section_history")) { Text(stringResource(R.string.thesis_section_history)) }
                OutlinedButton(onClick = { state.projectId?.let { host.continueThesisInChat(it, section.id) } }, modifier = Modifier.testTag("thesis_section_chat")) { Text(stringResource(R.string.thesis_section_chat)) }
            }
            state.sectionHistory?.let { history -> SectionHistoryCard(controller, history, onClose = { controller.selectSection(section.id) }) }
            if (elements.isEmpty()) Text(stringResource(R.string.thesis_section_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        items(elements, key = { it.id }) { e ->
            ElementCard(e, snapshot, state.assets, when (e.type) {
                ElementType.IMAGE -> ThesisStructure.figureCaption(e, numbering, snapshot.meta.language)
                ElementType.TABLE -> ThesisStructure.tableCaption(e, numbering, snapshot.meta.language)
                else -> null
            }, onEdit = { editing = e }, onDelete = { deleting = e },
                onUp = elements.indexOf(e).takeIf { it > 0 }?.let { i -> { controller.direct(listOf(ThesisOp.MoveElement(e.id, section.id, elements.getOrNull(i - 2)?.id ?: ThesisOperations.AFTER_START)), ChangeSource.STRUCTURE, "reorder") } },
                onDown = elements.indexOf(e).takeIf { it < elements.lastIndex }?.let { i -> { controller.direct(listOf(ThesisOp.MoveElement(e.id, section.id, elements[i + 1].id)), ChangeSource.STRUCTURE, "reorder") } })
        }
    }
    if (addingText) {
        TextInputDialog(stringResource(R.string.thesis_add_text), "", lines = 6, onDismiss = { addingText = false }) { text ->
            addingText = false
            if (text.isNotBlank()) controller.addText(section.id, text)
        }
    }
    if (addingHeading) {
        TextInputDialog(stringResource(R.string.thesis_add_heading), "", onDismiss = { addingHeading = false }) { text ->
            addingHeading = false
            controller.addHeading(section.id, text)
        }
    }
    if (addingTable) {
        TableEditDialog(ThesisElement("", section.id, ElementType.TABLE, 0, "", ElementData()), onDismiss = { addingTable = false }) { data ->
            addingTable = false
            controller.addTable(section.id, data.tableHeader, data.tableRows, data.caption)
        }
    }
    pendingImage?.let { uri ->
        MediaDataDialog(ElementData(), isImage = true, onDismiss = { pendingImage = null }) { data ->
            pendingImage = null
            val resolver = context.contentResolver
            controller.addImage(section.id, { resolver.openInputStream(uri) }, uri.lastPathSegment ?: "image", resolver.getType(uri) ?: "image/*", data)
        }
    }
    editing?.let { e ->
        when (e.type) {
            ElementType.IMAGE -> MediaDataDialog(e.data, isImage = true, onDismiss = { editing = null }) { data -> controller.direct(listOf(ThesisOp.UpdateData(e.id, data)), ChangeSource.IMAGE, "image"); editing = null }
            ElementType.TABLE -> TableEditDialog(e, onDismiss = { editing = null }) { data -> controller.direct(listOf(ThesisOp.UpdateData(e.id, data)), ChangeSource.TABLE, "table"); editing = null }
            else -> TextInputDialog(stringResource(R.string.thesis_edit), e.text, lines = 4, onDismiss = { editing = null }) { text ->
                if (text.isNotBlank()) controller.direct(listOf(ThesisOp.ReplaceText(e.id, text.trim())), ChangeSource.USER, "edit")
                editing = null
            }
        }
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.thesis_delete_element_title)) },
            text = { Text(stringResource(R.string.thesis_delete_element_text)) },
            confirmButton = { Button(onClick = { controller.direct(listOf(ThesisOp.DeleteElement(e.id)), ChangeSource.USER, "delete"); deleting = null },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("thesis_delete_confirm")) { Text(stringResource(R.string.template_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

/** Revisions that changed the open section; each one can be compared with the revision before it. */
@Composable
private fun SectionHistoryCard(controller: ThesisController, history: List<ThesisRevisionEntity>, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("thesis_section_history_list")) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.thesis_section_history), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
            }
            if (history.isEmpty()) Text(stringResource(R.string.thesis_section_history_empty), style = MaterialTheme.typography.bodySmall)
            history.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.thesis_revision_short, r.number) + " · " + sourceLabel(r.source), style = MaterialTheme.typography.labelLarge)
                        Text(formatDate(r.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.number > 1) TextButton(onClick = { controller.compareWithPrevious(r) }, modifier = Modifier.testTag("thesis_section_rev_${r.number}")) { Text(stringResource(R.string.thesis_changes_made)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ history

@Composable
private fun ThesisHistoryScreen(controller: ThesisController) {
    val state by controller.state.collectAsState()
    val revisions by controller.revisions.collectAsState()
    var restoring by remember { mutableStateOf<ThesisRevisionEntity?>(null) }
    val compare = state.compare
    val current = revisions.firstOrNull()?.number
    LazyColumn(Modifier.fillMaxSize().testTag("thesis_history_list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (compare != null) {
            item {
                Text(
                    if (compare.againstPrevious) stringResource(R.string.thesis_changes_in_revision, compare.revision.number)
                    else stringResource(R.string.thesis_compare_title, compare.revision.number),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("thesis_compare_header")
                )
                Text(stringResource(if (compare.againstPrevious) R.string.thesis_compare_previous_note else R.string.thesis_compare_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DiffSummary(compare.diff)
                if (compare.diff.elements.isEmpty() && compare.diff.sections.isEmpty()) Text(stringResource(R.string.thesis_compare_same), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (compare.revision.number != current) {
                        Button(onClick = { restoring = compare.revision }, enabled = state.busy == null, modifier = Modifier.testTag("thesis_restore")) { Text(stringResource(R.string.thesis_restore)) }
                    }
                    OutlinedButton(onClick = { controller.closeCompare() }, modifier = Modifier.testTag("thesis_compare_close")) { Text(stringResource(R.string.action_close)) }
                }
            }
            // Read from the old version toward the newer one.
            items(compare.diff.elements, key = { "c" + it.elementId }) { c -> ChangeCard(c, state.assets, null) }
            item { HorizontalDivider() }
        }
        item {
            Text(stringResource(R.string.thesis_history), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.thesis_history_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(revisions, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().testTag("thesis_revision_${r.number}")) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
                    Text(stringResource(R.string.thesis_revision_short, r.number) + " · " + sourceLabel(r.source) + if (r.number == current) " · " + stringResource(R.string.thesis_current) else "",
                        style = MaterialTheme.typography.labelLarge)
                    Text(formatDate(r.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    r.restoredFrom?.let { Text(stringResource(R.string.thesis_restored_from, it), style = MaterialTheme.typography.bodySmall) }
                    if (r.summary.isNotBlank()) Text(r.summary.take(160), style = contentStyle().copy(fontSize = 12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row {
                        if (r.number > 1) TextButton(onClick = { controller.compareWithPrevious(r) }, modifier = Modifier.testTag("thesis_revision_changes_${r.number}")) { Text(stringResource(R.string.thesis_changes_made)) }
                        if (r.number != current) TextButton(onClick = { controller.compareRevision(r) }, modifier = Modifier.testTag("thesis_revision_compare_${r.number}")) { Text(stringResource(R.string.thesis_compare_current)) }
                    }
                }
            }
        }
    }
    restoring?.let { r ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(stringResource(R.string.thesis_restore_title, r.number)) },
            text = { Text(stringResource(R.string.thesis_restore_text)) },
            confirmButton = { Button(onClick = { controller.restore(r); restoring = null }, modifier = Modifier.testTag("thesis_restore_confirm")) { Text(stringResource(R.string.thesis_restore)) } },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

// ------------------------------------------------------------------ preview

@Composable
private fun ThesisPreviewScreen(controller: ThesisController) {
    val state by controller.state.collectAsState()
    val file = state.previewFile
    if (file == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.thesis_preview_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { controller.buildPreview() }, enabled = state.busy == null, modifier = Modifier.heightIn(min = 48.dp).testTag("thesis_preview_build")) { Text(stringResource(R.string.thesis_preview_generate)) }
        }
        return
    }
    val pages = remember(file) { pageCount(file) }.takeIf { it > 0 } ?: state.previewPages
    var page by remember(file) { mutableIntStateOf(0) }
    var zoom by remember { mutableStateOf(1f) }
    val zoomIn = stringResource(R.string.thesis_zoom_in)
    val zoomOut = stringResource(R.string.thesis_zoom_out)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page-- }, enabled = page > 0, modifier = Modifier.testTag("thesis_page_prev")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.action_previous_page))
            }
            Text(stringResource(R.string.thesis_page_of, page + 1, pages), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("thesis_page_indicator"))
            IconButton(onClick = { page++ }, enabled = page < pages - 1, modifier = Modifier.testTag("thesis_page_next")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.action_next_page))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(0.5f) }, modifier = Modifier.semantics { contentDescription = zoomOut }.testTag("thesis_zoom_out")) { Text("−") }
            Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("thesis_zoom_level"))
            TextButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) }, modifier = Modifier.semantics { contentDescription = zoomIn }.testTag("thesis_zoom_in")) { Text("+") }
            IconButton(onClick = { controller.buildPreview() }, enabled = state.busy == null, modifier = Modifier.testTag("thesis_preview_refresh")) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.thesis_preview_refresh))
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF9E9E9E)).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            if (pages > 0) PdfPage(file, page.coerceIn(0, pages - 1), zoom)
        }
    }
}

private fun pageCount(file: File): Int = try {
    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> android.graphics.pdf.PdfRenderer(fd).use { it.pageCount } }
} catch (e: Throwable) { 0 }

/** One page rendered on demand. */
@Composable
private fun PdfPage(file: File, index: Int, zoom: Float) {
    val widthPx = (700 * zoom).toInt()
    val bitmap by produceState<ImageBitmap?>(null, file, index, widthPx) {
        value = withContext(Dispatchers.IO) { TemplateThumbnails.renderPage(file, index, widthPx)?.asImageBitmap() }
    }
    val b = bitmap
    if (b == null) Box(Modifier.width((300 * zoom).dp).height((420 * zoom).dp).background(Color.White), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
    else Image(b, contentDescription = stringResource(R.string.thesis_page_n, index + 1), modifier = Modifier.width((340 * zoom).dp).testTag("thesis_page_$index"), contentScale = ContentScale.FillWidth)
}

// ------------------------------------------------------------------ settings

@Composable
private fun ThesisSettingsScreen(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val projects by controller.projects.collectAsState()
    val snapshot = state.snapshot ?: return
    val project = projects.firstOrNull { it.id == state.projectId } ?: return
    var deleting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) controller.importLogo({ context.contentResolver.openInputStream(uri) }, "logo", context.contentResolver.getType(uri) ?: "image/png")
    }
    ThesisMetaForm(
        host = host,
        initial = snapshot.meta,
        initialAi = project.aiAssist,
        busy = state.busy != null,
        saveLabel = R.string.action_save,
        onCancel = { controller.show(ThesisScreen.PROJECT) },
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(if (snapshot.meta.logoAssetId == null) R.string.thesis_add_logo else R.string.thesis_change_logo))
                }
                OutlinedButton(onClick = { deleting = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("thesis_settings_delete")) {
                    Text(stringResource(R.string.thesis_delete_project))
                }
            }
        }
    ) { meta, ai ->
        if (ai != project.aiAssist) controller.setAiAssist(ai)
        // The logo is changed by its own button; keep the stored one.
        val updated = meta.copy(logoAssetId = snapshot.meta.logoAssetId)
        if (updated != snapshot.meta) controller.updateMeta(updated)
        controller.show(ThesisScreen.PROJECT)
    }
    if (deleting) DeleteProjectDialog(onDismiss = { deleting = false }) { deleting = false; controller.deleteProject(project.id) }
}
