@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.window.Dialog
import com.example.ocr.DocumentScannerHelper
import com.example.ui.components.OcrReviewDialog
import com.example.ui.components.SmartBubbleLogo
import com.example.ui.components.SlashCommandSuggestions
import com.example.ui.components.ClearConversationsDialog
import com.example.ui.components.OfflineFormattingDialog
import com.example.ui.components.OfflineFormattingSuggestion
import com.example.offline.document.OfflinePhase
import com.example.ui.commands.SendResult
import com.example.ui.commands.SlashCommand
import com.example.ui.commands.SlashCommands
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.R
import com.example.ai.orchestrator.AiProgressStage
import com.example.core.voice.VoiceState
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ConversationEntity
import com.example.data.model.DocumentEntity
import com.example.data.model.TemplateEntity
import com.example.ui.SmartBubbleViewModel
import com.example.ui.VoicePhase
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatScreen(
    viewModel: SmartBubbleViewModel,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.chatMessages.collectAsState()
    val allDocs by viewModel.allDocuments.collectAsState()
    val allTemplates by viewModel.allTemplates.collectAsState()
    val conversations by viewModel.allConversations.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val aiProgress by viewModel.aiProgress.collectAsState()
    val streamingReply by viewModel.streamingReply.collectAsState()
    val generatingSessionId by viewModel.generatingSessionId.collectAsState()
    // Progress of a running operation is shown only in the conversation it belongs to.
    val generatingHere = isGenerating && generatingSessionId == currentSessionId
    val voiceState by viewModel.voiceState.collectAsState()
    val voicePhase by viewModel.voicePhase.collectAsState()
    val voiceActive by viewModel.voiceConversationActive.collectAsState()
    val voicePartialText by viewModel.voicePartialText.collectAsState()
    val voiceInputLevel by viewModel.voiceInputLevel.collectAsState()
    val speakingMessageId by viewModel.speakingMessageId.collectAsState()
    val selectedTemplate by viewModel.selectedTemplateForChat.collectAsState()
    val defaultProvider by viewModel.defaultProvider.collectAsState()

    // TextFieldValue keeps the cursor at the end after a slash command is inserted.
    var inputField by remember { mutableStateOf(TextFieldValue("")) }
    val inputMessage = inputField.text
    val clearDialogVisible by viewModel.clearDialogVisible.collectAsState()
    val offlineState by viewModel.offlineFormatting.state.collectAsState()
    val thesisState by viewModel.thesis.state.collectAsState()
    val thesisProjects by viewModel.thesis.projects.collectAsState()
    val thesisChatProject = thesisState.chatProjectId?.let { id -> thesisProjects.firstOrNull { it.id == id } }
    var showTemplatePicker by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }

    // Scanned document review & progress states
    val scannedReviewState by viewModel.scannedReviewState.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val ocrLanguage by viewModel.ocrLanguage.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()

    // Conversation management dialog states
    var conversationToRename by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var conversationToDelete by remember { mutableStateOf<ConversationEntity?>(null) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uris = DocumentScannerHelper.parseScanResult(result.resultCode, result.data)
        if (uris.isNotEmpty()) {
            viewModel.processScannedPages(uris)
        }
    }

    // Runtime microphone permission for voice conversation
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceConversation() else viewModel.onMicrophonePermissionDenied()
    }
    val startVoice: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startVoiceConversation()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(25)
    ) { uris ->
        if (uris.isNotEmpty()) {
            // Thesis mode: images become thesis figures (the composer text is the placement instruction).
            if (viewModel.thesis.state.value.chatProjectId != null) {
                viewModel.addThesisImages(uris, inputField.text.trim())
                inputField = TextFieldValue("")
            } else {
                viewModel.processScannedPages(uris)
            }
        }
    }

    LaunchedEffect(messages.size, generatingHere) {
        val last = messages.size - 1 + (if (generatingHere) 1 else 0)
        if (last >= 0) listState.animateScrollToItem(last)
    }
    // Keep the streamed reply in view while it grows (only if the user is already at the bottom).
    LaunchedEffect(streamingReply.length / 200) {
        if (generatingHere && streamingReply.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
            // Scrolling is clamped at the end of the list.
            if (lastVisible >= messages.size - 1) listState.scrollBy(100_000f)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(310.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    // Drawer Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmartBubbleLogo(size = 34.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(onClick = { coroutineScope.launch { drawerState.close() } }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_close)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Prominent New Conversation Button
                    Button(
                        onClick = {
                            viewModel.startNewConversation()
                            inputField = TextFieldValue("")
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("chat_drawer_new_conversation"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.new_conversation),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = stringResource(R.string.conversations_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Conversations List
                    if (conversations.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.no_conversations),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(conversations, key = { it.id }) { conv ->
                                val isSelected = conv.id == currentSessionId
                                ConversationDrawerItem(
                                    conversation = conv,
                                    isSelected = isSelected,
                                    onSelect = {
                                        viewModel.selectConversation(conv.id)
                                        coroutineScope.launch { drawerState.close() }
                                    },
                                    onRename = {
                                        conversationToRename = conv
                                        renameInput = conv.title
                                    },
                                    onDelete = {
                                        conversationToDelete = conv
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Chat Top Bar with Menu Hamburger Button
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        // 1. Hamburger Menu Button (at top-left)
                        IconButton(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("chat_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = stringResource(R.string.chat_menu),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // SmartBubble colorful logo
                        SmartBubbleLogo(size = 34.dp)

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = defaultProvider.ifBlank { stringResource(R.string.chat_no_default_provider) },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (defaultProvider.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Thesis Builder (a workspace opened from Chat, not a navigation tab)
                    IconButton(
                        onClick = { viewModel.thesis.openWorkspace() },
                        modifier = Modifier.testTag("chat_thesis_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.School,
                            contentDescription = stringResource(R.string.thesis_builder),
                            tint = if (thesisChatProject != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Options / Template Filter button on top right
                    IconButton(
                        onClick = { showTemplatePicker = true },
                        modifier = Modifier.testTag("chat_filter_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(R.string.chat_select_template),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Selected Template Pill (if attached)
            AnimatedVisibility(visible = selectedTemplate != null) {
                selectedTemplate?.let { tmpl ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.chat_template_badge, tmpl.name, tmpl.fileType),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { viewModel.selectTemplateForChat(null) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.chat_remove_template),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Messages List
            val docsById = remember(allDocs) { allDocs.associateBy { it.id } }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    val isThesisProposal = msg.attachedDocType == com.example.thesis.THESIS_PROPOSAL_CARD
                    val attachedDoc = if (isThesisProposal) null else msg.attachedDocId?.let { docsById[it] }

                    ChatBubbleItem(
                        message = msg,
                        attachedDoc = attachedDoc,
                        audioState = when {
                            speakingMessageId != msg.id -> MessageAudioState.IDLE
                            voiceState == VoiceState.PAUSED -> MessageAudioState.PAUSED
                            else -> MessageAudioState.SPEAKING
                        },
                        onToggleAudio = { viewModel.toggleMessageSpeech(msg.id, msg.content) },
                        onStopAudio = { viewModel.stopSpeaking() },
                        onCopyText = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SmartBubble", msg.content))
                        },
                        onOpenDoc = { attachedDoc?.let { viewModel.openDocument(it) } },
                        onShareDoc = { attachedDoc?.let { viewModel.shareDocument(it) } },
                        onExportPdf = { attachedDoc?.let { viewModel.exportDocumentAsPdf(it) } },
                        onFollowUpAi = { action -> attachedDoc?.let { viewModel.executeFollowUpAiAction(action, it.id) } }
                    )
                    if (isThesisProposal && msg.attachedDocId != null) {
                        TextButton(
                            onClick = { viewModel.thesis.openReview(msg.attachedDocId) },
                            modifier = Modifier.testTag("thesis_review_${msg.attachedDocId}")
                        ) { Text(stringResource(R.string.thesis_review_changes)) }
                    }
                }

                if (generatingHere) {
                    item(key = "generating") {
                        GeneratingIndicatorBubble(stage = aiProgress, partialReply = streamingReply)
                    }
                }
            }

            // Voice conversation status bar: listening / recognizing / thinking / speaking
            if (voiceActive || voicePhase == VoicePhase.LISTENING || voicePhase == VoicePhase.RECOGNIZING) {
                VoiceConversationBar(
                    phase = voicePhase,
                    partialText = voicePartialText,
                    inputLevel = voiceInputLevel,
                    onEnd = { viewModel.stopVoiceConversation() }
                )
            }

            // Built-in command suggestions while the composer holds "/..." (nothing is sent on selection).
            val suggestions = remember(inputMessage) { SlashCommands.suggestions(inputMessage) }
            var highlightedSuggestion by remember(suggestions) { mutableIntStateOf(0) }
            val insertCommand: (SlashCommand) -> Unit = { command ->
                val text = SlashCommands.insert(command)
                inputField = TextFieldValue(text, selection = TextRange(text.length))
            }
            if (suggestions.isNotEmpty()) {
                SlashCommandSuggestions(
                    commands = suggestions,
                    highlightedIndex = highlightedSuggestion,
                    onSelect = insertCommand
                )
            }

            // Thesis mode: messages and images go to this thesis (until "Exit").
            thesisChatProject?.let { project ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("thesis_chat_banner")) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.School, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.thesis_chat_banner, project.title),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                val target = thesisState.chatSectionId
                                viewModel.thesis.openProject(project.id)
                                target?.let { viewModel.thesis.selectSection(it) }
                            }, modifier = Modifier.testTag("thesis_open_workspace")) { Text(stringResource(R.string.thesis_workspace)) }
                            TextButton(onClick = { viewModel.exitThesisChat() }, modifier = Modifier.testTag("thesis_exit_chat")) { Text(stringResource(R.string.thesis_exit)) }
                        }
                        // Target section: content whose message names no destination is proposed there.
                        val targetTitle = thesisState.chatSectionId?.let { id ->
                            thesisState.snapshot?.let { snap ->
                                snap.section(id)?.let { s -> com.example.thesis.core.ThesisStructure.displayTitle(s, com.example.thesis.core.ThesisStructure.numbering(snap), snap.meta.language) }
                            }
                        }
                        if (targetTitle != null) {
                            Text(stringResource(R.string.thesis_chat_target, targetTitle), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("thesis_chat_target"))
                        }
                        Text(stringResource(R.string.thesis_chat_hint), style = MaterialTheme.typography.bodySmall)
                        thesisState.busy?.let { com.example.thesis.ui.BusyBar(viewModel.thesis, it) }
                    }
                }
            }

            // Offline Formatting Agent: suggestion after a long paste (nothing runs until tapped).
            if (offlineState.suggestion != null || offlineState.phase == OfflinePhase.ANALYZING) {
                OfflineFormattingSuggestion(
                    analyzing = offlineState.phase == OfflinePhase.ANALYZING,
                    onFormat = { viewModel.offlineFormatting.formatOffline(selectedTemplate?.id) },
                    onDismiss = { viewModel.offlineFormatting.dismissSuggestion() },
                    onCancel = { viewModel.offlineFormatting.cancel() }
                )
            }

            // Bottom Input Dock
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Paperclip (Attachment Options) button
                    IconButton(
                        onClick = { showAttachmentMenu = true },
                        modifier = Modifier.testTag("chat_attach_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = stringResource(R.string.chat_attachment_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Text Field
                    TextField(
                        value = inputField,
                        onValueChange = { newValue ->
                            // Long pastes offer offline formatting (only the composer text is read,
                            // never the clipboard). Voice input is not interrupted.
                            viewModel.offlineFormatting.onComposerTextChanged(
                                before = inputField.text,
                                after = newValue.text,
                                inputEnabled = !voiceActive && thesisChatProject == null
                            )
                            inputField = newValue
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.chat_placeholder),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            // Hardware keyboard: arrows move in the command list, Tab/Enter insert.
                            .onPreviewKeyEvent { event ->
                                if (suggestions.isEmpty() || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        highlightedSuggestion = (highlightedSuggestion + 1) % suggestions.size
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        highlightedSuggestion = (highlightedSuggestion - 1 + suggestions.size) % suggestions.size
                                        true
                                    }
                                    Key.Tab, Key.Enter, Key.NumPadEnter -> {
                                        insertCommand(suggestions[highlightedSuggestion.coerceIn(0, suggestions.lastIndex)])
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .testTag("chat_input_field"),
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    if (isGenerating) {
                        // Stop Button: cancels the running AI operation (request, stream, document).
                        val stopLabel = stringResource(R.string.chat_stop_generation)
                        IconButton(
                            onClick = { viewModel.cancelGeneration() },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                                .testTag("chat_stop_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = stopLabel,
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else if (inputMessage.isNotBlank()) {
                        // Send Button
                        IconButton(
                            onClick = {
                                // Commands are routed by the ViewModel; the text is kept when a
                                // command still needs content or another request is running.
                                when (viewModel.sendMessage(inputMessage)) {
                                    SendResult.SENT, SendResult.CLEAR_DIALOG -> inputField = TextFieldValue("")
                                    SendResult.NEEDS_INPUT, SendResult.BUSY -> Unit
                                }
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("chat_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = stringResource(R.string.chat_send),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        // Microphone Voice Button
                        IconButton(
                            onClick = {
                                if (voiceActive) viewModel.stopVoiceConversation() else startVoice()
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("chat_voice_button")
                        ) {
                            Icon(
                                imageVector = if (voiceActive) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = stringResource(if (voiceActive) R.string.voice_end else R.string.chat_mic),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Attachment Options Bottom Sheet (Attach File, Select Image, Scan Document)
    if (showAttachmentMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentMenu = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    text = stringResource(R.string.chat_attachment_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(14.dp))

                // 1. Attach File
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAttachmentMenu = false
                            showTemplatePicker = true
                        }
                        .testTag("chat_action_attach_file"),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = stringResource(R.string.chat_attach_file),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Select Image
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAttachmentMenu = false
                            imagePickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        .testTag("chat_action_select_image"),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = stringResource(R.string.chat_select_image),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3. Scan Document
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAttachmentMenu = false
                            if (activity != null) {
                                DocumentScannerHelper.startDocumentScan(
                                    activity = activity,
                                    onIntentSenderReady = { request ->
                                        scannerLauncher.launch(request)
                                    },
                                    onErrorOrUnavailable = {
                                        // Fallback to gallery image picker
                                        imagePickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                )
                            } else {
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        }
                        .testTag("chat_action_scan_document"),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = null,
                            tint = Color(0xFF107C41),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = stringResource(R.string.chat_scan_document),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Scanned Document Review & Editing Dialog
    val activeReviewState = scannedReviewState
    if (activeReviewState != null) {
        OcrReviewDialog(
            reviewState = activeReviewState,
            templates = allTemplates,
            onUpdateTitle = { viewModel.updateScannedDocumentTitle(it) },
            onUpdatePageText = { idx, txt -> viewModel.updateScannedPageText(idx, txt) },
            onSelectPageIndex = { viewModel.setActiveScanPageIndex(it) },
            onRemovePage = { viewModel.removeScannedPage(it) },
            onAddPage = {
                imagePickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onSelectTemplate = { viewModel.setSelectedScanTemplate(it) },
            onGenerateDocx = { viewModel.generateDocxFromScannedDocument() },
            onDismiss = { viewModel.dismissScanReview() },
            ocrLanguage = ocrLanguage,
            onChangeOcrLanguage = { viewModel.changeOcrLanguage(it) },
            aiProviderName = defaultProvider,
            onEnhanceWithAi = { viewModel.enhanceScannedTextWithAi() }
        )
    }

    // Scanning & OCR Progress Dialog
    if (isScanning) {
        Dialog(onDismissRequest = { /* Don't dismiss during active scan */ }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(44.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = scanProgress ?: stringResource(R.string.recognizing_text),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // Modal Bottom Sheet to Pick Template
    if (showTemplatePicker) {
        ModalBottomSheet(
            onDismissRequest = { showTemplatePicker = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_select_template),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Option: No template (Default Style)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.selectTemplateForChat(null)
                            showTemplatePicker = false
                        },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedTemplate == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.chat_no_template),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                allTemplates.forEach { tmpl ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                viewModel.selectTemplateForChat(tmpl)
                                showTemplatePicker = false
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedTemplate?.id == tmpl.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when (tmpl.fileType) {
                                            "DOCX" -> Color(0xFF1D52D8)
                                            "PPTX" -> Color(0xFFE05D26)
                                            "XLSX" -> Color(0xFF107C41)
                                            else -> Color(0xFFD83B01)
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when (tmpl.fileType) {
                                        "DOCX" -> "W"
                                        "PPTX" -> "P"
                                        "XLSX" -> "X"
                                        "PDF" -> "PDF"
                                        else -> "D"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tmpl.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${tmpl.fileType} • ${tmpl.fileSize / 1024} KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Rename Conversation Dialog
    conversationToRename?.let { conv ->
        AlertDialog(
            onDismissRequest = { conversationToRename = null },
            title = { Text(stringResource(R.string.conversation_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.conversation_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newTitle = renameInput.trim()
                        if (newTitle.isNotBlank()) {
                            viewModel.renameConversation(conv.id, newTitle)
                        }
                        conversationToRename = null
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToRename = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Thesis Builder workspace and change review
    com.example.thesis.ui.ThesisWorkspaceDialog(viewModel)
    if (!thesisState.workspaceOpen) thesisState.review?.let { com.example.thesis.ui.ThesisReviewDialog(viewModel.thesis, it) }

    // Offline formatting preview, confirmation and Save to Library
    if (offlineState.dialogVisible) {
        OfflineFormattingDialog(
            state = offlineState,
            onSelectFormat = { viewModel.offlineFormatting.selectFormat(it) },
            onSelectTemplate = { viewModel.offlineFormatting.selectTemplate(it) },
            onApply = { viewModel.offlineFormatting.applyFormatting() },
            onSave = { viewModel.offlineFormatting.saveToLibrary() },
            onCancel = { viewModel.offlineFormatting.cancel() }
        )
    }

    // Delete Conversation Confirmation Dialog
    if (clearDialogVisible) {
        ClearConversationsDialog(
            onClearCurrent = { viewModel.clearCurrentConversation() },
            onDeleteAll = { viewModel.deleteAllConversations() },
            onDismiss = { viewModel.dismissClearDialog() }
        )
    }

    conversationToDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            title = { Text(stringResource(R.string.conversation_delete_title)) },
            text = { Text(stringResource(R.string.conversation_delete_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteConversation(conv.id)
                        conversationToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.conversation_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun ConversationDrawerItem(
    conversation: ConversationEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val formattedDate = remember(conversation.updatedAt) {
        val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        sdf.format(Date(conversation.updatedAt))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .testTag("conversation_item_${conversation.id}"),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (conversation.lastMessagePreview.isNotBlank()) {
                    Text(
                        text = conversation.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.library_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conversation_rename)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conversation_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubbleItem(
    message: ChatMessageEntity,
    attachedDoc: DocumentEntity?,
    audioState: MessageAudioState,
    onToggleAudio: () -> Unit,
    onStopAudio: () -> Unit,
    onCopyText: () -> Unit,
    onOpenDoc: () -> Unit,
    onShareDoc: () -> Unit,
    onExportPdf: () -> Unit,
    onFollowUpAi: (String) -> Unit = {}
) {
    val isUser = message.isUser

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            // Assistant Bot Avatar
            SmartBubbleLogo(size = 34.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            // Shrinks on narrow screens instead of pushing past the edge.
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Bubble Content
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )

                    // Copy button for assistant responses
                    if (!isUser) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = onCopyText,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.chat_copy),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Audio Waveform Player
            if (!isUser) {
                Spacer(modifier = Modifier.height(6.dp))
                WaveformAudioPlayer(
                    state = audioState,
                    onToggle = onToggleAudio,
                    onStop = onStopAudio
                )
            }

            // Attached Document Card (if document generated)
            if (attachedDoc != null) {
                Spacer(modifier = Modifier.height(8.dp))
                DocumentResultCard(
                    doc = attachedDoc,
                    onOpen = onOpenDoc,
                    onShare = onShareDoc,
                    onExportPdf = onExportPdf,
                    onFollowUpAi = onFollowUpAi
                )
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            // User Avatar
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Read-aloud state of one chat message. */
enum class MessageAudioState { IDLE, SPEAKING, PAUSED }

/**
 * Read-aloud control of an assistant message: tap to play, tap again to pause / resume, and a
 * stop button while it is active. The bars only animate while speech is actually playing.
 */
@Composable
fun WaveformAudioPlayer(
    state: MessageAudioState,
    onToggle: () -> Unit,
    onStop: () -> Unit
) {
    val isPlaying = state == MessageAudioState.SPEAKING
    val isActive = state != MessageAudioState.IDLE
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(36.dp).testTag("message_audio_toggle")) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = stringResource(
                        when (state) {
                            MessageAudioState.SPEAKING -> R.string.voice_pause
                            MessageAudioState.PAUSED -> R.string.voice_resume
                            MessageAudioState.IDLE -> R.string.chat_listen
                        }
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Waveform bars (animated only while speaking)
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val heights = listOf(8, 14, 18, 10, 22, 16, 24, 12, 19, 15, 8, 16, 12, 20, 14)
                heights.forEachIndexed { index, baseHeight ->
                    val animatedHeight by animateFloatAsState(
                        targetValue = if (isPlaying) (baseHeight * (0.6f + (index % 3) * 0.4f)) else baseHeight.toFloat(),
                        animationSpec = infiniteRepeatable(
                            animation = tween(400 + index * 30, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "wave_$index"
                    )
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(if (isPlaying) animatedHeight.dp else baseHeight.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                    )
                }
            }

            if (isActive) {
                IconButton(onClick = onStop, modifier = Modifier.size(36.dp).testTag("message_audio_stop")) {
                    Icon(
                        imageVector = Icons.Default.StopCircle,
                        contentDescription = stringResource(R.string.voice_stop_reading),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(10.dp))
            }
        }
    }
}

/** Status of the two-way voice conversation, with the live transcript and an End button. */
@Composable
fun VoiceConversationBar(
    phase: VoicePhase,
    partialText: String,
    inputLevel: Float,
    onEnd: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("voice_conversation_bar"),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (phase) {
                VoicePhase.LISTENING -> {
                    // Real microphone level from the recognizer
                    val level by animateFloatAsState(targetValue = inputLevel, label = "mic_level")
                    Box(
                        modifier = Modifier
                            .size((12 + 12 * level).dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                VoicePhase.RECOGNIZING, VoicePhase.THINKING ->
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                VoicePhase.SPEAKING -> Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                VoicePhase.PAUSED -> Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                VoicePhase.IDLE -> Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when (phase) {
                            VoicePhase.LISTENING -> R.string.chat_listening
                            VoicePhase.RECOGNIZING -> R.string.voice_recognizing
                            VoicePhase.THINKING -> R.string.voice_thinking
                            VoicePhase.SPEAKING -> R.string.voice_speaking
                            VoicePhase.PAUSED -> R.string.voice_paused
                            VoicePhase.IDLE -> R.string.voice_ready
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (phase == VoicePhase.LISTENING && partialText.isNotBlank()) {
                    Text(
                        text = partialText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            TextButton(onClick = onEnd, modifier = Modifier.testTag("voice_end_button")) {
                Text(stringResource(R.string.voice_end))
            }
        }
    }
}

@Composable
fun DocumentResultCard(
    doc: DocumentEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onExportPdf: () -> Unit,
    onFollowUpAi: (String) -> Unit = {}
) {
    val isScanned = doc.summary.contains("Scanned Document") || doc.title.contains("Document_")

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // File Type Badge
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when (doc.fileType) {
                                "DOCX" -> Color(0xFF1D52D8)
                                "PPTX" -> Color(0xFFE05D26)
                                "XLSX" -> Color(0xFF107C41)
                                else -> Color(0xFFD83B01)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (doc.fileType) {
                            "DOCX" -> "W"
                            "PPTX" -> "P"
                            "XLSX" -> "X"
                            else -> "PDF"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = doc.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${doc.fileType} • ${doc.fileSize / 1024} KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!doc.templateName.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.template_fidelity_used, doc.templateName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Scanned Pages Badge
                    if (isScanned && doc.pageOrSlideCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DocumentScanner,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.scan_pages_badge, doc.pageOrSlideCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons: content-sized; they wrap to a new line when a translation is long.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onOpen,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.chat_open_file), fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onShare,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.chat_share_file), fontSize = 12.sp)
                }

                if (doc.fileType != "PDF") {
                    FilledTonalButton(
                        onClick = onExportPdf,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = stringResource(R.string.chat_export_pdf), fontSize = 12.sp)
                    }
                }
            }

            // Follow-up AI edit prompts for DOCX and Scanned Documents
            if (doc.fileType == "DOCX" || isScanned) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.chat_ai_refinements),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.height(4.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AssistChip(
                        onClick = { onFollowUpAi("SPELLING") },
                        label = { Text(stringResource(R.string.scan_ai_spelling), fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Spellcheck, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                    AssistChip(
                        onClick = { onFollowUpAi("FORMAT") },
                        label = { Text(stringResource(R.string.scan_ai_format), fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.FormatAlignLeft, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                    AssistChip(
                        onClick = { onFollowUpAi("TRANSLATE") },
                        label = { Text(stringResource(R.string.scan_ai_translate), fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                    AssistChip(
                        onClick = { onFollowUpAi("SUMMARIZE") },
                        label = { Text(stringResource(R.string.scan_ai_summarize), fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Summarize, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
            }
        }
    }
}

/**
 * Shown while an operation runs in this conversation: the real stage reported by the
 * orchestrator and, while a reply is being streamed, the text received so far.
 */
@Composable
fun GeneratingIndicatorBubble(stage: AiProgressStage?, partialReply: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        SmartBubbleLogo(size = 34.dp)
        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (partialReply.isNotEmpty()) {
                    Text(
                        text = partialReply,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(progressStageText(stage)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("chat_progress_stage")
                    )
                }
            }
        }
    }
}

/** Localized text for a real orchestrator stage (null = the operation has just been started). */
fun progressStageText(stage: AiProgressStage?): Int = when (stage) {
    null, AiProgressStage.CONNECTING -> R.string.progress_connecting
    AiProgressStage.PROCESSING -> R.string.progress_processing
    AiProgressStage.GENERATING -> R.string.progress_generating
    AiProgressStage.VALIDATING -> R.string.progress_validating
    AiProgressStage.CREATING_DOCUMENT -> R.string.progress_creating_document
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
