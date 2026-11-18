package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.routing.PriorityEntry
import com.example.agent.routing.RoutingPolicy

/**
 * Settings > Agent: the order in which the Agent tries the configured models, which models it may
 * use, and how many passes over the list it makes before giving up. Reordering uses Up/Down
 * buttons (accessible, no drag gesture needed).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentPriorityCard(
    entries: List<PriorityEntry>,
    cycles: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onEnabled: (id: String, enabled: Boolean) -> Unit,
    onCycles: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("agent_settings"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.agent_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.agent_settings_hint), style = MaterialTheme.typography.bodySmall)
            if (entries.isEmpty()) {
                Text(stringResource(R.string.agent_settings_empty), style = MaterialTheme.typography.bodyMedium)
            }
            entries.forEachIndexed { i, entry ->
                val p = entry.provider
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("agent_model_${p.id}")) {
                    Text("${i + 1}.", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (entry.hasKey) p.modelName else stringResource(R.string.agent_settings_no_key),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.hasKey) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    val up = stringResource(R.string.agent_move_up, p.name)
                    val down = stringResource(R.string.agent_move_down, p.name)
                    val toggle = stringResource(R.string.agent_use_model, p.name)
                    IconButton(onClick = { onMove(i, i - 1) }, enabled = i > 0, modifier = Modifier.testTag("agent_up_${p.id}")) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = up)
                    }
                    IconButton(onClick = { onMove(i, i + 1) }, enabled = i < entries.lastIndex, modifier = Modifier.testTag("agent_down_${p.id}")) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = down)
                    }
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { onEnabled(p.id, it) },
                        modifier = Modifier.testTag("agent_enable_${p.id}").semantics { contentDescription = toggle }
                    )
                }
            }
            Text(stringResource(R.string.agent_settings_cycles), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..RoutingPolicy.MAX_CYCLES).forEach { n ->
                    FilterChip(
                        selected = cycles == n,
                        onClick = { onCycles(n) },
                        label = { Text(n.toString()) },
                        modifier = Modifier.testTag("agent_cycles_$n")
                    )
                }
            }
        }
    }
}
