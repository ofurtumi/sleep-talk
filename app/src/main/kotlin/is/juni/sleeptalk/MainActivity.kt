package `is`.juni.sleeptalk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import `is`.juni.sleeptalk.data.Clip
import `is`.juni.sleeptalk.data.ClipRepository
import `is`.juni.sleeptalk.service.AudioRecorderService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startRecordingService()
        else Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SleepTalkApp(
                onToggleRecording = { toggleRecording() },
                onShare           = { shareClip(it) },
            )
        }
    }

    private fun toggleRecording() {
        if (AudioRecorderService.isRunning) {
            startService(Intent(this, AudioRecorderService::class.java)
                .setAction(AudioRecorderService.ACTION_STOP))
        } else {
            val perms = buildList {
                if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                    add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (perms.isEmpty()) startRecordingService()
            else permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun startRecordingService() = ContextCompat.startForegroundService(
        this, Intent(this, AudioRecorderService::class.java).setAction(AudioRecorderService.ACTION_START)
    )

    private fun shareClip(clip: Clip) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", clip.file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, null
        ))
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

// ── Colours ───────────────────────────────────────────────────────────────────

private val Background = Color(0xFF121212)
private val Surface    = Color(0xFF1E1E1E)
private val Primary    = Color(0xFF5C6BC0)
private val RedColor   = Color(0xFFEF5350)
private val Gold       = Color(0xFFFFCA28)
private val OnBg       = Color(0xFFEEEEEE)
private val SubText    = Color(0xFF9E9E9E)

// ── Player controller ─────────────────────────────────────────────────────────

private class PlayerController {
    var mediaPlayer: MediaPlayer? = null
    var playingPath by mutableStateOf<String?>(null)
    var isPlaying   by mutableStateOf(false)
    var positionMs  by mutableIntStateOf(0)
    var durationMs  by mutableIntStateOf(0)
    var speed       by mutableFloatStateOf(1f)

    fun play(clip: Clip) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(clip.file.absolutePath)
            prepare()
            setPlaybackParams(playbackParams.setSpeed(speed))
            start()
            setOnCompletionListener { reset() }
        }
        playingPath = clip.file.absolutePath
        isPlaying   = true
        durationMs  = mediaPlayer!!.duration
    }

    fun togglePause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) { mp.pause(); isPlaying = false }
        else              { mp.start(); isPlaying = true  }
    }

    fun seekTo(ms: Int) { mediaPlayer?.seekTo(ms); positionMs = ms }

    fun changeSpeed(s: Float) {
        speed = s
        mediaPlayer?.let { mp ->
            val wasPlaying = mp.isPlaying
            mp.playbackParams = mp.playbackParams.setSpeed(s)
            if (!wasPlaying) mp.pause()
        }
    }

    fun reset() {
        mediaPlayer?.release(); mediaPlayer = null
        playingPath = null; isPlaying = false; positionMs = 0; durationMs = 0
    }

    fun tick() { positionMs = mediaPlayer?.currentPosition ?: 0 }
}

// ── Root composable ───────────────────────────────────────────────────────────

