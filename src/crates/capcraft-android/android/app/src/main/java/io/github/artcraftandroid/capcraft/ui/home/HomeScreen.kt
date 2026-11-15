package io.github.artcraftandroid.capcraft.ui.home

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.ProjectSummary
import io.github.artcraftandroid.capcraft.core.createProject
import io.github.artcraftandroid.capcraft.core.deleteProject
import io.github.artcraftandroid.capcraft.core.listTrash
import io.github.artcraftandroid.capcraft.core.restoreProject
import io.github.artcraftandroid.capcraft.core.trashProject
import io.github.artcraftandroid.capcraft.core.duplicateProject
import io.github.artcraftandroid.capcraft.core.listProjects
import io.github.artcraftandroid.capcraft.core.renameProject
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.clock
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.MaterialIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.common.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screens' colours: light, as the reference's are, or dark while the phone is in dark mode.
 * The editor is dark either way.
 */
private class HomeColors(
    val dark: Boolean,
    val Background: Color,
    val Ink: Color,
    /** A glyph on an [Ink] square. */
    val OnInk: Color,
    val Muted: Color,
    val Hairline: Color,
    val Tile: Color,
    /** The create cards over the picture. */
    val Card: Color,
    /** Sheets and dialogs over the tabs. */
    val Sheet: Color,
    /** Text fields and Cancel buttons. */
    val Field: Color,
    /** The handle at the top of a sheet. */
    val Grabber: Color,
    /** Tick rings, the clear button and small rules. */
    val Ring: Color,
    /** The card at the end of the recent strip. */
    val More: Color,
    /** The tools pages' wash, top to bottom, and their round buttons. */
    val Wash: List<Color>,
    val Round: Color,
)

private val LightHome = HomeColors(
    dark = false,
    Background = Color.White,
    Ink = Color(0xFF161823),
    OnInk = Color.White,
    Muted = Color(0xFF8A8B91),
    Hairline = Color(0xFFECECEE),
    Tile = Color(0xFFF1F1F3),
    Card = Color(0xC7FFFFFF),
    Sheet = Color.White,
    Field = Color(0xFFF1F2F4),
    Grabber = Color(0xFFDADADD),
    Ring = Color(0xFFC4C4C8),
    More = Color(0xFFC6D1DB),
    Wash = listOf(Color(0xFFA6D3F2), Color(0xFFDDE3E8)),
    Round = Color(0xFFEFF2F5),
)

/** The same pages in the editor's blacks and greys. */
private val DarkHome = HomeColors(
    dark = true,
    Background = Palette.Black,
    Ink = Palette.Text,
    OnInk = Palette.Black,
    Muted = Color(0xFF9A9BA1),
    Hairline = Palette.Divider,
    Tile = Palette.Card,
    Card = Color(0xC71A1A1A),
    Sheet = Palette.Panel,
    Field = Palette.CardPressed,
    Grabber = Palette.DockBack,
    Ring = Color(0xFF5C5C60),
    More = Palette.CardPressed,
    Wash = listOf(Color(0xFF15293A), Color(0xFF0B0B0D)),
    Round = Color(0xFF262A30),
)

private val Home: HomeColors
    @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) DarkHome else LightHome

@Composable
@ReadOnlyComposable
private fun ink(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = Home.Ink) =
    TextStyle(color = color, fontSize = size.sp, fontWeight = weight)

