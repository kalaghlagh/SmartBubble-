package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AppNavTab
import com.example.ui.SmartBubbleViewModel
import com.example.ui.components.SmartBubbleBottomNavBar
import com.example.ui.screens.*
import com.example.ui.theme.BubbleCyan
import com.example.ui.theme.BubblePurple
import com.example.ui.theme.SmartBubbleTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SmartBubbleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle shared social post text if launched via ACTION_SEND
        handleIncomingIntent(intent)

        setContent {
            val appLanguage by viewModel.appLanguage.collectAsState()
            val layoutDirection = if (appLanguage == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
            val currentTab by viewModel.currentTab.collectAsState()
            val snackBarMessage by viewModel.snackBarMessage.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(snackBarMessage) {
                snackBarMessage?.let { msg ->
                    snackbarHostState.showSnackbar(msg)
                    viewModel.clearSnackBar()
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                SmartBubbleTheme(darkTheme = true) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            SmartBubbleTopBar(
                                currentTab = currentTab,
                                onProfileClick = { viewModel.setNavTab(AppNavTab.SETTINGS) }
                            )
                        },
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
                        BackHandler(enabled = currentTab != AppNavTab.HOME) {
                            viewModel.setNavTab(AppNavTab.HOME)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (currentTab) {
                                AppNavTab.HOME -> HomeScreen(viewModel = viewModel)
                                AppNavTab.ASSISTANT -> AssistantScreen(viewModel = viewModel)
                                AppNavTab.CREATE -> CreateStudioScreen(viewModel = viewModel)
                                AppNavTab.LIBRARY -> LibraryScreen(viewModel = viewModel)
                                AppNavTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.handleSharedSocialText(sharedText)
            }
        }
    }
}

@Composable
fun SmartBubbleTopBar(
    currentTab: AppNavTab,
    onProfileClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(BubbleCyan, BubblePurple)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BubbleChart,
                        contentDescription = "Smart Bubble",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Smart Bubble",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text(
                        text = when (currentTab) {
                            AppNavTab.HOME -> "Executive Workspace"
                            AppNavTab.ASSISTANT -> "AI Assistant Studio"
                            AppNavTab.CREATE -> "Document Studio"
                            AppNavTab.LIBRARY -> "Enterprise Vault"
                            AppNavTab.SETTINGS -> "Preferences & Config"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { /* Notifications */ }) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Notifications",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onProfileClick,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Profile",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
