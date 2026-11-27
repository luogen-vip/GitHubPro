package com.luogen.ghpro.ui

import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

    fun open(ctx: android.content.Context, file: File) {
        if (!file.exists()) return
        when (file.extension.lowercase()) {
            "apk" -> Downloader.installApk(ctx, file)
            "png", "jpg", "jpeg", "webp", "gif", "bmp",
            "mp4", "mkv", "mov", "webm", "avi", "3gp",
            "txt", "log", "md", "json", "xml", "csv" -> target.value = file
            else -> Downloader.openExternal(ctx, file)
        }
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
    val video = remember(file.absolutePath) {
        VideoView(ctx).apply {
            setVideoPath(file.absolutePath)
            setMediaController(null)
            setOnPreparedListener { mp ->
                mp.isLooping = false
                duration = mp.duration.coerceAtLeast(0)
                prepared = true
                start()
                playing = true
            }
            setOnCompletionListener { playing = false; position = duration }
            setOnErrorListener { _, what, extra ->
                Toast.makeText(ctx, "视频播放失败（$what/$extra）", Toast.LENGTH_SHORT).show()
                prepared = false; playing = false
                true
            }
        }
    }
    DisposableEffect(video) { onDispose { runCatching { video.stopPlayback() } } }
    LaunchedEffect(video) {
        while (true) {
            if (prepared) {
                duration = runCatching { video.duration }.getOrDefault(duration).coerceAtLeast(0)
                if (dragging == null) position = runCatching { video.currentPosition }.getOrDefault(position)
                playing = runCatching { video.isPlaying }.getOrDefault(playing)
            }
            delay(300L)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { video }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = {
                    if (!prepared) return@IconButton
                    if (video.isPlaying) {
                        video.pause(); playing = false; position = runCatching { video.currentPosition }.getOrDefault(position)
                    } else {
                        if (position >= duration && duration > 0) video.seekTo(0)
                        video.start(); playing = true
                    }
                }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(25.dp))
                }
                Text(formatVideoTime(if (dragging != null) (dragging!! * duration).toInt() else position),
                    color = Color.White, fontSize = 11.sp)
                Slider(
                    value = (dragging ?: if (duration > 0) position.toFloat() / duration else 0f).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        val p = dragging
                        if (p != null && duration > 0) {
                            val ms = (p * duration).toInt().coerceIn(0, duration)
                            video.seekTo(ms); position = ms
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
