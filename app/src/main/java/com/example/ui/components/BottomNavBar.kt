package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.AppNavTab
import com.example.ui.theme.BubbleCyan
import com.example.ui.theme.BubblePurple

@Composable
fun SmartBubbleBottomNavBar(
    currentTab: AppNavTab,
    onTabSelected: (AppNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(72.dp)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Home
                NavBarItem(
                    label = stringResource(R.string.nav_home),
                    icon = Icons.Default.GridView,
                    isSelected = currentTab == AppNavTab.HOME,
                    onClick = { onTabSelected(AppNavTab.HOME) },
                    testTag = "nav_home"
                )

                // Assistant
                NavBarItem(
                    label = stringResource(R.string.nav_assistant),
                    icon = Icons.Default.AutoAwesome,
                    isSelected = currentTab == AppNavTab.ASSISTANT,
                    onClick = { onTabSelected(AppNavTab.ASSISTANT) },
                    testTag = "nav_assistant"
                )

                // Central Floating Create FAB
                Box(
                    modifier = Modifier
                        .offset(y = (-16).dp)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(BubbleCyan, BubblePurple)
                            )
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 28.dp)
                        ) {
                            onTabSelected(AppNavTab.CREATE)
                        }
                        .testTag("nav_create_fab"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create Document",
                        tint = Color.Black,
                        modifier = Modifier.size(30.dp)
                    )
                }

                // Library
                NavBarItem(
                    label = stringResource(R.string.nav_library),
                    icon = Icons.Default.FolderOpen,
                    isSelected = currentTab == AppNavTab.LIBRARY,
                    onClick = { onTabSelected(AppNavTab.LIBRARY) },
                    testTag = "nav_library"
                )

                // Settings
                NavBarItem(
                    label = stringResource(R.string.nav_settings),
                    icon = Icons.Default.Tune,
                    isSelected = currentTab == AppNavTab.SETTINGS,
                    onClick = { onTabSelected(AppNavTab.SETTINGS) },
                    testTag = "nav_settings"
                )
            }
        }
    }
}

@Composable
private fun NavBarItem(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isSelected) activeColor.copy(alpha = 0.18f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) activeColor else inactiveColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isSelected) activeColor else inactiveColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