@Composable
fun SleepTalkApp(onToggleRecording: () -> Unit, onShare: (Clip) -> Unit) {
    val context   = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var isRecording   by remember { mutableStateOf(AudioRecorderService.isRunning) }
    var clips         by remember { mutableStateOf(ClipRepository.getAll(context)) }
    var favourites    by remember { mutableStateOf(ClipRepository.getFavourites(context)) }
    var labels        by remember { mutableStateOf(ClipRepository.getLabels(context)) }
    var dreams           by remember { mutableStateOf(getDreams(context)) }
    var showSettings     by remember { mutableStateOf(false) }
    var showDeleteOf     by remember { mutableStateOf<Clip?>(null) }
    var showRenameOf     by remember { mutableStateOf<Clip?>(null) }
    var showDreamEditFor by remember { mutableStateOf<String?>(null) }   // raw yyyyMMdd key
    var selectedPaths    by remember { mutableStateOf(setOf<String>()) }
    val inSelectionMode  = selectedPaths.isNotEmpty()
    val player           = remember { PlayerController() }

    // Session stats tracked in the composable — no broadcast needed for the modal
    data class SessionResult(val clipCount: Int, val durationMs: Long)
    var sessionResult    by remember { mutableStateOf<SessionResult?>(null) }
    var sessionStartTime by remember { mutableLongStateOf(0L) }
    var sessionClipCount by remember { mutableIntStateOf(0) }

    // Live amplitude from service (0-100)
    var amplitude by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }

    // Poll playback position
    LaunchedEffect(player.isPlaying) {
        while (player.isPlaying) { player.tick(); delay(200) }
    }

    // Reload on resume (catches clips saved while screen was off)
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                clips      = ClipRepository.getAll(context)
                favourites = ClipRepository.getFavourites(context)
                labels     = ClipRepository.getLabels(context)
                dreams     = getDreams(context)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Service broadcasts
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    AudioRecorderService.BROADCAST_STATE_CHANGED ->
                        isRecording = intent.getBooleanExtra(AudioRecorderService.EXTRA_IS_RUNNING, false)
                    AudioRecorderService.BROADCAST_CLIP_SAVED -> {
                        val path = intent.getStringExtra(AudioRecorderService.EXTRA_CLIP_PATH) ?: return
                        clips = listOf(Clip(File(path))) + clips
                        sessionClipCount++
                    }
                    AudioRecorderService.BROADCAST_AMPLITUDE -> {
                        amplitude = intent.getIntExtra(AudioRecorderService.EXTRA_AMPLITUDE, 0)
                        capturing = intent.getBooleanExtra(AudioRecorderService.EXTRA_CAPTURING, false)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioRecorderService.BROADCAST_STATE_CHANGED)
            addAction(AudioRecorderService.BROADCAST_CLIP_SAVED)
            addAction(AudioRecorderService.BROADCAST_AMPLITUDE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Group clips: starred section first, then session-based groups (most recent first)
    data class SessionGroup(
        val header: String,
        val dateKey: String,        // yyyyMMdd — for dream journal lookup
        val isFirstOfDay: Boolean,  // show dream note row above this session
        val clips: List<Clip>,
    )
    val starredClips: List<Clip>
    val sessionGroups: List<SessionGroup>
    run {
        val (starred, rest) = clips.partition { it.file.absolutePath in favourites }
        starredClips = starred

        // Sort ascending so we can detect gaps between consecutive clips
        val sorted = rest.sortedBy { parseClipTime(it) }
        val rawSessions = mutableListOf<List<Clip>>()
        val cur = mutableListOf<Clip>()
        var lastEndMs = 0L
        for (clip in sorted) {
            val t = parseClipTime(clip)
            if (cur.isNotEmpty() && t - lastEndMs > SESSION_GAP_MS) {
                rawSessions += cur.toList(); cur.clear()
            }
            cur += clip
            lastEndMs = t + clip.durationSecs * 1_000L
        }
        if (cur.isNotEmpty()) rawSessions += cur.toList()

        // Newest session first; within each session, newest clip first — consistent newest→oldest
        val seenDates = mutableSetOf<String>()
        sessionGroups = rawSessions.reversed().map { sessionClips ->
            val dateKey = sessionClips.first().dateKey
            SessionGroup(
                header       = formatSessionHeader(sessionClips),
                dateKey      = dateKey,
                isFirstOfDay = seenDates.add(dateKey),
                clips        = sessionClips.reversed(),   // newest clip first within session
            )
        }
    }

    // Scroll to top whenever a new clip arrives
    val listState = rememberLazyListState()
    LaunchedEffect(clips.size) {
        if (clips.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {

            // Top bar
            Box(Modifier.fillMaxWidth().padding(top = 24.dp, start = 16.dp, end = 8.dp)) {
                Text("SleepTalk", color = OnBg, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center))
                IconButton(onClick = { showSettings = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Default.Settings, "Settings", tint = SubText)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (isRecording) RecBadge()
            else Text("Idle — tap to start a session", color = SubText, fontSize = 13.sp)

            Spacer(Modifier.height(32.dp))

            RecordButton(isRecording) {
                if (isRecording) {
                    // Stopping — snapshot stats and show modal
                    sessionResult = SessionResult(
                        clipCount  = sessionClipCount,
                        durationMs = System.currentTimeMillis() - sessionStartTime,
                    )
                    sessionClipCount = 0
                    amplitude = 0
                    capturing = false
                } else {
                    // Starting — reset counters
                    sessionStartTime  = System.currentTimeMillis()
                    sessionClipCount  = 0
                }
                isRecording = !isRecording
                onToggleRecording()
            }

            if (isRecording) {
                Spacer(Modifier.height(16.dp))
                LevelMeter(amplitude = amplitude, capturing = capturing)
            }

            Spacer(Modifier.height(if (isRecording) 16.dp else 28.dp))

            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Recordings", color = OnBg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = {
                    clips      = ClipRepository.getAll(context)
                    favourites = ClipRepository.getFavourites(context)
                    labels     = ClipRepository.getLabels(context)
                }) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = SubText)
                }
            }

            if (clips.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("No recordings yet.\nStart a session and go to sleep!",
                    color = SubText, fontSize = 14.sp, textAlign = TextAlign.Center)
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 80.dp)) {
                    // ── Starred section ───────────────────────────────────────
                    if (starredClips.isNotEmpty()) {
                        item(key = "header_starred") {
                            SectionHeader("★  Starred", null, null, null)
                        }
                        items(starredClips, key = { it.file.absolutePath }) { clip ->
                            ClipRowInList(clip, player, favourites, labels, selectedPaths,
                                inSelectionMode, context, onShare,
                                onFavChanged  = { favourites    = ClipRepository.getFavourites(context) },
                                onClipsChanged = { clips        = ClipRepository.getAll(context) },
                                onSelected    = { selectedPaths = it },
                                onRename      = { showRenameOf  = clip },
                                onDelete      = { showDeleteOf  = clip },
                            )
                        }
                    }

                    // ── Session groups ────────────────────────────────────────
                    sessionGroups.forEach { group ->
                        item(key = "header_${group.header}") {
                            SectionHeader(
                                header        = group.header,
                                dateKey       = if (group.isFirstOfDay) group.dateKey else null,
                                dreamNote     = if (group.isFirstOfDay) dreams[group.dateKey] else null,
                                onDreamClick  = { showDreamEditFor = group.dateKey },
                            )
                        }
                        items(group.clips, key = { it.file.absolutePath }) { clip ->
                            ClipRowInList(clip, player, favourites, labels, selectedPaths,
                                inSelectionMode, context, onShare,
                                onFavChanged  = { favourites    = ClipRepository.getFavourites(context) },
                                onClipsChanged = { clips        = ClipRepository.getAll(context) },
                                onSelected    = { selectedPaths = it },
                                onRename      = { showRenameOf  = clip },
                                onDelete      = { showDeleteOf  = clip },
                            )
                        }
                    }
                }
            }
        }

        // Multiselect action bar
        if (inSelectionMode) {
            MultiSelectBar(
                count     = selectedPaths.size,
                onCancel  = { selectedPaths = emptySet() },
                onStar    = {
                    val selected = clips.filter { it.file.absolutePath in selectedPaths }
                    val allStarred = selected.all { it.file.absolutePath in favourites }
                    selected.forEach { clip ->
                        val isStarred = clip.file.absolutePath in favourites
                        if (allStarred || !isStarred) ClipRepository.toggleFavourite(context, clip)
                    }
                    favourites    = ClipRepository.getFavourites(context)
                    selectedPaths = emptySet()
                },
                onDelete  = {
                    val toDelete = clips.filter { it.file.absolutePath in selectedPaths }
                    toDelete.forEach { clip ->
                        if (player.playingPath == clip.file.absolutePath) player.reset()
                        ClipRepository.delete(clip)
                    }
                    val deletedPaths = toDelete.map { it.file.absolutePath }.toSet()
                    clips         = clips.filter { it.file.absolutePath !in deletedPaths }
                    selectedPaths = emptySet()
                },
                modifier  = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (showSettings) {
            SettingsDialog(context) { showSettings = false }
        }

        showDeleteOf?.let { clip ->
            AlertDialog(
                onDismissRequest = { showDeleteOf = null },
                text = { Text("Delete this recording?", color = OnBg) },
                confirmButton = {
                    TextButton(onClick = {
                        if (player.playingPath == clip.file.absolutePath) player.reset()
                        ClipRepository.delete(clip)
                        clips = clips.filter { it.file.absolutePath != clip.file.absolutePath }
                        showDeleteOf = null
                    }) { Text("Delete", color = RedColor) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteOf = null }) { Text("Cancel", color = SubText) }
                },
                containerColor = Surface,
            )
        }

        showRenameOf?.let { clip ->
            RenameDialog(
                current = labels[clip.file.name] ?: formatName(clip.name),
                onDismiss = { showRenameOf = null },
                onConfirm = { newLabel ->
                    ClipRepository.setLabel(context, clip, newLabel)
                    labels = ClipRepository.getLabels(context)
                    showRenameOf = null
                }
            )
        }

        showDreamEditFor?.let { dateKey ->
            DreamEditDialog(
                initial   = dreams[dateKey] ?: "",
                dateLabel = formatDateHeader(dateKey),
                onDismiss = { showDreamEditFor = null },
                onSave    = { note ->
                    context.getSharedPreferences("dreams", Context.MODE_PRIVATE)
                        .edit()
                        .let { if (note.isBlank()) it.remove(dateKey) else it.putString(dateKey, note.trim()) }
                        .apply()
                    dreams = getDreams(context)
                    showDreamEditFor = null
                }
            )
        }

        sessionResult?.let { result ->
            SessionEndedDialog(
                clipCount  = result.clipCount,
                durationMs = result.durationMs,
                onDismiss  = {
                    clips         = ClipRepository.getAll(context)
                    favourites    = ClipRepository.getFavourites(context)
                    labels        = ClipRepository.getLabels(context)
                    sessionResult = null
                },
                onSaveDream = { note ->
                    if (note.isNotBlank()) {
                        context.getSharedPreferences("dreams", Context.MODE_PRIVATE)
                            .edit()
                            .putString(
                                java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                                    .format(java.util.Date()),
                                note.trim()
                            )
                            .apply()
                    }
                    clips         = ClipRepository.getAll(context)
                    favourites    = ClipRepository.getFavourites(context)
                    labels        = ClipRepository.getLabels(context)
                    dreams        = getDreams(context)
                    sessionResult = null
                }
            )
        }
    }
}

