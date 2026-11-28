package com.luogen.ghpro.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.luogen.ghpro.download.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** 全 App 共用的“打开下载文件”：APK 直接安装，图片/视频/文本在 App 内预览，其余交给系统。 */
object PreviewBus {
    val target = MutableStateFlow<File?>(null)

    /** 返回 false 表示文件已经不存在（删除/取消），调用方要提示且不能打开。 */
    fun open(ctx: android.content.Context, file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val lower = file.name.lowercase()
        if (lower.endsWith(".apk") || lower.contains(".apk.")) {
            Downloader.installApk(ctx, file)
            return true
        }
        when (file.extension.lowercase()) {
            "png", "jpg", "jpeg", "webp", "gif", "bmp",
            "mp4", "mkv", "mov", "webm", "avi", "3gp",
            "txt", "log", "md", "json", "xml", "csv" -> target.value = file
            else -> Downloader.openExternal(ctx, file)
        }
        return true
    }
}

private fun kindOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "webp", "gif", "bmp" -> "image"
    "mp4", "mkv", "mov", "webm", "avi", "3gp" -> "video"
    "txt", "log", "md", "json", "xml", "csv" -> "text"
    else -> "other"
}

@Composable
fun FilePreviewDialog(file: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0D10)) {
            Box(Modifier.fillMaxSize()) {
                when (kindOf(file.name)) {
                    "image" -> ImagePreview(file)
                    "video" -> VideoPreview(file)
                    else -> TextPreview(file)
                }
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, color = Color.White, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(humanSize(file.length()), color = Color.White.copy(alpha = 0.65f), fontSize = 10.sp)
                    }
                    IconButton(onDismiss) { Icon(Icons.Filled.Close, "关闭预览", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(file: File) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(0.7f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize().transformable(transform), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = file,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
        )
    }
}

@Composable
private fun VideoPreview(file: File) {
    val ctx = LocalContext.current
    var prepared by remember(file.absolutePath) { mutableStateOf(false) }
    var playing by remember(file.absolutePath) { mutableStateOf(false) }
    var duration by remember(file.absolutePath) { mutableStateOf(0) }
    var position by remember(file.absolutePath) { mutableStateOf(0) }
    var dragging by remember(file.absolutePath) { mutableStateOf<Float?>(null) }
    var seekTarget by remember(file.absolutePath) { mutableStateOf<Int?>(null) }
    var showCenter by remember(file.absolutePath) { mutableStateOf(true) }

    // Media3 ExoPlayer 的精确 seek：不再像 VideoView 那样落到前一个关键帧，
    // 用户拖到哪就从哪开始，不会先播旧位置再向左跳。
    val exo = remember(file.absolutePath) {
        ExoPlayer.Builder(ctx).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    prepared = true
                    duration = exo.duration.coerceAtLeast(0L).toInt()
                    playing = exo.playWhenReady
                } else if (playbackState == Player.STATE_ENDED) {
                    playing = false
                    position = duration
                    seekTarget = null
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // 缓冲时 isPlaying 会短暂变 false；按钮表达的是“播放意图”，用 playWhenReady 才不会闪成暂停。
                if (exo.playbackState != Player.STATE_ENDED) playing = exo.playWhenReady
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    val target = seekTarget
                    val newPos = newPosition.positionMs.coerceAtLeast(0L).toInt()
                    if (target == null || kotlin.math.abs(newPos - target) <= 800) {
                        position = newPos
                        if (target != null) seekTarget = null
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(ctx, "视频播放失败：${error.errorCodeName}", Toast.LENGTH_SHORT).show()
                prepared = false
                playing = false
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    LaunchedEffect(exo) {
        while (true) {
            if (prepared) {
                duration = exo.duration.coerceAtLeast(0L).toInt()
                if (dragging == null) {
                    val target = seekTarget
                    if (target == null) {
                        position = exo.currentPosition.coerceAtLeast(0L).toInt()
                    } else {
                        val current = exo.currentPosition.toInt()
                        if (kotlin.math.abs(current - target) <= 250) {
                            position = current
                            seekTarget = null
                        } else {
                            // seek 还没完成时滑块停在用户拖到的位置，绝不回跳。
                            position = target
                        }
                    }
                }
            }
            delay(200L)
        }
    }

    LaunchedEffect(playing) {
        if (playing) {
            showCenter = true
            delay(700L)
            showCenter = false
        } else {
            showCenter = true
        }
    }

    fun seekToPosition(ms: Int) {
        if (duration <= 0) return
        val target = ms.coerceIn(0, duration)
        position = target
        seekTarget = target
        exo.seekTo(target.toLong())
        if (exo.playWhenReady) exo.play()
    }

    fun togglePlayPause() {
        if (!prepared) return
        val ended = exo.playbackState == Player.STATE_ENDED || (duration > 0 && position >= duration)
        if (exo.playWhenReady && !ended) {
            exo.pause()
            playing = false
            position = exo.currentPosition.coerceAtLeast(0L).toInt()
        } else {
            if (ended) {
                position = 0
                seekTarget = null
                exo.seekTo(0L)
            }
            exo.play()
            playing = true
            showCenter = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    player = exo
                }
            },
            update = { it.player = exo },
            modifier = Modifier.fillMaxSize()
        )
        // 点视频画面本身播放/暂停，和底部按钮完全同步；底部进度条仍可拖动。
        Box(Modifier.fillMaxSize().padding(bottom = 52.dp).clickable { togglePlayPause() })
        // 暂停后一直显示大播放键；播放/暂停切换时短暂显示两秒状态，避免用户以为画面卡住。
        if (showCenter) {
            Box(
                Modifier.align(Alignment.Center).size(66.dp)
                    .background(Color.Black.copy(alpha = 0.48f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) "正在播放" else "已暂停",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { togglePlayPause() }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(25.dp))
                }
                Text(formatVideoTime(if (dragging != null) (dragging!! * duration).toInt() else position),
                    color = Color.White, fontSize = 11.sp)
                Slider(
                    value = (dragging ?: seekTarget?.let { if (duration > 0) it.toFloat() / duration else 0f }
                        ?: if (duration > 0) position.toFloat() / duration else 0f).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        val p = dragging
                        if (p != null && duration > 0) {
                            seekToPosition((p * duration).toInt())
                        }
                        dragging = null
                    },
                    enabled = prepared && duration > 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 3.dp)
                )
                Text(formatVideoTime(duration), color = Color.White, fontSize = 11.sp)
            }
            Text("点播放/暂停，拖动进度条快进或快退", color = Color.White.copy(alpha = 0.55f),
                fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        }
    }
}

private fun formatVideoTime(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun TextPreview(file: File) {
    var text by remember(file.absolutePath) { mutableStateOf("正在读取…") }
    LaunchedEffect(file.absolutePath) {
        text = withContext(Dispatchers.IO) {
            runCatching { file.readText().take(2_000_000) }.getOrElse { "读取失败：${it.message}" }
        }
    }
    SelectionContainer {
        Text(
            text,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 58.dp, bottom = 20.dp)
        )
    }
}
