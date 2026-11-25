package com.luogen.ghpro.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.luogen.ghpro.data.Label
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun parseTime(iso: String?): Instant? = try {
    if (iso == null || iso.isBlank()) null else Instant.parse(iso)
} catch (e: Exception) { null }

fun timeAgo(iso: String?): String {
    val t = parseTime(iso) ?: return ""
    val now = Instant.now()
    val sec = Duration.between(t, now).seconds
    return when {
        sec < 0 -> "刚刚"
        sec < 60 -> "刚刚"
        sec < 3600 -> "${sec / 60} 分钟前"
        sec < 86400 -> "${sec / 3600} 小时前"
        sec < 86400 * 7 -> "${sec / 86400} 天前"
        sec < 86400 * 30L -> "${sec / (86400 * 7)} 周前"
        sec < 86400 * 365L -> "${sec / (86400 * 30)} 个月前"
        else -> "${sec / (86400 * 365)} 年前"
    }
}

fun fullTime(iso: String?): String {
    val t = parseTime(iso) ?: return ""
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(t)
}

fun durationText(startIso: String?, endIso: String?): String {
    val s = parseTime(startIso) ?: return ""
    val e = parseTime(endIso) ?: Instant.now()
    val d = Duration.between(s, e)
    if (d.isNegative) return ""
    val days = d.toDays()
    val h = d.toHours() % 24
    val m = d.toMinutes() % 60
    val sec = d.seconds % 60
    return buildString {
        if (days > 0) append("${days}天")
        if (h > 0) append("${h}小时")
        if (m > 0) append("${m}分")
        if (days == 0L && h == 0L) append("${sec}秒")
    }.ifEmpty { "0秒" }
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

fun compactNum(n: Int?): String {
    val v = n ?: 0
    return when {
        v >= 10000 -> String.format("%.1f万", v / 10000.0)
        else -> v.toString()
    }
}

@Composable
fun Avatar(url: String?, size: Int = 32, modifier: Modifier = Modifier) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size.dp).clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
fun LabelChip(label: Label, onClick: (() -> Unit)? = null) {
    val c = try { Color(android.graphics.Color.parseColor("#" + label.color)) } catch (e: Exception) { GH.muted }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(c.copy(alpha = 0.18f))
            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label.name, fontSize = 11.sp, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TinyTag(text: String, color: Color = GH.muted) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, fontSize = 10.sp, color = color, maxLines = 1)
    }
}

@Composable
fun StateChip(state: String?, status: String? = null, conclusion: String? = null) {
    val c = GH.statusColor(status, conclusion)
    val label = when {
        status == "in_progress" -> "进行中"
        status == "queued" -> "排队中"
        conclusion == "success" -> "成功"
        conclusion == "failure" -> "失败"
        conclusion == "cancelled" -> "已取消"
        conclusion == "skipped" -> "跳过"
        conclusion == "timed_out" -> "超时"
        conclusion == "startup_failure" -> "启动失败"
        state == "open" -> "打开"
        state == "closed" -> "已关闭"
        state == "merged" -> "已合并"
        else -> status ?: conclusion ?: state ?: ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = c, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LoadingBox(text: String = "加载中…") {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = GH.blue)
            Spacer(Modifier.width(10.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
fun ErrorBox(msg: String?, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("出错了：${messageOf(msg)}", color = GH.red, fontSize = 13.sp)
        if (onRetry != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun KeyValue(label: String, value: String?, onClick: (() -> Unit)? = null) {
    if (value.isNullOrBlank()) return
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 5.dp)
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun Divider1() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), thickness = 1.dp)
}

@Composable
fun MonoText(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        maxLines = maxLines,
        color = MaterialTheme.colorScheme.onSurface
    )
}

fun openUrl(context: android.content.Context, url: String?) {
    if (url.isNullOrBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (e: Exception) { }
}

/** 把内部异常翻译成人话；被组合取消的请求不当作错误吓用户。 */
fun messageOf(msg: String?): String {
    if (msg.isNullOrBlank()) return "出错了"
    if (msg.contains("coroutine scope left the composition")) return "请求被页面切换打断，点重试再搜一次就行"
    if (msg.contains("Software caused connection abort") || msg.contains("Connection reset")) return "网络中断了一下，已自动重试，还失败就再点一次"
    return msg
}
