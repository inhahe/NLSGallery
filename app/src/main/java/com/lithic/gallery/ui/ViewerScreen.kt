package com.lithic.gallery.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import java.util.Date

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
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var pendingDelete by remember { mutableStateOf<MediaEntity?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val item = pendingDelete
        pendingDelete = null
        if (r.resultCode == Activity.RESULT_OK && item != null) {
            if (Build.VERSION.SDK_INT < 30) runCatching { context.contentResolver.delete(item.contentUri, null, null) }
            items.remove(item)
            scope.launch { vm.resync() }
            if (items.isEmpty()) onBack()
        }
    }
    fun delete(item: MediaEntity) {
        pendingDelete = item
        if (Build.VERSION.SDK_INT >= 30) {
            // To the trash, from where the system restores it for 30 days.
            val pi = MediaStore.createTrashRequest(context.contentResolver, listOf(item.contentUri), true)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            try {
                context.contentResolver.delete(item.contentUri, null, null)
                items.remove(item)
                scope.launch { vm.resync() }
            } catch (e: RecoverableSecurityException) {
                deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            }
        }
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
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = current.mimeType.ifEmpty { if (current.isVideo) "video/*" else "image/*" }
                            putExtra(Intent.EXTRA_STREAM, current.contentUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    onEdit = {
                        val edit = Intent(Intent.ACTION_EDIT).apply {
                            setDataAndType(current.contentUri, current.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(Intent.createChooser(edit, null)) }
                    },
                    onOpenWith = {
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(current.contentUri, current.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(Intent.createChooser(view, null)) }
                    },
                    onDelete = { delete(current) },
                )
            }
        }
        if (details && current != null) {
            ModalBottomSheet(onDismissRequest = { details = false }) {
                DetailsSheet(current, vm.viewerHits[current.id], vm)
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
                            text = { Text(if (description == null) "Describe now" else "Describe again") },
                            onClick = { menu = false; vm.redescribe(item) },
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
    if (!current) playing = false
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing) {
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

@Composable
private fun DetailsSheet(item: MediaEntity, hit: SearchService.Result?, vm: GalleryViewModel) {
    val context = LocalContext.current
    val d by remember(item.aiKey) { vm.description(item) }.collectAsState(null)
    val ai by vm.aiStatus.collectAsState()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        SelectionContainer {
            Column {
                Field("Taken", DateFormat.getLongDateFormat(context).format(Date(item.sortDate)) + ", " +
                    DateFormat.getTimeFormat(context).format(Date(item.sortDate)))
                Field("File", item.displayName)
                Field("Folder", item.relativePath.ifEmpty { "/" })
                Field("Size", Formatter.formatFileSize(context, item.size) +
                    if (item.width > 0) "  ·  ${item.width} × ${item.height}" else "")
                if (item.isVideo && item.durationMs > 0) Field("Length", formatDuration(item.durationMs))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val desc = d
                val s by vm.settings.collectAsState()
                when {
                    desc == null -> Field("AI description", "Not described yet")
                    desc.error != null -> Field("AI description", "Could not be described: ${desc.error}")
                    else -> {
                        Field("AI description", desc.caption ?: if (s.captions) "Not described yet" else "(captions are off)")
                        Field("Text in image", desc.ocr?.ifBlank { "(none found)" }
                            ?: if (s.ocr) "Not read yet (text is read after every picture has a caption)" else "(text reading is off)")
                    }
                }
                desc?.manualCaption?.takeIf { it.isNotBlank() }?.let { Field("Own caption", it) }
                if (hit != null) {
                    Field("Search", buildString {
                        append(if (hit.semanticOnly) "Found by meaning" else "Matched words")
                        hit.similarity?.let { append(" · similarity %.2f".format(it)) }
                    })
                }
            }
        }
        if (ai.ready) {
            Row {
                TextButton(onClick = { vm.redescribe(item) }) { Text(if (d == null) "Describe now" else "Describe again") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