/** A new project's title: the date and a count for the day, as phone editors name new drafts ("1016-02"). */
private fun newProjectName(existing: List<ProjectSummary>): String {
    val day = SimpleDateFormat("MMdd", Locale.US).format(Date())
    val used = existing.mapNotNull { Regex("^$day-(\\d+)$").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
    return "%s-%02d".format(Locale.US, day, (used.maxOrNull() ?: 0) + 1)
}

/** A home shortcut: the tool that opens on the media picked for it. */
private class Shortcut(val id: String, val label: String, val icon: ImageVector, val videoOnly: Boolean = true)

/**
 * The home tools, in the order the reference lists the ones that work on the phone alone. Shoot and
 * record hands over to the phone's camera; Audio tools opens a list of its own.
 */
private val SHORTCUTS = listOf(
    Shortcut("autocut", "AutoCut", LucideIcons.Clapperboard, videoOnly = false),
    Shortcut("retouch", "Retouch", LucideIcons.Smile, videoOnly = false),
    Shortcut("phototools", "Photo tools", LucideIcons.Image, videoOnly = false),
    Shortcut("camera", "Shoot and record", LucideIcons.Camera),
    Shortcut("enhance", "Auto enhance", LucideIcons.WandSparkles, videoOnly = false),
    Shortcut("cover", "Cover maker", LucideIcons.ImagePlay),
    Shortcut("captions", "Auto captions", LucideIcons.Captions),
    Shortcut("removebg", "Remove background", LucideIcons.UserRoundX, videoOnly = false),
    Shortcut("speed", "Adjust speed", LucideIcons.Gauge),
    Shortcut("audiotools", "Audio tools", LucideIcons.AudioLines),
)

/** Photo tools' list: each a tool, with what it does in a line. */
private val PHOTO_TOOLS = listOf(
    Triple("collage", "Collage", "Lay two to nine photos or videos out together."),
    Triple("lighting", "Smart lighting", "Brighten a dark photo in one tap."),
    Triple("framecapture", "Frame capture", "Keep a still from a video as a photo."),
)

/** Audio tools' list: each a tool the picked video opens on, with what it does in a line. */
private val AUDIO_TOOLS = listOf(
    Triple("record", "Record", "Speak over a video as it plays."),
    Triple("noise", "Reduce noise", "Quiet the hiss and hum behind a voice."),
    Triple("enhancevoice", "Enhance voice", "Make a voice clearer and closer."),
    Triple("extract", "Extract audio", "Take a video's sound out as a clip of its own."),
)

@Composable
fun HomeScreen(
    shared: List<Uri>,
    consumeShared: () -> Unit,
    open: (path: String, incoming: List<Uri>, then: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectSummary>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var menuFor by remember { mutableStateOf<ProjectSummary?>(null) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var licences by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    /** 0 Edit, 1 Projects, 2 Me. */
    var tab by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    /** Projects ticked in Select; null when not selecting. */
    var managing by remember { mutableStateOf<Set<String>?>(null) }
    var deletingMany by remember { mutableStateOf(false) }
    /** The trash: projects deleted in the last thirty days. */
    var trash by remember { mutableStateOf<List<ProjectSummary>?>(null) }
    var showingTrash by remember { mutableStateOf(false) }
    var trashMenu by remember { mutableStateOf<ProjectSummary?>(null) }
    var purging by remember { mutableStateOf<ProjectSummary?>(null) }

    // the Projects tab, however it is reached, takes the mark of the project just edited off
    LaunchedEffect(tab) { if (tab == 1) JustEdited.path = null }

    // a project opened here is the one the Projects tab shows on coming back
    fun edit(path: String, incoming: List<Uri>, then: String?) {
        JustEdited.path = path
        open(path, incoming, then)
    }

    LaunchedEffect(refresh) {
        projects = withContext(Threads.commands) { runCatching { listProjects() }.getOrDefault(emptyList()) }
        trash = withContext(Threads.commands) { runCatching { listTrash() }.getOrDefault(emptyList()) }
    }

    // Light bars over the light pages, light icons over the picture at the top of Edit and over the
    // dark pages; dark again for the editor.
    val activity = LocalContext.current as? ComponentActivity
    val overPicture = tab == 0 && !licences && !about
    val dark = Home.dark
    DisposableEffect(activity, overPicture, dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (overPicture || dark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            },
            navigationBarStyle = if (dark) {
                SystemBarStyle.dark(android.graphics.Color.BLACK)
            } else {
                SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE)
            },
        )
        onDispose {
            activity?.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            )
        }
    }

    /** The tool the next picked media is for (a shortcut), or null for a plain new project. */
    var tool by remember { mutableStateOf<String?>(null) }

    fun startProject(uris: List<Uri>, then: String?) {
        if (uris.isEmpty()) return
        scope.launch {
            runCatching {
                withContext(Threads.commands) {
                    val existing = projects ?: runCatching { listProjects() }.getOrDefault(emptyList())
                    createProject(newProjectName(existing), 1080u, 1920u, 30u)
                }
            }.onSuccess { edit(it.path, uris, then) }
                .onFailure { message = it.userMessage }
        }
    }

    // Files shared to the app become a project of their own.
    LaunchedEffect(shared) {
        if (shared.isNotEmpty()) {
            val uris = shared
            consumeShared()
            // shared files open plainly, whatever shortcut was last begun
            startProject(uris, null)
        }
    }

    /** The media picker for a new project; the shortcut belongs to this pick only, a cancelled pick forgets it. */
    fun pick(kinds: io.github.artcraftandroid.capcraft.ui.common.MediaKinds) =
        io.github.artcraftandroid.capcraft.ui.common.MediaPick.open(kinds, multiple = true, onCancel = { tool = null }) { uris ->
            val then = tool
            tool = null
            startProject(uris, then)
        }
    fun newVideo() = pick(io.github.artcraftandroid.capcraft.ui.common.MediaKinds.BOTH)
    fun shortcut(id: String, videoOnly: Boolean) {
        tool = id
        pick(if (videoOnly) io.github.artcraftandroid.capcraft.ui.common.MediaKinds.VIDEO else io.github.artcraftandroid.capcraft.ui.common.MediaKinds.BOTH)
    }

    var audioTools by remember { mutableStateOf(false) }
    var photoTools by remember { mutableStateOf(false) }
    // Shoot and record: the phone's camera records into our cache, and what it records starts a project
    val homeContext = LocalContext.current
    var capture by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { recorded ->
        val uri = capture
        capture = null
        if (recorded && uri != null) startProject(listOf(uri), null)
    }
    fun shoot() {
        val folder = java.io.File(homeContext.cacheDir, "captures").apply { mkdirs() }
        val file = java.io.File(folder, "capture-${System.currentTimeMillis()}.mp4")
        val uri = androidx.core.content.FileProvider.getUriForFile(homeContext, homeContext.packageName + ".files", file)
        capture = uri
        runCatching { camera.launch(uri) }.onFailure {
            capture = null
            message = "There is no camera app to record with"
        }
    }

    androidx.activity.compose.BackHandler(enabled = tab != 0 || managing != null || searching) {
        when {
            managing != null -> managing = null
            searching -> searching = false
            else -> tab = 0
        }
    }

    Box(Modifier.fillMaxSize().background(Home.Background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> EditTab(
                        projects = projects.orEmpty(),
                        onNewVideo = ::newVideo,
                        onCaptions = {
                            // Edit photo: a photo of the phone's, opened as the editor's picture
                            tool = "photo"
                            io.github.artcraftandroid.capcraft.ui.common.MediaPick.open(
                                io.github.artcraftandroid.capcraft.ui.common.MediaKinds.IMAGE,
                                multiple = false,
                                onCancel = { tool = null },
                                // the picker's second tab, as the reference's photo editor has it
                                onCollage = { uris ->
                                    tool = null
                                    startProject(uris, "collage")
                                },
                            ) { uris ->
                                tool = null
                                startProject(uris, "photo")
                            }
                        },
                        onShortcut = {
                            when (it.id) {
                                "camera" -> shoot()
                                "audiotools" -> audioTools = true
                                "phototools" -> photoTools = true
                                else -> shortcut(it.id, it.videoOnly)
                            }
                        },
                        onOpen = { edit(it.path, emptyList(), null) },
                        onAllProjects = { tab = 1 },
                        onSearch = { tab = 1; searching = true },
                    )
                    1 -> ProjectsTab(
                        projects = projects,
                        searching = searching,
                        onSearching = { searching = it },
                        managing = managing,
                        onManaging = { managing = it },
                        onOpen = { edit(it.path, emptyList(), null) },
                        onMenu = { menuFor = it },
                        onCreate = ::newVideo,
                        trash = trash,
                        showingTrash = showingTrash,
                        onShowTrash = { showingTrash = it; managing = null },
                        onTrashMenu = { trashMenu = it },
                    )
                    else -> MeTab(onLicences = { licences = true }, onAbout = { about = true }, onCleared = { message = it })
                }
            }
            val picked = managing
            if (picked != null && tab == 1) {
                SelectBar(
                    all = picked.size == projects.orEmpty().size,
                    count = picked.size,
                    onAll = { managing = if (picked.size == projects.orEmpty().size) emptySet() else projects.orEmpty().map { it.path }.toSet() },
                    onDelete = { deletingMany = true },
                )
            } else {
                BottomBar(tab, latest = projects?.firstOrNull { it.path == JustEdited.path }) {
                    tab = it
                    searching = false
                }
            }
        }

        if (audioTools) {
            ToolsSheet("Audio tools", AUDIO_TOOLS, onClose = { audioTools = false }) { id ->
                audioTools = false
                shortcut(id, videoOnly = true)
            }
        }
        if (photoTools) {
            ToolsSheet("Photo tools", PHOTO_TOOLS, onClose = { photoTools = false }) { id ->
                photoTools = false
                val kinds = when (id) {
                    "collage" -> io.github.artcraftandroid.capcraft.ui.common.MediaKinds.BOTH
                    "lighting" -> io.github.artcraftandroid.capcraft.ui.common.MediaKinds.IMAGE
                    else -> io.github.artcraftandroid.capcraft.ui.common.MediaKinds.VIDEO
                }
                io.github.artcraftandroid.capcraft.ui.common.MediaPick.open(kinds, multiple = id == "collage", onCancel = {}) { uris ->
                    startProject(uris.take(9), id)
                }
            }
        }

        if (deletingMany) {
            val picked = managing.orEmpty()
            HomeConfirm(
                text = "Move ${picked.size} project${if (picked.size == 1) "" else "s"} to the Trash? They're kept there for 30 days.",
                confirm = "Move",
                onDismiss = { deletingMany = false },
            ) {
                deletingMany = false
                managing = null
                scope.launch {
                    withContext(Threads.commands) { picked.forEach { runCatching { trashProject(it) } } }
                    message = "Moved to the Trash"
                    refresh++
                }
            }
        }
        menuFor?.let { p ->
            ProjectSheet(
                p,
                onDismiss = { menuFor = null },
                onRename = { menuFor = null; renaming = p },
                onDuplicate = {
                    menuFor = null
                    scope.launch {
                        runCatching { withContext(Threads.commands) { duplicateProject(p.path, p.name) } }
                            .onFailure { message = it.userMessage }
                        refresh++
                    }
                },
                onDelete = { menuFor = null; deleting = p },
            )
        }
        renaming?.let { p ->
            RenameSheet(p.name, onDismiss = { renaming = null }) { name ->
                renaming = null
                scope.launch {
                    runCatching { withContext(Threads.commands) { renameProject(p.path, name) } }
                        .onFailure { message = it.userMessage }
                    refresh++
                }
            }
        }
        deleting?.let { p ->
            HomeConfirm(
                text = "Move this project to the Trash? It's kept there for 30 days.",
                confirm = "Move",
                onDismiss = { deleting = null },
            ) {
                deleting = null
                scope.launch {
                    runCatching { withContext(Threads.commands) { trashProject(p.path) } }
                        .onSuccess { message = "Moved to the Trash" }
                        .onFailure { message = it.userMessage }
                    refresh++
                }
            }
        }
        trashMenu?.let { p ->
            TrashSheet(
                p,
                onDismiss = { trashMenu = null },
                onRestore = {
                    trashMenu = null
                    scope.launch {
                        runCatching { withContext(Threads.commands) { restoreProject(p.path) } }
                            .onSuccess { message = "Restored" }
                            .onFailure { message = it.userMessage }
                        refresh++
                    }
                },
                onDelete = { trashMenu = null; purging = p },
            )
        }
        purging?.let { p ->
            HomeConfirm(
                text = "Delete this project for good? This can't be undone.",
                confirm = "Delete",
                onDismiss = { purging = null },
            ) {
                purging = null
                scope.launch {
                    runCatching { withContext(Threads.commands) { deleteProject(p.path) } }
                        .onFailure { message = it.userMessage }
                    refresh++
                }
            }
        }
        if (about) AboutScreen(onBack = { about = false })
        if (licences) LicencesScreen(onBack = { licences = false })
        Toast(message) { message = null }
    }
}

