package com.luogen.ghpro.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import com.luogen.ghpro.data.OrgSummary
import com.luogen.ghpro.data.UserStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private val statusPresets = listOf("💬", "🏠", "🏢", "🚀", "🎯", "💤", "🍜", "🎮", "📚", "🛠️", "🧪", "👀", "✅", "🔥", "❤️", "☕")

/** GitHub 状态只接受一个 emoji（或 :shortcode:）；文字/数字会被服务端忽略，必须在保存前拦截。 */
private fun isStatusEmoji(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty() || t.length > 20) return false
    if (Regex("^:[a-zA-Z0-9_+\\-]+:$").matches(t)) return true
    var hasEmoji = false
    var i = 0
    while (i < t.length) {
        val cp = t.codePointAt(i)
        val next = if (i + Character.charCount(cp) < t.length) t.codePointAt(i + Character.charCount(cp)) else -1
        // 字母绝对不行；数字只有组成 keycap（1️⃣）时才允许。
        if (Character.isLetter(cp)) return false
        if (Character.isDigit(cp) && next != 0xFE0F && next != 0x20E3) return false
        if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp in 0x1F1E6..0x1F1FF || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 ||
            cp in 0x2190..0x21FF || cp in 0x2300..0x23FF) hasEmoji = true
        i += Character.charCount(cp)
    }
    return hasEmoji
}

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
    var emojiErr by remember { mutableStateOf<String?>(null) }
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
    var visibility by remember { mutableStateOf(if (Cfg.statusOrgId.isNullOrBlank()) 0 else 1) }
    var selectedOrg by remember { mutableStateOf(Cfg.statusOrgId) }
    var orgs by remember { mutableStateOf<List<OrgSummary>>(emptyList()) }
    var orgErr by remember { mutableStateOf<String?>(null) }
    var loadingOrgs by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loadingOrgs = true
        runCatching { Api.myOrganizations() }
            .onSuccess {
                orgs = it
                if (selectedOrg.isNullOrBlank()) selectedOrg = it.firstOrNull()?.id
            }
            .onFailure { orgErr = it.message }
        loadingOrgs = false
    }

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
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(preview, fontSize = 18.sp, color = GH.blue, modifier = Modifier.padding(bottom = 8.dp))

                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    statusPresets.forEach { e ->
                        FilterChip(selected = emoji == e, onClick = { emoji = e }, label = { Text(e, fontSize = 16.sp) },
                            modifier = Modifier.padding(end = 5.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(emoji, { emoji = it.take(20); if (emojiErr != null) emojiErr = null }, Modifier.width(96.dp),
                        label = { Text("表情") }, singleLine = true,
                        isError = emojiErr != null)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(message, { message = it.take(80) }, Modifier.weight(1f),
                        label = { Text("留言（最多 80 字）") }, singleLine = true)
                }
                if (emojiErr != null) {
                    Text(emojiErr!!, fontSize = 10.sp, color = GH.red, modifier = Modifier.padding(top = 3.dp))
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
                Text("谁可见", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = visibility == 0, onClick = { visibility = 0 },
                        label = { Text("所有人", fontSize = 11.sp) }, modifier = Modifier.padding(end = 5.dp))
                    FilterChip(selected = visibility == 1, onClick = {
                        visibility = 1
                        if (selectedOrg.isNullOrBlank()) selectedOrg = orgs.firstOrNull()?.id
                    }, label = { Text("仅组织成员", fontSize = 11.sp) })
                }
                if (visibility == 1) {
                    when {
                        loadingOrgs -> Text("正在读取你的组织…", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        orgs.isEmpty() -> Text(
                            if (orgErr.isNullOrBlank()) "你的账号没有加入任何组织，无法使用仅组织可见。"
                            else "读取组织失败：$orgErr",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            orgs.forEach { o ->
                                FilterChip(selected = selectedOrg == o.id, onClick = { selectedOrg = o.id },
                                    label = { Text(o.name?.takeIf { it.isNotBlank() } ?: o.login, fontSize = 11.sp) },
                                    modifier = Modifier.padding(end = 5.dp))
                            }
                        }
                    }
                    Text("GitHub 原生只支持「所有人」或「指定组织成员」；没有“仅某几个人/好友可见”。组织范围会真实写到 GitHub。",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                } else {
                    Text("所有人可见：任何登录 GitHub 的人都能在你的主页看到，和网页版设置一致。",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text("头像不能在 App 内换：GitHub 没有开放头像上传 API，GraphQL 也没有；请用下面“头像”入口到网页版上传。状态可以真正改。",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val e = emoji.trim()
                if (e.isNotBlank() && !isStatusEmoji(e)) {
                    emojiErr = "这里只能填 1 个表情（如 😀），不能填文字或数字；不想填表情就留空"
                    Toast.makeText(ctx, emojiErr!!, Toast.LENGTH_LONG).show()
                    return@TextButton
                }
                emoji = e
                emojiErr = null
                saving = true
                val previous = UserStatus(Cfg.statusEmoji, Cfg.statusMessage, Cfg.statusBusy, Cfg.statusExpiresAt,
                    Cfg.statusOrgId, Cfg.statusOrgLogin, Cfg.statusOrgName)
                val exp = if (expireHours <= 0) null else Instant.now().plus(Duration.ofHours(expireHours.toLong())).toString()
                val org = if (visibility == 1) orgs.firstOrNull { it.id == selectedOrg } else null
                if (visibility == 1 && org == null) {
                    Toast.makeText(ctx, "请选择一个组织，或改回所有人可见", Toast.LENGTH_SHORT).show()
                    return@TextButton
                }
                // 先本地立即生效，再写服务端；失败会恢复原样，不会假装成功。
                Cfg.saveStatus(UserStatus(emoji, message.trim(), busy, exp, org?.id, org?.login, org?.name))
                Bus.statusChanged()
                scope.launch {
                    try {
                        val msg = message.trim()
                        val clearing = emoji.isBlank() && msg.isBlank() && !busy
                        val real = Api.changeMyStatus(emoji, msg, busy, exp, org?.id)
                        if (!clearing && real == null) {
                            throw java.io.IOException("GitHub 没有返回状态对象")
                        }
                        // 不拿 mutation 的返回值直接当成功：再读一次 viewer.status，确认服务端真的保存了。
                        var confirmed = Api.myStatus()
                        if (confirmed == null && !clearing) {
                            delay(700)
                            confirmed = Api.myStatus()
                                ?: Cfg.login?.takeIf { it.isNotBlank() }?.let { runCatching { Api.userStatus(it) }.getOrNull() }
                        }
                        val ok = if (clearing) {
                            confirmed == null || ((confirmed.emoji ?: "").isBlank() && (confirmed.message ?: "").isBlank() && !confirmed.limitedAvailability)
                        } else {
                            confirmed != null && (confirmed.emoji ?: "") == emoji &&
                                (confirmed.message ?: "") == msg &&
                                confirmed.limitedAvailability == busy &&
                                (org == null || confirmed.organizationId == org.id)
                        }
                        if (!ok) throw java.io.IOException("GitHub 返回成功，但重新读取状态不一致，可能未真正生效")
                        Cfg.saveStatus(if (clearing) null else confirmed)
                        Bus.statusChanged()
                        val visible = confirmed?.organizationLogin
                        Toast.makeText(ctx, if (visible.isNullOrBlank()) "状态已更新，并从 GitHub 确认生效" else "状态已更新，仅 $visible 成员可见（已确认）", Toast.LENGTH_SHORT).show()
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
                        val previous = UserStatus(Cfg.statusEmoji, Cfg.statusMessage, Cfg.statusBusy, Cfg.statusExpiresAt,
                            Cfg.statusOrgId, Cfg.statusOrgLogin, Cfg.statusOrgName)
                        Cfg.saveStatus(null); Bus.statusChanged()
                        scope.launch {
                            try {
                                Api.changeMyStatus("", "", false, null)
                                // 清理也要回读确认；不能再出现“提示成功但 GitHub 还有状态”。
                                delay(500)
                                val still = Api.myStatus()
                                val gone = still == null || ((still.emoji ?: "").isBlank() && (still.message ?: "").isBlank() && !still.limitedAvailability)
                                if (!gone) throw java.io.IOException("GitHub 仍返回状态，未真正清除")
                                Cfg.saveStatus(null); Bus.statusChanged()
                                Toast.makeText(ctx, "已清除状态，并从 GitHub 确认", Toast.LENGTH_SHORT).show()
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
