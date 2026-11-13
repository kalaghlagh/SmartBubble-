package com.example.thesis.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.runtime.mutableStateListOf
import com.example.thesis.core.EditorOps
import com.example.thesis.core.EditorText
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.ThesisProjectEntity
import com.example.thesis.AiAssist
import com.example.thesis.ThesisController
import com.example.thesis.ThesisScreen
import com.example.thesis.core.EditMode
import com.example.thesis.core.SectionDraft
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.core.ThesisStructure
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ChangeSource
import com.example.thesis.model.ElementData
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot

// =================================================================== window

/**
 * The Thesis Builder (opened by the Thesis Builder button at the top of Chat; no navigation tab).
 * Six screens: projects, new thesis, workspace, section editor, AI assistant and preview.
 */
@Composable
fun ThesisWorkspaceDialog(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    if (!state.workspaceOpen) return
    Dialog(
        onDismissRequest = { controller.back() }, // system back goes one level up
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)
    ) {
        ThesisWorkspaceContent(host)
    }
    state.review?.let { ThesisReviewDialog(controller, it) }
}

/** Current screen with its own snackbar (the app's snackbar is hidden behind this window). */
@Composable
fun ThesisWorkspaceContent(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val feedback by host.feedback.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(feedback) {
        feedback?.let { message ->
            host.consumeFeedback()
            snackbar.showSnackbar(message)
        }
    }
    Surface(Modifier.fillMaxSize().testTag("thesis_workspace"), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.systemBarsPadding().imePadding()) {
            when (state.screen) {
                ThesisScreen.HOME -> ThesisHomeScreen(host)
                ThesisScreen.CREATE -> ThesisCreateScreen(host)
                ThesisScreen.PROJECT -> ThesisProjectWorkspace(host)
                ThesisScreen.SECTION -> ThesisSectionEditor(host)
                ThesisScreen.AI -> ThesisAiAssistant(host)
                ThesisScreen.PREVIEW -> ThesisPreview(host)
                ThesisScreen.CHANGES -> Screen(stringResource(R.string.thesis_pending_changes), controller) { ThesisChangesScreen(controller) }
                ThesisScreen.HISTORY -> Screen(stringResource(R.string.thesis_history), controller) { ThesisHistoryScreen(controller) }
                ThesisScreen.SETTINGS -> Screen(stringResource(R.string.thesis_project_settings), controller) { ThesisSettingsScreen(host) }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).testTag("thesis_snackbar"))
        }
    }
}

// =================================================================== shared pieces

private val CompletedGreen = Color(0xFF1E9E5A)

/** Blue top bar of the Thesis Builder screens (back follows the layout direction). */
@Composable
private fun BlueTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("thesis_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.thesis_back))
                }
            } else Spacer(Modifier.width(12.dp))
            Text(
                title, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() }.testTag("thesis_title_bar")
            )
            actions()
        }
    }
}

/** Secondary screens (pending changes, history, settings) under a blue bar with back. */
@Composable
private fun Screen(title: String, controller: ThesisController, content: @Composable () -> Unit) {
    val state by controller.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        BlueTopBar(title, onBack = { controller.back() })
        state.busy?.let { BusyBar(controller, it) }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun degreeLabel(level: AcademicLevel) = stringResource(
    when (level) { AcademicLevel.LICENCE -> R.string.thesis_degree_bachelor; AcademicLevel.MASTER -> R.string.thesis_degree_master; AcademicLevel.DOCTORATE -> R.string.thesis_degree_phd }
)

private fun levelOf(name: String) = AcademicLevel.values().firstOrNull { it.name == name } ?: AcademicLevel.MASTER

private val languages = listOf("ar" to "العربية", "fr" to "Français", "en" to "English")

@Composable
private fun ProgressRow(percent: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { percent / 100f }, color = CompletedGreen, trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text("$percent%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("thesis_progress"))
    }
}

// =================================================================== 1. home

@Composable
private fun ThesisHomeScreen(host: ThesisHost) {
    val controller = host.thesis
    val projects by controller.projects.collectAsState()
    val state by controller.state.collectAsState()
    val progress by controller.projectProgress.collectAsState()
    var renaming by remember { mutableStateOf<ThesisProjectEntity?>(null) }
    var deleting by remember { mutableStateOf<ThesisProjectEntity?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("thesis_home"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 28.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { controller.closeWorkspace() }, modifier = Modifier.testTag("thesis_close")) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.School, contentDescription = null, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(stringResource(R.string.thesis_builder), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                            Text(stringResource(R.string.thesis_home_subtitle), style = MaterialTheme.typography.bodyMedium)
                            // Build identity (version and source commit) to check which build is installed.
                            Text("v" + com.example.BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f), modifier = Modifier.testTag("thesis_build_info"))
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = { controller.show(ThesisScreen.CREATE) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(56.dp).testTag("thesis_new")
            ) {
                Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.thesis_new_thesis), style = MaterialTheme.typography.titleMedium)
            }
        }
        state.formDraft?.let { d ->
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("thesis_form_draft"), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.thesis_form_draft_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (d.title.isNotBlank()) Text(d.title, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { controller.show(ThesisScreen.CREATE) }, modifier = Modifier.testTag("thesis_form_draft_continue")) { Text(stringResource(R.string.thesis_continue_editing)) }
                            TextButton(onClick = { controller.discardFormDraft() }, modifier = Modifier.testTag("thesis_form_draft_discard")) { Text(stringResource(R.string.thesis_discard)) }
                        }
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.thesis_my_projects), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).semantics { heading() })
        }
        if (projects.isEmpty()) item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("thesis_empty_state"), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.thesis_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.thesis_empty_text), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(projects, key = { it.id }) { p ->
            ProjectCard(p, progress[p.id] ?: 0, busy = state.busy != null, onOpen = { controller.openProject(p.id) }, onRename = { renaming = p }, onDelete = { deleting = p })
        }
    }
    renaming?.let { p ->
        TextInputDialog(stringResource(R.string.thesis_rename_project), p.title, onDismiss = { renaming = null }) { title ->
            renaming = null
            if (title.isNotBlank() && title.trim() != p.title) controller.renameProject(p.id, title)
        }
    }
    deleting?.let { p -> DeleteProjectDialog(onDismiss = { deleting = null }) { deleting = null; controller.deleteProject(p.id) } }
}

