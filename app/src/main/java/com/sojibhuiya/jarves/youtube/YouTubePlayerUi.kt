package com.sojibhuiya.jarves.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.ui.compose.PlayerSurface
import coil.compose.AsyncImage
import kotlin.math.roundToInt

/**
 * What the floating player shows. `Choose` is the version list (tap one, or answer by
 * voice); `Playing` is the video with YouTube-style controls.
 */
sealed interface YouTubePlayerUiState {
    data object Hidden : YouTubePlayerUiState
    data class Choose(val videos: List<YouTubeVideo>) : YouTubePlayerUiState
    data object Playing : YouTubePlayerUiState
}

private val CardBackground = Color(0xFF14181D)
private val CardBorder = Color(0xFF2A323B)
private val MutedText = Color(0xFF9AA7B4)
private val YouTubeRed = Color(0xFFFF0000)

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

private fun formatDurationSeconds(seconds: Long?): String =
    if (seconds == null) "" else formatMs(seconds * 1000)

/**
 * The floating player. It sits above every screen (added in the activity's root Box),
 * can be dragged by its handle, and expands to fill the screen.
 *
 * Deliberately NOT YouTube's player: it plays through our own ExoPlayer, so no ads,
 * and it has no Like / Share / Download buttons — only the video, the song and the
 * channel name.
 */
@Composable
fun YouTubePlayerOverlay(
    uiState: YouTubePlayerUiState,
    playerState: YouTubePlayerState,
    player: Player,
    onPick: (YouTubeVideo) -> Unit,
    onClose: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleAudioOnly: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    if (uiState == YouTubePlayerUiState.Hidden) return
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            offsetX = 0f
            offsetY = 0f
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .padding(
                    top = if (expanded) 0.dp else 72.dp,
                    start = if (expanded) 0.dp else 16.dp,
                    end = if (expanded) 0.dp else 16.dp
                )
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (expanded) 0.dp else 16.dp))
                .background(CardBackground)
                .border(1.dp, CardBorder, RoundedCornerShape(if (expanded) 0.dp else 16.dp))
        ) {
            // Drag handle: only this strip moves the card, so the seek slider below never fights it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            offsetX += drag.x
                            offsetY += drag.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(36.dp, 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF4A5560))
                )
            }
            when (uiState) {
                is YouTubePlayerUiState.Choose -> ChooseContent(
                    videos = uiState.videos,
                    onPick = onPick,
                    onClose = onClose
                )
                is YouTubePlayerUiState.Playing -> PlayingContent(
                    playerState = playerState,
                    player = player,
                    expanded = expanded,
                    onExpand = { expanded = !expanded },
                    onClose = onClose,
                    onPause = onPause,
                    onResume = onResume,
                    onSeek = onSeek,
                    onToggleAudioOnly = onToggleAudioOnly,
                    onNext = onNext,
                    onPrevious = onPrevious
                )
                YouTubePlayerUiState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun ChooseContent(
    videos: List<YouTubeVideo>,
    onPick: (YouTubeVideo) -> Unit,
    onClose: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "কোনটা শুনবেন?",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "বন্ধ করুন", tint = Color.White)
        }
    }
    LazyColumn(Modifier.heightIn(max = 340.dp)) {
        items(videos, key = { it.videoId }) { video ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(video) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(112.dp, 63.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0F14))
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        video.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${video.channelTitle} • ${formatDurationSeconds(video.durationSeconds)}",
                        color = MutedText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PlayingContent(
    playerState: YouTubePlayerState,
    player: Player,
    expanded: Boolean,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleAudioOnly: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val video = playerState.video
    var sliderValue by remember(video?.videoId) { mutableStateOf<Float?>(null) }
    val duration = playerState.durationMs.coerceAtLeast(1L)

    PlayerSurface(
        player = player,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (expanded) 4f / 3f else 16f / 9f)
            .background(Color.Black)
    )
    // Red progress bar, like YouTube's.
    Slider(
        value = sliderValue ?: playerState.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
        onValueChange = { sliderValue = it },
        onValueChangeFinished = {
            sliderValue?.let { onSeek(it.toLong()) }
            sliderValue = null
        },
        valueRange = 0f..duration.toFloat(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = SliderDefaults.colors(
            thumbColor = YouTubeRed,
            activeTrackColor = YouTubeRed,
            inactiveTrackColor = Color(0xFF3A444E)
        )
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatMs(playerState.positionMs), color = MutedText, fontSize = 12.sp)
        Text(formatMs(playerState.durationMs), color = MutedText, fontSize = 12.sp)
    }
    if (video != null) {
        Text(
            video.title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)
        )
        Text(
            video.channelTitle,
            color = MutedText,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp)
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "আগেরটা", tint = Color.White)
        }
        IconButton(
            onClick = if (playerState.isPlaying) onPause else onResume,
            modifier = Modifier
                .size(56.dp)
                .background(YouTubeRed, CircleShape)
        ) {
            Icon(
                if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playerState.isPlaying) "থামান" else "চালান",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, contentDescription = "পরেরটা", tint = Color.White)
        }
        IconButton(onClick = onToggleAudioOnly) {
            Text(
                if (playerState.audioOnly) "ভিডিও" else "শুধু অডিও",
                color = if (playerState.audioOnly) YouTubeRed else Color.White,
                fontSize = 12.sp
            )
        }
        IconButton(onClick = onExpand) {
            Icon(
                if (expanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (expanded) "ছোট করুন" else "বড় করুন",
                tint = Color.White
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "বন্ধ করুন", tint = Color.White)
        }
    }
    if (playerState.statusLine.isNotBlank()) {
        Text(
            playerState.statusLine,
            color = MutedText,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
        )
    } else {
        Spacer(Modifier.height(8.dp))
    }
}
