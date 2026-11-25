package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.download.Downloader
import com.luogen.ghpro.sync.WatchService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavController, onThemeChange: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mirrorOn by remember { mutableStateOf(Cfg.useMirror) }
    var mt by remember { mutableStateOf(Cfg.multiThread) }
    var threads by remember { mutableStateOf(Cfg.downThreads) }
    var poll by remember { mutableStateOf(Cfg.pollSeconds) }
    var notify by remember { mutableStateOf(Cfg.notifyEnabled) }
    var auto by remember { mutableStateOf(Cfg.autoRefresh) }
    var theme by remember { mutableStateOf(Cfg.themeMode) }
    var mirrors by remember { mutableStateOf(Cfg.mirrors.toList()) }
    var watch by remember { mutableStateOf(Cfg.watchRepos.toList()) }
    var newMirror by remember { mutableStateOf("") }
    var newWatch by remember { mutableStateOf("") }
    var watching by remember { mutableStateOf(WatchService.running.value) }
    var rate by remember { mutableStateOf<String?>("") }

    LaunchedEffect(Unit) { watching = WatchService.running.value }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(Cfg.avatar, 46)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Cfg.userName ?: Cfg.login ?: "未登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("@" + (Cfg.login ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (Cfg.token.isNullOrBlank()) "未保存令牌" else "已保存访问令牌", fontSize = 11.sp, color = GH.green)
                    }
                    OutlinedButton(onClick = {
                        Cfg.clearAll()
                        nav.navigate("login") { popUpTo(0) { inclusive = true } }
                    }) { Text("退出") }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            SectionTitle("同步与通知")
            SwitchRow("自动刷新（每 20 秒）", auto) { auto = it; Cfg.autoRefresh = it }
            SwitchRow("状态变化通知", notify) { notify = it; Cfg.notifyEnabled = it }
            SliderRow("监听间隔", poll, 8f..120f, "${poll} 秒") {
                poll = it; Cfg.pollSeconds = it
            }
            SwitchRow("后台实时监听（前台服务）", watching) {
                watching = it
                if (it) WatchService.start(ctx) else WatchService.stop(ctx)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("监听仓库（${watch.size}）", fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            if (watch.isEmpty()) Text("还没有监听仓库，打开某个仓库详情会自动加入", fontSize = 11.sp, color = GH.muted)
            watch.forEach { w ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(w, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val s = Cfg.watchRepos; s.remove(w); Cfg.watchRepos = s; watch = s.toList()
                    }) { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = GH.red) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newWatch, { newWatch = it }, Modifier.weight(1f),
                    label = { Text("owner/repo") }, singleLine = true)
                IconButton(onClick = {
                    if (newWatch.contains("/")) {
                        val s = Cfg.watchRepos; s.add(newWatch.trim()); Cfg.watchRepos = s
                        watch = s.toList(); newWatch = ""
                    }
                }) { Icon(Icons.Filled.Add, null, tint = GH.green) }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            SectionTitle("下载加速（镜像）")
            SwitchRow("启用加速镜像", mirrorOn) { mirrorOn = it; Cfg.useMirror = it }
            SwitchRow("多线程分片下载", mt) { mt = it; Cfg.multiThread = it }
            SliderRow("线程数", threads, 1f..16f, "$threads 线程") { threads = it; Cfg.downThreads = it }
            Text("镜像只用于 github.com / raw / codeload 的文件下载；工作流产物与日志走官方 API 原地址。",
                fontSize = 11.sp, color = GH.muted, modifier = Modifier.padding(vertical = 6.dp))
            mirrors.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(m, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val s = Cfg.mirrors; s.remove(m); Cfg.mirrors = s; mirrors = s.toList()
                    }) { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = GH.red) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newMirror, { newMirror = it }, Modifier.weight(1f),
                    label = { Text("https://镜像地址/") }, singleLine = true)
                IconButton(onClick = {
                    if (newMirror.startsWith("http")) {
                        val s = Cfg.mirrors; s.add(newMirror.trim().let { if (it.endsWith("/")) it else "$it/" }); Cfg.mirrors = s
                        mirrors = s.toList(); newMirror = ""
                    }
                }) { Icon(Icons.Filled.Add, null, tint = GH.green) }
            }
            Row {
                TextButton(onClick = {
                    val s = Cfg.mirrors; s.addAll(Cfg.DEFAULT_MIRRORS.split("|")); Cfg.mirrors = s; mirrors = s.toList()
                }) { Text("恢复默认镜像") }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            SectionTitle("外观")
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "跟随系统", 1 to "深色", 2 to "浅色").forEach { (k, v) ->
                    FilterChip(selected = theme == k, onClick = { theme = k; Cfg.themeMode = k; onThemeChange() }, label = { Text(v) })
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            SectionTitle("API 用量")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("剩余额度", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(rate ?: "-", fontSize = 12.sp, color = GH.muted)
                IconButton(onClick = {
                    scope.launch {
                        rate = try {
                            val r = Api.rateLimit().resources?.core
                            "核心 ${r?.remaining}/${r?.limit} · 重启 ${r?.reset?.let { fullTime(java.time.Instant.ofEpochSecond(it).toString()) } ?: ""}"
                        } catch (e: Exception) { e.message }
                    }
                }) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            val dl by Downloader.state.collectAsState()
            if (dl.isNotEmpty()) {
                SectionTitle("本次下载")
                dl.values.sortedByDescending { it.done }.take(8).forEach { d ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        d.path?.let { p -> Downloader.installApk(ctx, java.io.File(p)) }
                    }) {
                        Text(d.fileName, fontSize = 12.sp, maxLines = 1)
                        Text("${d.status} · ${humanSize(d.done)}/${if (d.total > 0) humanSize(d.total) else "?"}" +
                                if (d.speed > 0) " · ${humanSize(d.speed)}/s" else "",
                            fontSize = 10.sp, color = GH.muted)
                        if (d.total > 0) LinearProgressIndicator(progress = { d.percent / 100f }, Modifier.fillMaxWidth().padding(top = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            Text("GitHub Pro 1.0.0 · 由 luogen-vip 打造", fontSize = 11.sp, color = GH.muted)
            Text("数据全部来自 GitHub 官方 REST / GraphQL API", fontSize = 11.sp, color = GH.muted)
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SliderRow(title: String, value: Int, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row {
            Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(label, fontSize = 12.sp, color = GH.muted)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range.start.toInt(), range.endInclusive.toInt())) },
            valueRange = range
        )
    }
}
