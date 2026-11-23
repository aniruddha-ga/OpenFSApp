package com.openlock.filesystem.ui.preview

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openlock.filesystem.data.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.crypto.SecretKey
import kotlin.time.Duration.Companion.milliseconds

sealed class PreviewContent {
    object Loading : PreviewContent()
    data class ImagePreview(val bitmap: Bitmap) : PreviewContent()
    data class TextPreview(val text: String) : PreviewContent()
    data class AudioPreview(val dataSource: RamDecryptedMediaDataSource) : PreviewContent()
    data class VideoPreview(val dataSource: RamDecryptedMediaDataSource) : PreviewContent()
    data class Fallback(val mimeType: String) : PreviewContent()
    data class Error(val message: String) : PreviewContent()
}

/**
 * Ephemeral In-RAM Decrypting File Media Viewer.
 * - Zero unencrypted bytes are ever written to ROM or disk storage.
 * - Chunks are decrypted on demand in RAM by RamDecryptedMediaDataSource.
 * - Decrypted RAM chunks are purged from memory immediately on exit or lock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    itemId: String,
    vaultRepository: VaultRepository,
    masterKey: SecretKey,
    onNavigateBack: () -> Unit
) {
    var previewState by remember { mutableStateOf<PreviewContent>(PreviewContent.Loading) }
    var fileName by remember { mutableStateOf("Encrypted Stream Preview") }

    // Immediate cleanup of RAM memory on screen exit
    DisposableEffect(itemId) {
        onDispose {
            when (val currentState = previewState) {
                is PreviewContent.ImagePreview -> currentState.bitmap.recycle()
                is PreviewContent.AudioPreview -> currentState.dataSource.close()
                is PreviewContent.VideoPreview -> currentState.dataSource.close()
                else -> {}
            }
        }
    }

    LaunchedEffect(itemId) {
        withContext(Dispatchers.IO) {
            val item = vaultRepository.getItemById(itemId)
            if (item == null) {
                previewState = PreviewContent.Error("File not found.")
                return@withContext
            }

            fileName = item.name.ifBlank { "Encrypted Stream Preview" }
            val mimeType = item.mimeType.lowercase()
            val ext = item.name.substringAfterLast('.', "").lowercase()

            val isImage = mimeType.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
            val isText = mimeType.startsWith("text/") || mimeType in setOf("application/json", "application/xml") || ext in setOf("txt", "json", "xml", "csv", "md", "log", "html", "htm")
            val isAudio = mimeType.startsWith("audio/") || ext in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")
            val isVideo = mimeType.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp")

            try {
                if (isImage) {
                    val stream = vaultRepository.openDecryptedStream(itemId, masterKey)
                    if (stream == null) {
                        previewState = PreviewContent.Error("File not found or unreadable in storage.")
                        return@withContext
                    }
                    stream.use { inputStream ->
                        val bytes = inputStream.readBytes()
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        previewState = if (bitmap != null) {
                            PreviewContent.ImagePreview(bitmap)
                        } else {
                            PreviewContent.Fallback(mimeType)
                        }
                    }
                } else if (isText) {
                    val stream = vaultRepository.openDecryptedStream(itemId, masterKey)
                    if (stream == null) {
                        previewState = PreviewContent.Error("File not found or unreadable in storage.")
                        return@withContext
                    }
                    stream.use { inputStream ->
                        if (item.sizeBytes > 2 * 1024 * 1024) {
                            previewState = PreviewContent.Error("Text file is too large to preview (> 2 MB). Please export to view.")
                        } else {
                            val bytes = inputStream.readBytes()
                            val text = String(bytes, Charsets.UTF_8)
                            previewState = PreviewContent.TextPreview(text)
                        }
                    }
                } else if (isAudio || isVideo) {
                    val physicalFile = vaultRepository.getPhysicalFile(itemId)
                    if (physicalFile != null) {
                        val dataSource = RamDecryptedMediaDataSource(physicalFile, masterKey)
                        previewState = if (isAudio) {
                            PreviewContent.AudioPreview(dataSource)
                        } else {
                            PreviewContent.VideoPreview(dataSource)
                        }
                    } else {
                        previewState = PreviewContent.Error("Encrypted media file not found in storage.")
                    }
                } else {
                    previewState = PreviewContent.Fallback(mimeType)
                }
            } catch (e: Exception) {
                previewState = PreviewContent.Error("Failed to decrypt file: ${e.localizedMessage ?: e.toString()}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            when (val state = previewState) {
                is PreviewContent.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is PreviewContent.ImagePreview -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!state.bitmap.isRecycled) {
                            Image(
                                bitmap = state.bitmap.asImageBitmap(),
                                contentDescription = "Image Preview",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                is PreviewContent.TextPreview -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = state.text,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                is PreviewContent.AudioPreview -> {
                    AudioPlayerView(dataSource = state.dataSource)
                }
                is PreviewContent.VideoPreview -> {
                    VideoPlayerView(dataSource = state.dataSource)
                }
                is PreviewContent.Fallback -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Preview not supported for this format.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Export file to view in external viewer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                is PreviewContent.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPlayerView(dataSource: RamDecryptedMediaDataSource) {
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun applySpeed(speed: Float) {
        currentSpeed = speed
        mediaPlayer?.let { player ->
            try {
                val params = player.playbackParams
                params.speed = speed
                player.playbackParams = params
                if (!isPlaying) {
                    player.pause()
                }
            } catch (e: Exception) {
                Log.e("AudioPlayerView", "Error setting audio speed", e)
            }
        }
    }

    DisposableEffect(dataSource) {
        val player = MediaPlayer().apply {
            try {
                setDataSource(dataSource)
                prepare()
                durationMs = duration
            } catch (e: Exception) {
                Log.e("AudioPlayerView", "Failed to prepare audio player", e)
                errorMessage = "Audio playback error: ${e.localizedMessage ?: e.toString()}"
            }
        }
        mediaPlayer = player

        onDispose {
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let { player ->
                try {
                    if (player.isPlaying) {
                        currentPositionMs = player.currentPosition
                    } else {
                        isPlaying = false
                    }
                } catch (_: Exception) {
                    isPlaying = false
                }
            }
            delay(200L.milliseconds)
        }
    }

    if (errorMessage != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AudioFile,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Encrypted Stream Audio Player",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Progress Seek Bar
            Slider(
                value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f,
                onValueChange = { fraction ->
                    val newPos = (fraction * durationMs).toInt()
                    currentPositionMs = newPos
                    mediaPlayer?.seekTo(newPos)
                },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatTimeMs(currentPositionMs.toLong()), style = MaterialTheme.typography.bodySmall)

                // Speed Selector
                Box {
                    TextButton(onClick = { speedMenuExpanded = true }) {
                        Text(
                            text = if (currentSpeed == 1.0f) "1x" else "${currentSpeed}x",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    DropdownMenu(
                        expanded = speedMenuExpanded,
                        onDismissRequest = { speedMenuExpanded = false }
                    ) {
                        listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (speed == 1.0f) "Normal (1x)" else "${speed}x",
                                        fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    speedMenuExpanded = false
                                    applySpeed(speed)
                                }
                            )
                        }
                    }
                }

                Text(formatTimeMs(durationMs.toLong()), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Play/Pause Button
            IconButton(
                onClick = {
                    mediaPlayer?.let { player ->
                        try {
                            if (player.isPlaying) {
                                player.pause()
                                isPlaying = false
                            } else {
                                player.start()
                                isPlaying = true
                            }
                        } catch (e: Exception) {
                            Log.e("AudioPlayerView", "Error toggling play/pause", e)
                        }
                    }
                },
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun VideoPlayerView(dataSource: RamDecryptedMediaDataSource) {
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showControls by remember { mutableStateOf(true) }
    var lastTouchTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val context = LocalContext.current

    DisposableEffect(isPlaying) {
        val window = (context as? Activity)?.window
        if (isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun resetControlsTimer() {
        showControls = true
        lastTouchTimeMs = System.currentTimeMillis()
    }

    LaunchedEffect(showControls, isPlaying, lastTouchTimeMs) {
        if (showControls && isPlaying) {
            delay(2000L.milliseconds)
            showControls = false
        }
    }

    fun seekToFrame(player: MediaPlayer, targetMs: Int) {
        try {
            player.seekTo(targetMs.toLong(), MediaPlayer.SEEK_CLOSEST)
        } catch (e: Exception) {
            Log.e("VideoPlayerView", "Error seeking to frame $targetMs", e)
        }
    }

    fun applySpeed(speed: Float) {
        resetControlsTimer()
        currentSpeed = speed
        mediaPlayer?.let { player ->
            try {
                if (speed == 0.04f) {
                    player.setVolume(0f, 0f)
                    if (player.isPlaying) player.pause()
                } else {
                    player.setVolume(1f, 1f)
                    val params = player.playbackParams
                    params.speed = speed
                    player.playbackParams = params
                    if (isPlaying && !player.isPlaying) {
                        player.start()
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoPlayerView", "Error setting playback speed", e)
            }
        }
    }

    val videoAspectRatio = remember(videoWidth, videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) {
            videoWidth.toFloat() / videoHeight.toFloat()
        } else {
            16f / 9f
        }
    }

    DisposableEffect(dataSource) {
        onDispose {
            try {
                mediaPlayer?.run {
                    if (isPlaying) stop()
                    release()
                }
            } catch (_: Exception) {}
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying, currentSpeed) {
        if (currentSpeed == 0.04f && isPlaying) {
            mediaPlayer?.let { player ->
                try {
                    player.setVolume(0f, 0f)
                    if (player.isPlaying) player.pause()
                } catch (_: Exception) {}
            }
            var frameCount = (currentPositionMs / 33.3333f).toInt()
            while (isPlaying && currentSpeed == 0.04f) {
                delay(1000L.milliseconds)
                mediaPlayer?.let { player ->
                    try {
                        frameCount++
                        val nextPos = (frameCount * 33.3333f).toInt()
                        if (nextPos <= durationMs) {
                            seekToFrame(player, nextPos)
                            currentPositionMs = nextPos
                        } else {
                            isPlaying = false
                        }
                    } catch (_: Exception) {
                        isPlaying = false
                    }
                }
            }
        } else if (currentSpeed != 0.04f && isPlaying) {
            mediaPlayer?.let { player ->
                try {
                    player.setVolume(1f, 1f)
                    if (!player.isPlaying) player.start()
                } catch (_: Exception) {}
            }
            while (isPlaying && currentSpeed != 0.04f) {
                mediaPlayer?.let { player ->
                    try {
                        if (player.isPlaying) {
                            currentPositionMs = player.currentPosition
                        } else {
                            isPlaying = false
                        }
                    } catch (_: Exception) {
                        isPlaying = false
                    }
                }
                delay(200L.milliseconds)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (showControls) {
                    showControls = false
                } else {
                    resetControlsTimer()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(videoAspectRatio)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    try {
                                        holder.setKeepScreenOn(true)
                                        val player = MediaPlayer().apply {
                                            setDataSource(dataSource)
                                            setDisplay(holder)
                                            setScreenOnWhilePlaying(true)
                                            prepare()
                                        }
                                        durationMs = player.duration
                                        videoWidth = player.videoWidth
                                        videoHeight = player.videoHeight
                                        mediaPlayer = player
                                        player.start()
                                        isPlaying = true
                                    } catch (e: Exception) {
                                        Log.e("VideoPlayerView", "Error preparing video player", e)
                                        errorMessage = "Video playback failed: ${e.localizedMessage ?: e.toString()}"
                                    }
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    try {
                                        mediaPlayer?.run {
                                            if (isPlaying) stop()
                                            release()
                                        }
                                    } catch (_: Exception) {}
                                    mediaPlayer = null
                                    isPlaying = false
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Controls Bar Overlay
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(16.dp)
                ) {
                    Slider(
                        value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f,
                        onValueChange = { fraction ->
                            resetControlsTimer()
                            val newPos = (fraction * durationMs).toInt()
                            currentPositionMs = newPos
                            mediaPlayer?.let { player ->
                                seekToFrame(player, newPos)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(formatTimeMs(currentPositionMs.toLong()), style = MaterialTheme.typography.bodySmall, color = Color.White)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    resetControlsTimer()
                                    mediaPlayer?.let { player ->
                                        try {
                                            if (isPlaying) {
                                                if (player.isPlaying) player.pause()
                                                isPlaying = false
                                            } else {
                                                if (currentSpeed != 0.04f) {
                                                    player.start()
                                                }
                                                isPlaying = true
                                            }
                                        } catch (e: Exception) {
                                            Log.e("VideoPlayerView", "Error toggling play/pause", e)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White
                                )
                            }

                            // Speed Selector Button & Dropdown Menu
                            Box {
                                TextButton(onClick = {
                                    resetControlsTimer()
                                    speedMenuExpanded = true
                                }) {
                                    Text(
                                        text = when (currentSpeed) {
                                            0.04f -> "1 FPS"
                                            1.0f -> "1x"
                                            else -> "${currentSpeed}x"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                DropdownMenu(
                                    expanded = speedMenuExpanded,
                                    onDismissRequest = { speedMenuExpanded = false }
                                ) {
                                    val speeds = listOf(
                                        0.04f to "1 FPS (Muted)",
                                        0.25f to "0.25x",
                                        0.5f to "0.5x",
                                        0.75f to "0.75x",
                                        1.0f to "Normal (1x)",
                                        1.25f to "1.25x",
                                        1.5f to "1.5x",
                                        1.75f to "1.75x",
                                        2.0f to "2.0x"
                                    )
                                    speeds.forEach { (speed, label) ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = label,
                                                    fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                speedMenuExpanded = false
                                                applySpeed(speed)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Text(formatTimeMs(durationMs.toLong()), style = MaterialTheme.typography.bodySmall, color = Color.White)
                    }
                }
            }
        }
    }
}

private fun formatTimeMs(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
