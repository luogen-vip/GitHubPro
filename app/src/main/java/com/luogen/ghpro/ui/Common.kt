package com.luogen.ghpro.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Label
import com.luogen.ghpro.data.UserStatus
import com.luogen.ghpro.download.DownloadState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun parseTime(iso: String?): Instant? = try {
    if (iso == null || iso.isBlank()) null else Instant.parse(iso)
} catch (e: Exception) { null }

fun timeAgo(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    // 全 App 统一用日历时间：今天/昨天/前天显示清楚，更早显示具体日期，避免“1 天前”把 47 小时说成 1 天。
    return notifTime(iso)
}

private val NOTIF_HM = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val NOTIF_MD = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val NOTIF_YMD = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

/**
 * 通知时间：今天显示“刚刚 / X 分钟前 / 今天 HH:mm”，跨天一律带日期。
 * 不能只显示 HH:mm：昨天的通知会被误看成今天；到第二天也必须自动从“今天”变成“昨天”。
 */
fun notifTime(iso: String?, nowMs: Long = System.currentTimeMillis()): String {
    val t = parseTime(iso) ?: return "时间未知"
    if (t.toEpochMilli() <= 0L) return "时间未知"
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowMs)
    val at = t.atZone(zone)
    val nowAt = now.atZone(zone)
    val sec = Duration.between(t, now).seconds

    // GitHub/本机时间偶尔有几秒时钟偏差，极小未来时间仍按“刚刚”，不显示负数。
    if (sec in -60L..59L) return "刚刚"

    if (at.toLocalDate() == nowAt.toLocalDate()) {
        if (sec in 60L..3599L) return "${sec / 60} 分钟前"
        return "今天 ${at.format(NOTIF_HM)}"
    }

    val date = at.toLocalDate()
    val today = nowAt.toLocalDate()
    return when {
        date == today.minusDays(1) -> "昨天 ${at.format(NOTIF_HM)}"
        date == today.minusDays(2) -> "前天 ${at.format(NOTIF_HM)}"
        date.year == today.year -> "${date.format(NOTIF_MD)} ${at.format(NOTIF_HM)}"
        else -> "${date.format(NOTIF_YMD)} ${at.format(NOTIF_HM)}"
    }
}

private val STATUS_EMOJI = mapOf(
    ":test_tube:" to "🧪", ":wave:" to "👋", ":house:" to "🏠", ":office:" to "🏢",
    ":rocket:" to "🚀", ":dart:" to "🎯", ":zzz:" to "💤", ":ramen:" to "🍜",
    ":video_game:" to "🎮", ":books:" to "📚", ":hammer_and_wrench:" to "🛠️",
    ":eyes:" to "👀", ":white_check_mark:" to "✅", ":x:" to "❌", ":fire:" to "🔥",
    ":heart:" to "❤️", ":coffee:" to "☕", ":airplane:" to "✈️", ":palm_tree:" to "🌴"
)

fun prettyStatusEmoji(raw: String?): String {
    val v = raw?.trim().orEmpty()
    if (v.isBlank()) return ""
    return STATUS_EMOJI[v] ?: v
}

/** 状态文本；包含“仅某组织可见”，让用户一眼知道别人看不到的原因。 */
fun statusLine(s: UserStatus?, now: Instant = Instant.now()): String? {
    if (s == null) return null
    val expired = s.expiresAt?.let { raw ->
        runCatching { Instant.parse(raw).isBefore(now) }.getOrDefault(false)
    } ?: false
    if (expired) return null
    val emoji = prettyStatusEmoji(s.emoji)
    val msg = s.message?.trim().orEmpty()
    if (emoji.isBlank() && msg.isBlank() && !s.limitedAvailability) return null
    return buildString {
        if (emoji.isNotBlank()) append(emoji)
        if (msg.isNotBlank()) { if (isNotEmpty()) append(" "); append(msg) }
        if (s.limitedAvailability) { if (isNotEmpty()) append(" · "); append("忙碌") }
        val org = s.organizationLogin?.takeIf { it.isNotBlank() }
        if (org != null) { if (isNotEmpty()) append(" · "); append("仅 $org 可见") }
    }
}

/** 头像旁的我的状态；没有设置时返回 null，不占额外一行。 */
fun myStatusLine(): String? = statusLine(UserStatus(
    Cfg.statusEmoji, Cfg.statusMessage, Cfg.statusBusy, Cfg.statusExpiresAt,
    Cfg.statusOrgId, Cfg.statusOrgLogin, Cfg.statusOrgName
))

