package com.eladcohen.buswatch.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import com.eladcohen.buswatch.model.Arrival
import com.eladcohen.buswatch.model.BusMode
import com.eladcohen.buswatch.model.SampleData
import com.eladcohen.buswatch.model.StopBoard
import com.eladcohen.buswatch.net.LinkState
import com.eladcohen.buswatch.presentation.theme.BusWatchTheme

@Composable
fun BusBoardScreen(
    boards: List<StopBoard>,
    link: LinkState,
    mode: BusMode,
    onOpenStops: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(
                    RotaryScrollableDefaults.behavior(scrollableState = listState),
                    focusRequester = focusRequester,
                ),
        ) {
            item(key = "mode") {
                CompactChip(
                    onClick = onOpenStops,
                    label = {
                        Text(
                            text = if (mode == BusMode.NEARBY) "📍 קרובה · החלף" else "📌 קבועה · החלף",
                            textAlign = TextAlign.Center,
                        )
                    },
                )
            }
            item(key = "link") { LinkIndicator(link) }

            boards.forEach { board ->
                item(key = "h_${board.stopCode}") {
                    ListHeader {
                        Text(
                            text = board.stopName,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.title3,
                        )
                    }
                }
                item(key = "c_${board.stopCode}") {
                    Text(
                        text = "תחנה ${board.stopCode}",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (board.arrivals.isEmpty()) {
                    item(key = "e_${board.stopCode}") {
                        Text(
                            text = "אין נסיעות כעת",
                            style = MaterialTheme.typography.caption1,
                            color = MaterialTheme.colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    items(board.arrivals, key = { "${board.stopCode}_${it.line}" }) { arrival ->
                        ArrivalCard(arrival)
                    }
                }
            }
            item(key = "focus") { LaunchedEffect(Unit) { focusRequester.requestFocus() } }
        }
    }
}

@Composable
private fun LinkIndicator(link: LinkState) {
    val (label, tint) = when (link) {
        LinkState.CONNECTED -> "⬤ חי" to MaterialTheme.colors.primary
        LinkState.CONNECTING -> "◌ מתחבר…" to MaterialTheme.colors.onSurfaceVariant
        LinkState.DISCONNECTED -> "⚠ לא מחובר" to MaterialTheme.colors.error
    }
    Text(
        text = label,
        style = MaterialTheme.typography.caption2,
        color = tint,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ArrivalCard(arrival: Arrival) {
    val bg = etaColor(arrival.etaMinutes)
    val bgPainter = remember(bg) { ColorPainter(bg) }
    Card(
        onClick = {},
        backgroundPainter = bgPainter,
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(
                text = "קו ${arrival.line}  ←  ${arrival.destination}",
                style = MaterialTheme.typography.button,
                color = Color.White,
            )
            val realtimeMark = if (arrival.realtime) "  ⏱" else ""
            val next = arrival.nextMinutes?.let { "   ·   ובעוד $it" } ?: ""
            Text(
                text = "בעוד ${arrival.etaMinutes} דק׳$realtimeMark$next",
                style = MaterialTheme.typography.caption1,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

/** Card tint by remaining minutes: green = soon, amber = mid, red = far off. */
private fun etaColor(minutes: Int): Color = when {
    minutes <= 3 -> Color(0xFF1E4D2B)
    minutes <= 8 -> Color(0xFF6B4E16)
    else -> Color(0xFF7A1E1E)
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun BusBoardPreview() {
    BusWatchTheme {
        BusBoardScreen(
            boards = listOf(SampleData.board),
            link = LinkState.CONNECTED,
            mode = BusMode.NEARBY,
            onOpenStops = {},
        )
    }
}
