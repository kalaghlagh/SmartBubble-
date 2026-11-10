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

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
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
