package com.asl.app.ui.display

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asl.app.ble.BleConnectionState
import kotlinx.coroutines.delay

private const val CURSOR = "▌"
private val CaptionStyle =
    TextStyle(
        fontSize = 56.sp,
        lineHeight = 68.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )

@Composable
fun DisplayScreen(modifier: Modifier = Modifier, viewModel: DisplayViewModel = viewModel()) {
    val caption by viewModel.caption.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    // Keep the caption visible for the person reading it.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val isLandscape = maxWidth > maxHeight

            when {
                !isLandscape -> RotatePrompt()
                else ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        CaptionText(text = caption, modifier = Modifier.weight(1f))
                        if (connection != BleConnectionState.Connected) {
                            Text(
                                text = "Not connected",
                                color = Color(0xFF7A7A7A),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun CaptionText(text: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val cursorVisible by
    produceState(initialValue = true) {
        while (true) {
            delay(530)
            value = !value
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val cursorWidthPx = remember(measurer) {
            measurer.measure(CURSOR, CaptionStyle, maxLines = 1, softWrap = false).size.width
        }
        val availableWidthPx = (constraints.maxWidth - cursorWidthPx).coerceAtLeast(1)

        val lines = remember(text, availableWidthPx) {
            wrapCaption(text, CaptionStyle, measurer, availableWidthPx)
        }

        val listState = rememberLazyListState()
        LaunchedEffect(lines.size, lines.lastOrNull()) {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
        }

        LazyColumn(state = listState, verticalArrangement = Arrangement.Bottom) {
            itemsIndexed(lines) { index, line ->
                Row {
                    Text(
                        text = line,
                        style = CaptionStyle,
                        color = Color.White,
                        softWrap = false,
                        maxLines = 1,
                    )
                    if (index == lines.lastIndex) {
                        Text(
                            text = CURSOR,
                            style = CaptionStyle,
                            color = if (cursorVisible) Color.White else Color.Transparent,
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Greedy character wrap measured against the real font, so each returned string fits on one line.
 * Compose's own wrapping is then disabled (`softWrap = false`), which guarantees whole-line
 * scrolling instead of a clipped line at the top.
 */
private fun wrapCaption(
    text: String,
    style: TextStyle,
    measurer: TextMeasurer,
    maxWidthPx: Int,
): List<String> {
    val lines = mutableListOf<String>()
    val current = StringBuilder()

    for (ch in text) {
        when {
            ch == '\n' -> {
                lines += current.toString()
                current.clear()
            }

            current.isEmpty() -> if (ch != ' ') current.append(ch)
            else -> {
                val candidate = current.toString() + ch
                val fits = measurer.measure(candidate, style, maxLines = 1, softWrap = false).size.width
                if (fits > maxWidthPx) {
                    lines += current.toString()
                    current.clear()
                    if (ch != ' ') current.append(ch)
                } else {
                    current.append(ch)
                }
            }
        }
    }

    lines += current.toString()
    return lines
}

@Composable
private fun RotatePrompt() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Rotate your device",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "The caption screen only works in landscape.",
            color = Color(0xFFB0B0B0),
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
