package com.lithic.gallery.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lithic.gallery.core.layout.Layouts
import com.lithic.gallery.core.layout.Row as LayoutRow
import com.lithic.gallery.data.LayoutMode
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Thumbnails of [items] in order, rows laid out by [settings] (justified, or a grid of N per row),
 * never cropped. Pinching changes the row height or the number of columns.
 *
 * @param onOpen the item tapped: the list on screen and its index in it.
 * @param onLongPress the item long-pressed, the same way.
 * @param badge an optional marker drawn on an item (search results mark "found by meaning").
 * @param aspectFixes displayed aspect ratios that override MediaStore's, by id: (dateModified, aspect).
 * @param onAspect a thumbnail decoded at this aspect ratio (to learn [aspectFixes]).
 */
@Composable
fun ThumbnailGrid(
    items: List<MediaEntity>,
    settings: Settings,
    contentPadding: PaddingValues,
    onOpen: (List<MediaEntity>, Int) -> Unit,
    onLongPress: (List<MediaEntity>, Int) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    badge: ((MediaEntity) -> Boolean)? = null,
    aspectFixes: Map<Long, Pair<Long, Float>> = emptyMap(),
    onAspect: (MediaEntity, Float) -> Unit = { _, _ -> },
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val spacingPx = with(density) { settings.spacingDp.dp.roundToPx() }
        val rowHeightPx = with(density) { settings.rowHeightDp.dp.roundToPx() }
        // Laid out off the main thread; the previous layout (with the list it was made for) stays
        // up meanwhile, so rows and items always match.
        val laidOut by produceState(LaidOut(emptyList(), emptyList()), items, widthPx, settings.layoutMode, rowHeightPx, settings.gridColumns, spacingPx, aspectFixes) {
            value = withContext(Dispatchers.Default) {
                LaidOut(items, when (settings.layoutMode) {
                    LayoutMode.JUSTIFIED -> Layouts.justified(
                        FloatArray(items.size) { i ->
                            val it = items[i]
                            aspectFixes[it.id]?.takeIf { f -> f.first == it.dateModified }?.second ?: it.aspect
                        }, widthPx, rowHeightPx, spacingPx)
                    LayoutMode.GRID -> Layouts.grid(items.size, widthPx, settings.gridColumns, spacingPx)
                })
            }
        }
        val shown = laidOut.items
        val rows = laidOut.rows
        var pinch by remember { mutableFloatStateOf(1f) }
        val fit = settings.layoutMode == LayoutMode.GRID
        LazyColumn(
            state = state,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = pinch; scaleY = pinch }
                .pointerInput(Unit) {
                    // Two fingers: zoom the thumbnails (applied on release); one finger scrolls.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var zoom = 1f
                        var pinching = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                pinching = true
                                zoom *= event.calculateZoom()
                                pinch = zoom.coerceIn(0.5f, 2f)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) onZoom(zoom)
                        pinch = 1f
                    }
                },
        ) {
            items(rows, key = { r -> shown[r.start].id }) { row ->
                ThumbnailRow(shown, row, spacingPx, fit, onOpen, onLongPress, badge, onAspect)
            }
        }
        FastScroller(state, rows, shown, contentPadding)
    }
}

/** A layout and the list it was computed for. */
private class LaidOut(val items: List<MediaEntity>, val rows: List<LayoutRow>)

@Composable
private fun ThumbnailRow(
    items: List<MediaEntity>,
    row: LayoutRow,
    spacingPx: Int,
    fit: Boolean,
    onOpen: (List<MediaEntity>, Int) -> Unit,
    onLongPress: (List<MediaEntity>, Int) -> Unit,
    badge: ((MediaEntity) -> Boolean)?,
    onAspect: (MediaEntity, Float) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(with(density) { (row.height + spacingPx).toDp() }),
    ) {
        for (k in 0 until row.count) {
            val index = row.start + k
            val item = items[index]
            val w = row.width[k]
            Box(
                Modifier
                    .offset { IntOffset(row.x[k], 0) }
                    .size(with(density) { w.toDp() }, with(density) { row.height.toDp() })
                    .background(if (fit) Color(0xFF151515) else Color.Transparent)
                    .combinedClickable(
                        onClick = { onOpen(items, index) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(items, index)
                        },
                    ),
            ) {
                AsyncImage(
                    model = MediaThumb.forCell(item.uri, maxOf(w, row.height), item.dateModified),
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { s ->
                        val img = s.result.image
                        if (img.width > 0 && img.height > 0) onAspect(item, img.width.toFloat() / img.height)
                    },
                )
                if (item.isVideo) VideoBadge(item, Modifier.align(Alignment.BottomEnd))
                if (badge?.invoke(item) == true) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoBadge(item: MediaEntity, modifier: Modifier) {
    Row(
        modifier
            .padding(3.dp)
            .background(Color(0x99000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayCircle, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(12.dp))
        if (item.durationMs > 0) {
            Text(
                formatDuration(item.durationMs), color = Color.White, fontSize = 10.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * A thumb on the right edge, shown while scrolling, that can be dragged to move through the
 * whole list quickly, with the month and year of the photos at that point.
 */
@Composable
private fun FastScroller(
    state: LazyListState,
    rows: List<LayoutRow>,
    items: List<MediaEntity>,
    contentPadding: PaddingValues,
) {
    if (rows.size < 30) return
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
            if (scrolling) visible = true
            else if (!dragging) { kotlinx.coroutines.delay(1500); if (!state.isScrollInProgress && !dragging) visible = false }
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
    ) {
        val density = LocalDensity.current
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = with(density) { 48.dp.toPx() }
        val fraction = if (rows.isEmpty()) 0f else state.firstVisibleItemIndex / (rows.size - 1).coerceAtLeast(1).toFloat()
        val y = (trackPx - thumbPx) * fraction.coerceIn(0f, 1f)
        // The drag outlives these values (the thumb moves, the list grows): read them current.
        val thumbY by rememberUpdatedState(y)
        val travel by rememberUpdatedState(trackPx - thumbPx)
        val lastRow by rememberUpdatedState(rows.size - 1)
        AnimatedVisibility(visible || dragging, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
            Box(Modifier.fillMaxHeight().width(56.dp)) {
                if (dragging) {
                    val row = rows.getOrNull(state.firstVisibleItemIndex)
                    val date = row?.let { items.getOrNull(it.start)?.sortDate }
                    if (date != null) {
                        Text(
                            MONTH.format(Date(date)),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .offset { IntOffset(-with(density) { 120.dp.roundToPx() }, y.roundToInt()) }
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .width(96.dp),
                        )
                    }
                }
                // A finger-sized touch area around the thin visible bar.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, y.roundToInt()) }
                        .size(36.dp, 48.dp)
                        .pointerInput(thumbPx) {
                            // The finger's position down the track; the thumb follows it, so
                            // positions relative to the thumb would not say where it is.
                            var finger = 0f
                            detectVerticalDragGestures(
                                onDragStart = { at -> finger = thumbY + at.y; dragging = true; visible = true },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, dy ->
                                change.consume()
                                finger += dy
                                val pos = (finger - thumbPx / 2) / travel
                                val target = (pos.coerceIn(0f, 1f) * lastRow).roundToInt()
                                scope.launch { state.scrollToItem(target) }
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .size(8.dp, 48.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}

private val MONTH = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
