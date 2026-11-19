package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocalLibrary
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

/**
 * Main navigation (Stitch design): Thesis, My Templates, Agent (centre), Library, Settings.
 * Five equal columns, so Agent is always exactly in the middle (also in right-to-left layouts,
 * where the order is mirrored). The selected destination shows a tinted 48 x 28 dp pill behind
 * its icon and a bold primary label; Agent always has its filled pill (the app's main action).
 * Destinations, back stack and state restoration are unchanged: only [onTabSelected] is called.
 */
@Composable
fun SmartBubbleBottomNavBar(
    currentTab: AppNavTab,
    onTabSelected: (AppNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(16.dp, ambientColor = Color(0x0A0F172A), spotColor = Color(0x140F172A)),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = 72.dp)
                // Every item as tall as the tallest (a label on two lines): equal touch targets.
                .height(IntrinsicSize.Min)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem(Modifier.weight(1f), stringResource(R.string.nav_thesis), Icons.Filled.School, Icons.Outlined.School,
                currentTab == AppNavTab.THESIS, { onTabSelected(AppNavTab.THESIS) }, "nav_thesis")
            BottomNavItem(Modifier.weight(1f), stringResource(R.string.nav_templates), Icons.Filled.Description, Icons.Outlined.Description,
                currentTab == AppNavTab.TEMPLATES, { onTabSelected(AppNavTab.TEMPLATES) }, "nav_templates")
            BottomNavItem(Modifier.weight(1f), stringResource(R.string.nav_chat), Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome,
                currentTab == AppNavTab.CHAT, { onTabSelected(AppNavTab.CHAT) }, "nav_chat", center = true)
            BottomNavItem(Modifier.weight(1f), stringResource(R.string.nav_library), Icons.Filled.LocalLibrary, Icons.Outlined.LocalLibrary,
                currentTab == AppNavTab.LIBRARY, { onTabSelected(AppNavTab.LIBRARY) }, "nav_library")
            BottomNavItem(Modifier.weight(1f), stringResource(R.string.nav_settings), Icons.Filled.Settings, Icons.Outlined.Settings,
                currentTab == AppNavTab.SETTINGS, { onTabSelected(AppNavTab.SETTINGS) }, "nav_settings")
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
    testTag: String,
    center: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val activeColor = colors.primary
    val inactiveColor = colors.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .selectable(
                selected = isSelected,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 28.dp),
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (center) {
            // Agent: always a filled pill (56 x 32 dp) with a soft primary shadow.
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 32.dp)
                    .shadow(if (isSelected) 6.dp else 3.dp, RoundedCornerShape(50), spotColor = activeColor.copy(alpha = 0.3f))
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) activeColor else colors.primary.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(selectedIcon, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
            }
        } else {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) colors.primaryContainer else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                // The label below is read by accessibility services; no duplicate description.
                Icon(
                    imageVector = if (isSelected) selectedIcon else unselectedIcon,
                    contentDescription = null,
                    tint = if (isSelected) activeColor else inactiveColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            letterSpacing = 0.2.sp,
            fontWeight = if (isSelected || center) FontWeight.Bold else FontWeight.SemiBold,
            color = if (isSelected || center) activeColor else inactiveColor,
            style = MaterialTheme.typography.labelSmall,
            // Up to two lines (e.g. "My Templates" on a narrow phone) instead of cutting the label.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
