package com.lithic.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lithic.gallery.ai.Hardware
import com.lithic.gallery.core.search.HybridSearch
import com.lithic.gallery.core.search.SearchReading
import com.lithic.gallery.data.Category
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.data.LayoutMode
import com.lithic.gallery.data.OnBattery
import com.lithic.gallery.work.BatteryBudget
import kotlin.math.roundToInt

fun Category.label(): String = when (this) {
    Category.CAMERA -> "Camera"
    Category.VIDEO -> "Video"
    Category.SCREENSHOTS -> "Screenshots"
    Category.MESSAGES -> "Messages"
    Category.DOWNLOADS -> "Downloads"
    Category.OTHER -> "Other"
    Category.ALL -> "All"
}

fun Category.icon(): ImageVector = when (this) {
    Category.CAMERA -> Icons.Outlined.PhotoCamera
    Category.VIDEO -> Icons.Outlined.Videocam
    Category.SCREENSHOTS -> Icons.Outlined.Screenshot
    Category.MESSAGES -> Icons.AutoMirrored.Outlined.Chat
    Category.DOWNLOADS -> Icons.Outlined.Download
    Category.OTHER -> Icons.Outlined.FolderOpen
    Category.ALL -> Icons.Outlined.PhotoLibrary
}

private val FIELD_LABELS = mapOf(
    HybridSearch.AI_CAPTION to "Description",
    HybridSearch.AI_OCR to "Text in image",
    HybridSearch.CAPTION to "Own caption",
    HybridSearch.FILENAME to "File name",
    HybridSearch.PATH to "Folder",
)

