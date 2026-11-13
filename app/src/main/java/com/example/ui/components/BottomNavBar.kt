package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.AppNavTab

@Composable
fun SmartBubbleBottomNavBar(
    currentTab: AppNavTab,
    onTabSelected: (AppNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp),
            // Equal columns: every label gets the same width in every language.
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chat
            BottomNavItem(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.nav_chat),
                selectedIcon = Icons.Filled.ChatBubble,
                unselectedIcon = Icons.Outlined.ChatBubbleOutline,
                isSelected = currentTab == AppNavTab.CHAT,
                onClick = { onTabSelected(AppNavTab.CHAT) },
                testTag = "nav_chat"
            )

            // Thesis Builder (its own destination)
            BottomNavItem(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.nav_thesis),
                selectedIcon = Icons.Filled.School,
                unselectedIcon = Icons.Outlined.School,
                isSelected = currentTab == AppNavTab.THESIS,
                onClick = { onTabSelected(AppNavTab.THESIS) },
                testTag = "nav_thesis"
            )

            // My Templates
            BottomNavItem(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.nav_templates),
                selectedIcon = Icons.Filled.Description,
                unselectedIcon = Icons.Outlined.Description,
                isSelected = currentTab == AppNavTab.TEMPLATES,
                onClick = { onTabSelected(AppNavTab.TEMPLATES) },
                testTag = "nav_templates"
            )

            // Library
            BottomNavItem(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.nav_library),
                selectedIcon = Icons.Filled.Folder,
                unselectedIcon = Icons.Outlined.Folder,
                isSelected = currentTab == AppNavTab.LIBRARY,
                onClick = { onTabSelected(AppNavTab.LIBRARY) },
                testTag = "nav_library"
            )

            // Settings
            BottomNavItem(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.nav_settings),
                selectedIcon = Icons.Filled.Settings,
                unselectedIcon = Icons.Outlined.Settings,
                isSelected = currentTab == AppNavTab.SETTINGS,
                onClick = { onTabSelected(AppNavTab.SETTINGS) },
                testTag = "nav_settings"
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    modifier: Modifier,
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = isSelected,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 28.dp),
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 2.dp, vertical = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isSelected) activeColor.copy(alpha = 0.16f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSelected) selectedIcon else unselectedIcon,
                // The label below is read by accessibility services; no duplicate description.
                contentDescription = null,
                tint = if (isSelected) activeColor else inactiveColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) activeColor else inactiveColor,
            style = MaterialTheme.typography.labelSmall,
            // One line, never broken inside a word; the columns are equally wide.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
