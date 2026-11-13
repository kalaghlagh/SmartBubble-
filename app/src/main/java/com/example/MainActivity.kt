package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.example.ui.AppNavTab
import com.example.ui.SmartBubbleViewModel
import com.example.ui.components.SmartBubbleBottomNavBar
import com.example.ui.screens.*
import com.example.ui.theme.SmartBubbleTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SmartBubbleViewModel by viewModels()

    override fun onStop() {
        super.onStop()
        // Never keep the microphone open or keep talking once the app is no longer visible.
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before anything else: a crash from here on is kept and shown on the next launch.
        com.example.diagnostics.CrashLog.install(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appLanguage by viewModel.appLanguage.collectAsState()
            val appTheme by viewModel.appTheme.collectAsState()
            val layoutDirection = if (appLanguage == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
            val currentTab by viewModel.currentTab.collectAsState()
            val snackBarMessage by viewModel.snackBarMessage.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }

            val activity = this@MainActivity
            val localizedContext = remember(appLanguage, activity) {
                val locale = when (appLanguage) {
                    "ar" -> java.util.Locale("ar")
                    "fr" -> java.util.Locale("fr")
                    else -> java.util.Locale("en")
                }
                java.util.Locale.setDefault(locale)
                val config = android.content.res.Configuration(activity.resources.configuration)
                config.setLocale(locale)
                config.setLayoutDirection(locale)
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val baseConfigContext = activity.createConfigurationContext(config)

                object : android.content.ContextWrapper(activity), androidx.activity.result.ActivityResultRegistryOwner {
                    override fun getResources(): android.content.res.Resources = baseConfigContext.resources
                    override fun getAssets(): android.content.res.AssetManager = baseConfigContext.assets
                    override val activityResultRegistry: androidx.activity.result.ActivityResultRegistry
                        get() = activity.activityResultRegistry
                }
            }
            val localizedConfiguration = remember(appLanguage, localizedContext) {
                localizedContext.resources.configuration
            }

            val isDark = when (appTheme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            // On the Thesis Builder destination, its own screens show the messages (with their
            // own snackbar placed above their bottom actions).
            LaunchedEffect(snackBarMessage, currentTab) {
                if (currentTab == AppNavTab.THESIS) return@LaunchedEffect
                snackBarMessage?.let { msg ->
                    snackbarHostState.showSnackbar(msg)
                    viewModel.clearSnackBar()
                }
            }

            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localizedContext,
                androidx.compose.ui.platform.LocalConfiguration provides localizedConfiguration,
                LocalLayoutDirection provides layoutDirection,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                SmartBubbleTheme(darkTheme = isDark) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            SmartBubbleBottomNavBar(
                                currentTab = currentTab,
                                onTabSelected = { viewModel.setNavTab(it) }
                            )
                        },
                        snackbarHost = {
                            SnackbarHost(hostState = snackbarHostState)
                        }
                    ) { innerPadding ->
                        BackHandler(enabled = currentTab != AppNavTab.CHAT) {
                            viewModel.setNavTab(AppNavTab.CHAT)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .statusBarsPadding()
                        ) {
                            when (currentTab) {
                                AppNavTab.CHAT -> ChatScreen(viewModel = viewModel)
                                AppNavTab.THESIS -> com.example.thesis.ui.ThesisBuilderDestination(viewModel, onExit = { viewModel.setNavTab(AppNavTab.CHAT) })
                                AppNavTab.TEMPLATES -> TemplatesScreen(viewModel = viewModel)
                                AppNavTab.LIBRARY -> LibraryScreen(viewModel = viewModel)
                                AppNavTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                            }
                        }
                    }
                    CrashReportDialog()
                }
            }
        }
    }

    /** Stack trace of the last crash (kept by CrashLog), shown once so it can be copied without adb. */
    @Composable
    private fun CrashReportDialog() {
        val context = androidx.compose.ui.platform.LocalContext.current
        var report by remember { mutableStateOf(com.example.diagnostics.CrashLog.read(this)) }
        val text = report ?: return
        val close = { com.example.diagnostics.CrashLog.clear(this); report = null }
        AlertDialog(
            onDismissRequest = close,
            modifier = Modifier.testTag("crash_report_dialog"),
            title = { Text(androidx.compose.ui.res.stringResource(R.string.crash_report_title)) },
            text = {
                Column {
                    Text(androidx.compose.ui.res.stringResource(R.string.crash_report_text), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).testTag("crash_report_text")
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("SmartBubble crash report", text))
                    android.widget.Toast.makeText(context, context.getString(R.string.thesis_copied), android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(androidx.compose.ui.res.stringResource(R.string.crash_report_copy)) }
            },
            dismissButton = { TextButton(onClick = close) { Text(androidx.compose.ui.res.stringResource(R.string.action_close)) } }
        )
    }
}