@Composable
private fun ProjectCard(p: ThesisProjectEntity, percent: Int, busy: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("thesis_project_${p.id}"), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.School, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (p.studentName.isNotBlank()) Text(p.studentName, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(degreeLabel(levelOf(p.level)) + " • " + formatDate(p.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            ProgressRow(percent)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onOpen, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("thesis_continue_${p.id}")) {
                    Text(stringResource(R.string.thesis_continue_editing), fontWeight = FontWeight.SemiBold)
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.testTag("thesis_project_menu_${p.id}")) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.thesis_rename)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; onRename() }, modifier = Modifier.testTag("thesis_project_rename"))
                        DropdownMenuItem(text = { Text(stringResource(R.string.template_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() }, modifier = Modifier.testTag("thesis_project_delete"))
                    }
                }
            }
        }
    }
}

// =================================================================== 2. new thesis

@Composable
private fun ThesisCreateScreen(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    // A saved, unfinished form ("Save draft") is filled in again.
    val initial = remember { controller.state.value.formDraft }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var subtitle by remember { mutableStateOf(initial?.subtitle.orEmpty()) }
    var level by remember { mutableStateOf(initial?.level ?: AcademicLevel.MASTER) }
    var specialization by remember { mutableStateOf(initial?.specialization.orEmpty()) }
    var university by remember { mutableStateOf(initial?.university.orEmpty()) }
    var faculty by remember { mutableStateOf(initial?.faculty.orEmpty()) }
    var department by remember { mutableStateOf(initial?.department.orEmpty()) }
    var student by remember { mutableStateOf(initial?.studentName.orEmpty()) }
    var studentId by remember { mutableStateOf(initial?.studentId.orEmpty()) }
    var supervisor by remember { mutableStateOf(initial?.supervisor.orEmpty()) }
    var coSupervisor by remember { mutableStateOf(initial?.coSupervisor.orEmpty()) }
    var academicYear by remember { mutableStateOf(initial?.academicYear.orEmpty()) }
    var submissionDate by remember { mutableStateOf(initial?.submissionDate.orEmpty()) }
    var language by remember {
        mutableStateOf(initial?.language?.takeIf { l -> languages.any { it.first == l } }
            ?: host.defaultContentLanguage.takeIf { l -> languages.any { it.first == l } } ?: "en")
    }
    var showErrors by remember { mutableStateOf(false) }
    val busy = state.busy != null
    val yearOk = academicYear.isBlank() || Regex("""^\d{4}\s*[-/–]\s*\d{4}$""").matches(academicYear.trim())
    fun meta() = ThesisMeta(
        title = title.trim(), level = level, language = language, studentName = student.trim(), university = university.trim(),
        faculty = faculty.trim(), department = department.trim(), specialization = specialization.trim(), supervisor = supervisor.trim(),
        coSupervisor = coSupervisor.trim(), academicYear = academicYear.trim(), subtitle = subtitle.trim(), studentId = studentId.trim(),
        submissionDate = submissionDate
    )
    val anything = listOf(title, subtitle, specialization, university, faculty, department, student, studentId, supervisor, coSupervisor, academicYear, submissionDate).any { it.isNotBlank() }
    Column(Modifier.fillMaxSize()) {
        BlueTopBar(stringResource(R.string.thesis_new_thesis), onBack = { controller.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (initial != null) Text(stringResource(R.string.thesis_form_draft_restored), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("thesis_form_draft_restored"))
            FormHeader(R.string.thesis_form_academic)
            LabeledField(R.string.thesis_field_title, title, "thesis_field_title", required = true, error = showErrors && title.isBlank(), errorText = R.string.thesis_title_required) { title = it }
            LabeledField(R.string.thesis_field_subtitle, subtitle, "thesis_field_subtitle") { subtitle = it }
            Text(stringResource(R.string.thesis_degree_level) + " *", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AcademicLevel.values().forEach { l ->
                    Row(
                        Modifier.selectable(selected = level == l, onClick = { level = l }, role = Role.RadioButton).heightIn(min = 48.dp).testTag("thesis_level_${l.name}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = level == l, onClick = null)
                        Spacer(Modifier.width(4.dp))
                        Text(degreeLabel(l), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            LabeledField(R.string.thesis_field_specialization, specialization, "thesis_field_specialization") { specialization = it }
            LabeledField(R.string.thesis_field_university, university, "thesis_field_university") { university = it }
            LabeledField(R.string.thesis_field_faculty, faculty, "thesis_field_faculty") { faculty = it }
            LabeledField(R.string.thesis_field_department, department, "thesis_field_department") { department = it }
            FormHeader(R.string.thesis_form_student)
            LabeledField(R.string.thesis_field_student_full, student, "thesis_field_student", required = true, error = showErrors && student.isBlank(), errorText = R.string.thesis_student_required) { student = it }
            LabeledField(R.string.thesis_field_student_id, studentId, "thesis_field_student_id") { studentId = it }
            FormHeader(R.string.thesis_form_supervision)
            LabeledField(R.string.thesis_field_supervisor, supervisor, "thesis_field_supervisor") { supervisor = it }
            LabeledField(R.string.thesis_field_cosupervisor, coSupervisor, "thesis_field_cosupervisor") { coSupervisor = it }
            FormHeader(R.string.thesis_form_year)
            LabeledField(R.string.thesis_field_year, academicYear, "thesis_field_year", error = !yearOk, errorText = R.string.thesis_year_format, placeholder = "2025-2026") { academicYear = it }
            DateField(R.string.thesis_field_submission_date, submissionDate, "thesis_field_submission_date") { submissionDate = it }
            FormHeader(R.string.thesis_form_language)
            LanguagePicker(language) { language = it }
            Text(stringResource(R.string.thesis_required_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { controller.back() }, enabled = !busy, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1f).height(52.dp).testTag("thesis_cancel_meta")) {
                Text(stringResource(R.string.action_cancel), maxLines = 1)
            }
            OutlinedButton(onClick = { controller.saveFormDraft(meta()) }, enabled = !busy && anything, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1f).height(52.dp).testTag("thesis_save_draft")) {
                Text(stringResource(R.string.thesis_save_draft), maxLines = 1, textAlign = TextAlign.Center)
            }
            Button(
                onClick = {
                    if (title.isBlank() || student.isBlank() || !yearOk) showErrors = true
                    else controller.createProject(meta(), aiAssist = true)
                },
                // Disabled while saving: repeated taps create one project only.
                enabled = !busy, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1.3f).height(52.dp).testTag("thesis_save_meta")
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.thesis_create), maxLines = 1)
            }
        }
    }
}

@Composable
private fun FormHeader(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

/** Date chosen with the system date picker (stored as yyyy-MM-dd); can be cleared. */
@Composable
private fun DateField(label: Int, value: String, tag: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val c = java.util.Calendar.getInstance()
                    Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(value)?.destructured?.let { (y, m, d) -> c.set(y.toInt(), m.toInt() - 1, d.toInt()) }
                    android.app.DatePickerDialog(context, { _, y, m, d -> onChange(String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d)) },
                        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
                },
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(tag)
            ) {
                Icon(Icons.Outlined.Event, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(value.ifBlank { stringResource(R.string.thesis_choose_date) }, modifier = Modifier.weight(1f))
            }
            if (value.isNotBlank()) IconButton(onClick = { onChange("") }, modifier = Modifier.testTag("${tag}_clear")) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear))
            }
        }
    }
}

