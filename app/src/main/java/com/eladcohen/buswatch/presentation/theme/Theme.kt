package com.eladcohen.buswatch.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme

/** App theme. Default Wear dark palette/typography for now. */
@Composable
fun BusWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
