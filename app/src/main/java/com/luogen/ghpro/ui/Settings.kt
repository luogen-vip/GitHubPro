package com.luogen.ghpro.ui

import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.UserStatus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.TranslateApi
import com.luogen.ghpro.download.Downloader
import com.luogen.ghpro.sync.CrashReporter
import com.luogen.ghpro.sync.WatchService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavController, onThemeChange: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profileVer by Bus.statusPoke.collectAsState()
    var mirrorOn by remember { mutableStateOf(Cfg.useMirror) }
    var mt by remember { mutableStateOf(Cfg.multiThread) }
    var threads by remember { mutableStateOf(Cfg.downThreads) }
    var poll by remember { mutableStateOf(Cfg.pollSeconds) }
    var notify by remember { mutableStateOf(Cfg.notifyEnabled) }
    var auto by remember { mutableStateOf(Cfg.autoRefresh) }
    var mobileDlWarn by remember { mutableStateOf(Cfg.mobileDownloadWarn) }
    var mobileBrowseWarn by remember { mutableStateOf(Cfg.mobileBrowseWarn) }
    var theme by remember { mutableStateOf(Cfg.themeMode) }
    var transAuto by remember { mutableStateOf(Cfg.translateAuto) }
    var transTarget by remember { mutableStateOf(Cfg.translateTarget) }
    var testingTrans by remember { mutableStateOf(false) }
    var mirrors by remember { mutableStateOf(Cfg.mirrors.toList()) }
    var watch by remember { mutableStateOf(Cfg.watchRepos.toList()) }
    var newMirror by remember { mutableStateOf("") }
    var newWatch by remember { mutableStateOf("") }
    var muteTick by remember { mutableStateOf(0) }
    var showRepoPicker by remember { mutableStateOf(false) }
    var pickerMine by remember { mutableStateOf<List<com.luogen.ghpro.data.Repo>>(emptyList()) }
    var pickerStar by remember { mutableStateOf<List<com.luogen.ghpro.data.Repo>>(emptyList()) }
    var pickerLoading by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf(Cfg.watchEnabled) }
    var rate by remember { mutableStateOf<String?>(null) }
    var mirrorProbe by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var probing by remember { mutableStateOf(false) }
    var rateLoading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (isActive) {
            rateLoading = rate == null
            rate = try {
                val r = Api.rateLimit().resources?.core
                "核心 ${r?.remaining ?: "-"}/${r?.limit ?: "-"} · 重置 ${r?.reset?.let { fullTime(java.time.Instant.ofEpochSecond(it).toString()) } ?: "-"}"
            } catch (e: Exception) { "读取失败：${e.message}" }
            rateLoading = false
            delay(8_000L)
        }
    }
    var safe by remember { mutableStateOf(Cfg.safeMode) }
    var crash by remember { mutableStateOf(CrashReporter.lastCrash(ctx)) }
    var crashHistory by remember { mutableStateOf(CrashReporter.history(ctx)) }
    var crashMsg by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var showCrash by remember { mutableStateOf(false) }
    var showCrashHistory by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { watching = WatchService.running.value }

    // 只在进入设置页时同步状态，之后每 10 秒静默对齐一次；滚动不会触发，不再反复“正在读取”。
    LaunchedEffect(Unit) {
        if (Cfg.token.isNullOrBlank()) return@LaunchedEffect
        runCatching { Api.myStatus() }.onSuccess { Cfg.saveStatus(it); Bus.statusChanged() }
        while (isActive) {
            delay(10_000L)
            runCatching { Api.myStatus() }.onSuccess { fresh ->
                val current = UserStatus(Cfg.statusEmoji, Cfg.statusMessage, Cfg.statusBusy, Cfg.statusExpiresAt)
                if (fresh != current) { Cfg.saveStatus(fresh); Bus.statusChanged() }
            }
        }
    }

    // 重装后监听列表为空时，自动把你的仓库 + 已 Star 的仓库补进来（只补一次，删掉不会复活）。
    LaunchedEffect(Unit) {
        if (watch.isNotEmpty() || Cfg.token.isNullOrBlank()) return@LaunchedEffect
        val names = ArrayList<String>()
        var p = 1
        while (p <= 3) {
            val pg = runCatching { Api.myRepos(p, 100) }.getOrNull() ?: break
            names.addAll(pg.items.map { it.full_name })
            if (!pg.hasNext) break
            p++
        }
        p = 1
        while (p <= 3) {
            val pg = runCatching { Api.starred(p) }.getOrNull() ?: break
            names.addAll(pg.items.map { it.full_name })
            if (!pg.hasNext) break
            p++
        }
        Cfg.seedWatchRepos(names)
        watch = Cfg.watchRepos.toList()
        muteTick++
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(remember(profileVer) { Cfg.avatar }, 46)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Cfg.userName ?: Cfg.login ?: "未登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("@" + (Cfg.login ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (Cfg.token.isNullOrBlank()) "未保存令牌" else "已保存访问令牌", fontSize = 11.sp, color = GH.green)
                    }
                    OutlinedButton(onClick = {
                        Cfg.logoutPreservingSettings()
                        nav.navigate("login") { popUpTo("home") { inclusive = true } }
                    }) { Text("退出") }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item { MyStatusCard(); Spacer(Modifier.height(10.dp)) }
        item { AvatarCard(); Spacer(Modifier.height(14.dp)) }

        item {
            SectionTitle("翻译")
            SwitchRow("自动翻译仓库描述/议题/PR 正文", transAuto) {
                transAuto = it
                Cfg.translateAuto = it
            }
            Text("翻译成", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 3.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TranslateApi.langs.forEach { lang ->
                    FilterChip(selected = transTarget == lang.code, onClick = {
                        transTarget = lang.code
                        Cfg.translateTarget = lang.code
                    }, label = { Text(lang.label, fontSize = 11.sp) })
                }
            }
            Text("自动识别原文语言；原文已经是目标语言时会直接提示，不强行翻译。译文旁可随时切回原文。",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("免费接口顺序：彩云小译 → 有道 → MyMemory，前一个失败自动换下一个；彩云公开接口无需自己申请 key，也不经过本软件服务器。",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !testingTrans, onClick = {
                    testingTrans = true
                    scope.launch {
                        try {
                            val r = TranslateApi.translate("Hello, this is a translation test.", transTarget)
                            Toast.makeText(ctx, "成功（${r.provider}）：${r.text.take(60)}", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "翻译失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                        testingTrans = false
                    }
                }) { Text(if (testingTrans) "测试中…" else "测试翻译接口", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(12.dp))
        }

        item {
            SectionTitle("同步与通知")
            SwitchRow("自动刷新（每 20 秒）", auto) { auto = it; Cfg.autoRefresh = it }
            SwitchRow("状态变化通知", notify) { notify = it; Cfg.notifyEnabled = it }
            SwitchRow("移动数据下载前确认", mobileDlWarn) { mobileDlWarn = it; Cfg.mobileDownloadWarn = it }
            SwitchRow("使用移动数据浏览时提醒", mobileBrowseWarn) { mobileBrowseWarn = it; Cfg.mobileBrowseWarn = it }
            SliderRow("监听间隔", poll, 8f..120f, "${poll} 秒") {
                poll = it; Cfg.pollSeconds = it
            }
            SwitchRow("后台实时监听（前台服务）", watching) {
                watching = it
                Cfg.watchEnabled = it
                if (it) WatchService.start(ctx) else WatchService.stop(ctx)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("监听仓库（${watch.size}）", fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            if (watch.isEmpty()) Text("监听列表为空：正在从你的仓库和已 Star 仓库自动补入，也可下面手动添加…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("通知不等于只看这个列表：GitHub 通知接口覆盖你的所有仓库；这里只是额外重点轮询推送/发行版，默认全部通知，只有手动静音才停。",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 3.dp))
            watch.forEach { w ->
                val muted = remember(w, muteTick) { Cfg.isRepoMuted(w) }
                Row(Modifier.fillMaxWidth()
                    .background(if (muted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f) else Color.Transparent)
                    .padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    // 当前真实状态直接写在行里，不靠会排队的 Toast
                    Text(if (muted) "已静音" else "通知中", fontSize = 10.sp,
                        color = if (muted) GH.muted else GH.green, modifier = Modifier.padding(end = 4.dp))
                    IconButton(onClick = {
                        Cfg.setRepoMuted(w, !muted)
                        muteTick++          // 立刻重组，图标和文字同一帧切换
                    }) {
                        Icon(if (muted) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                            if (muted) "已静音，点一下恢复" else "通知中，点一下静音",
                            Modifier.size(18.dp), tint = if (muted) GH.muted else GH.green)
                    }
                    IconButton(onClick = {
                        val s = Cfg.watchRepos; s.remove(w); Cfg.watchRepos = s; watch = s.toList(); muteTick++
                    }) { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = GH.red) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newWatch, { newWatch = it }, Modifier.weight(1f),
                    label = { Text("owner/repo") }, singleLine = true)
                IconButton(onClick = {
                    val v = newWatch.trim().trimEnd('/')
                    if (v.contains("/") && v.substringBefore("/").isNotBlank() && v.substringAfter("/").isNotBlank()) {
                        val s = Cfg.watchRepos; s.add(v); Cfg.watchRepos = s
                        watch = s.toList(); newWatch = ""; muteTick++
                    } else {
                        // 空输入 / 只写了半截：直接打开真实仓库选择器
                        showRepoPicker = true
                    }
                }) { Icon(Icons.Filled.Add, "添加监听仓库", tint = GH.green) }
            }
            Text("点右边的 + 可以从你的仓库和已 Star 的仓库里直接选；输入框只用于手动填 owner/repo。左侧删除只是移出重点轮询，不会拉黑仓库；要彻底停止这个仓库的通知，点右侧的铃铛静音。",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.height(14.dp))
        }

        item {
            SectionTitle("下载加速（镜像）")
            SwitchRow("启用加速镜像", mirrorOn) { mirrorOn = it; Cfg.useMirror = it }
            SwitchRow("多线程分片下载", mt) { mt = it; Cfg.multiThread = it }
            SliderRow("线程数（默认 64，最高 128）", threads, 1f..128f, "$threads 线程") { threads = it; Cfg.downThreads = it }
            Text("小文件会自动少开线程；多线程被服务器拒绝或连续失败时，会自动降级单线程并断点续传，不需要你手动改。",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            Text("当前内置的 3 个镜像都实测可用；已失效的 gitmirror / moeyy / llkk 会自动清理。镜像只用于 github.com / raw / codeload 的文件下载，工作流产物与日志必须走官方 API 原地址。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            Row {
                TextButton(enabled = !probing, onClick = {
                    scope.launch {
                        probing = true
                        val res = HashMap<String, String>()
                        mirrors.forEach { m ->
                            val (ok, detail) = Downloader.probeMirror(m)
                            res[m] = (if (ok) "✓ " else "✗ ") + detail
                            mirrorProbe = HashMap(res)
                        }
                        probing = false
                    }
                }) { Text(if (probing) "正在检测…" else "检测这些镜像", fontSize = 12.sp) }
            }
            mirrors.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m, fontSize = 12.sp)
                        mirrorProbe[m]?.let {
                            Text(it, fontSize = 9.sp, color = if (it.startsWith("✓")) GH.green else GH.red)
                        }
                    }
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
                }) { Text("恢复可用默认镜像") }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            SectionTitle("诊断")
            SwitchRow("安全模式（简化界面）", safe) {
                safe = it; Cfg.safeMode = it
                crashMsg = if (it) "已开启，重进首页后生效" else "已关闭，重进首页后恢复完整界面"
            }
            Text("掉帧、被杀、进首页后异常退出时，安全模式会关掉头像加载与自动刷新，用来判断问题是否出在图片或轮询上。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("历史异常次数", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${Cfg.crashCount}", fontSize = 12.sp, color = if (Cfg.crashCount > 0) GH.red else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (crash == null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("上次崩溃日志", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("无", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("上次崩溃日志", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if (uploading) "上传中…" else "有（${crash!!.length} 字符）",
                        fontSize = 12.sp, color = GH.yellow,
                        modifier = Modifier.clickable { showCrash = true })
                }
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            uploading = true; crashMsg = null
                            try {
                                val repo = Cfg.crashRepo
                                val parts = repo.split("/")
                                if (parts.size != 2) throw Exception("上报仓库格式应为 owner/repo")
                                val t = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                                Api.createIssue(parts[0], parts[1], "崩溃日志 $t",
                                    "### 手动上报的崩溃日志\n\n\u0060\u0060\u0060\n" + crash!!.take(15000) + "\n\u0060\u0060\u0060\n",
                                    listOf("bug"), null)
                                CrashReporter.clear(ctx)
                                crash = null
                                crashMsg = "已上传到 $repo 的 Issues"
                            } catch (e: Exception) { crashMsg = "上传失败：" + e.message }
                            finally { uploading = false }
                        }
                    }) { Text("上传到 GitHub Issues") }
                    TextButton(onClick = {
                        CrashReporter.clear(ctx); crash = null; crashMsg = "已清除"
                    }) { Text("清除", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            crashMsg?.let { Text(it, fontSize = 11.sp, color = GH.green, modifier = Modifier.padding(vertical = 2.dp)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val historyCount = crashHistory?.split("===== 新崩溃 =====")?.count { it.isNotBlank() } ?: 0
                TextButton(enabled = crashHistory != null, onClick = { showCrashHistory = true }) {
                    Text(if (crashHistory == null) "暂无历史日志" else "查看历史日志（$historyCount 条）", fontSize = 11.sp)
                }
                if (crashHistory != null) TextButton(onClick = {
                    CrashReporter.clearHistory(ctx); crashHistory = null; crashMsg = "历史日志已清空"
                }) { Text("清空历史", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("历史异常次数只在未捕获异常导致 App 退出时 +1；崩溃日志保存在本机，最多保留约 20 万字符，上传/清除上次日志不会影响历史记录。",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
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
            SectionTitle("API 用量 · 每 8 秒自动刷新")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("剩余额度", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(if (rateLoading) "读取中…" else (rate ?: "-"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = {
                    scope.launch {
                        rateLoading = true
                        rate = try {
                            val r = Api.rateLimit().resources?.core
                            "核心 ${r?.remaining ?: "-"}/${r?.limit ?: "-"} · 重置 ${r?.reset?.let { fullTime(java.time.Instant.ofEpochSecond(it).toString()) } ?: "-"}"
                        } catch (e: Exception) { "读取失败：${e.message}" }
                        rateLoading = false
                    }
                }) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            Spacer(Modifier.height(40.dp))
            Text("GitHub Pro 1.0.25 · 由 罗根 打造", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("数据全部来自 GitHub 官方 REST / GraphQL API", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(30.dp))
        }
    }
    if (showCrash && crash != null) CrashDialog(crash!!) { showCrash = false }
    if (showCrashHistory && crashHistory != null) CrashHistoryDialog(crashHistory!!) { showCrashHistory = false }

    LaunchedEffect(showRepoPicker) {
        if (!showRepoPicker) return@LaunchedEffect
        pickerLoading = true
        runCatching { Api.myRepos(1, 100).items }.onSuccess { pickerMine = it }
        runCatching { Api.starred(1).items }.onSuccess { pickerStar = it }
        pickerLoading = false
    }

    if (showRepoPicker) {
        AlertDialog(
            onDismissRequest = { showRepoPicker = false },
            title = { Text("选择要监听的仓库", fontSize = 15.sp) },
            text = {
                Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                    if (pickerLoading) LoadingBox("正在读取你的仓库…")
                    else {
                        val mine = pickerMine.filter { it.full_name !in watch }
                        val star = pickerStar.filter { it.full_name !in watch && it.full_name !in pickerMine.map { p -> p.full_name } }
                        Text("我的仓库（${mine.size}）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (mine.isEmpty()) Text("没有可添加的仓库", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        mine.take(50).forEach { r ->
                            Row(Modifier.fillMaxWidth().clickable {
                                val s = Cfg.watchRepos; s.add(r.full_name); Cfg.watchRepos = s
                                watch = s.toList(); muteTick++
                            }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(r.owner?.avatar_url, 24, name = r.owner?.login ?: r.full_name.substringBefore("/"))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.full_name, fontSize = 12.sp, maxLines = 1)
                                    r.description?.let { Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                                }
                                Icon(Icons.Filled.Add, "添加", Modifier.size(17.dp), tint = GH.green)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("已 Star 的仓库（${star.size}）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (star.isEmpty()) Text("没有额外的 Star 仓库", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        star.take(50).forEach { r ->
                            Row(Modifier.fillMaxWidth().clickable {
                                val s = Cfg.watchRepos; s.add(r.full_name); Cfg.watchRepos = s
                                watch = s.toList(); muteTick++
                            }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(r.owner?.avatar_url, 24, name = r.owner?.login ?: r.full_name.substringBefore("/"))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.full_name, fontSize = 12.sp, maxLines = 1)
                                    r.description?.let { Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                                }
                                Icon(Icons.Filled.Add, "添加", Modifier.size(17.dp), tint = GH.green)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showRepoPicker = false }) { Text("完成") } }
        )
    }
}

@Composable
fun CrashHistoryDialog(text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        title = { Text("历史崩溃日志", fontSize = 15.sp) },
        text = {
            Column(Modifier.heightIn(max = 440.dp)) {
                Text(text, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        }
    )
}

@Composable
fun CrashDialog(text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        title = { Text("崩溃日志", fontSize = 15.sp) },
        text = {
            Column(Modifier.heightIn(max = 380.dp)) {
                Text(text, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        }
    )
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
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range.start.toInt(), range.endInclusive.toInt())) },
            valueRange = range
        )
    }
}
