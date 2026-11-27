package com.luogen.ghpro.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.UserStatus
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private val statusPresets = listOf("💬", "🏠", "🏢", "🚀", "🎯", "💤", "🍜", "🎮", "📚", "🛠️", "🧪", "👀", "✅", "🔥", "❤️", "☕")

@Composable
fun MyStatusCard() {
    val ver by Bus.statusPoke.collectAsState()
    var show by remember { mutableStateOf(false) }
    val line = remember(ver) { myStatusLine() }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WavingHand, null, Modifier.size(18.dp), tint = GH.blue)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("我的状态", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (line.isNullOrBlank()) "未设置。设置后立即写回 GitHub，别人也能看到。" else line,
                        fontSize = 11.sp, color = if (line.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else GH.blue)
                }
                TextButton(onClick = { show = true }) { Text(if (line.isNullOrBlank()) "设置" else "修改", fontSize = 12.sp) }
            }
            Text("状态由 GitHub 官方 GraphQL 保存，不是只改本机；外部改动会在本页静默同步，设置/清除后立即更新。",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }

    if (show) {
        StatusDialog(onClose = { show = false })
    }
}

@Composable
private fun StatusDialog(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var emoji by remember { mutableStateOf(prettyStatusEmoji(Cfg.statusEmoji)) }
    var message by remember { mutableStateOf(Cfg.statusMessage.orEmpty()) }
    var busy by remember { mutableStateOf(Cfg.statusBusy) }
    var expireHours by remember {
        val left = Cfg.statusExpiresAt?.let { raw ->
            runCatching { Duration.between(Instant.now(), Instant.parse(raw)).toHours() }.getOrNull()
        }
        mutableStateOf(when {
            left == null -> 0
            left <= 0 -> 0
            left <= 2 -> 1
            left <= 6 -> 4
            left <= 30 -> 24
            else -> 168
        })
    }
    var saving by remember { mutableStateOf(false) }

    val preview = buildString {
        if (emoji.isNotBlank()) append(emoji)
        if (message.isNotBlank()) { if (isNotEmpty()) append(" "); append(message.trim()) }
        if (busy) { if (isNotEmpty()) append(" · "); append("忙碌") }
        if (isEmpty()) append("（状态预览）")
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text("设置 GitHub 状态", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(preview, fontSize = 18.sp, color = GH.blue, modifier = Modifier.padding(bottom = 8.dp))

                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    statusPresets.forEach { e ->
                        FilterChip(selected = emoji == e, onClick = { emoji = e }, label = { Text(e, fontSize = 16.sp) },
                            modifier = Modifier.padding(end = 5.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(emoji, { emoji = it.take(8) }, Modifier.width(86.dp),
                        label = { Text("表情") }, singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(message, { message = it.take(80) }, Modifier.weight(1f),
                        label = { Text("留言（最多 80 字）") }, singleLine = true)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("忙碌 / 有限可用", fontSize = 12.sp)
                        Text("会显示为“Busy”，别人能看见", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = busy, onCheckedChange = { busy = it })
                }
                Text("多久后自动清除", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    listOf(0 to "永久", 1 to "1 小时", 4 to "4 小时", 24 to "今天", 168 to "本周").forEach { (h, label) ->
                        FilterChip(selected = expireHours == h, onClick = { expireHours = h }, label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.padding(end = 5.dp))
                    }
                }
                Text("头像不能在 App 内换：GitHub 没有开放头像上传 API，GraphQL 也没有；请用下面“头像”入口到网页版上传。状态可以真正改。",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                val previous = UserStatus(Cfg.statusEmoji, Cfg.statusMessage, Cfg.statusBusy, Cfg.statusExpiresAt)
                val exp = if (expireHours <= 0) null else Instant.now().plus(Duration.ofHours(expireHours.toLong())).toString()
                // 先本地立即生效，再写服务端；失败会恢复原样，不会假装成功。
                Cfg.saveStatus(UserStatus(emoji, message.trim(), busy, exp))
                Bus.statusChanged()
                scope.launch {
                    try {
                        val real = Api.changeMyStatus(emoji, message.trim(), busy, exp)
                        Cfg.saveStatus(real)
                        Bus.statusChanged()
                        Toast.makeText(ctx, "状态已更新，GitHub 已生效", Toast.LENGTH_SHORT).show()
                        onClose()
                    } catch (e: Exception) {
                        Cfg.saveStatus(previous)
                        Bus.statusChanged()
                        Toast.makeText(ctx, "状态没有改成：${e.message}", Toast.LENGTH_LONG).show()
                    } finally { saving = false }
                }
            }) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            Row {
                if (!Cfg.statusMessage.isNullOrBlank() || !Cfg.statusEmoji.isNullOrBlank() || Cfg.statusBusy) {
                    TextButton(enabled = !saving, onClick = {
                        saving = true
                        val previous = UserStatus(Cfg.statusEmoji, Cfg.statusMessage, Cfg.statusBusy, Cfg.statusExpiresAt)
                        Cfg.saveStatus(null); Bus.statusChanged()
                        scope.launch {
                            try {
                                Api.changeMyStatus("", "", false, null)
                                Cfg.saveStatus(null); Bus.statusChanged()
                                Toast.makeText(ctx, "已清除状态", Toast.LENGTH_SHORT).show()
                                onClose()
                            } catch (e: Exception) {
                                Cfg.saveStatus(previous); Bus.statusChanged()
                                Toast.makeText(ctx, "清除失败：${e.message}", Toast.LENGTH_LONG).show()
                            } finally { saving = false }
                        }
                    }) { Text("清除", fontSize = 12.sp) }
                }
                TextButton(enabled = !saving, onClick = onClose) { Text("取消") }
            }
        }
    )
}

@Composable
fun AvatarCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var avatar by remember { mutableStateOf(Cfg.avatar) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(avatar, 48, name = Cfg.userName ?: Cfg.login)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("头像", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("GitHub 官方没有头像上传 API；只能在网页版换，App 不能假装同步。",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !refreshing, onClick = {
                    refreshing = true
                    scope.launch {
                        try {
                            val me = Api.me()
                            val base = me.avatar_url
                            val bust = if (base.isNullOrBlank()) Cfg.avatar else base + (if (base.contains("?")) "&" else "?") + "cb=" + System.currentTimeMillis()
                            Cfg.userName = me.name ?: Cfg.userName
                            Cfg.avatar = bust
                            avatar = bust
                            UiCache.homeMe = me.copy(avatar_url = bust)
                            Bus.statusChanged()
                            Toast.makeText(ctx, "已从 GitHub 刷新头像", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "刷新失败：${e.message}", Toast.LENGTH_LONG).show()
                        } finally { refreshing = false }
                    }
                }) { Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (refreshing) "刷新中" else "刷新头像", fontSize = 11.sp) }
                TextButton(onClick = { openUrl(ctx, "https://github.com/settings/profile") }) {
                    Icon(Icons.Filled.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("打开网页设置", fontSize = 11.sp)
                }
            }
        }
    }
}