// ── Section header (session title + optional dream note) ──────────────────────

@Composable
private fun SectionHeader(
    header: String,
    dateKey: String?,
    dreamNote: String?,
    onDreamClick: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            header,
            color      = SubText,
            fontSize   = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = if (dateKey != null) 2.dp else 4.dp),
        )
        if (dateKey != null && onDreamClick != null) {
            DreamNoteRow(note = dreamNote, onClick = onDreamClick)
        }
    }
}

// ── Shared clip-row helper used in the lazy list ──────────────────────────────

@Composable
private fun ClipRowInList(
    clip: Clip,
    player: PlayerController,
    favourites: Set<String>,
    labels: Map<String, String>,
    selectedPaths: Set<String>,
    inSelectionMode: Boolean,
    context: Context,
    onShare: (Clip) -> Unit,
    onFavChanged: () -> Unit,
    onClipsChanged: () -> Unit,
    onSelected: (Set<String>) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val path       = clip.file.absolutePath
    val isActive   = player.playingPath == path
    val isStarred  = path in favourites
    val isSelected = path in selectedPaths
    ClipRow(
        clip            = clip,
        label           = labels[clip.file.name],
        isActive        = isActive,
        isPlaying       = isActive && player.isPlaying,
        isStarred       = isStarred,
        isSelected      = isSelected,
        inSelectionMode = inSelectionMode,
        positionMs      = if (isActive) player.positionMs else 0,
        durationMs      = if (isActive) player.durationMs else clip.durationSecs * 1000,
        speed           = if (isActive) player.speed else 1f,
        onPlayPause     = { if (isActive) player.togglePause() else player.play(clip) },
        onSeek          = { player.seekTo(it) },
        onSetSpeed      = { player.changeSpeed(it) },
        onStar          = { ClipRepository.toggleFavourite(context, clip); onFavChanged() },
        onRename        = onRename,
        onShare         = { onShare(clip) },
        onDelete        = onDelete,
        onToggleSelect  = { onSelected(if (isSelected) selectedPaths - path else selectedPaths + path) },
        onLongPress     = { onSelected(selectedPaths + path) },
    )
}