// ── Edit ──

@Composable
private fun EditTab(
    projects: List<ProjectSummary>,
    onNewVideo: () -> Unit,
    onCaptions: () -> Unit,
    onShortcut: (Shortcut) -> Unit,
    onOpen: (ProjectSummary) -> Unit,
    onAllProjects: () -> Unit,
    onSearch: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth()) {
            HeroArt(Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onSearch), contentAlignment = Alignment.Center) {
                        Glyph(LucideIcons.Search, Color.White, size = 24.dp, description = "Search projects")
                    }
                }
                Spacer(Modifier.height(176.dp))
                Column(Modifier.padding(horizontal = 16.dp).clickable(onClick = onNewVideo)) {
                    Text("Ready when you are", style = ink(14f, color = Color.White.copy(alpha = 0.92f)))
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Make your next video", style = ink(21f, FontWeight.Bold, Color.White))
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                            Glyph(LucideIcons.ChevronRight, Color.White, size = 14.dp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CreateCard("New video", Modifier.weight(1.7f), onNewVideo) {
                        Glyph(LucideIcons.Plus, Home.OnInk, size = 18.dp)
                    }
                    CreateCard("Edit photo", Modifier.weight(1f), onCaptions) {
                        Glyph(LucideIcons.Image, Home.OnInk, size = 16.dp)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        if (projects.isNotEmpty()) {
            // the recent projects scroll under a narrow card pinned at the right that opens them all,
            // as the reference's strip
            Box(Modifier.fillMaxWidth()) {
                LazyRow(
                    contentPadding = PaddingValues(start = 16.dp, end = 70.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(projects.take(12), key = { it.path }) { p -> StripTile(p) { onOpen(p) } }
                }
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(46.dp, 70.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Home.More).clickable(onClick = onAllProjects),
                    contentAlignment = Alignment.Center,
                ) { Glyph(LucideIcons.ChevronRight, Color.White, size = 24.dp, description = "All projects") }
            }
            Spacer(Modifier.height(12.dp))
        }
        SHORTCUTS.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                row.forEach { s ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onShortcut(s) }.padding(vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Glyph(s.icon, Home.Ink, size = 26.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(s.label, style = ink(13f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Audio tools: a frosted page over Home, its tools listed at the foot with a line each, and a round
 * button to close it, as the reference shows its own.
 */
@Composable
private fun ToolsSheet(title: String, tools: List<Triple<String, String, String>>, onClose: () -> Unit, onTool: (String) -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(Home.Wash))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(title, style = ink(22f, FontWeight.Bold))
            Spacer(Modifier.height(18.dp))
            tools.forEach { (id, name, line) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onTool(id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Home.Round), contentAlignment = Alignment.Center) {
                        Glyph(
                            when (id) {
                                "record" -> LucideIcons.Mic
                                "noise" -> LucideIcons.AudioWaveform
                                "enhancevoice" -> LucideIcons.MicVocal
                                "collage" -> LucideIcons.LayoutTemplate
                                "lighting" -> LucideIcons.Lightbulb
                                "framecapture" -> LucideIcons.ImagePlay
                                else -> LucideIcons.AudioLines
                            },
                            Home.Ink,
                            size = 22.dp,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(name, style = ink(16f, FontWeight.Medium))
                        Text(line, style = ink(13f, color = Home.Muted))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Home.Round).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Glyph(LucideIcons.X, Home.Ink, size = 20.dp, description = "Close") }
        }
    }
}

/**
 * The picture at the top of Edit, our own: a cyan-to-violet wash with a few tilted frames, as of
 * clips on a timeline, fading to the page's colour at the bottom.
 */
@Composable
private fun HeroArt(modifier: Modifier) {
    val page = Home.Background
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.linearGradient(listOf(Color(0xFF27B9D9), Color(0xFF5C9CF0), Color(0xFF8B7CF2)), Offset.Zero, Offset(w, h * 0.8f)))
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFFFFF), Color.Transparent), Offset(w * 0.85f, h * 0.12f), w * 0.5f), w * 0.5f, Offset(w * 0.85f, h * 0.12f))
        drawCircle(Brush.radialGradient(listOf(Color(0x40FFE4F2), Color.Transparent), Offset(w * 0.1f, h * 0.45f), w * 0.45f), w * 0.45f, Offset(w * 0.1f, h * 0.45f))
        // a strip of frames running up the picture
        rotate(-14f, Offset(w * 0.6f, h * 0.3f)) {
            val fw = w * 0.2f
            val fh = fw * 0.62f
            for (row in 0 until 2) {
                for (i in -1 until 6) {
                    val x = i * (fw + w * 0.025f) + row * fw * 0.5f
                    val y = h * 0.16f + row * (fh + w * 0.03f)
                    drawRoundRect(Color.White.copy(alpha = if ((i + row) % 3 == 0) 0.34f else 0.16f), Offset(x, y), Size(fw, fh), CornerRadius(fw * 0.08f))
                }
            }
        }
        // the page coming up under the cards
        drawRect(Brush.verticalGradient(listOf(page.copy(alpha = 0f), page), startY = h * 0.68f, endY = h))
    }
}

