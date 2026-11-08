package com.lithic.gallery.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.search.SearchService
import kotlinx.coroutines.launch
import java.util.Date

/** What can be done with a file from the grid or the viewer: share it, edit it, open it elsewhere, delete it. */
class MediaActions internal constructor(private val context: Context, private val deleter: (MediaEntity) -> Unit) {
    fun share(item: MediaEntity) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType.ifEmpty { if (item.isVideo) "video/*" else "image/*" }
            putExtra(Intent.EXTRA_STREAM, item.contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }

    fun edit(item: MediaEntity) {
        val edit = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(item.contentUri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(edit, null)) }
    }

    fun openWith(item: MediaEntity) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.contentUri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(view, null)) }
    }

    /** Moves [item] to the trash (Android 11+, where the system keeps it 30 days), or deletes it, once the user agrees. */
    fun delete(item: MediaEntity) = deleter(item)

    fun copyName(item: MediaEntity) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("File name", item.displayName))
        // Android 13+ shows its own confirmation of a copy.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "File name copied", Toast.LENGTH_SHORT).show()
    }
}

/**
 * [MediaActions] for the screen it is remembered in; [onDeleted] is told of a file once it is
 * gone (the user agreed). The library is synced after each.
 */
@Composable
fun rememberMediaActions(vm: GalleryViewModel, onDeleted: (MediaEntity) -> Unit = {}): MediaActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deleted by rememberUpdatedState(onDeleted)
    var pending by remember { mutableStateOf<MediaEntity?>(null) }
    fun gone(item: MediaEntity) {
        deleted(item)
        scope.launch { vm.resync() }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val item = pending
        pending = null
        if (r.resultCode == Activity.RESULT_OK && item != null) {
            // Before Android 11 the user only allowed the deleting; it is done here.
            if (Build.VERSION.SDK_INT < 30) runCatching { context.contentResolver.delete(item.contentUri, null, null) }
            gone(item)
        }
    }
    return remember(context) {
        MediaActions(context) { item ->
            pending = item
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createTrashRequest(context.contentResolver, listOf(item.contentUri), true)
                launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                try {
                    context.contentResolver.delete(item.contentUri, null, null)
                    pending = null
                    gone(item)
                } catch (e: RecoverableSecurityException) {
                    launcher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                }
            }
        }
    }
}

/**
 * What a long press on a thumbnail shows: the file's name and folder, what can be done with it
 * ([onOpen] opens it in the viewer), and its details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSheet(item: MediaEntity, hit: SearchService.Result?, vm: GalleryViewModel, actions: MediaActions, onOpen: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                Text(item.relativePath.ifEmpty { "/" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SheetAction(Icons.Outlined.Fullscreen, "Open") { onDismiss(); onOpen() }
            SheetAction(Icons.Outlined.Share, "Share") { actions.share(item) }
            SheetAction(Icons.Outlined.Delete, "Delete") { onDismiss(); actions.delete(item) }
            SheetAction(Icons.Outlined.Edit, "Edit") { actions.edit(item) }
            SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "Open with") { actions.openWith(item) }
            SheetAction(Icons.Outlined.ContentCopy, "Copy name") { actions.copyName(item) }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        MediaDetails(item, hit, vm)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** A file's details: when, which file, its size, and what the AI made of it ([hit]: how a search found it). */
@Composable
fun MediaDetails(item: MediaEntity, hit: SearchService.Result?, vm: GalleryViewModel) {
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
