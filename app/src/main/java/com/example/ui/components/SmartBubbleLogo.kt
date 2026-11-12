package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R

/** The SmartBubble brand mark (same artwork as the launcher icon). Decorative: no description. */
@Composable
fun SmartBubbleLogo(size: Dp = 34.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.smart_bubble_logo),
        contentDescription = null,
        modifier = modifier.size(size)
    )
}