/** A create card over the picture: a dark rounded square with the glyph, the label under it. */
@Composable
private fun CreateCard(label: String, modifier: Modifier, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Home.Card).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Home.Ink), contentAlignment = Alignment.Center) { glyph() }
        Spacer(Modifier.height(10.dp))
        Text(label, style = ink(15f, FontWeight.Medium), maxLines = 1)
    }
}

/** A recent project in the strip: its cover, its name over the bottom. */
@Composable
private fun StripTile(p: ProjectSummary, onClick: () -> Unit) {
    val cover = rememberCover(p)
    Box(Modifier.size(70.dp).clip(RoundedCornerShape(6.dp)).background(Home.Tile).clickable(onClick = onClick)) {
        cover?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxWidth().height(28.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
        Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 5.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(if (isPhoto(p)) LucideIcons.Image else LucideIcons.Scissors, Color.White, size = 10.dp)
            Spacer(Modifier.width(3.dp))
            Text(p.name.ifBlank { "Untitled" }, style = ink(10.5f, FontWeight.Medium, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun rememberCover(p: ProjectSummary): android.graphics.Bitmap? {
    val cover by produceState<android.graphics.Bitmap?>(null, p.cover, p.modifiedMs) {
        value = p.cover?.let { path ->
            withContext(Threads.thumbnails) {
                runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull()
            }
        }
    }
    return cover
}

// ── Projects ──

@Composable
private fun ProjectsTab(
    projects: List<ProjectSummary>?,
    searching: Boolean,
    onSearching: (Boolean) -> Unit,
    managing: Set<String>?,
    onManaging: (Set<String>?) -> Unit,
    onOpen: (ProjectSummary) -> Unit,
    onMenu: (ProjectSummary) -> Unit,
    onCreate: () -> Unit,
    trash: List<ProjectSummary>?,
    showingTrash: Boolean,
    onShowTrash: (Boolean) -> Unit,
    onTrashMenu: (ProjectSummary) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // All, Video and Photo, as the reference's chips sort its projects
    var kind by remember { mutableIntStateOf(0) }
    val shown = projects?.let { list ->
        val sorted = when (kind) {
            1 -> list.filter { !isPhoto(it) }
            2 -> list.filter { isPhoto(it) }
            else -> list
        }
        if (searching && query.isNotBlank()) sorted.filter { it.name.contains(query.trim(), ignoreCase = true) } else sorted
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searching) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    Row(
                        Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp)).background(Home.Tile).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(LucideIcons.Search, Home.Muted, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Search projects", style = ink(15f, color = Home.Muted))
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = ink(15f),
                                cursorBrush = SolidColor(Palette.Accent),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                    }
                    Text(
                        "Cancel",
                        style = ink(15f),
                        modifier = Modifier.clickable { query = ""; onSearching(false) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                } else {
                    Text("Projects", style = ink(22f, FontWeight.Bold), modifier = Modifier.weight(1f))
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { onSearching(true) }, contentAlignment = Alignment.Center) {
                        Glyph(LucideIcons.Search, Home.Ink, size = 24.dp, description = "Search projects")
                    }
                }
            }
            // the shelves along the top, as the reference has its Local and Trash
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                listOf("Local" to false, "Trash" to true).forEach { (label, isTrash) ->
                    val on = showingTrash == isTrash
                    Column(
                        Modifier.clickable { onShowTrash(isTrash) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(label, style = ink(15f, if (on) FontWeight.SemiBold else FontWeight.Normal, if (on) Home.Ink else Home.Muted))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.size(28.dp, 2.dp).background(if (on) Home.Ink else Color.Transparent))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Home.Hairline))
            if (showingTrash) {
                Text(
                    "Projects here are deleted for good after 30 days.",
                    style = ink(13f, color = Home.Muted),
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                )
                val bin = trash
                when {
                    bin == null -> Unit
                    bin.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                        Text("The Trash is empty", style = ink(14f, color = Home.Muted))
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(bin, key = { it.path }) { p -> ProjectRow(p, ticked = null, onOpen = { onTrashMenu(p) }, onMenu = { onTrashMenu(p) }) }
                    }
                }
                return@Column
            }
            if (!projects.isNullOrEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Video", "Photo").forEachIndexed { i, name ->
                        Box(
                            Modifier.height(34.dp).clip(RoundedCornerShape(8.dp))
                                .border(if (i == kind) 1.5.dp else 1.dp, if (i == kind) Home.Ink else Home.Hairline, RoundedCornerShape(8.dp))
                                .clickable { kind = i }.padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(name, style = ink(14f, if (i == kind) FontWeight.Medium else FontWeight.Normal)) }
                    }
                }
                val count = shown?.size ?: projects.size
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$count project${if (count == 1) "" else "s"}", style = ink(16f, FontWeight.Medium), modifier = Modifier.weight(1f))
                    Text(
                        if (managing == null) "Select" else "Done",
                        style = ink(15f, color = Home.Muted),
                        modifier = Modifier.clickable { onManaging(if (managing == null) emptySet() else null) }.padding(8.dp),
                    )
                }
            }
            when {
                shown == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
                shown.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                    Text(if (searching && query.isNotBlank()) "No projects found" else "No projects yet", style = ink(14f, color = Home.Muted))
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(shown, key = { it.path }) { p ->
                        ProjectRow(
                            p,
                            ticked = managing?.contains(p.path),
                            onOpen = {
                                val m = managing
                                if (m != null) onManaging(if (p.path in m) m - p.path else m + p.path) else onOpen(p)
                            },
                            onMenu = { onMenu(p) },
                        )
                    }
                }
            }
        }
        if (managing == null && !showingTrash) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 14.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Palette.Accent)
                    .clickable(onClick = onCreate)
                    .padding(start = 14.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Glyph(LucideIcons.Plus, Palette.Accent, size = 16.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text("Create", style = ink(17f, FontWeight.Medium, Color.White))
            }
        }
    }
}