/**
 * 状态 + 结束倒计时。倒计时按 GitHub 返回的 expiresAt 绝对时间计算，每秒只刷新这一行，
 * 不依赖页面是否重新加载；App 被杀掉再打开也会按当前时间重新算。
 */
@Composable
fun StatusLine(
    status: UserStatus?,
    prefix: String? = null,
    emptyText: String? = null,
    fontSize: TextUnit = 11.sp,
    color: Color = GH.blue,
    showCountdown: Boolean = true,
    modifier: Modifier = Modifier
) {
    val expiresAt = status?.expiresAt
    var nowMs by remember(expiresAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        if (expiresAt.isNullOrBlank()) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            nowMs = now
            // 对齐到下一整秒，避免每秒刷新漂移；只刷新这一小块，不重载页面。
            delay((1000L - now % 1000L).coerceAtLeast(50L))
        }
    }
    val line = status?.let { statusLine(it, Instant.ofEpochMilli(nowMs)) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!prefix.isNullOrBlank()) {
            Text(prefix, fontSize = fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp))
        }
        if (line.isNullOrBlank()) {
            if (!emptyText.isNullOrBlank()) {
                if (!prefix.isNullOrBlank()) Text("  ·  ", fontSize = fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(emptyText, fontSize = fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        } else {
            if (!prefix.isNullOrBlank()) Text("  ·  ", fontSize = fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(line, fontSize = fontSize, color = color, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val remaining = if (showCountdown) statusRemainingText(expiresAt, nowMs) else null
            if (remaining != null) {
                Spacer(Modifier.width(5.dp))
                Text(remaining, fontSize = 9.sp, color = color, maxLines = 1, softWrap = false,
                    modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
    }
}

/** 首页标题行用的独立倒计时徽标；没有状态/已到期时完全不占位。 */
@Composable
fun StatusCountdown(status: UserStatus?, modifier: Modifier = Modifier) {
    val expiresAt = status?.expiresAt
    var nowMs by remember(expiresAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        if (expiresAt.isNullOrBlank()) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            nowMs = now
            delay((1000L - now % 1000L).coerceAtLeast(50L))
        }
    }
    val active = status?.let { statusLine(it, Instant.ofEpochMilli(nowMs)) } != null
    val remaining = if (active) statusRemainingText(expiresAt, nowMs) else null
    if (remaining != null) {
        Text(remaining, fontSize = 9.sp, color = GH.blue, maxLines = 1, softWrap = false,
            modifier = modifier.background(GH.blue.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

/** 永久返回“永久”；有结束时间返回精确到秒的剩余时间。 */
fun statusRemainingText(expiresAt: String?, nowMs: Long = System.currentTimeMillis()): String? {
    if (expiresAt.isNullOrBlank()) return "永久"
    val end = runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull() ?: return null
    val left = end - nowMs
    if (left <= 0) return null
    val total = (left + 999L) / 1000L
    val days = total / 86400L
    val hours = (total % 86400L) / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (days > 0) {
        "剩 ${days}天 ${"%02d".format(hours)}:${"%02d".format(minutes)}:${"%02d".format(seconds)}"
    } else {
        "剩 ${"%02d".format(hours)}:${"%02d".format(minutes)}:${"%02d".format(seconds)}"
    }
}

fun fullTime(iso: String?): String {
    val t = parseTime(iso) ?: return ""
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(t)
}

private val CN_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** GitHub 额度重置时间固定按北京时间显示，不受手机时区/夏令时设置影响。 */
fun fullTimeCn(iso: String?): String {
    val t = parseTime(iso) ?: return ""
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(CN_ZONE).format(t)
}

fun clockCn(): String = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(CN_ZONE).format(Instant.now())

fun durationText(startIso: String?, endIso: String?): String {
    val s = parseTime(startIso) ?: return ""
    val e = parseTime(endIso) ?: Instant.now()
    val d = Duration.between(s, e)
    if (d.isNegative) return ""
    val days = d.toDays()
    val h = d.toHours() % 24
    val m = d.toMinutes() % 60
    val sec = d.seconds % 60
    // GitHub 时间戳只精确到秒；同一个秒内完成的步骤以前会显示“0秒”，现在显示真实的小数秒
    val secText = if (d.seconds < 1) "不到1秒" else String.format("%.1f秒", d.toMillis() / 1000.0)
    return buildString {
        if (days > 0) append("${days}天")
        if (h > 0) append("${h}小时")
        if (m > 0) append("${m}分")
        if (days > 0 || h > 0 || m > 0) {
            if (sec > 0) append("${sec}秒")
        } else {
            append(secText)
        }
    }.ifEmpty { secText }
}

fun copyText(ctx: android.content.Context, text: String, tip: String = "已复制") {
    try {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("文本", text))
    } catch (e: Exception) { }
    android.widget.Toast.makeText(ctx, tip, android.widget.Toast.LENGTH_SHORT).show()
}

/** 下载卡片统一显示：已下载 / 总大小 / 还剩。总大小来自服务器 Content-Length 或 API 的 size，不估算。 */
fun downloadAmountText(d: DownloadState): String {
    val done = humanSize(d.done)
    if (d.total > 0) {
        val left = (d.total - d.done).coerceAtLeast(0L)
        return if (d.done >= d.total) "$done / ${humanSize(d.total)}"
        else "$done / ${humanSize(d.total)} · 还剩 ${humanSize(left)}"
    }
    return "已下载 $done · 总大小未知"
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

fun reasonCn(r: String?): String = when (r) {
    "assign" -> "指派给你"
    "mention" -> "提到了你"
    "team_mention" -> "提到了你所在的团队"
    "review_requested" -> "请你审核"
    "author" -> "你创建的"
    "comment" -> "你参与过"
    "state_change" -> "状态变化"
    "subscribed" -> "仓库动态"
    "manual" -> "手动关注"
    "security_alert" -> "安全提醒"
    "ci_activity" -> "工作流动态"
    "push" -> "代码提交"
    "star" -> "新增 Star"
    "run" -> "工作流动态"
    "run_start" -> "工作流开始"
    "release" -> "新发行版"
    "release_asset" -> "发行版附件更新"
    else -> r ?: ""
}

fun compactNum(n: Int?): String {
    val v = n ?: 0
    return when {
        v >= 10000 -> String.format("%.1f万", v / 10000.0)
        else -> v.toString()
    }
}

@Composable
fun Avatar(url: String?, size: Int = 32, modifier: Modifier = Modifier, name: String? = null) {
    val letter = (name ?: "GitHub").trim().take(1).uppercase().ifBlank { "G" }
    Box(
        modifier.size(size.dp).clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
            .background(GH.blue.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        // GitHub 头像偶尔会 404 / 无头像 / 网络瞬断：底下永远有一层字母兜底，绝不会出现空白圆圈
        Text(letter, fontSize = (size * 0.42f).sp, color = GH.blue, fontWeight = FontWeight.Bold)
        // 不用 Subcompose 的“成功才组合内容”：AsyncImage 一进入组合就发请求，
        // 头像只要服务器有就立刻加载，不需要等用户滑动触发重组。
        val avatarPx = with(androidx.compose.ui.platform.LocalDensity.current) { size.dp.roundToPx() }
        val context = LocalContext.current
        // 关键：请求只在 url / 显示尺寸变化时重建；普通重组不再重新发一次图片请求。
        val req = remember(url, avatarPx) {
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                // 头像只按实际显示尺寸解码，避免设置页滚到头像时解码大图造成一顿一顿。
                .size(avatarPx.coerceAtLeast(1))
                .memoryCacheKey("$url@$avatarPx")
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .networkCachePolicy(coil.request.CachePolicy.ENABLED)
                .build()
        }
        AsyncImage(
            model = req,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun LabelChip(label: Label, onClick: (() -> Unit)? = null) {
    val c = try { Color(android.graphics.Color.parseColor("#" + label.color)) } catch (e: Exception) { MaterialTheme.colorScheme.onSurfaceVariant }
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
fun TinyTag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
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
    if (messageOf(msg) == CANCEL_MESSAGE) { LoadingBox("正在同步最新内容…"); return }
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

/** 列表滑动后出现的「一键回到顶部」小圆钮。 */
@Composable
fun SmallFab(modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.material3.SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) { androidx.compose.material3.Icon(Icons.Filled.ArrowUpward, "回到顶部") }
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
private const val CANCEL_MESSAGE = "正在同步最新内容…"

fun messageOf(msg: String?): String {
    if (msg.isNullOrBlank()) return "出错了"
    if (msg.contains("coroutine scope left the composition") ||
        msg.contains("Job was cancelled") || msg.contains("StandaloneCoroutine was cancelled") ||
        msg.contains("Parent job is Cancelling") || msg.contains("CancellationException") ||
        msg.lowercase().contains("cancel"))
        return CANCEL_MESSAGE
    if (msg.contains("Software caused connection abort") || msg.contains("Connection reset")) return "网络中断了一下，已自动重试，还失败就再点一次"
    return msg
}