@Composable
private fun LabeledField(label: Int, value: String, tag: String, required: Boolean = false, error: Boolean = false, errorText: Int? = null, placeholder: String? = null, onChange: (String) -> Unit) {
    Column {
        Text(stringResource(label) + if (required) " *" else "", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true, isError = error, shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            placeholder = if (placeholder != null) ({ Text(placeholder) }) else null,
            supportingText = if (error && errorText != null) ({ Text(stringResource(errorText), modifier = Modifier.testTag("${tag}_error")) }) else null,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            modifier = Modifier.fillMaxWidth().testTag(tag)
        )
    }
}

@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("thesis_language_picker")) {
            Icon(Icons.Outlined.Language, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(languages.first { it.first == selected }.second, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            languages.forEach { (code, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(code) }, modifier = Modifier.testTag("thesis_lang_$code"),
                    trailingIcon = { if (code == selected) Icon(Icons.Default.Check, null) })
            }
        }
    }
}

// =================================================================== 3. workspace

@Composable
private fun ThesisProjectWorkspace(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val proposals by controller.openProposals.collectAsState()
    val snapshot = state.snapshot
    var menu by remember { mutableStateOf(false) }
    var deletingProject by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var renaming by remember { mutableStateOf<ThesisSection?>(null) }
    var deleting by remember { mutableStateOf<ThesisSection?>(null) }
    var addingChapter by remember { mutableStateOf(false) }
    var addingSubTo by remember { mutableStateOf<ThesisSection?>(null) }
    val projectId = state.projectId
    Column(Modifier.fillMaxSize()) {
        BlueTopBar(snapshot?.meta?.title.orEmpty(), onBack = { controller.back() }) {
            SaveStatus(saving = state.busy == com.example.thesis.ThesisBusy.SAVING, dirty = false, onPrimary = true)
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("thesis_workspace_menu")) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(R.string.thesis_preview_thesis, "thesis_menu_preview") { menu = false; controller.show(ThesisScreen.PREVIEW) }
                    MenuItem(R.string.thesis_export_docx, "thesis_menu_export_docx", enabled = state.busy == null) { menu = false; controller.export("DOCX") }
                    MenuItem(R.string.thesis_export_pdf, "thesis_menu_export_pdf", enabled = state.busy == null) { menu = false; controller.export("PDF") }
                    if (proposals.isNotEmpty()) MenuItem(stringResource(R.string.thesis_review_pending, proposals.size), "thesis_menu_pending") { menu = false; controller.show(ThesisScreen.CHANGES) }
                    MenuItem(R.string.thesis_history, "thesis_menu_history") { menu = false; controller.show(ThesisScreen.HISTORY) }
                    MenuItem(R.string.thesis_continue_in_chat, "thesis_menu_chat") { menu = false; projectId?.let { host.continueThesisInChat(it, null) } }
                    MenuItem(R.string.thesis_project_settings, "thesis_menu_settings") { menu = false; controller.show(ThesisScreen.SETTINGS) }
                    MenuItem(R.string.thesis_delete_project, "thesis_menu_delete", danger = true, enabled = state.busy == null) { menu = false; deletingProject = true }
                }
            }
        }
        state.busy?.let { BusyBar(controller, it) }
        if (snapshot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val numbering = remember(snapshot) { ThesisStructure.numbering(snapshot) }
        val top = snapshot.children(null)
        val visible = top.filter { it.enabled }
        val hidden = top.filter { !it.enabled }
        LazyColumn(Modifier.weight(1f).testTag("thesis_structure"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ProgressRow(ThesisStructure.progress(snapshot), Modifier.padding(bottom = 4.dp)) }
            state.lastExport?.let { e -> item { ExportResultCard(host, e) } }
            if (proposals.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth().clickable { controller.show(ThesisScreen.CHANGES) }.testTag("thesis_pending_banner"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(stringResource(R.string.thesis_review_pending, proposals.size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                }
            }
            items(visible, key = { it.id }) { s ->
                SectionCard(
                    controller, snapshot, s, numbering, depth = 0,
                    expanded = s.id in expanded, expandedIds = expanded,
                    onToggle = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
                    onRename = { renaming = it }, onDelete = { deleting = it }, onAddSub = { addingSubTo = it }
                )
            }
            if (hidden.isNotEmpty()) item {
                TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.testTag("thesis_hidden_toggle")) {
                    Text(stringResource(R.string.thesis_hidden_sections, hidden.size))
                    Icon(if (showHidden) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
            }
            if (showHidden) items(hidden, key = { "h" + it.id }) { s ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ThesisStructure.displayTitle(s, numbering, snapshot.meta.language), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { controller.direct(listOf(ThesisOp.UpdateSection(s.id, enabled = true)), ChangeSource.STRUCTURE, "show") },
                            modifier = Modifier.testTag("thesis_restore_section_${s.id}")) { Text(stringResource(R.string.thesis_add_to_thesis)) }
                    }
                }
            }
        }
        Button(
            onClick = { addingChapter = true },
            enabled = state.busy == null, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp).testTag("thesis_add_chapter")
        ) {
            Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.thesis_add_chapter), style = MaterialTheme.typography.titleMedium)
        }
    }
    renaming?.let { s ->
        TextInputDialog(stringResource(R.string.thesis_rename), s.title, onDismiss = { renaming = null }) { title ->
            renaming = null
            if (title.isNotBlank()) controller.direct(listOf(ThesisOp.UpdateSection(s.id, title = title.trim())), ChangeSource.STRUCTURE, "rename")
        }
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.thesis_delete_section_title)) },
            text = { Text(stringResource(R.string.thesis_delete_section_text)) },
            confirmButton = { Button(onClick = { deleting = null; controller.direct(listOf(ThesisOp.DeleteSection(s.id)), ChangeSource.STRUCTURE, "delete section") },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("thesis_delete_section_confirm")) { Text(stringResource(R.string.template_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (addingChapter) TextInputDialog(stringResource(R.string.thesis_new_chapter_title), "", onDismiss = { addingChapter = false }) { title ->
        addingChapter = false
        controller.addChapter(title)
    }
    addingSubTo?.let { parent ->
        TextInputDialog(stringResource(R.string.thesis_new_subsection_title), "", onDismiss = { addingSubTo = null }) { title ->
            addingSubTo = null
            controller.addSubsection(parent.id, title)
            expanded = expanded + parent.id
        }
    }
    if (deletingProject && projectId != null) DeleteProjectDialog(onDismiss = { deletingProject = false }) { deletingProject = false; controller.deleteProject(projectId) }
}

@Composable
private fun MenuItem(label: Int, tag: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) =
    MenuItem(stringResource(label), tag, enabled, danger, onClick)

@Composable
private fun MenuItem(label: String, tag: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
        onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)
    )
}

/** "Saved" / "Saving…" / "Unsaved changes" indicator. */
@Composable
private fun SaveStatus(saving: Boolean, dirty: Boolean, onPrimary: Boolean) {
    val color = if (onPrimary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp).testTag("thesis_save_status")) {
        when {
            saving -> { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.thesis_saving), color = color, style = MaterialTheme.typography.labelMedium) }
            dirty -> Text(stringResource(R.string.thesis_unsaved), color = if (onPrimary) color else MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
            else -> {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = if (onPrimary) Color(0xFF7CE0A6) else CompletedGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.thesis_saved), color = color, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun StatusIcon(status: ThesisStructure.Status) {
    val (icon: ImageVector, tint: Color) = when (status) {
        ThesisStructure.Status.COMPLETED -> Icons.Default.CheckCircle to CompletedGreen
        ThesisStructure.Status.IN_PROGRESS -> Icons.Outlined.Timelapse to MaterialTheme.colorScheme.primary
        ThesisStructure.Status.AUTOMATIC -> Icons.Outlined.Description to MaterialTheme.colorScheme.primary
        ThesisStructure.Status.NOT_STARTED -> Icons.Outlined.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
}

@Composable
private fun statusText(status: ThesisStructure.Status) = stringResource(
    when (status) {
        ThesisStructure.Status.COMPLETED -> R.string.thesis_status_completed
        ThesisStructure.Status.IN_PROGRESS -> R.string.thesis_status_in_progress
        ThesisStructure.Status.AUTOMATIC -> R.string.thesis_status_automatic
        ThesisStructure.Status.NOT_STARTED -> R.string.thesis_status_not_started
    }
)

/** One section of the outline: status, Edit, and (expanded) its sub-sections and management actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionCard(
    controller: ThesisController,
    snapshot: ThesisSnapshot,
    s: ThesisSection,
    numbering: com.example.thesis.core.ThesisNumbering,
    depth: Int,
    expanded: Boolean,
    expandedIds: Set<String>,
    onToggle: (String) -> Unit,
    onRename: (ThesisSection) -> Unit,
    onDelete: (ThesisSection) -> Unit,
    onAddSub: (ThesisSection) -> Unit
) {
    val state by controller.state.collectAsState()
    val idle = state.busy == null
    val status = ThesisStructure.status(snapshot, s)
    val siblings = snapshot.children(s.parentId)
    val index = siblings.indexOfFirst { it.id == s.id }
    val children = snapshot.children(s.id).filter { it.enabled }
    Card(
        Modifier.fillMaxWidth().padding(start = (depth * 16).dp).testTag("thesis_section_${s.id}"), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(status)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ThesisStructure.displayTitle(s, numbering, snapshot.meta.language), style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(statusText(status), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("thesis_status_${s.id}"),
                        color = when (status) { ThesisStructure.Status.COMPLETED -> CompletedGreen; ThesisStructure.Status.NOT_STARTED -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.primary })
                }
                when {
                    s.kind == SectionKind.COVER -> FilledTonalButton(onClick = { controller.show(ThesisScreen.SETTINGS) }, modifier = Modifier.testTag("thesis_edit_${s.id}")) { Text(stringResource(R.string.thesis_edit)) }
                    s.kind.generated -> {}
                    else -> FilledTonalButton(onClick = { controller.selectSection(s.id) }, modifier = Modifier.testTag("thesis_edit_${s.id}")) { Text(stringResource(R.string.thesis_edit)) }
                }
                IconButton(onClick = { onToggle(s.id) }, modifier = Modifier.testTag("thesis_expand_${s.id}")) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(if (expanded) R.string.thesis_collapse else R.string.thesis_expand))
                }
            }
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.kind.generated) Text(stringResource(if (s.kind == SectionKind.COVER) R.string.thesis_cover_hint else R.string.thesis_generated_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text(stringResource(R.string.thesis_elements_count, snapshot.elementsOf(s.id).size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallAction(Icons.Outlined.Edit, R.string.thesis_rename, "thesis_rename_${s.id}", idle) { onRename(s) }
                        if (index > 0) SmallAction(Icons.Outlined.ArrowUpward, R.string.thesis_move_up, "thesis_up_${s.id}", idle) {
                            controller.direct(listOf(ThesisOp.MoveSection(s.id, s.parentId, siblings.getOrNull(index - 2)?.id ?: ThesisOperations.AFTER_START)), ChangeSource.STRUCTURE, "reorder")
                        }
                        if (index in 0 until siblings.lastIndex) SmallAction(Icons.Outlined.ArrowDownward, R.string.thesis_move_down, "thesis_down_${s.id}", idle) {
                            controller.direct(listOf(ThesisOp.MoveSection(s.id, s.parentId, siblings[index + 1].id)), ChangeSource.STRUCTURE, "reorder")
                        }
                        if (s.kind.container) SmallAction(Icons.Default.Add, R.string.thesis_add_section, "thesis_add_sub_${s.id}", idle) { onAddSub(s) }
                        if (!s.kind.generated) SmallAction(Icons.Default.CheckCircle, if (s.completed) R.string.thesis_mark_in_progress else R.string.thesis_mark_completed, "thesis_complete_${s.id}", idle) {
                            controller.setSectionCompleted(s.id, !s.completed)
                        }
                        if (s.kind != SectionKind.COVER) SmallAction(Icons.Outlined.Visibility, R.string.thesis_remove_from_thesis, "thesis_hide_${s.id}", idle) {
                            controller.direct(listOf(ThesisOp.UpdateSection(s.id, enabled = false)), ChangeSource.STRUCTURE, "hide")
                        }
                        SmallAction(Icons.Outlined.Delete, R.string.template_delete, "thesis_delete_${s.id}", idle, danger = true) { onDelete(s) }
                    }
                    children.forEach { c ->
                        SectionCard(controller, snapshot, c, numbering, depth = 1, expanded = c.id in expandedIds, expandedIds = expandedIds,
                            onToggle = onToggle, onRename = onRename, onDelete = onDelete, onAddSub = onAddSub)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: Int, tag: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.heightIn(min = 40.dp).testTag(tag),
        colors = if (danger) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonColors()
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

// =================================================================== 4. section editor

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThesisSectionEditor(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val snapshot = state.snapshot
    val draft = state.draft
    val section = snapshot?.section(state.selectedSectionId)
    val context = LocalContext.current
    var value by remember(draft?.sectionId) { mutableStateOf(TextFieldValue(draft?.text.orEmpty())) }
    // Undo / redo of the text (typing is grouped by pauses).
    val undo = remember(draft?.sectionId) { mutableStateListOf<TextFieldValue>() }
    val redo = remember(draft?.sectionId) { mutableStateListOf<TextFieldValue>() }
    var lastPush by remember(draft?.sectionId) { mutableStateOf(0L) }
    // Text changed outside the field (image, table, AI result, save): show it, cursor at the end.
    LaunchedEffect(draft?.text) {
        val t = draft?.text ?: return@LaunchedEffect
        if (t != value.text) value = TextFieldValue(t, TextRange(t.length))
    }
    var menu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var headingMenu by remember { mutableStateOf(false) }
    var pickChat by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var addingTable by remember { mutableStateOf(false) }
    var editingMedia by remember { mutableStateOf<String?>(null) }
    var confirmFormat by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> pendingImage = uri }
    val idle = state.busy == null
    val language = snapshot?.meta?.language ?: "en"
    val title = if (snapshot != null && section != null) ThesisStructure.displayTitle(section, ThesisStructure.numbering(snapshot), snapshot.meta.language) else ""

    fun commit(new: TextFieldValue, group: Boolean = false) {
        if (new.text != value.text) {
            val now = System.currentTimeMillis()
            if (!group || now - lastPush > 800) {
                undo.add(value)
                if (undo.size > 200) undo.removeAt(0)
                lastPush = now
            }
            redo.clear()
        }
        value = new
        if (new.text != draft?.text) controller.updateDraft(new.text)
    }
    fun applyOp(op: (EditorText) -> EditorText) {
        val r = op(EditorText.of(value.text, value.selection.start, value.selection.end))
        commit(TextFieldValue(r.text, TextRange(r.start, r.end)))
    }
    // Blocks go after the selection: selected text is never replaced by an insertion.
    fun insertAtCursor(text: String) = applyOp { EditorOps.insertBlock(EditorText(it.text, it.end, it.end), text) }
    fun heading(level: Int) {
        val t = EditorText.of(value.text, value.selection.start, value.selection.end)
        val lineStart = t.text.lastIndexOf('\n', (t.start - 1).coerceAtLeast(0)).let { if (t.start == 0 || it < 0) 0 else it + 1 }
        val lineEnd = t.text.indexOf('\n', t.start).let { if (it < 0) t.text.length else it }
        if (t.text.substring(lineStart, lineEnd).isBlank() && level > 0) {
            // Empty line: start a new heading there.
            val prefix = "#".repeat(level) + " "
            commit(TextFieldValue(t.text.substring(0, lineStart) + prefix + t.text.substring(lineEnd), TextRange(lineStart + prefix.length)))
        } else applyOp { EditorOps.setHeading(it, level) }
    }

    Column(Modifier.fillMaxSize()) {
        BlueTopBar(title, onBack = { controller.back() }) {
            IconButton(onClick = { controller.saveDraft() }, enabled = idle && draft?.dirty == true, modifier = Modifier.testTag("thesis_save_icon")) {
                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.thesis_save_changes))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("thesis_editor_menu")) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(R.string.thesis_section_history, "thesis_menu_section_history", enabled = idle) { menu = false; section?.let { controller.loadSectionHistory(it.id) } }
                    MenuItem(R.string.thesis_apply_formatting, "thesis_menu_format", enabled = idle) { menu = false; if (draft?.dirty == true) confirmFormat = true else section?.let { controller.formatSection(it.id) } }
                    MenuItem(R.string.thesis_section_chat, "thesis_menu_section_chat") { menu = false; state.projectId?.let { pid -> section?.let { host.continueThesisInChat(pid, it.id) } } }
                }
            }
        }
        state.busy?.let { BusyBar(controller, it) }
        if (snapshot == null || section == null || draft == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SaveStatus(saving = state.busy == com.example.thesis.ThesisBusy.SAVING, dirty = draft.dirty, onPrimary = false)
                Spacer(Modifier.weight(1f))
                Row(Modifier.clickable(enabled = idle) { controller.setSectionCompleted(section.id, !section.completed) }.heightIn(min = 48.dp).testTag("thesis_section_completed"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = section.completed, onCheckedChange = null)
                    Text(stringResource(R.string.thesis_status_completed), style = MaterialTheme.typography.labelLarge)
                }
            }
            // Section title and note (saved with the text).
            TextButton(onClick = { showInfo = !showInfo }, modifier = Modifier.testTag("thesis_section_info_toggle")) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.thesis_section_details))
                Icon(if (showInfo) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
            if (showInfo) {
                OutlinedTextField(
                    value = draft.title, onValueChange = { controller.updateDraftInfo(it, draft.description) }, singleLine = true,
                    label = { Text(stringResource(R.string.thesis_section_title)) }, shape = RoundedCornerShape(12.dp),
                    supportingText = if (section.kind == SectionKind.CHAPTER || section.kind == SectionKind.SECTION || section.kind == SectionKind.SUBSECTION) ({ Text(stringResource(R.string.thesis_section_title_hint)) }) else null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth().testTag("thesis_section_title_field")
                )
                OutlinedTextField(
                    value = draft.description, onValueChange = { controller.updateDraftInfo(draft.title, it) }, minLines = 2,
                    label = { Text(stringResource(R.string.thesis_section_description)) }, shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth().testTag("thesis_section_description")
                )
            }
            state.sectionHistory?.let { SectionHistoryCard(controller, it, onClose = { controller.selectSection(section.id) }) }
            // Formatting toolbar.
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.Filled.Undo, R.string.thesis_undo, "thesis_undo", undo.isNotEmpty()) {
                        val prev = undo.removeAt(undo.lastIndex); redo.add(value); value = prev; controller.updateDraft(prev.text)
                    }
                    ToolIcon(Icons.Filled.Redo, R.string.thesis_redo, "thesis_redo", redo.isNotEmpty()) {
                        val next = redo.removeAt(redo.lastIndex); undo.add(value); value = next; controller.updateDraft(next.text)
                    }
                    ToolDivider()
                    ToolIcon(Icons.Filled.FormatBold, R.string.thesis_fmt_bold, "thesis_fmt_bold") { applyOp { EditorOps.toggleWrap(it, "**") } }
                    ToolIcon(Icons.Filled.FormatItalic, R.string.thesis_fmt_italic, "thesis_fmt_italic") { applyOp { EditorOps.toggleWrap(it, "*") } }
                    ToolIcon(Icons.Filled.FormatUnderlined, R.string.thesis_fmt_underline, "thesis_fmt_underline") { applyOp { EditorOps.toggleWrap(it, "__") } }
                    ToolDivider()
                    Box {
                        ToolIcon(Icons.Filled.Title, R.string.thesis_add_heading, "thesis_fmt_heading") { headingMenu = true }
                        DropdownMenu(expanded = headingMenu, onDismissRequest = { headingMenu = false }) {
                            listOf(1 to R.string.thesis_heading_1, 2 to R.string.thesis_heading_2, 3 to R.string.thesis_heading_3, 0 to R.string.thesis_heading_normal).forEach { (lvl, label) ->
                                MenuItem(label, "thesis_heading_$lvl") { headingMenu = false; heading(lvl) }
                            }
                        }
                    }
                    ToolIcon(Icons.Filled.FormatListBulleted, R.string.thesis_fmt_bullets, "thesis_fmt_bullets") { applyOp { EditorOps.toggleBullets(it) } }
                    ToolIcon(Icons.Filled.FormatListNumbered, R.string.thesis_fmt_numbers, "thesis_fmt_numbers") { applyOp { EditorOps.toggleNumbers(it) } }
                    ToolDivider()
                    ToolIcon(Icons.Filled.FormatAlignLeft, R.string.thesis_align_start, "thesis_align_start") { applyOp { EditorOps.setAlign(it, "") } }
                    ToolIcon(Icons.Filled.FormatAlignCenter, R.string.thesis_align_center, "thesis_align_center") { applyOp { EditorOps.setAlign(it, "center") } }
                    ToolIcon(Icons.Filled.FormatAlignRight, R.string.thesis_align_end, "thesis_align_end") { applyOp { EditorOps.setAlign(it, "end") } }
                    ToolIcon(Icons.Filled.FormatAlignJustify, R.string.thesis_align_justify, "thesis_align_justify") { applyOp { EditorOps.setAlign(it, "justify") } }
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = { commit(it, group = true) },
                placeholder = { Text(stringResource(R.string.thesis_paste_placeholder)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content, lineHeight = 24.sp),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp).testTag("thesis_editor_text")
            )
            Text(stringResource(R.string.thesis_format_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (draft.media.isNotEmpty()) MediaStrip(state.assets, draft, onEdit = { editingMedia = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContentAction(Icons.Outlined.ContentPaste, R.string.thesis_paste_text, "thesis_paste_text", true) {
                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text.isNullOrBlank()) android.widget.Toast.makeText(context, context.getString(R.string.thesis_clipboard_empty), android.widget.Toast.LENGTH_SHORT).show()
                    else insertAtCursor(text)
                }
                ContentAction(Icons.Outlined.Forum, R.string.thesis_insert_from_chat, "thesis_insert_from_chat", true) { pickChat = true }
                ContentAction(Icons.Outlined.Image, R.string.thesis_add_image, "thesis_add_image", idle) { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContentAction(Icons.Outlined.TableChart, R.string.thesis_insert_table, "thesis_insert_table", idle) { addingTable = true }
                ContentAction(Icons.Filled.Title, R.string.thesis_add_heading, "thesis_add_heading", true) { headingMenu = true }
                ContentAction(Icons.Filled.HorizontalRule, R.string.thesis_page_break, "thesis_page_break", true) { insertAtCursor(com.example.thesis.core.SectionText.pageBreakToken(language)) }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { controller.buildSectionPreview(section.id) }, enabled = idle, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1f).height(52.dp).testTag("thesis_editor_preview")) {
                Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.thesis_preview_page), maxLines = 1)
            }
            FilledTonalButton(onClick = {
                controller.updateDraft(value.text)
                controller.openAssistant(minOf(value.selection.start, value.selection.end), maxOf(value.selection.start, value.selection.end))
            }, enabled = idle, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.weight(1f).height(52.dp).testTag("thesis_open_assistant")) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.thesis_ai_short), maxLines = 1)
            }
            Button(onClick = { controller.saveDraft() }, enabled = idle && draft.dirty, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1f).height(52.dp).testTag("thesis_save_changes")) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.action_save), maxLines = 1)
            }
        }
    }
    if (pickChat) ChatPickerDialog(host, onDismiss = { pickChat = false }) { text -> pickChat = false; insertAtCursor(text) }
    pendingImage?.let { uri ->
        MediaDataDialog(ElementData(), isImage = true, onDismiss = { pendingImage = null }) { data ->
            pendingImage = null
            val resolver = context.contentResolver
            controller.updateDraft(value.text)
            controller.insertDraftImage({ resolver.openInputStream(uri) }, uri.lastPathSegment ?: "image", resolver.getType(uri) ?: "image/*", data, value.selection.end)
        }
    }
    if (addingTable) TableGridDialog(null, onDismiss = { addingTable = false }) { data ->
        addingTable = false
        controller.updateDraft(value.text)
        controller.insertDraftTable(data, value.selection.end)
    }
    editingMedia?.let { token ->
        draft?.media?.get(token)?.let { m ->
            if (m.type == com.example.thesis.model.ElementType.TABLE) TableGridDialog(m.data, onDismiss = { editingMedia = null }) { data -> editingMedia = null; controller.updateDraftMedia(token, data) }
            else MediaDataDialog(m.data, isImage = true, onDismiss = { editingMedia = null }) { data -> editingMedia = null; controller.updateDraftMedia(token, data) }
        }
    }
    if (confirmFormat) {
        AlertDialog(
            onDismissRequest = { confirmFormat = false },
            title = { Text(stringResource(R.string.thesis_apply_formatting)) },
            text = { Text(stringResource(R.string.thesis_save_before_format)) },
            confirmButton = { Button(onClick = { confirmFormat = false; controller.saveDraft() }) { Text(stringResource(R.string.thesis_save_changes)) } },
            dismissButton = { TextButton(onClick = { confirmFormat = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: Int, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) { Icon(icon, contentDescription = stringResource(label)) }
}

@Composable
private fun ToolDivider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** Creates or edits a table: rows and columns, header row and cells, caption. */
@Composable
private fun TableGridDialog(initial: ElementData?, onDismiss: () -> Unit, onSave: (ElementData) -> Unit) {
    val startCols = initial?.tableHeader?.size?.takeIf { it > 0 } ?: 2
    val startRows = initial?.tableRows?.size?.takeIf { it > 0 } ?: 2
    var cols by remember { mutableIntStateOf(startCols) }
    var rows by remember { mutableIntStateOf(startRows) }
    var caption by remember { mutableStateOf(initial?.caption.orEmpty()) }
    // Row 0 is the header row.
    var cells by remember {
        mutableStateOf(List(startRows + 1) { r -> List(startCols) { c -> (if (r == 0) initial?.tableHeader else initial?.tableRows?.getOrNull(r - 1))?.getOrNull(c).orEmpty() } })
    }
    fun resize(newRows: Int, newCols: Int) {
        rows = newRows; cols = newCols
        cells = List(newRows + 1) { r -> List(newCols) { c -> cells.getOrNull(r)?.getOrNull(c).orEmpty() } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.thesis_insert_table else R.string.thesis_edit_table)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper(R.string.thesis_table_rows, rows, 1, 30, "thesis_table_rows") { resize(it, cols) }
                Stepper(R.string.thesis_table_columns, cols, 1, 8, "thesis_table_cols") { resize(rows, it) }
                OutlinedTextField(caption, { caption = it }, label = { Text(stringResource(R.string.thesis_caption)) }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth().testTag("thesis_table_caption"))
                Text(stringResource(R.string.thesis_table_header_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cells.forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEachIndexed { c, v ->
                                OutlinedTextField(
                                    value = v, singleLine = true,
                                    onValueChange = { nv -> cells = cells.mapIndexed { ri, rr -> if (ri == r) rr.mapIndexed { ci, cv -> if (ci == c) nv else cv } else rr } },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content, fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal),
                                    modifier = Modifier.width(110.dp).testTag("thesis_cell_${r}_$c")
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave((initial ?: ElementData()).copy(caption = caption.trim(), tableHeader = cells.first().map { it.trim() }, tableRows = cells.drop(1).map { r -> r.map { it.trim() } }))
            }, modifier = Modifier.testTag("thesis_table_save")) { Text(stringResource(if (initial == null) R.string.action_add else R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun Stepper(label: Int, value: Int, min: Int, max: Int, tag: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange(value - 1) }, enabled = value > min, modifier = Modifier.testTag("${tag}_minus")) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("${tag}_value"))
        IconButton(onClick = { onChange(value + 1) }, enabled = value < max, modifier = Modifier.testTag("${tag}_plus")) { Icon(Icons.Default.Add, contentDescription = null) }
    }
}

@Composable
private fun RowScope.ContentAction(icon: ImageVector, label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(tag)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Figures and tables of the section (their placeholder lines mark where they appear). */
@Composable
private fun MediaStrip(assets: List<com.example.thesis.model.ThesisAsset>, draft: SectionDraft, onEdit: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        draft.media.forEach { (token, m) ->
            Card(Modifier.width(120.dp).clickable { onEdit(token) }.testTag("thesis_media_$token"), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(6.dp)) {
                    if (m.type == com.example.thesis.model.ElementType.IMAGE) ThesisImageThumb(assets.firstOrNull { it.id == m.data.assetId }?.path, Modifier.fillMaxWidth().height(60.dp), maxPx = 240)
                    Text(token, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    if (m.data.caption.isNotBlank()) Text(m.data.caption, style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "Insert from Chat": recent Chat messages; the chosen one goes into the text at the cursor. */
@Composable
private fun ChatPickerDialog(host: ThesisHost, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val messages by produceState<List<ChatSnippet>?>(null) { value = try { host.recentChatMessages(30) } catch (e: Exception) { emptyList() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thesis_insert_from_chat)) },
        text = {
            val list = messages
            when {
                list == null -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(stringResource(R.string.thesis_chat_none), modifier = Modifier.testTag("thesis_chat_none"))
                else -> LazyColumn(Modifier.heightIn(max = 420.dp).testTag("thesis_chat_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list) { m ->
                        Card(Modifier.fillMaxWidth().clickable { onPick(m.text) }.testTag("thesis_chat_item"), shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(stringResource(if (m.fromUser) R.string.thesis_chat_you else R.string.thesis_chat_assistant) + " · " + formatDate(m.time),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(m.text, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content), maxLines = 5, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

// =================================================================== 5. AI assistant

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThesisAiAssistant(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val assist: AiAssist? = state.assist
    val context = LocalContext.current
    // null while loading, empty when no default provider is set in Settings.
    val providers by produceState<List<Pair<String, String>>?>(null) { value = try { listOfNotNull(host.aiProviderInfo()) } catch (e: Exception) { emptyList() } }
    val provider = providers?.firstOrNull()
    val idle = state.busy == null
    var confirmReplace by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BlueTopBar(stringResource(R.string.thesis_ai_assistant), onBack = { controller.closeAssistant() })
        state.busy?.let { BusyBar(controller, it) }
        if (assist == null) {
            LaunchedEffect(Unit) { controller.closeAssistant() }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth().testTag("thesis_ai_provider"), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    if (providers == null) CircularProgressIndicator(Modifier.size(20.dp))
                    else if (provider != null) Column {
                        Text(stringResource(R.string.thesis_ai_provider, provider.first), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.thesis_ai_model, provider.second.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text(stringResource(R.string.thesis_ai_no_provider), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("thesis_ai_no_provider"))
                }
            }
            Text(stringResource(if (assist.hasSelection) R.string.thesis_ai_selected_text else R.string.thesis_ai_whole_section), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp)) {
                Text(assist.source.ifBlank { stringResource(R.string.thesis_ai_empty_section) }, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(10.dp).testTag("thesis_ai_source"))
            }
            Text(stringResource(R.string.thesis_ai_request), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = assist.instruction, onValueChange = { controller.setAssistInstruction(it) }, minLines = 2,
                placeholder = { Text(stringResource(R.string.thesis_ai_request_hint)) }, shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth().testTag("thesis_ai_instruction")
            )
            val canRun = idle && provider != null
            val hasText = assist.source.isNotBlank()
            Button(onClick = { controller.runAssistant(EditMode.IMPROVE, assist.instruction) }, enabled = canRun && hasText, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("thesis_ai_IMPROVE")) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.thesis_ai_improve_selected))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple(EditMode.EXPAND, Icons.Outlined.UnfoldMore, R.string.thesis_ai_expand_content),
                    Triple(EditMode.REWRITE, Icons.Outlined.Autorenew, R.string.thesis_ai_rewrite),
                    Triple(EditMode.CORRECT, Icons.Outlined.Spellcheck, R.string.thesis_ai_correct_grammar),
                    Triple(EditMode.ORGANIZE, Icons.Outlined.Reorder, R.string.thesis_ai_organize)
                ).forEach { (mode, icon, label) ->
                    OutlinedButton(onClick = { controller.runAssistant(mode, assist.instruction) }, enabled = canRun && hasText, shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("thesis_ai_${mode.name}")) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(label))
                    }
                }
            }
            FilledTonalButton(onClick = { controller.runAssistant(EditMode.GENERATE, assist.instruction) }, enabled = canRun, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("thesis_ai_generate")) {
                Icon(Icons.Outlined.Edit, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.thesis_ai_generate))
            }
            Text(stringResource(R.string.thesis_ai_integrity), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                assist.failed -> Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("thesis_ai_failed")) {
                    Text(stringResource(R.string.thesis_ai_failed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
                assist.unchanged -> Text(stringResource(R.string.thesis_ai_unchanged), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("thesis_ai_unchanged"))
            }
            assist.result?.let { result ->
                Text(stringResource(R.string.thesis_ai_result_editable), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = result, onValueChange = { controller.setAssistResult(it) }, minLines = 4, shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.fillMaxWidth().testTag("thesis_ai_result")
                )
                assist.warnings.forEach { Text("⚠ " + noteText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                Text(stringResource(R.string.thesis_ai_review_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { controller.insertAssistResult(replace = false) }, enabled = idle && result.isNotBlank(), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CompletedGreen, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("thesis_ai_insert")) {
                    Icon(Icons.Default.Check, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.thesis_ai_insert))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasText && assist.mode != EditMode.GENERATE) OutlinedButton(onClick = { confirmReplace = true }, enabled = idle && result.isNotBlank(), shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("thesis_ai_replace")) {
                        Icon(Icons.Outlined.Autorenew, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.thesis_ai_replace_short), maxLines = 1)
                    }
                    OutlinedButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("thesis", result))
                        android.widget.Toast.makeText(context, context.getString(R.string.thesis_copied), android.widget.Toast.LENGTH_SHORT).show()
                    }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("thesis_ai_copy")) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.thesis_ai_copy_short), maxLines = 1)
                    }
                    OutlinedButton(onClick = { controller.closeAssistant() }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("thesis_ai_discard")) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.action_cancel), maxLines = 1)
                    }
                }
            }
        }
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(if (assist?.hasSelection == true) R.string.thesis_ai_replace_selection else R.string.thesis_ai_replace_section)) },
            text = { Text(stringResource(R.string.thesis_ai_replace_confirm_text)) },
            confirmButton = { Button(onClick = { confirmReplace = false; controller.insertAssistResult(replace = true) }, modifier = Modifier.testTag("thesis_ai_replace_confirm")) { Text(stringResource(R.string.thesis_ai_replace_short)) } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

// =================================================================== 6. preview and export

@Composable
private fun ThesisPreview(host: ThesisHost) {
    val controller = host.thesis
    val state by controller.state.collectAsState()
    val file = state.previewFile
    LaunchedEffect(file == null) { if (file == null && controller.state.value.busy == null && controller.state.value.previewSectionId == null) controller.buildPreview() }
    val sectionPreview = state.previewSectionId?.let { id -> state.snapshot?.let { s -> s.section(id)?.let { ThesisStructure.displayTitle(it, ThesisStructure.numbering(s), s.meta.language) } } }
    val pages = remember(file) { file?.let { pageCount(it) } ?: 0 }.takeIf { it > 0 } ?: if (file != null) state.previewPages else 0
    var page by remember(file) { mutableIntStateOf(0) }
    var zoom by remember { mutableStateOf(1f) }
    val idle = state.busy == null
    val zoomOut = stringResource(R.string.thesis_zoom_out)
    val zoomIn = stringResource(R.string.thesis_zoom_in)
    Column(Modifier.fillMaxSize()) {
        BlueTopBar(sectionPreview ?: stringResource(R.string.thesis_preview_thesis), onBack = { controller.back() }) {
            IconButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(0.75f) }, modifier = Modifier.semantics { contentDescription = zoomOut }.testTag("thesis_zoom_out")) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(2.5f) }, modifier = Modifier.semantics { contentDescription = zoomIn }.testTag("thesis_zoom_in")) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
        state.busy?.let { BusyBar(controller, it) }
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            when {
                file == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.thesis_preview_hint), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { controller.buildPreview() }, enabled = idle, modifier = Modifier.testTag("thesis_preview_build")) { Text(stringResource(R.string.thesis_preview_generate)) }
                }
                pages == 0 -> Text(stringResource(R.string.thesis_preview_unavailable), textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp).testTag("thesis_preview_unavailable"))
                else -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 16.dp),
                    contentAlignment = Alignment.TopCenter) {
                    Surface(shadowElevation = 4.dp, color = Color.White) { PdfPage(file, page.coerceIn(0, pages - 1), zoom) }
                }
            }
            if (file != null && pages > 0) {
                PageArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, R.string.action_previous_page, "thesis_page_prev", page > 0, Modifier.align(Alignment.CenterStart)) { page-- }
                PageArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, R.string.action_next_page, "thesis_page_next", page < pages - 1, Modifier.align(Alignment.CenterEnd)) { page++ }
            }
        }
        Text(
            if (pages > 0) stringResource(R.string.thesis_page_slash, page + 1, pages) else "",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("thesis_page_indicator")
        )
        state.lastExport?.let { Box(Modifier.padding(horizontal = 16.dp)) { ExportResultCard(host, it) } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { controller.show(ThesisScreen.PROJECT) }, modifier = Modifier.testTag("thesis_preview_back_workspace")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.thesis_back_to_workspace))
            }
            Spacer(Modifier.weight(1f))
            if (sectionPreview != null) TextButton(onClick = { controller.buildPreview() }, enabled = idle, modifier = Modifier.testTag("thesis_preview_whole")) { Text(stringResource(R.string.thesis_preview_whole)) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { controller.export("DOCX") }, enabled = idle, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(52.dp).testTag("thesis_export_docx")) {
                Icon(Icons.Outlined.Description, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.thesis_export_word), maxLines = 1)
            }
            Button(onClick = { controller.export("PDF") }, enabled = idle, shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935), contentColor = Color.White),
                modifier = Modifier.weight(1f).height(52.dp).testTag("thesis_export_pdf")) {
                Icon(Icons.Outlined.PictureAsPdf, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.thesis_export_pdf_short), maxLines = 1)
            }
        }
    }
}

@Composable
private fun PageArrow(icon: ImageVector, label: Int, tag: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp, modifier = modifier.padding(8.dp)) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
            Icon(icon, contentDescription = stringResource(label), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