/** Whether [p] was made as a photo (Edit photo, Smart lighting, a collage of photos). */
private fun isPhoto(p: ProjectSummary) = java.io.File(p.path, io.github.artcraftandroid.capcraft.ui.editor.PHOTO_MARK).exists()

@Composable
private fun ProjectRow(p: ProjectSummary, ticked: Boolean?, onOpen: () -> Unit, onMenu: () -> Unit) {
    val cover = rememberCover(p)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(74.dp).clip(RoundedCornerShape(6.dp)).background(Home.Tile), contentAlignment = Alignment.Center) {
            if (cover != null) {
                Image(cover.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Glyph(LucideIcons.Film, Home.Muted, size = 24.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name.ifBlank { "Untitled" }, style = ink(15f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(stamp(p.modifiedMs), style = ink(13f, color = Home.Muted))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // a photo project shows a picture and its size alone, as the reference lists them
                val photo = isPhoto(p)
                Glyph(if (photo) LucideIcons.Image else LucideIcons.Scissors, Home.Muted, size = 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(size(p.sizeBytes), style = ink(13f, color = Home.Muted))
                if (!photo) {
                    Box(Modifier.padding(horizontal = 6.dp).size(1.dp, 10.dp).background(Home.Ring))
                    Text(clock(p.durationUs), style = ink(13f, color = Home.Muted))
                }
            }
        }
        if (ticked == null) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.Ellipsis, Home.Muted, size = 20.dp, description = "More")
            }
        } else {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (ticked) Palette.Accent else Color.Transparent)
                    .then(if (ticked) Modifier else Modifier.border(1.5.dp, Home.Ring, RoundedCornerShape(12.dp))),
                contentAlignment = Alignment.Center,
            ) {
                if (ticked) Glyph(LucideIcons.Check, Color.White, size = 16.dp)
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** When a project was last changed, in the phone's own order of day, month and year. */
private fun stamp(ms: Long): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMddyyyyHHmm").replace(", ", " ")
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.0fGB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0fMB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0fKB".format(bytes / 1024.0)
}