/** The tabs, the thumbnails, search, and the AI button. */
@Composable
fun GalleryScreen(
    vm: GalleryViewModel,
    /** Opens the viewer at the given index of the list [GalleryViewModel.openViewer] was given. */
    onOpen: (Int) -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val tabs by vm.tabs.collectAsState()
    val category by vm.category.collectAsState()
    val items by vm.visibleMedia.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.searchResults.collectAsState()
    val searchDate by vm.searchDate.collectAsState()
    val reading by vm.reading.collectAsState()
    val scope by vm.searchScope.collectAsState()
    val ai by vm.aiStatus.collectAsState()
    val secondsLeft by vm.passSecondsLeft.collectAsState()
    val weakPhoneNotice by vm.weakPhoneNotice.collectAsState()
    val all by vm.allMedia.collectAsState()
    val aspectFixes by vm.aspectFixes.collectAsState()
    val density = LocalDensity.current

    // The header (search field and tabs) slides away when scrolling down, back when scrolling up.
    var headerPx by remember { mutableIntStateOf(0) }
    var headerOffset by remember { mutableFloatStateOf(0f) }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                headerOffset = (headerOffset + available.y).coerceIn(-headerPx.toFloat(), 0f)
                return Offset.Zero
            }
        }
    }
    val headerDp = with(density) { headerPx.toDp() }
    val navBar = WindowInsets.navigationBars
    val bottomPad = with(density) { navBar.getBottom(this).toDp() }
    val gridState = rememberLazyListState()
    val searchState = rememberLazyListState()
    LaunchedEffect(category) { gridState.scrollToItem(0); headerOffset = 0f }

    var aiDialog by remember { mutableStateOf(false) }
    // The thumbnail long-pressed: its list and its index in it.
    var pressed by remember { mutableStateOf<Pair<List<MediaEntity>, Int>?>(null) }
    val actions = rememberMediaActions(vm)
    val places by vm.places.collectAsState()

    // Opens the viewer at [i] of [list], with how each was found when searching.
    val open: (List<MediaEntity>, Int) -> Unit = { list, i ->
        vm.openViewer(list, results?.mapNotNull { h -> h.result?.let { h.item.id to it } }?.toMap().orEmpty())
        onOpen(i)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).nestedScroll(connection)) {
        val padding = PaddingValues(top = headerDp, bottom = bottomPad + 8.dp)
        val zoom: (Float) -> Unit = { z ->
            vm.updateSettings { s ->
                when (s.layoutMode) {
                    LayoutMode.JUSTIFIED -> s.copy(rowHeightDp = (s.rowHeightDp * z).roundToInt().coerceIn(60, 400))
                    LayoutMode.GRID -> s.copy(gridColumns = (s.gridColumns / z).roundToInt().coerceIn(1, 10))
                }
            }
        }
        val hits = results
        if (hits == null) {
            if (items.isEmpty()) {
                EmptyTab(category, all.isEmpty(), Modifier.padding(top = headerDp))
            } else {
                ThumbnailGrid(items, settings, padding, onOpen = open,
                    onLongPress = { list, i -> pressed = list to i }, onZoom = zoom,
                    state = gridState, aspectFixes = aspectFixes, onAspect = vm::recordAspect)
            }
        } else {
            val list = remember(hits) { hits.map { it.item } }
            if (list.isEmpty()) {
                NoResults(reading, scope, ai, Modifier.padding(top = headerDp))
            } else {
                val semantic = remember(hits) { hits.filter { it.result?.semanticOnly == true }.map { it.item.id }.toSet() }
                ThumbnailGrid(
                    list, settings, padding,
                    onOpen = open,
                    onLongPress = { list, i -> pressed = list to i },
                    onZoom = zoom, state = searchState,
                    badge = { it.id in semantic },
                    aspectFixes = aspectFixes, onAspect = vm::recordAspect,
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, headerOffset.roundToInt()) }
                .onSizeChanged { headerPx = it.height }
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            TopRow(
                query = query,
                onQuery = { vm.query.value = it },
                ai = ai,
                onAi = { if (ai.ready || ai.featuresOff || ai.notAllowed) aiDialog = true else onModels() },
                onSettings = onSettings,
                onModels = onModels,
                layoutMode = settings.layoutMode,
                onLayout = { m -> vm.updateSettings { it.copy(layoutMode = m) } },
            )
            if (ai.running) {
                LinearProgressIndicator(
                    progress = { if (ai.total > 0) ai.done / ai.total.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
                DescribeStatus(ai, secondsLeft, onClick = { aiDialog = true })
            }
            CategoryTabs(tabs, category, onSelect = vm::selectCategory)
            if (hits != null) {
                searchDate?.let { date -> ReadChip(date.filter.label, Icons.Outlined.DateRange, onDismiss = vm::dismissDate) }
                reading.place?.let { place -> ReadChip(place.label, Icons.Outlined.Place, onDismiss = vm::dismissPlace) }
                // A place named in passing ranks the pictures taken there first: said, so it can be undone
                // (a name that is also a word: "nice beach").
                val named = reading.named.mapNotNull { places.names[it]?.label }
                if (named.isNotEmpty()) {
                    ReadChip(named.joinToString("; ") + " first", Icons.Outlined.Place, onDismiss = vm::dismissPlace,
                        dismiss = "Rank without where they were taken")
                }
                SearchChips(
                    words = reading.words.isNotEmpty(),
                    fields = settings.searchFields,
                    onToggle = { f ->
                        vm.updateSettings { s ->
                            val next = if (f in s.searchFields) s.searchFields - f else s.searchFields + f
                            s.copy(searchFields = next.ifEmpty { s.searchFields })
                        }
                    },
                    tabOnly = settings.searchCurrentTabOnly,
                    onTabOnly = { vm.updateSettings { s -> s.copy(searchCurrentTabOnly = !s.searchCurrentTabOnly) } },
                    category = category,
                    count = hits.size,
                )
            } else if (!ai.featuresOff && !ai.notAllowed && ai.modelsMissing && !ai.downloading && all.isNotEmpty()) {
                SetupCard(onModels)
            }
        }
    }

    pressed?.let { (list, i) ->
        val item = list[i]
        val hit = results?.firstOrNull { it.item.id == item.id }?.result
        MediaSheet(item, hit, vm, actions, onDismiss = { pressed = null }, onOpen = { open(list, i) })
    }

    // Once: this phone has too little memory for the AI.
    if (weakPhoneNotice) {
        WeakPhoneDialog(
            vm.memory.soldAsGb,
            onOk = { vm.updateSettings { it.copy(weakPhoneNoticeShown = true) } },
            onAnyway = { vm.updateSettings { it.copy(weakPhoneNoticeShown = true, aiOnWeakPhone = true) } },
        )
    }

    if (aiDialog) AiDialog(ai, vm.memory.soldAsGb, onDismiss = { aiDialog = false }, onDescribe = { vm.describeNow(); aiDialog = false },
        onStop = { vm.stopDescribing(); aiDialog = false }, onRetry = { vm.retryFailures(); aiDialog = false },
        onSettings = { aiDialog = false; onSettings() })
}

@Composable
private fun TopRow(
    query: String,
    onQuery: (String) -> Unit,
    ai: AiStatus,
    onAi: () -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
    layoutMode: LayoutMode,
    onLayout: (LayoutMode) -> Unit,
) {
    val focus = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f).height(44.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    if (query.isEmpty()) {
                        Text("Search your photos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Close, contentDescription = "Clear search",
                        modifier = Modifier.size(20.dp).clickable { onQuery(""); focus.clearFocus() },
                    )
                }
            }
        }
        AiButton(ai, onAi)
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (layoutMode == LayoutMode.JUSTIFIED) "Show as grid" else "Show as justified rows") },
                    onClick = {
                        menu = false
                        onLayout(if (layoutMode == LayoutMode.JUSTIFIED) LayoutMode.GRID else LayoutMode.JUSTIFIED)
                    },
                )
                DropdownMenuItem(text = { Text("AI models") }, onClick = { menu = false; onModels() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
            }
        }
    }
}

