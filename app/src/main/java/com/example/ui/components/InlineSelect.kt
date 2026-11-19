package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One choice of an [InlineSelect]. */
data class SelectOption(
    val key: String,
    val label: String,
    val supporting: String? = null,
    val icon: ImageVector? = null,
    /** Extra text at the end (e.g. "العربية (RTL)"), shown when not selected. */
    val trailing: String? = null
)

/** A titled group of options; [action] is an optional link at the end of the group header. */
data class SelectGroup(val title: String?, val options: List<SelectOption>, val action: (@Composable () -> Unit)? = null)

/**
 * Stitch dropdown: a full-width trigger (icon, selected label, "Selected" badge, chevron) that
 * opens a panel INSIDE the layout, under the trigger (not a popup), so it is never cut at a
 * screen edge, scrolls with the page, follows RTL, and works with large text. Opening the panel
 * only shows the choices: nothing runs until an option is tapped.
 */
@Composable
fun InlineSelect(
    triggerLabel: String,
    triggerIcon: ImageVector,
    groups: List<SelectGroup>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    selectedBadge: String? = null,
    enabled: Boolean = true,
    searchPlaceholder: String? = null,
    footer: (@Composable () -> Unit)? = null,
    /** Test tag of each option row (from its key). */
    optionTag: (String) -> String = { "select_option_$it" }
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val state = androidx.compose.ui.res.stringResource(if (expanded) com.example.R.string.select_expanded else com.example.R.string.select_collapsed)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.surfaceContainerLow,
            shadowElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = !expanded }
                .semantics { stateDescription = state }
                .testTag("${testTag}_trigger")
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).background(colors.primaryContainer, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(triggerIcon, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(16.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(
                    triggerLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (selectedBadge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        selectedBadge, style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer,
                        modifier = Modifier.background(colors.primaryContainer.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = colors.primary)
            }
        }
        AnimatedVisibility(expanded) {
            Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLow, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag("${testTag}_menu")) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (searchPlaceholder != null) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text(searchPlaceholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = colors.surfaceContainerLowest, focusedContainerColor = colors.surfaceContainerLowest),
                            modifier = Modifier.fillMaxWidth().testTag("${testTag}_search")
                        )
                    }
                    val q = query.trim()
                    groups.forEachIndexed { gi, group ->
                        val shown = if (q.isEmpty()) group.options else group.options.filter { it.label.contains(q, true) || it.supporting?.contains(q, true) == true }
                        if (shown.isEmpty() && group.action == null) return@forEachIndexed
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (gi > 0) Box(Modifier.fillMaxWidth().padding(bottom = 4.dp).heightIn(min = 1.dp).background(colors.surfaceContainerHighest.copy(alpha = 0.6f)))
                            if (group.title != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(group.title.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                    color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                                group.action?.invoke()
                            }
                            shown.forEach { option ->
                                SelectRow(option, option.key == selectedKey, enabled, optionTag(option.key)) {
                                    expanded = false
                                    onSelect(option.key)
                                }
                            }
                        }
                    }
                    footer?.invoke()
                }
            }
        }
    }
}

@Composable
private fun SelectRow(option: SelectOption, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.4f) else colors.surfaceContainerLowest)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = option.label }
            .heightIn(min = 48.dp)
            .padding(10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        option.icon?.let { icon ->
            Box(
                Modifier.size(32.dp).background(if (selected) colors.primary else colors.surfaceContainer, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = if (selected) colors.onPrimary else colors.secondary, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) colors.primary else colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            option.supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (!selected && option.trailing != null) {
            Spacer(Modifier.width(6.dp))
            Text(option.trailing, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                modifier = Modifier.background(colors.surfaceContainer, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Spacer(Modifier.width(6.dp))
        Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = null,
            tint = if (selected) colors.primary else colors.outlineVariant, modifier = Modifier.size(22.dp))
    }
}