/** In Select, in place of the tab bar: all or none, and delete the ticked. */
@Composable
private fun SelectBar(all: Boolean, count: Int, onAll: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Home.Background).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Home.Hairline))
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (all) "Deselect all" else "Select all", style = ink(15f), modifier = Modifier.clickable(onClick = onAll).padding(8.dp))
            Spacer(Modifier.weight(1f))
            Text(
                "Delete ($count)",
                style = ink(15f, FontWeight.SemiBold, if (count == 0) Home.Muted else Palette.Danger),
                modifier = Modifier.clickable(enabled = count > 0, onClick = onDelete).padding(8.dp),
            )
        }
    }
}

/** The project last opened in the editor this run, until the Projects tab is opened. */
private object JustEdited {
    var path by mutableStateOf<String?>(null)
}

/**
 * The tabs along the bottom. Projects shows the cover of the project just edited until it is opened,
 * and a filled folder while it is the one open, and Edit solid scissors while open, as the
 * reference's bar does.
 */
@Composable
private fun BottomBar(tab: Int, latest: ProjectSummary?, onTab: (Int) -> Unit) {
    val cover = latest?.let { rememberCover(it) }
    Column(Modifier.fillMaxWidth().background(Home.Background).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Home.Hairline))
        Row(Modifier.fillMaxWidth().height(58.dp)) {
            listOf(
                Triple("Edit", LucideIcons.Scissors, 0),
                Triple("Projects", LucideIcons.Folder, 1),
                Triple("Me", LucideIcons.UserRound, 2),
            ).forEach { (label, icon, i) ->
                val on = tab == i
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when {
                        i == 1 && on -> Glyph(MaterialIcons.FolderFill, Home.Ink, size = 25.dp)
                        i == 0 && on -> Glyph(MaterialIcons.ContentCut, Home.Ink, size = 24.dp)
                        i == 1 && cover != null -> Image(
                            cover.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(23.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        else -> Glyph(icon, if (on) Home.Ink else Home.Muted, size = 23.dp)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(label, style = ink(11f, if (on) FontWeight.SemiBold else FontWeight.Normal, if (on) Home.Ink else Home.Muted))
                }
            }
        }
    }
}

