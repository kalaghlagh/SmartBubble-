package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.routing.PriorityEntry
import com.example.agent.routing.RoutingPolicy

/**
 * Settings ▸ AI Providers & Agent Routing (Stitch): one expandable card holding the configured
 * providers (add, edit, delete, test, set default) and, under a divider, the Agent's model
 * priority order (up/down, on/off) and the passes over the list. Only presentation: every action
 * calls the same view-model functions as before; routing itself is unchanged.
 */
@Composable
fun AiProvidersRoutingSection(
    providerCount: Int,
    activeCount: Int,
    notice: Pair<String, Boolean>?,
    providerCards: @Composable () -> Unit,
    onAddProvider: () -> Unit,
    entries: List<PriorityEntry>,
    cycles: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onEnabled: (id: String, enabled: Boolean) -> Unit,
    onCycles: (Int) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLowest,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().testTag("ai_routing_section")
    ) {
        Column(Modifier.padding(16.dp)) {
            val toggle = stringResource(if (expanded) R.string.settings_section_collapse else R.string.settings_section_expand)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { contentDescription = toggle }
                    .padding(bottom = 12.dp)
                    .testTag("ai_routing_header")
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Psychology, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_ai_routing_title), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.settings_ai_routing_desc), style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = colors.secondary)
            }
            AnimatedVisibility(expanded) {
                Column {
                    // A. Configured providers
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SubsectionHeader(Icons.Default.Hub, stringResource(R.string.settings_configured_providers)) {
                            if (providerCount > 0) {
                                Text(
                                    stringResource(R.string.settings_providers_active, activeCount),
                                    style = MaterialTheme.typography.labelSmall, color = colors.secondary,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.surfaceContainer)
                                        .padding(horizontal = 8.dp, vertical = 2.dp).testTag("providers_active_count")
                                )
                            }
                        }
                        notice?.let { (text, isError) ->
                            Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) colors.error else colors.onSurfaceVariant)
                        }
                        providerCards()
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = colors.surfaceContainerLowest,
                            shadowElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(role = Role.Button, onClick = onAddProvider)
                                .testTag("add_provider_button")
                        ) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_add_provider), style = MaterialTheme.typography.titleSmall, color = colors.primary)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 20.dp), color = colors.surfaceContainer)
                    // B. Agent model priority order
                    AgentPriorityContent(entries, cycles, onMove, onEnabled, onCycles)
                }
            }
        }
    }
}

@Composable
private fun SubsectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

/**
 * The Agent's model priority order: numbered rows with Up/Down (accessible, no drag gesture) and
 * an on/off switch, then the passes over the list (1–3).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentPriorityContent(
    entries: List<PriorityEntry>,
    cycles: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onEnabled: (id: String, enabled: Boolean) -> Unit,
    onCycles: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.testTag("agent_settings"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubsectionHeader(Icons.Default.Reorder, stringResource(R.string.agent_settings_title))
        Text(stringResource(R.string.agent_settings_hint), style = MaterialTheme.typography.bodySmall, color = colors.secondary,
            modifier = Modifier.padding(horizontal = 4.dp))
        if (entries.isEmpty()) {
            Text(stringResource(R.string.agent_settings_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }
        entries.forEachIndexed { i, entry ->
            val p = entry.provider
            Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("agent_model_${p.id}")) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = if (i == 0) colors.primary else colors.secondary, modifier = Modifier.widthIn(min = 24.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (entry.hasKey) p.modelName else stringResource(R.string.agent_settings_no_key),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.hasKey) colors.secondary else colors.error,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    val up = stringResource(R.string.agent_move_up, p.name)
                    val down = stringResource(R.string.agent_move_down, p.name)
                    val toggle = stringResource(R.string.agent_use_model, p.name)
                    // Up / Down grouped in one pill.
                    Row(Modifier.clip(RoundedCornerShape(50)).background(colors.surfaceContainerLowest)) {
                        IconButton(onClick = { onMove(i, i - 1) }, enabled = i > 0, modifier = Modifier.testTag("agent_up_${p.id}")) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = up)
                        }
                        IconButton(onClick = { onMove(i, i + 1) }, enabled = i < entries.lastIndex, modifier = Modifier.testTag("agent_down_${p.id}")) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = down)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { onEnabled(p.id, it) },
                        modifier = Modifier.testTag("agent_enable_${p.id}").semantics { contentDescription = toggle }
                    )
                }
            }
        }
        Text(stringResource(R.string.agent_settings_cycles), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            (1..RoutingPolicy.MAX_CYCLES).forEach { n ->
                val isSelected = cycles == n
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) colors.primaryContainer else colors.surfaceContainer)
                        .clickable(role = Role.RadioButton) { onCycles(n) }
                        .semantics { selected = isSelected }
                        .testTag("agent_cycles_$n"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(n.toString(), style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) colors.onPrimaryContainer else colors.onSurface)
                }
            }
        }
    }
}

/** The priority order on its own card (kept for screens and tests that show only this part). */
@Composable
fun AgentPriorityCard(
    entries: List<PriorityEntry>,
    cycles: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onEnabled: (id: String, enabled: Boolean) -> Unit,
    onCycles: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            AgentPriorityContent(entries, cycles, onMove, onEnabled, onCycles)
            Spacer(Modifier.height(4.dp))
        }
    }
}