// ── Clip row ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipRow(
    clip: Clip, label: String?,
    isActive: Boolean, isPlaying: Boolean, isStarred: Boolean,
    isSelected: Boolean, inSelectionMode: Boolean,
    positionMs: Int, durationMs: Int, speed: Float,
    onPlayPause: () -> Unit, onSeek: (Int) -> Unit, onSetSpeed: (Float) -> Unit,
    onStar: () -> Unit, onRename: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
    onToggleSelect: () -> Unit, onLongPress: () -> Unit,
) {
    val bgColor            = if (isSelected) Primary.copy(alpha = 0.15f) else Surface
    val interactionSource  = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(bgColor, RoundedCornerShape(8.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication        = null,          // buttons show their own ripples
                onClick           = { if (inSelectionMode) onToggleSelect() },
                onLongClick       = { onLongPress() },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Checkbox / selection indicator
            if (inSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Primary,
                        uncheckedColor = SubText,
                    ),
                    modifier = Modifier.size(36.dp).padding(end = 4.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label ?: formatName(clip.name),
                    color = OnBg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(formatDuration(clip.durationSecs), color = SubText, fontSize = 12.sp)
            }
            if (!inSelectionMode) {
                IconButton(onClick = onStar) {
                    Icon(
                        if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                        "Star", tint = if (isStarred) Gold else SubText,
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play", tint = Primary,
                    )
                }
                IconButton(onClick = onRename) { Icon(Icons.Default.DriveFileRenameOutline, "Rename", tint = SubText) }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share", tint = SubText) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = RedColor) }
            }
        }

        if (isActive && durationMs > 0 && !inSelectionMode) {
            // Waveform (static thumbnail)
            WaveformBar(clip = clip, modifier = Modifier.fillMaxWidth().height(32.dp))

            Slider(
                value         = positionMs.toFloat(),
                onValueChange = { onSeek(it.toInt()) },
                valueRange    = 0f..durationMs.toFloat(),
                colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary),
                modifier = Modifier.fillMaxWidth().height(24.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(positionMs), color = SubText, fontSize = 11.sp)
                Text(formatMs(durationMs), color = SubText, fontSize = 11.sp)
            }

            // Speed chips
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                    val selected = speed == s
                    Surface(
                        onClick       = { onSetSpeed(s) },
                        shape         = RoundedCornerShape(4.dp),
                        color         = if (selected) Primary else Surface,
                        border        = if (!selected) ButtonDefaults.outlinedButtonBorder else null,
                        modifier      = Modifier.height(24.dp),
                    ) {
                        Text(
                            "${s}×".trimEnd('0').trimEnd('.').let { if (it.endsWith("×")) it else "${it}×" },
                            color    = if (selected) Color.White else SubText,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ── Waveform thumbnail ────────────────────────────────────────────────────────

@Composable
private fun WaveformBar(clip: Clip, modifier: Modifier = Modifier) {
    val waveform by produceState<FloatArray?>(null, clip.file.absolutePath) {
        value = withContext(Dispatchers.IO) { ClipRepository.readWaveform(clip) }
    }
    Canvas(modifier) {
        val bars   = waveform ?: return@Canvas
        val w      = size.width
        val h      = size.height
        val barW   = (w / bars.size) * 0.6f
        val gapW   = (w / bars.size) * 0.4f
        bars.forEachIndexed { i, amp ->
            val barH   = (amp * h).coerceAtLeast(2f)
            val left   = i * (barW + gapW) + gapW / 2
            val top    = (h - barH) / 2
            drawRoundRect(
                color       = Primary.copy(alpha = 0.7f),
                topLeft     = Offset(left, top),
                size        = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}

// ── Rename dialog ─────────────────────────────────────────────────────────────

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text  by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface,
        title = { Text("Rename", color = OnBg) },
        text  = {
            OutlinedTextField(
                value         = text,
                onValueChange = { text = it },
                singleLine    = true,
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedTextColor   = OnBg,
                    unfocusedTextColor = OnBg,
                    focusedBorderColor = Primary,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) {
                Text("Save", color = Primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SubText) } },
    )
}

// ── Session ended dialog ──────────────────────────────────────────────────────

@Composable
private fun SessionEndedDialog(
    clipCount: Int,
    durationMs: Long,
    onDismiss: () -> Unit,
    onSaveDream: (String) -> Unit,
) {
    val hours   = durationMs / 3_600_000
    val mins    = (durationMs % 3_600_000) / 60_000
    val durStr  = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    val clipWord = if (clipCount == 1) "clip" else "clips"
    var dreamText by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Session complete", color = OnBg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Recorded $clipCount $clipWord over $durStr",
                    color = SubText, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Column {
                HorizontalDivider(color = Surface.copy(alpha = 0f))
                Text(
                    "Dream journal",
                    color = OnBg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Jot down anything you remember before it fades.",
                    color = SubText, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value         = dreamText,
                    onValueChange = { dreamText = it },
                    placeholder   = { Text("I was flying over…", color = SubText, fontSize = 13.sp) },
                    minLines      = 4,
                    maxLines      = 8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor    = OnBg,
                        unfocusedTextColor  = OnBg,
                        focusedBorderColor  = Primary,
                        unfocusedBorderColor = SubText.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveDream(dreamText) },
                colors  = ButtonDefaults.buttonColors(containerColor = Primary),
            ) {
                Text(if (dreamText.isBlank()) "Continue" else "Save & continue", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Skip", color = SubText) }
        },
    )
}

// ── Level meter ───────────────────────────────────────────────────────────────

@Composable
private fun LevelMeter(amplitude: Int, capturing: Boolean) {
    val animatedLevel by animateFloatAsState(
        targetValue = amplitude / 100f,
        animationSpec = tween(200),
        label = "level"
    )
    val barColor = if (capturing) RedColor else Primary

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (capturing) "● Capturing" else "Listening…",
            color  = if (capturing) RedColor else SubText,
            fontSize = 11.sp,
            fontWeight = if (capturing) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(200.dp)
                .height(6.dp)
                .background(Surface, RoundedCornerShape(3.dp))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedLevel)
                    .background(barColor, RoundedCornerShape(3.dp))
            )
        }
    }
}

// ── Animations ────────────────────────────────────────────────────────────────

@Composable
private fun RecBadge() {
    val t        = rememberInfiniteTransition(label = "dot")
    val dotAlpha by t.animateFloat(1f, 0f, infiniteRepeatable(tween(600), RepeatMode.Reverse), "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(RedColor.copy(alpha = dotAlpha), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("REC", color = RedColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.15.sp)
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    val t   = rememberInfiniteTransition(label = "pulse")
    val p1  by t.animateFloat(1f, 1.6f, infiniteRepeatable(tween(1400, easing = LinearEasing)), "p1")
    val p1a by t.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1400, easing = LinearEasing)), "p1a")
    val p2  by t.animateFloat(1f, 1.6f, infiniteRepeatable(tween(1400, 700, LinearEasing)), "p2")
    val p2a by t.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1400, 700, LinearEasing)), "p2a")

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        if (isRecording) {
            Box(Modifier.size(200.dp).scale(p1).border(2.dp, RedColor.copy(alpha = p1a), CircleShape))
            Box(Modifier.size(200.dp).scale(p2).border(2.dp, RedColor.copy(alpha = p2a), CircleShape))
        }
        Button(
            onClick  = onClick,
            modifier = Modifier.size(200.dp),
            shape    = CircleShape,
            colors   = ButtonDefaults.buttonColors(containerColor = if (isRecording) RedColor else Primary),
        ) {
            Text(if (isRecording) "Stop" else "Start\nListening",
                color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}

// ── Settings dialog ───────────────────────────────────────────────────────────

@Composable
private fun SettingsDialog(context: Context, onDismiss: () -> Unit) {
    val prefs        = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var threshold    by remember { mutableIntStateOf(prefs.getInt(AudioRecorderService.PREF_THRESHOLD, 600)) }
    var preRoll      by remember { mutableIntStateOf(prefs.getInt(AudioRecorderService.PREF_PRE_ROLL_SECS, 5)) }
    var postSilence  by remember { mutableIntStateOf(prefs.getInt(AudioRecorderService.PREF_POST_SILENCE_SECS, 4)) }
    var activDelay   by remember { mutableIntStateOf(prefs.getInt(AudioRecorderService.PREF_ACTIVATION_DELAY_MINS, 0)) }
    var autoCalib    by remember { mutableStateOf(prefs.getBoolean(AudioRecorderService.PREF_AUTO_CALIBRATE, true)) }
    var autoDelete   by remember { mutableIntStateOf(prefs.getInt(AudioRecorderService.PREF_AUTO_DELETE_DAYS, 0)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface,
        title = { Text("Settings", color = OnBg) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // Auto-calibrate toggle
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-calibrate on start", color = OnBg, fontSize = 14.sp)
                        Text("Samples ambient noise for 5s to set threshold automatically",
                            color = SubText, fontSize = 11.sp)
                    }
                    Switch(checked = autoCalib, onCheckedChange = { autoCalib = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Primary, checkedTrackColor = Primary.copy(0.5f)))
                }

                SettingSlider("Detection sensitivity", "Lower = more sensitive. Default 600.",
                    threshold, 0f..3000f, { it.toString() }) { threshold = it }

                SettingSlider("Pre-roll buffer", "Seconds of audio kept before trigger.",
                    preRoll, 1f..30f, { "${it}s" }) { preRoll = it }

                SettingSlider("Post-silence cutoff", "Seconds of quiet before clip is saved.",
                    postSilence, 1f..30f, { "${it}s" }) { postSilence = it }

                SettingSlider("Activation delay",
                    "Wait this long after starting before listening (e.g. while you read).",
                    activDelay, 0f..60f,
                    { if (it == 0) "Off" else "${it}m" }) { activDelay = it }

                SettingSlider("Auto-delete after",
                    "Clips older than this are deleted when a session starts. 0 = never.",
                    autoDelete, 0f..90f,
                    { if (it == 0) "Off" else "${it}d" }) { autoDelete = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit()
                    .putInt(AudioRecorderService.PREF_THRESHOLD, threshold)
                    .putInt(AudioRecorderService.PREF_PRE_ROLL_SECS, preRoll.coerceAtLeast(1))
                    .putInt(AudioRecorderService.PREF_POST_SILENCE_SECS, postSilence.coerceAtLeast(1))
                    .putInt(AudioRecorderService.PREF_ACTIVATION_DELAY_MINS, activDelay)
                    .putBoolean(AudioRecorderService.PREF_AUTO_CALIBRATE, autoCalib)
                    .putInt(AudioRecorderService.PREF_AUTO_DELETE_DAYS, autoDelete)
                    .apply()
                onDismiss()
            }) { Text("Save", color = Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SubText) } },
    )
}

