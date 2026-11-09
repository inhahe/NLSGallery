package com.lithic.gallery.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.search.SearchService
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import java.util.Date
import kotlinx.coroutines.flow.map

/** Full-screen photos and videos, swiped through in the order they were listed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: GalleryViewModel, startIndex: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val items = remember { mutableStateListOf<MediaEntity>().apply { addAll(vm.viewerList) } }
    if (items.isEmpty()) { onBack(); return }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.size - 1)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    val actions = rememberMediaActions(vm) { item ->
        items.remove(item)
        if (items.isEmpty()) onBack()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, key = { items.getOrNull(it)?.id ?: it.toLong() }, modifier = Modifier.fillMaxSize()) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) {
                VideoPage(item, current = pager.settledPage == page, onTap = { chrome = !chrome })
            } else {
                ZoomableAsyncImage(
                    model = ImageRequest.Builder(context).data(item.contentUri).build(),
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { chrome = !chrome },
                )
            }
        }
        val current = items.getOrNull(pager.currentPage)
        AnimatedVisibility(chrome && current != null, enter = fadeIn(), exit = fadeOut()) {
            if (current != null) {
                ViewerChrome(
                    item = current,
                    hit = vm.viewerHits[current.id],
                    vm = vm,
                    onBack = onBack,
                    onDetails = { details = true },
                    onShare = { actions.share(current) },
                    onEdit = { actions.edit(current) },
                    onOpenWith = { actions.openWith(current) },
                    onDelete = { actions.delete(current) },
                )
            }
        }
        if (details && current != null) {
            ModalBottomSheet(onDismissRequest = { details = false }) {
                MediaDetails(current, vm.viewerHits[current.id], vm)
            }
        }
    }
}

@Composable
private fun ViewerChrome(
    item: MediaEntity,
    hit: SearchService.Result?,
    vm: GalleryViewModel,
    onBack: () -> Unit,
    onDetails: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onOpenWith: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val description by remember(item.aiKey) { vm.description(item) }.collectAsState(null)
    val redescribing by remember(item.id) { vm.redescribing(item).map { it.going } }.collectAsState(false)
    val ai by vm.aiStatus.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(
                    DateFormat.getMediumDateFormat(context).format(Date(item.sortDate)) + "  " +
                        DateFormat.getTimeFormat(context).format(Date(item.sortDate)),
                    color = Color.White, style = MaterialTheme.typography.titleSmall,
                )
                Text(item.displayName, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDetails) { Icon(Icons.Outlined.Info, "Details", tint = Color.White) }
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share", tint = Color.White) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Move to trash", tint = Color.White) }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit in…") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Open with…") }, onClick = { menu = false; onOpenWith() })
                    if (ai.ready) {
                        DropdownMenuItem(
                            text = { Text(if (redescribing) "Describing…" else if (description == null) "Describe now" else "Describe again") },
                            onClick = { menu = false; vm.redescribe(item) },
                            enabled = !redescribing,
                        )
                    }
                }
            }
        }
        val caption = description?.caption
        if (!caption.isNullOrBlank() || hit != null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clickable(onClick = onDetails),
            ) {
                if (!caption.isNullOrBlank()) Text(caption, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (hit != null && hit.semanticOnly) {
                    Text("Found by meaning (similarity %.2f)".format(hit.similarity ?: 0f),
                        color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** A video: its thumbnail with a play mark, playing (with controls) while it is the current page. */
@Composable
private fun VideoPage(item: MediaEntity, current: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    var playing by remember(item.id) { mutableStateOf(false) }
    // Swiped away, it stops at once and shows its thumbnail when it comes back.
    LaunchedEffect(current) { if (!current) playing = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing && current) {
            val player = remember(item.id) { ExoPlayer.Builder(context).build() }
            DisposableEffect(item.id) {
                player.setMediaItem(MediaItem.fromUri(item.contentUri))
                player.prepare()
                player.playWhenReady = true
                onDispose { player.release() }
            }
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(
                model = MediaThumb.forCell(item.uri, 1024, item.dateModified),
                contentDescription = item.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable(onClick = onTap),
            )
            IconButton(onClick = { playing = true }, modifier = Modifier.size(80.dp)) {
                Icon(Icons.Filled.PlayCircle, "Play", tint = Color.White, modifier = Modifier.size(72.dp))
            }
        }
    }
}