/** What describing is doing: the pass, how far through it, and about how long it has left. */
@Composable
private fun DescribeStatus(ai: AiStatus, secondsLeft: Long?, onClick: () -> Unit) {
    val what = when (ai.phase) {
        "text" -> "Reading text in pictures"
        "embeddings" -> "Preparing search"
        else -> "Describing pictures"
    }
    val count = if (ai.phaseTotal > 0) " · ${"%,d".format(ai.phaseDone)} of ${"%,d".format(ai.phaseTotal)}" else " · starting"
    val left = secondsLeft?.let { " · ${aboutLeft(it)}" } ?: ""
    Text(
        what + count + left,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** [seconds] as "about 25 min left", "about 3 h left", "about 2 days left". */
private fun aboutLeft(seconds: Long): String = when {
    seconds < 90 -> "about a minute left"
    seconds < 90 * 60 -> "about ${(seconds + 30) / 60} min left"
    seconds < 48 * 3600 -> "about ${(seconds + 1800) / 3600} h left"
    else -> "about ${(seconds + 43_200) / 86_400} days left"
}

/** The AI button: sparkles, with a count of files waiting to be described, or progress. */
@Composable
private fun AiButton(ai: AiStatus, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            if (ai.running || ai.downloading) {
                CircularProgressIndicator(
                    progress = {
                        when {
                            ai.running && ai.total > 0 -> ai.done / ai.total.toFloat()
                            ai.downloading && ai.downloadTotal > 0 -> ai.downloadDone / ai.downloadTotal.toFloat()
                            else -> 0f
                        }
                    },
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 2.dp,
                )
            }
            BadgedBox(badge = {
                if (!ai.running && ai.ready && ai.undescribed > 0) Badge { Text(compactCount(ai.undescribed)) }
            }) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = "AI descriptions",
                    tint = if (ai.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun compactCount(n: Int) = if (n >= 10_000) "${n / 1000}k" else n.toString()

@Composable
private fun CategoryTabs(tabs: List<Category>, selected: Category, onSelect: (Category) -> Unit) {
    val index = tabs.indexOf(selected).coerceAtLeast(0)
    SecondaryTabRow(
        selectedTabIndex = index,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // TabRow's colour, which this replaces (SecondaryTabRow's own is the surface's).
        contentColor = androidx.compose.material3.TabRowDefaults.primaryContentColor,
        divider = { HorizontalDivider(thickness = 0.5.dp) },
    ) {
        tabs.forEach { c ->
            Tab(
                selected = c == selected,
                onClick = { onSelect(c) },
                icon = { Icon(c.icon(), contentDescription = null, modifier = Modifier.size(22.dp)) },
                text = { Text(c.label(), fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                modifier = Modifier.height(56.dp),
            )
        }
    }
}

@Composable
private fun SearchChips(
    /** Whether words are searched for: not when nothing is left to search for ("last summer", "show me videos"). */
    words: Boolean,
    fields: Set<String>,
    onToggle: (String) -> Unit,
    tabOnly: Boolean,
    onTabOnly: () -> Unit,
    category: Category,
    count: Int,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (words) for (f in HybridSearch.FIELDS) {
                FilterChip(selected = f in fields, onClick = { onToggle(f) }, label = { Text(FIELD_LABELS.getValue(f), fontSize = 12.sp) })
            }
            // In the All tab there is nothing to keep a search to.
            if (category != Category.ALL) {
                FilterChip(selected = tabOnly, onClick = onTabOnly, label = { Text("Only in ${category.label()}", fontSize = 12.sp) })
            }
        }
        Text(
            if (count == 1) "1 match" else "$count matches",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )
    }
}

/**
 * A date or a place read in the query ([label]: what it was taken to mean, beside its [icon]);
 * × ([dismiss] to accessibility) searches for its words instead.
 */
@Composable
private fun ReadChip(label: String, icon: ImageVector, onDismiss: () -> Unit, dismiss: String = "Search for these words instead") {
    InputChip(
        selected = true,
        onClick = onDismiss,
        label = { Text(label, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, contentDescription = null, Modifier.size(18.dp)) },
        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = dismiss, Modifier.size(18.dp)) },
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun SetupCard(onModels: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(8.dp).clickable(onClick = onModels),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                "Find photos by what is in them: download the AI models (once, about 1.2 GB).",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyTab(category: Category, libraryEmpty: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            if (libraryEmpty) "No photos or videos found on this phone yet."
            else "Nothing in ${category.label()}.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoResults(r: SearchReading, tab: Category?, ai: AiStatus, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            noResults(r, tab),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A listing (no words searched for): describing has no part in it.
        if (r.words.isEmpty()) return@Column
        if (ai.notAllowed) {
            Spacer(Modifier.height(8.dp))
            Text(
                "This phone has too little memory for the AI, so search finds dates, places, file names and folders.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        } else if (ai.featuresOff) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Captions and text reading are off (Settings, AI), so only what was described before, " +
                    "file names and folders are searched.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        } else if (!ai.ready) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Until the AI models are downloaded and photos described, only file names and folders are searched.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        } else if (ai.undescribed > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${ai.undescribed} photos and videos are not described yet, so their contents cannot be searched.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** What an empty search says: what was looked for ([r]), where ([tab]: kept to it). */
private fun noResults(r: SearchReading, tab: Category?): String {
    val inTab = tab?.takeIf { !(r.videosOnly && it == Category.VIDEO) }?.let { " in ${it.label()}" } ?: ""
    // The chips above say when and where.
    val read = listOfNotNull("then".takeIf { r.date != null }, "there".takeIf { r.place != null }).joinToString(" and ")
    return when {
        r.words.isEmpty() && read.isNotEmpty() -> if (r.videosOnly) "No videos$inTab were taken $read."
            else if (tab != null) "Nothing$inTab was taken $read." else "No photos or videos were taken $read."
        r.words.isEmpty() -> if (r.videosOnly) "No videos$inTab." else "Nothing$inTab."
        read.isNotEmpty() -> if (r.videosOnly) "No videos taken $read match “${r.words}”." else "Nothing taken $read matches “${r.words}”."
        r.videosOnly -> "No videos match “${r.words}”."
        else -> "Nothing matches “${r.text}”."
    }
}

/** What a phone with too little memory for the AI ([memoryGb], as sold) is told, once. */
@Composable
private fun WeakPhoneDialog(memoryGb: Int, onOk: () -> Unit, onAnyway: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
        title = { Text("Not enough memory for the AI") },
        text = { Text(weakPhoneText(memoryGb)) },
        confirmButton = { TextButton(onClick = onOk) { Text("OK") } },
        dismissButton = { TextButton(onClick = onAnyway) { Text("Use the AI anyway") } },
    )
}

/** Why the AI is off on a phone with [memoryGb] (as sold), and what still works. */
fun weakPhoneText(memoryGb: Int) =
    "This phone has $memoryGb GB of memory. Describing pictures and searching them by what they show need at least " +
        "${Hardware.NEEDED_GB} GB: with less, Android closes the app, or others, while it works. So they are off. " +
        "You can still browse, and search by date, place, file name and folder."

@Composable
private fun AiDialog(
    ai: AiStatus,
    memoryGb: Int,
    onDismiss: () -> Unit,
    onDescribe: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
        title = { Text("AI descriptions") },
        text = {
            Column {
                when {
                    ai.notAllowed && !ai.running -> Text(weakPhoneText(memoryGb) + " (Settings, AI, to use it anyway.)")
                    ai.featuresOff && !ai.running -> Text(
                        "Captions and text reading are both off (Settings, AI), so nothing is described. " +
                            "Search still finds what was described before.",
                    )
                    ai.running -> Text(
                        if (ai.total > 0) "Describing: ${ai.done} of ${ai.total} done " +
                            when (ai.phase) {
                                "text" -> "(now reading the text in pictures, the slower part). "
                                "embeddings" -> "(now making what search by meaning needs, which is quick). "
                                else -> "(captions first, so everything is searchable soon; then the text in pictures). "
                            } +
                            "It carries on in the background; the notification shows progress."
                        else "Starting…",
                    )
                    ai.undescribed > 0 -> Text(
                        "${ai.undescribed} photos and videos have no description yet. " +
                            if (!ai.automatic) "Nothing describes them by itself (Settings, AI); Describe now starts on " +
                                "them all, newest first, and each is searchable as soon as it is done."
                            else "Describing them runs in the background, newest first, and each is searchable as " +
                                "soon as it is done." +
                                when (ai.onBattery) {
                                    OnBattery.NEW_ONLY -> " On battery only those from the last week are described, up " +
                                        "to ${BatteryBudget.PER_DAY} a day; the rest wait for the charger (Settings, AI)."
                                    OnBattery.CHARGER_ONLY -> " It waits for the charger (Settings, AI)."
                                    OnBattery.ANYTIME -> ""
                                } + " Describe now starts on them all at once.",
                    )
                    else -> Text("Everything is described and searchable.")
                }
                if (ai.failures > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("${ai.failures} could not be described.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when {
                ai.running -> TextButton(onClick = onStop) { Text("Stop") }
                ai.notAllowed || ai.featuresOff -> TextButton(onClick = onDismiss) { Text("OK") }
                ai.undescribed > 0 -> TextButton(onClick = onDescribe) { Text("Describe now") }
                ai.failures > 0 -> TextButton(onClick = onRetry) { Text("Retry failed") }
                else -> TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = { TextButton(onClick = onSettings) { Text("Settings") } },
    )
}
