package com.lithic.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lithic.gallery.data.LayoutMode
import com.lithic.gallery.data.Settings
import com.lithic.gallery.data.TextDetail
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: GalleryViewModel, onBack: () -> Unit, onModels: () -> Unit) {
    BackHandler(onBack = onBack)
    val s by vm.settings.collectAsState()
    val ai by vm.aiStatus.collectAsState()
    val gpu by vm.gpuAvailable.collectAsState()
    fun set(change: (Settings) -> Settings) = vm.updateSettings(change)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section("Thumbnails")
            Choice("Justified rows", "Rows filled edge to edge; each picture whole, sized by its shape",
                s.layoutMode == LayoutMode.JUSTIFIED) { set { it.copy(layoutMode = LayoutMode.JUSTIFIED) } }
            Choice("Grid", "The same number in every row, each fitted inside a square",
                s.layoutMode == LayoutMode.GRID) { set { it.copy(layoutMode = LayoutMode.GRID) } }
            if (s.layoutMode == LayoutMode.JUSTIFIED) {
                SliderRow("Row height", "${s.rowHeightDp} dp", s.rowHeightDp.toFloat(), 60f..400f) { v ->
                    set { it.copy(rowHeightDp = v.roundToInt()) }
                }
            } else {
                SliderRow("Pictures per row", "${s.gridColumns}", s.gridColumns.toFloat(), 1f..10f, steps = 8) { v ->
                    set { it.copy(gridColumns = v.roundToInt()) }
                }
            }
            SliderRow("Gap between pictures", "${s.spacingDp} dp", s.spacingDp.toFloat(), 0f..8f, steps = 7) { v ->
                set { it.copy(spacingDp = v.roundToInt()) }
            }
            Hint("Pinch the thumbnails to make them bigger or smaller.")

            Section("Tabs")
            Toggle("Videos in folder tabs", "Camera, Messages, Downloads… also show their videos (Video always shows every video)",
                s.videosInFolderTabs) { v -> set { it.copy(videosInFolderTabs = v) } }
            Toggle("Other tab", "Everything not in Camera, Screenshots, Messages or Downloads", s.showOtherTab) { v ->
                set { it.copy(showOtherTab = v) }
            }
            Toggle("All tab", "Every photo and video together", s.showAllTab) { v -> set { it.copy(showAllTab = v) } }

            Section("AI descriptions")
            Row(Modifier.fillMaxWidth().clickable(onClick = onModels).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("AI models")
                    Text(
                        when {
                            ai.downloading -> "Downloading…"
                            ai.modelsMissing -> "Not downloaded: tap to download"
                            else -> "Downloaded"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Toggle("Describe new photos as they arrive", "Even with the app closed (runs a minute or so after they are added)",
                s.describeNewMedia) { v -> set { it.copy(describeNewMedia = v) } }
            Toggle("Describe the rest when the app opens", "Everything not yet described, in the background, newest first",
                s.describeOnOpen) { v -> set { it.copy(describeOnOpen = v) } }
            Toggle("Only while charging", "Automatic describing waits for the charger (the AI button always works)",
                s.onlyWhileCharging) { v -> set { it.copy(onlyWhileCharging = v) } }
            Toggle("Captions", "BLIP describes what each picture shows", s.captions) { v -> set { it.copy(captions = v) } }
            Toggle("Text in pictures", "EasyOCR reads signs, screenshots and documents", s.ocr) { v -> set { it.copy(ocr = v) } }
            Toggle("Videos", "Caption three frames of each video and read the text in the middle one", s.describeVideos) { v ->
                set { it.copy(describeVideos = v) }
            }
            Text("Text reading detail", Modifier.padding(start = 16.dp, top = 12.dp))
            for ((detail, label, about) in listOf(
                Triple(TextDetail.BALANCED, "Balanced", "Full detail for screenshots, downloads and messages; " +
                    "camera photos and videos at half (each photo about 10 s instead of a minute)"),
                Triple(TextDetail.FULL, "Full", "Everything at thumbsup2's detail (about a minute per camera photo)"),
                Triple(TextDetail.FAST, "Fast", "Everything at half detail (misses small text in screenshots)"),
            )) {
                Choice(label, about, s.textDetail == detail) { set { it.copy(textDetail = detail) } }
            }
            Toggle("Read text on the GPU",
                if (gpu) "This phone's Snapdragon GPU finds text about five times as fast. In half precision: " +
                    "now and then a word reads differently from thumbsup2's"
                else "This phone has no GPU the text reader runs on: it uses the processor",
                s.gpuText && gpu, enabled = gpu) { v -> set { it.copy(gpuText = v) } }
            SliderRow("Processor threads", if (s.aiThreads == 0) "automatic" else "${s.aiThreads}",
                s.aiThreads.toFloat(), 0f..8f, steps = 7) { v -> set { it.copy(aiThreads = v.roundToInt()) } }

            Section("Search")
            Hint("Search looks in the AI description, the text in the picture, the file's own caption, its name and folder, as " +
                "thumbsup2's search does: matching words (with typos and word starts) blended with matching meaning.")
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Choice(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SliderRow(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            Text(title, Modifier.weight(1f))
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = current, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