// ── Me ──

/** The app's settings: one tall row a setting, its value or a chevron at the right. */
@Composable
private fun MeTab(onLicences: () -> Unit, onAbout: () -> Unit, onCleared: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember { appVersion(context) }
    var cache by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cache = withContext(Threads.commands) { cacheSize(context) } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = ink(22f, FontWeight.Bold))
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SettingRow("Clear cache", cache?.let(::size) ?: "") { clearing = true }
            SettingRow("Licences", null, onLicences)
            SettingRow("About CapCraft", null, onAbout)
            SettingRow("Version", version, null)
        }
    }
    if (clearing) {
        HomeConfirm(
            text = "Clear the cache? Previews and waveforms are made again when needed; projects are kept.",
            confirm = "Clear",
            onDismiss = { clearing = false },
        ) {
            clearing = false
            scope.launch {
                val freed = withContext(Threads.commands) { clearCaches(context) }
                cache = withContext(Threads.commands) { cacheSize(context) }
                onCleared("Cleared ${size(freed)}")
            }
        }
    }
}

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

/** One settings row: the label, then a value or (when it opens something) a chevron. */
@Composable
private fun SettingRow(label: String, value: String?, onClick: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = ink(16f), modifier = Modifier.weight(1f))
            if (!value.isNullOrEmpty()) Text(value, style = ink(15f, color = Home.Muted))
            if (onClick != null && value == null) Glyph(LucideIcons.ChevronRight, Home.Muted, size = 20.dp)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Home.Hairline))
    }
}

/** A page over the tabs, with a back chevron and a title. */
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Home.Background).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
            Text(title, style = ink(17f, FontWeight.SemiBold))
            Box(Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.ChevronLeft, Home.Ink, size = 24.dp, description = "Back")
            }
        }
        content()
    }
}

/** Where the app's source lives. */
private const val SOURCE_URL = "https://github.com/artcraft-android/capcraft-android"

/** What CapCraft is, who made the engine under it, and that it is nobody else's app. */
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    Page("About CapCraft", onBack) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text("CapCraft", style = ink(26f, FontWeight.Bold))
            Spacer(Modifier.height(6.dp))
            Text("Version $version", style = ink(13f, color = Home.Muted))
            Spacer(Modifier.height(28.dp))
            Text(
                "A free video editor that keeps your work on your phone. Built on Concat's engine; free " +
                    "software under the GNU Affero General Public License, version 3 or later.",
                style = ink(13f, color = Home.Muted).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Not affiliated with or endorsed by ByteDance or CapCut.",
                style = ink(13f, color = Home.Muted).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
            Spacer(Modifier.height(28.dp))
            // the source, as the licence asks of every copy handed on
            Text("Source code", style = ink(13f, color = Home.Muted))
            Spacer(Modifier.height(4.dp))
            Text(
                SOURCE_URL.removePrefix("https://"),
                style = ink(14f, FontWeight.SemiBold).copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL))) }
                }.padding(8.dp),
            )
        }
    }
}

/** How much the engine could make again if it were deleted (see [clearCaches]). */
private fun cacheSize(context: android.content.Context): Long {
    fun sizeOf(f: java.io.File): Long = if (f.isDirectory) f.listFiles().orEmpty().sumOf { sizeOf(it) } else f.length()
    val app = context.cacheDir.listFiles().orEmpty().filter { it.name != "exports" }.sumOf { sizeOf(it) }
    val projects = java.io.File(context.filesDir, "projects").listFiles().orEmpty().sumOf { p ->
        java.io.File(p, "cache").listFiles().orEmpty().filterNot(::kept).sumOf(::sizeOf)
    }
    return app + projects
}

/** A file in a project's cache that a clip shows instead of its media (a reversed, enhanced or steadied copy): never cleared. */
private fun kept(f: java.io.File): Boolean = listOf("reverse-", "enhance-", "stabilize-").any { f.name.startsWith(it) } && !f.name.contains(".part.")

/** Deletes what the engine can make again: the app's cache and each project's cache folder. */
private fun clearCaches(context: android.content.Context): Long {
    fun sizeOf(f: java.io.File): Long = if (f.isDirectory) f.listFiles().orEmpty().sumOf { sizeOf(it) } else f.length()
    var freed = 0L
    context.cacheDir.listFiles().orEmpty().filter { it.name != "exports" }.forEach { freed += sizeOf(it); it.deleteRecursively() }
    java.io.File(context.filesDir, "projects").listFiles().orEmpty().forEach { p ->
        java.io.File(p, "cache").listFiles().orEmpty().filterNot(::kept).forEach { freed += sizeOf(it); it.deleteRecursively() }
    }
    return freed
}

