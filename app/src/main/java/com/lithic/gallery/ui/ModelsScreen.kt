package com.lithic.gallery.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lithic.gallery.ai.ModelManager
import com.lithic.gallery.ai.ModelState

private val MODEL_INFO = mapOf(
    ModelManager.BLIP to ("Captions" to "Salesforce BLIP (base): writes a sentence about what each picture shows"),
    ModelManager.MINILM to ("Search by meaning" to "sentence-transformers all-MiniLM-L6-v2: finds pictures whose description means what you typed"),
    ModelManager.CRAFT to ("Text detection" to "EasyOCR's CRAFT: finds the text in a picture"),
    ModelManager.RECOGNIZER to ("Text reading" to "EasyOCR's English recognizer: reads the text found"),
)

/** The AI models: what each is for, its size, and downloading or removing it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val states by vm.modelStates.collectAsState()
    val ai by vm.aiStatus.collectAsState()
    val settings by vm.settings.collectAsState()
    val sizes = remember { vm.modelSizes() }
    val needed = remember(settings.captions, settings.ocr) { vm.neededModels() }
    var wifiOnly by remember { mutableStateOf(true) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("AI models") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "The same models thumbsup2 uses, run on this phone: nothing is sent anywhere. Each is the original " +
                    "file from its publisher (HuggingFace, EasyOCR's GitHub), downloaded once and checked.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            for (key in listOf(ModelManager.BLIP, ModelManager.MINILM, ModelManager.CRAFT, ModelManager.RECOGNIZER)) {
                val (title, about) = MODEL_INFO.getValue(key)
                val state = states[key] ?: ModelState.Missing
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                Formatter.formatFileSize(context, sizes[key] ?: 0) + "  ·  " + when (state) {
                                    ModelState.Missing -> if (key in needed) "not downloaded" else "not needed with current settings"
                                    is ModelState.Downloading -> "downloading"
                                    ModelState.Verifying -> "checking"
                                    ModelState.Ready -> "ready"
                                    is ModelState.Failed -> "failed: ${state.message}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (state == ModelState.Ready) {
                            TextButton(onClick = { vm.deleteModel(key) }) { Text("Remove") }
                        }
                    }
                    if (state is ModelState.Downloading) {
                        LinearProgressIndicator(
                            progress = { if (state.total > 0) state.done / state.total.toFloat() else 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val missing = needed.filter { states[it] != ModelState.Ready }
            if (ai.downloading) {
                Text(
                    "Downloading ${Formatter.formatFileSize(context, ai.downloadDone)} of " +
                        Formatter.formatFileSize(context, ai.downloadTotal) + ". It continues in the background.",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = vm::cancelDownload) { Text("Cancel download") }
            } else if (missing.isNotEmpty()) {
                val total = missing.sumOf { sizes[it] ?: 0 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                    Text("Only over Wi-Fi")
                }
                Button(onClick = { vm.downloadModels(wifiOnly) }) {
                    Text("Download ${Formatter.formatFileSize(context, total)}")
                }
                ai.downloadError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("The last download failed: $it", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("Everything needed is downloaded.", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Space used: " + Formatter.formatFileSize(context, vm.storageUsedByModels()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
