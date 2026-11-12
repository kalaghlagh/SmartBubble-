package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One chip of a [FilterChipRow]: [key] identifies it in state, [label] is the visible text. */
data class FilterOption(val key: String, val label: String)

/**
 * A single horizontally scrollable row of filter chips. Each chip is as wide as its label and
 * the label always stays on one line, so long translations (e.g. French) make the row scroll
 * instead of squeezing a chip until its text breaks letter by letter. LazyRow follows the layout
 * direction: in Arabic (RTL) the row starts on the right and scrolls the other way. The selected
 * chip is filled and shows a check mark, and it is scrolled into view when it changes.
 */
@Composable
fun FilterChipRow(
    options: List<FilterOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
    rowDescription: String? = null
) {
    val listState = rememberLazyListState()
    val selectedIndex = options.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    LaunchedEffect(selectedIndex) {
        val info = listState.layoutInfo
        val fullyVisible = info.visibleItemsInfo.any {
            it.index == selectedIndex && it.offset >= info.viewportStartOffset &&
                it.offset + it.size <= info.viewportEndOffset
        }
        if (!fullyVisible) listState.animateScrollToItem(selectedIndex)
    }
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .then(if (rowDescription != null) Modifier.semantics { contentDescription = rowDescription } else Modifier)
            .testTag("${testTagPrefix}row"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(options, key = { _, option -> option.key }) { _, option ->
            val isSelected = option.key == selectedKey
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option.key) },
                label = {
                    Text(
                        text = option.label,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        softWrap = false
                    )
                },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
                shape = RoundedCornerShape(20.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .testTag("$testTagPrefix${option.key}")
            )
        }
    }
}