/** The licences the app ships with, one after another. */
@Composable
private fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val files = listOf(
        "NOTICE.md" to "CapCraft",
        "LICENSE.txt" to "GNU Affero General Public License",
        "THIRD_PARTY_NOTICES.md" to "Engine third-party notices",
        "LICENSE-lucide.txt" to "Lucide icons",
        "LICENSE-material-symbols.txt" to "Material Symbols",
    )
    var open by remember { mutableStateOf<String?>(null) }
    Page(files.firstOrNull { it.first == open }?.second ?: "Licences", onBack = { if (open != null) open = null else onBack() }) {
        val file = open
        if (file == null) {
            files.forEach { (name, title) ->
                SettingRow(title, null) { open = name }
            }
        } else {
            val text = remember(file) {
                runCatching { context.assets.open("licences/$file").bufferedReader().readText() }.getOrDefault("")
            }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text,
                    style = ink(12f).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
}

// ── Sheets and dialogs ──

/** A dimmed backdrop that closes what sits on it when tapped. */
@Composable
private fun Scrim(onDismiss: () -> Unit, alignment: Alignment, content: @Composable () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)).clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
        ),
        contentAlignment = alignment,
    ) {
        Box(Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}) {
            content()
        }
    }
}

/** How long ago a project was changed, briefly. */
private fun ago(ms: Long): String {
    val minutes = (System.currentTimeMillis() - ms) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d ago"
        else -> stamp(ms).substringBefore(' ')
    }
}

/** A trashed project's menu: put it back, or delete it for good. */
@Composable
private fun TrashSheet(p: ProjectSummary, onDismiss: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit) {
    val cover = rememberCover(p)
    Scrim(onDismiss, Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Home.Sheet)
                .navigationBarsPadding().padding(bottom = 12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Home.Grabber))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(Home.Tile)) {
                    cover?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(p.name.ifBlank { "Untitled" }, style = ink(15f, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(size(p.sizeBytes), style = ink(12f, color = Home.Muted))
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).height(1.dp).background(Home.Hairline))
            SheetRow(LucideIcons.RotateCcw, "Restore", onRestore)
            SheetRow(LucideIcons.Trash2, "Delete for good", onDelete)
        }
    }
}

/** A project's menu: its cover and name at the top, then one row an action. */
@Composable
private fun ProjectSheet(p: ProjectSummary, onDismiss: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val cover = rememberCover(p)
    Scrim(onDismiss, Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Home.Sheet)
                .navigationBarsPadding().padding(bottom = 12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Home.Grabber))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(Home.Tile)) {
                    cover?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(p.name.ifBlank { "Untitled" }, style = ink(15f, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text("${size(p.sizeBytes)} · Updated ${ago(p.modifiedMs)}", style = ink(12f, color = Home.Muted))
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).height(1.dp).background(Home.Hairline))
            SheetRow(LucideIcons.Pencil, "Rename", onRename)
            SheetRow(LucideIcons.Copy, "Duplicate", onDuplicate)
            SheetRow(LucideIcons.Trash2, "Delete", onDelete)
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, Home.Ink, size = 22.dp)
        Spacer(Modifier.width(14.dp))
        Text(label, style = ink(16f))
    }
}

/** Renaming, in a sheet over the keyboard: the name to change and a Confirm button. */
@Composable
private fun RenameSheet(current: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current, androidx.compose.ui.text.TextRange(current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Scrim(onDismiss, Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Home.Sheet)
                .navigationBarsPadding().imePadding().padding(horizontal = 16.dp).padding(bottom = 12.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
                Text("Rename", style = ink(16f, FontWeight.Bold))
                Box(Modifier.align(Alignment.CenterEnd).size(40.dp).clickable(onClick = onDismiss), contentAlignment = Alignment.CenterEnd) {
                    Glyph(LucideIcons.X, Home.Ink, size = 22.dp, description = "Close")
                }
            }
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(6.dp)).background(Home.Field).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.copy(text = it.text.take(50)) },
                    singleLine = true,
                    textStyle = ink(15f),
                    cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                if (text.text.isNotEmpty()) {
                    Box(Modifier.size(34.dp).clickable { text = androidx.compose.ui.text.input.TextFieldValue("") }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(Home.Ring), contentAlignment = Alignment.Center) {
                            Glyph(LucideIcons.X, Color.White, size = 12.dp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            val ok = text.text.isNotBlank()
            Box(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (ok) Palette.Accent else Palette.Accent.copy(alpha = 0.4f))
                    .clickable(enabled = ok) { onDone(text.text.trim()) },
                contentAlignment = Alignment.Center,
            ) { Text("Confirm", style = ink(15f, FontWeight.Medium, Color.White)) }
        }
    }
}

/** A question in a card: Cancel, or the action. */
@Composable
private fun HomeConfirm(text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Scrim(onDismiss, Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 40.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Home.Sheet).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text, style = ink(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(6.dp)).background(Home.Field).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Text("Cancel", style = ink(15f, FontWeight.Medium)) }
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Accent).clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) { Text(confirm, style = ink(15f, FontWeight.Medium, Color.White)) }
            }
        }
    }
}
