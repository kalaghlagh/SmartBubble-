package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ai.providers.AiProviderType
import com.example.ai.providers.ProviderField
import com.example.ai.providers.ProviderInput
import com.example.ai.providers.ProviderStatus
import com.example.ai.providers.ProviderValidator
import com.example.data.model.ProviderEntity
import com.example.ui.aiErrorStringRes
import com.example.ui.SmartBubbleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SmartBubbleViewModel,
    modifier: Modifier = Modifier
) {
    val providers by viewModel.allProviders.collectAsState()
    val providersWithKeys by viewModel.providersWithKeys.collectAsState()
    val testingProviderIds by viewModel.testingProviderIds.collectAsState()
    var editingProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    val appLanguage by viewModel.appLanguage.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()

    var showAddProviderDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showVoiceLanguageDialog by remember { mutableStateOf(false) }
    val voiceLanguage by viewModel.voiceLanguage.collectAsState()
    val voiceLanguageOptions = listOf(
        "auto" to stringResource(R.string.voice_language_auto),
        "en" to "English",
        "ar" to "العربية",
        "fr" to "Français"
    )
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Header matching mockup
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.setNavTab(com.example.ui.AppNavTab.CHAT) },
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = { /* Settings action */ },
                    modifier = Modifier.testTag("settings_gear_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Gear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section: AI Providers Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Card Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Psychology,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.settings_ai_providers),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_ai_providers_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Provider Cards
                        if (providers.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_no_providers),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        } else if (providers.none { it.isDefault }) {
                            Text(
                                text = stringResource(R.string.settings_choose_default_provider),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                        providers.forEach { provider ->
                            ProviderItemRow(
                                provider = provider,
                                hasKey = provider.id in providersWithKeys,
                                isTesting = provider.id in testingProviderIds,
                                onTest = { viewModel.testProviderConnection(provider) },
                                onSetDefault = { viewModel.setDefaultProvider(provider) },
                                onEdit = { editingProvider = provider },
                                onDelete = { viewModel.deleteProvider(provider) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // "+ Add Provider" button matching mockup
                        OutlinedButton(
                            onClick = { showAddProviderDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("add_provider_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_add_provider),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Section: Preferences Card (Language, Theme, About)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        // Language Row
                        SettingsNavRow(
                            icon = Icons.Outlined.Language,
                            title = stringResource(R.string.settings_language),
                            value = when (appLanguage) {
                                "ar" -> "العربية"
                                "fr" -> "Français"
                                else -> "English"
                            },
                            onClick = { showLanguageDialog = true },
                            testTag = "settings_language_row"
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Voice conversation language Row
                        SettingsNavRow(
                            icon = Icons.Outlined.Mic,
                            title = stringResource(R.string.settings_voice_language),
                            value = voiceLanguageOptions.firstOrNull { it.first == voiceLanguage }?.second
                                ?: stringResource(R.string.voice_language_auto),
                            onClick = { showVoiceLanguageDialog = true },
                            testTag = "settings_voice_language_row"
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Theme Row
                        SettingsNavRow(
                            icon = Icons.Outlined.Palette,
                            title = stringResource(R.string.settings_theme),
                            value = when (appTheme) {
                                "light" -> stringResource(R.string.theme_light)
                                "dark" -> stringResource(R.string.theme_dark)
                                else -> stringResource(R.string.theme_system)
                            },
                            onClick = { showThemeDialog = true },
                            testTag = "settings_theme_row"
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // About Row
                        SettingsNavRow(
                            icon = Icons.Outlined.Info,
                            title = stringResource(R.string.settings_about),
                            value = "",
                            onClick = { showAboutDialog = true },
                            testTag = "settings_about_row"
                        )
                    }
                }
            }
        }
    }

    // Add Provider Dialog
    if (showAddProviderDialog) {
        ProviderDialog(
            existing = null,
            hasStoredKey = false,
            onDismiss = { showAddProviderDialog = false },
            onSave = { input ->
                viewModel.addProvider(input)
                showAddProviderDialog = false
            }
        )
    }

    // Edit Provider Dialog
    editingProvider?.let { provider ->
        ProviderDialog(
            existing = provider,
            hasStoredKey = provider.id in providersWithKeys,
            onDismiss = { editingProvider = null },
            onSave = { input ->
                viewModel.updateProvider(provider.id, input)
                editingProvider = null
            }
        )
    }

    // Language Dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    listOf(
                        "en" to "English",
                        "ar" to "العربية (Arabic RTL)",
                        "fr" to "Français (French)"
                    ).forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAppLanguage(code)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = appLanguage == code,
                                onClick = {
                                    viewModel.setAppLanguage(code)
                                    showLanguageDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    // Voice Language Dialog
    if (showVoiceLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_voice_language)) },
            text = {
                Column {
                    voiceLanguageOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setVoiceLanguage(option.first)
                                    showVoiceLanguageDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = voiceLanguage == option.first,
                                onClick = {
                                    viewModel.setVoiceLanguage(option.first)
                                    showVoiceLanguageDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = option.second, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoiceLanguageDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    // Theme Dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.settings_theme)) },
            text = {
                Column {
                    listOf(
                        "light" to stringResource(R.string.theme_light),
                        "dark" to stringResource(R.string.theme_dark),
                        "system" to stringResource(R.string.theme_system)
                    ).forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAppTheme(code)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = appTheme == code,
                                onClick = {
                                    viewModel.setAppTheme(code)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BubbleChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "SmartBubble v1.0")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.about_app_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.security_notice_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.security_notice_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showAboutDialog = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }
}

@Composable
fun ProviderItemRow(
    provider: ProviderEntity,
    hasKey: Boolean,
    isTesting: Boolean,
    onTest: () -> Unit,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val type = AiProviderType.fromStorageKey(provider.providerType)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Provider Logo Icon
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                when (type) {
                                    AiProviderType.OPENAI -> Color(0xFF10A37F)
                                    AiProviderType.GEMINI -> Color(0xFF1D52D8)
                                    AiProviderType.ANTHROPIC -> Color(0xFFD97706)
                                    AiProviderType.OPENAI_COMPATIBLE -> MaterialTheme.colorScheme.primary
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${providerTypeLabel(type)} • ${provider.modelName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val status = providerStatusText(provider, hasKey, isTesting)
                        Text(
                            text = status.first,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (provider.isDefault) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF10A37F).copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_default_badge),
                            color = Color(0xFF10A37F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons: [Test Connection] [Set as Default] [Edit] [Delete]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onTest,
                    enabled = !isTesting && hasKey,
                    modifier = Modifier.weight(1.2f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = stringResource(R.string.settings_test_connection),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (!provider.isDefault) {
                    OutlinedButton(
                        onClick = onSetDefault,
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_set_default),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(36.dp).testTag("edit_provider_${provider.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.provider_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.provider_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun providerTypeLabel(type: AiProviderType): String = when (type) {
    AiProviderType.OPENAI -> "OpenAI"
    AiProviderType.ANTHROPIC -> "Anthropic Claude"
    AiProviderType.GEMINI -> "Google Gemini"
    AiProviderType.OPENAI_COMPATIBLE -> stringResource(R.string.provider_type_compatible)
}

/** Localized status text and whether it is an error. Only real test results are shown. */
@Composable
private fun providerStatusText(provider: ProviderEntity, hasKey: Boolean, isTesting: Boolean): Pair<String, Boolean> {
    if (isTesting) return stringResource(R.string.provider_status_testing) to false
    if (!hasKey) return stringResource(R.string.provider_status_needs_key) to true
    ProviderStatus.latencyOf(provider.status)?.let {
        return stringResource(R.string.provider_status_connected, it) to false
    }
    ProviderStatus.errorOf(provider.status)?.let {
        return stringResource(R.string.provider_status_error, stringResource(aiErrorStringRes(it))) to true
    }
    return stringResource(R.string.provider_status_not_tested) to false
}

@Composable
fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Add / edit provider dialog. The API key is masked by default (explicit show/hide toggle) and is
 * never pre-filled: when editing, an empty key field keeps the key that is already stored.
 */
@Composable
fun ProviderDialog(
    existing: ProviderEntity?,
    hasStoredKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderInput) -> Unit
) {
    val initialType = existing?.let { AiProviderType.fromStorageKey(it.providerType) } ?: AiProviderType.OPENAI
    var type by remember { mutableStateOf(initialType) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.baseUrl?.ifBlank { initialType.defaultBaseUrl } ?: initialType.defaultBaseUrl) }
    var model by remember { mutableStateOf(existing?.modelName ?: "") }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(emptySet<ProviderField>()) }
    val requireKey = existing == null || !hasStoredKey

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (existing == null) R.string.provider_add_dialog_title else R.string.provider_edit_dialog_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.provider_type_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AiProviderType.values().toList().chunked(2).forEach { rowTypes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowTypes.forEach { option ->
                            FilterChip(
                                selected = type == option,
                                onClick = {
                                    // Replace the URL only if it is still the previous type's default.
                                    if (url.isBlank() || url == type.defaultBaseUrl) url = option.defaultBaseUrl
                                    type = option
                                },
                                label = { Text(providerTypeLabel(option), fontSize = 12.sp) },
                                modifier = Modifier.testTag("provider_type_${option.name}")
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.provider_name_label)) },
                    isError = ProviderField.NAME in invalid,
                    supportingText = if (ProviderField.NAME in invalid) {
                        { Text(stringResource(R.string.provider_error_name_required)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.provider_url_label)) },
                    isError = ProviderField.BASE_URL in invalid,
                    supportingText = if (ProviderField.BASE_URL in invalid) {
                        { Text(stringResource(R.string.provider_error_url_invalid)) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.provider_model_label)) },
                    placeholder = {
                        Text(stringResource(R.string.provider_model_hint, modelExample(type)))
                    },
                    isError = ProviderField.MODEL in invalid,
                    supportingText = if (ProviderField.MODEL in invalid) {
                        { Text(stringResource(R.string.provider_error_model_required)) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )

                val keySupport: String? = when {
                    ProviderField.API_KEY in invalid -> stringResource(R.string.provider_error_key_required)
                    !requireKey -> stringResource(R.string.provider_key_saved)
                    else -> null
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.provider_key_label)) },
                    placeholder = if (!requireKey) {
                        { Text(stringResource(R.string.provider_key_keep_hint)) }
                    } else null,
                    isError = ProviderField.API_KEY in invalid,
                    supportingText = if (keySupport != null) {
                        { Text(keySupport) }
                    } else null,
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(if (showKey) R.string.provider_key_hide else R.string.provider_key_show)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("provider_api_key_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val input = ProviderInput(
                        name = name.trim(),
                        type = type,
                        baseUrl = url.trim(),
                        model = model.trim(),
                        apiKey = key.trim()
                    )
                    invalid = ProviderValidator.validate(input, requireKey)
                    if (invalid.isEmpty()) {
                        onSave(input)
                        key = ""
                    }
                }
            ) {
                Text(stringResource(if (existing == null) R.string.action_add else R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Example model ids shown as a hint only (never pre-filled). */
private fun modelExample(type: AiProviderType): String = when (type) {
    AiProviderType.OPENAI -> "gpt-4.1"
    AiProviderType.ANTHROPIC -> "claude-sonnet-5-5"
    AiProviderType.GEMINI -> "gemini-2.5-flash"
    AiProviderType.OPENAI_COMPATIBLE -> "deepseek-chat"
}
