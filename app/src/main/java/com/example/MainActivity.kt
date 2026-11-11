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
import androidx.compose.ui.platform.LocalLayoutDirection
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

            LaunchedEffect(snackBarMessage) {
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
                                AppNavTab.TEMPLATES -> TemplatesScreen(viewModel = viewModel)
                                AppNavTab.LIBRARY -> LibraryScreen(viewModel = viewModel)
                                AppNavTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}
