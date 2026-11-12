package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.commands.SlashCommand

/**
 * Compact list of built-in commands shown above the composer while the user types "/...".
 * Command names and descriptions are English in every language and always read left to
 * right; the list itself follows the layout direction (right-aligned in Arabic).
 */
@Composable
fun SlashCommandSuggestions(
    commands: List<SlashCommand>,
    highlightedIndex: Int,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    val listDescription = stringResource(R.string.commands_suggestions_desc)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = listDescription }
            .testTag("slash_suggestions"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        // Short enough to leave the composer and the keyboard free.
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
            itemsIndexed(commands, key = { _, c -> c.name }) { index, command ->
                val highlighted = index == highlightedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { onSelect(command) }
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("slash_command_${command.name}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = command.token,
                        style = TextStyle(textDirection = TextDirection.Ltr),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(104.dp)
                    )
                    Text(
                        text = command.description,
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * The "/clear" dialog. Each choice is an explicit confirmation; "Delete All Conversations"
 * asks a second time. Cancelling at any step deletes nothing.
 */
@Composable
fun ClearConversationsDialog(
    onClearCurrent: () -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    if (!confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.clear_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.clear_message), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = onClearCurrent,
                        modifier = Modifier.fillMaxWidth().testTag("clear_current_button")
                    ) { Text(stringResource(R.string.clear_current)) }
                    Button(
                        onClick = { confirmDeleteAll = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("clear_all_button")
                    ) { Text(stringResource(R.string.clear_all)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("clear_cancel_button")) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.testTag("clear_dialog")
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.clear_all_confirm_title)) },
            text = { Text(stringResource(R.string.clear_all_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = onDeleteAll,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("clear_all_confirm_button")
                ) { Text(stringResource(R.string.clear_all_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("clear_all_cancel_button")) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.testTag("clear_all_confirm_dialog")
        )
    }
}