@Composable
private fun SettingSlider(
    label: String, hint: String, value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    display: (Int) -> String, onValueChange: (Int) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = OnBg, fontSize = 14.sp)
            Text(display(value), color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(hint, color = SubText, fontSize = 11.sp)
        Slider(value = value.toFloat(), onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange,
            colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary))
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

// ── Multiselect action bar ────────────────────────────────────────────────────

@Composable
private fun MultiSelectBar(
    count: Int,
    onCancel: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color  = Surface,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, "Cancel selection", tint = SubText)
            }
            Text(
                "$count selected",
                color = OnBg,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = onStar) {
                Icon(Icons.Default.Star, "Star selected", tint = Gold)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete selected", tint = RedColor)
            }
        }
    }
}

// ── Dream note row ────────────────────────────────────────────────────────────

@Composable
private fun DreamNoteRow(note: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
            .background(Surface, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Nightlight, "Dream", tint = Primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        if (note.isNullOrBlank()) {
            Text("Tap to add a dream journal note…", color = SubText, fontSize = 12.sp)
        } else {
            Text(note, color = OnBg.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Dream edit dialog ─────────────────────────────────────────────────────────

@Composable
private fun DreamEditDialog(
    initial: String, dateLabel: String,
    onDismiss: () -> Unit, onSave: (String) -> Unit,
) {
    var text  by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface,
        title = {
            Column {
                Text("Dream journal", color = OnBg, fontWeight = FontWeight.Bold)
                Text(dateLabel, color = SubText, fontSize = 12.sp)
            }
        },
        text = {
            OutlinedTextField(
                value         = text,
                onValueChange = { text = it },
                placeholder   = { Text("I was flying over…", color = SubText, fontSize = 13.sp) },
                minLines      = 4,
                maxLines      = 10,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor    = OnBg,
                    unfocusedTextColor  = OnBg,
                    focusedBorderColor  = Primary,
                    unfocusedBorderColor = SubText.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save", color = Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SubText) } },
    )
}

private fun getDreams(context: Context): Map<String, String> =
    context.getSharedPreferences("dreams", Context.MODE_PRIVATE).all
        .mapNotNull { (k, v) -> if (v is String) k to v else null }
        .toMap()

// ── Session grouping helpers ──────────────────────────────────────────────────

private const val SESSION_GAP_MS = 30 * 60 * 1_000L   // 30 min between clips = new session

private val CLIP_TIME_FMT = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)

private fun parseClipTime(clip: Clip): Long =
    runCatching { CLIP_TIME_FMT.parse(clip.name)?.time ?: 0L }.getOrDefault(0L)

private fun formatSessionHeader(clips: List<Clip>): String = runCatching {
    val startMs = parseClipTime(clips.first())
    val lastClip = clips.last()
    val endMs   = parseClipTime(lastClip) + lastClip.durationSecs * 1_000L

    val dayFmt  = java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.US)
    val timeFmt = java.text.SimpleDateFormat("h:mm", java.util.Locale.US)
    val ampmFmt = java.text.SimpleDateFormat("a", java.util.Locale.US)

    val startDate = java.util.Date(startMs)
    val endDate   = java.util.Date(endMs)

    val day       = dayFmt.format(startDate)
    val startTime = timeFmt.format(startDate)
    val endTime   = timeFmt.format(endDate)
    val ampm      = ampmFmt.format(endDate)

    "$day  ·  $startTime – $endTime $ampm"
}.getOrDefault("")

private fun formatDateHeader(raw: String): String = runCatching {
    val months = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
    val month  = months[raw.substring(4, 6).toInt() - 1]
    val day    = raw.substring(6, 8).trimStart('0')
    val year   = raw.substring(0, 4)
    "$month $day, $year"
}.getOrDefault(raw)

private fun formatName(raw: String): String = runCatching {
    val months = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
    val month  = months[raw.substring(4, 6).toInt() - 1]
    val day    = raw.substring(6, 8).trimStart('0')
    val hour   = raw.substring(9, 11).toInt()
    val min    = raw.substring(11, 13)
    val ampm   = if (hour < 12) "AM" else "PM"
    val h12    = if (hour % 12 == 0) 12 else hour % 12
    "$month $day  $h12:$min $ampm"
}.getOrDefault(raw)

private fun formatDuration(secs: Int) = "%d:%02d".format(secs / 60, secs % 60)
private fun formatMs(ms: Int)         = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

