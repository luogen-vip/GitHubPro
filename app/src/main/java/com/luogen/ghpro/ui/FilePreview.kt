package com.luogen.ghpro.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
    val video = remember(file.absolutePath) {
        VideoView(ctx).apply {
            setVideoPath(file.absolutePath)
            val mc = MediaController(ctx)
            mc.setAnchorView(this)
            setMediaController(mc)
            setOnPreparedListener { mp -> mp.isLooping = false; start() }
        }
    }
    DisposableEffect(video) { onDispose { runCatching { video.stopPlayback() } } }
    AndroidView(factory = { video }, modifier = Modifier.fillMaxSize())
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
