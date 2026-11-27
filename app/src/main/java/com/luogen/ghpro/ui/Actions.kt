package com.luogen.ghpro.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Artifact
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Job
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.Run
import com.luogen.ghpro.data.Step
import com.luogen.ghpro.data.Workflow
import com.luogen.ghpro.download.Downloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ActionsTab(nav: NavController, owner: String, name: String, repo: Repo) {
    val scope = rememberCoroutineScope()
    val cacheKey = "$owner/$name"
    var workflows by remember { mutableStateOf<List<Workflow>>(emptyList()) }
    var runs by remember { mutableStateOf(UiCache.repoRuns[cacheKey] ?: emptyList()) }
    var loading by remember { mutableStateOf(runs.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastSync by remember { mutableStateOf(0L) }
    var autoOn by remember { mutableStateOf(Cfg.autoRefresh) }
    var showWf by remember { mutableStateOf(false) }

    suspend fun load(silent: Boolean = false) {
        if (!silent) loading = true
        try {
            runs = Api.runs(owner, name, 1).first
            UiCache.repoRuns[cacheKey] = runs
            if (workflows.isEmpty()) workflows = runCatching { Api.workflows(owner, name) }.getOrDefault(emptyList())
            error = null
            lastSync = System.currentTimeMillis()
        } catch (e: Exception) { if (!silent) error = e.message }
        loading = false
    }

    LaunchedEffect(owner, name) { if (runs.isEmpty()) load() }
    LaunchedEffect(autoOn) {
        while (isActive && autoOn) {
            delay((Cfg.pollSeconds.coerceIn(8, 300)) * 1000L)
            load(true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton({ scope.launch { load() } }) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) }
            Text(if (lastSync == 0L) "同步中" else "${(System.currentTimeMillis() - lastSync) / 1000} 秒前同步 · 每 ${Cfg.pollSeconds} 秒自动",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("自动", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = autoOn, onCheckedChange = { autoOn = it }, modifier = Modifier.height(28.dp))
            IconButton({ showWf = !showWf }) { Text("工作流", fontSize = 10.sp, color = GH.blue) }
        }

        if (showWf && workflows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(10.dp)) {
                workflows.take(8).forEach { w ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(w.name, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TinyTag(w.state, if (w.state == "active") GH.green else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("运行", fontSize = 10.sp, color = GH.blue,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable {
                                scope.launch {
                                    try { Api.dispatch(owner, name, w.id, repo.default_branch ?: "main"); Cfg.touchRepo("$owner/$name"); load() }
                                    catch (e: Exception) { }
                                }
                            }.padding(horizontal = 6.dp, vertical = 4.dp))
                    }
                }
            }
        }

        when {
            loading && runs.isEmpty() -> LoadingBox()
            error != null -> ErrorBox(error) { scope.launch { load() } }
            runs.isEmpty() -> EmptyBox("这个仓库还没有工作流运行记录")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(runs, key = { it.id }) { run ->
                    RunRow(run) { nav.navigate("run/$owner/$name/${run.id}") }
                    Divider1()
                }
            }
        }
    }
}

@Composable
fun RunRow(run: Run, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(GH.statusColor(run.status, run.conclusion)))
            Spacer(Modifier.width(7.dp))
            Text(run.display_title ?: run.name ?: "运行 #${run.run_number}",
                fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text("#${run.run_number}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateChip(null, run.status, run.conclusion)
            Spacer(Modifier.width(8.dp))
            // 分支名可能很长（refs/pull/1710/head）：让它占剩余宽度并省略，
            // 否则右侧“耗时”会被挤成一行一个字，把整张卡片撑出几行空白。
            Text("${run.event ?: ""} · ${run.head_branch ?: ""}", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("耗时 ${durationText(run.run_started_at ?: run.created_at, run.updated_at)}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            run.actor?.let {
                Avatar(it.avatar_url, 12); Spacer(Modifier.width(3.dp))
                Text(it.login, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Text("${timeAgo(run.created_at)} · 第 ${run.run_attempt ?: 1} 次", fontSize = 9.sp,
                maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(nav: NavController, owner: String, name: String, runId: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var run by remember { mutableStateOf<Run?>(UiCache.runData[runId]) }
    var jobs by remember { mutableStateOf(UiCache.runJobs[runId] ?: emptyList()) }
    var artifacts by remember { mutableStateOf(UiCache.runArtifacts[runId] ?: emptyList()) }
    val dlStates by Downloader.state.collectAsState()
    var loading by remember { mutableStateOf(run == null) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var stepSel by remember { mutableStateOf<Pair<Job, Step>?>(null) }
    var stepLog by remember { mutableStateOf("") }
    var stepLogLoading by remember { mutableStateOf(false) }
    var stepLogError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load(showToast: Boolean = false) {
        if (showToast) refreshing = true
        try {
            run = Api.run(owner, name, runId)
            jobs = Api.runJobs(owner, name, runId)
            artifacts = Api.runArtifacts(owner, name, runId)
            UiCache.runData[runId] = run!!
            UiCache.runJobs[runId] = jobs
            UiCache.runArtifacts[runId] = artifacts
            error = null
            if (showToast) Toast.makeText(ctx, "已刷新运行状态", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            error = e.message
            if (showToast) Toast.makeText(ctx, "刷新失败：${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            loading = false
            if (showToast) refreshing = false
        }
    }
    LaunchedEffect(runId) { load() }
    LaunchedEffect(auto, run?.status) {
        while (isActive && auto) {
            val st = run?.status
            if (st != "in_progress" && st != "queued" && st != "pending") break
            delay((Cfg.pollSeconds.coerceIn(8, 300)) * 1000L)
            load()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("工作流运行 #${run?.run_number ?: runId}", fontSize = 15.sp) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(enabled = !refreshing, onClick = { scope.launch { load(showToast = true) } }) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading && run == null -> LoadingBox()
                error != null -> ErrorBox(error) { scope.launch { load() } }
                run != null -> {
                    val r = run!!
                    var more by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                            .heightIn(max = 60.dp).padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.display_title ?: r.name ?: "工作流", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StateChip(null, r.status, r.conclusion)
                                Spacer(Modifier.width(6.dp))
                                Text("${r.event ?: ""} · ${r.head_branch ?: "-"} · #${r.run_number ?: runId}", fontSize = 10.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Box {
                            IconButton({ more = true }) { Icon(Icons.Filled.MoreVert, "更多操作") }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(text = { Text("重新运行", fontSize = 13.sp) }, onClick = {
                                    more = false
                                    scope.launch {
                                        try { Api.rerun(owner, name, runId); Cfg.touchRepo("$owner/$name"); snackbar.showSnackbar("已重新发起"); delay(1500); load() }
                                        catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                    }
                                })
                                DropdownMenuItem(text = { Text("取消运行", fontSize = 13.sp) }, onClick = {
                                    more = false
                                    scope.launch {
                                        try { Api.cancelRun(owner, name, runId); snackbar.showSnackbar("已取消"); delay(1500); load() }
                                        catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                    }
                                })
                                DropdownMenuItem(text = { Text("复制运行链接", fontSize = 13.sp) }, onClick = {
                                    more = false
                                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("链接", r.html_url ?: ""))
                                    Toast.makeText(ctx, "已复制链接", Toast.LENGTH_SHORT).show()
                                })
                                DropdownMenuItem(text = { Text("下载本次日志", fontSize = 13.sp) }, onClick = {
                                    more = false
                                    Toast.makeText(ctx, "开始下载本次日志…", Toast.LENGTH_SHORT).show()
                                    Downloader.startDownload(ctx,
                                        "https://api.github.com/repos/$owner/$name/actions/runs/$runId/logs",
                                        "logs-$runId.log", Cfg.token, useMirror = false,
                                        source = "工作流日志", repo = "$owner/$name",
                                        onDone = { },
                                        onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
                                })
                                DropdownMenuItem(text = { Text("删除这次运行", fontSize = 13.sp, color = GH.red) }, onClick = {
                                    more = false
                                    scope.launch {
                                        try { Api.deleteRun(owner, name, runId); snackbar.showSnackbar("已删除运行记录"); nav.popBackStack() }
                                        catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                    }
                                })
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                                KeyValue("发起人", r.actor?.login ?: r.triggering_actor?.login)
                                KeyValue("开始时间", fullTime(r.run_started_at ?: r.created_at))
                                KeyValue("耗时", if (r.status == "in_progress") "已运行 ${durationText(r.run_started_at ?: r.created_at, null)}"
                                    else "总耗时 ${durationText(r.run_started_at ?: r.created_at, r.updated_at)}")
                                KeyValue("提交", r.head_sha?.take(7))
                                KeyValue("提交说明", r.head_commit?.message?.lineSequence()?.firstOrNull())
                            }
                            Divider1()
                        }
                        if (artifacts.isNotEmpty()) {
                            item {
                                Text("产物（${artifacts.size}）· 官方原地址下载", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(14.dp, 8.dp))
                            }
                            items(artifacts, key = { it.id }) { a ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(a.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${humanSize(a.size_in_bytes)} · ${timeAgo(a.created_at)} · ${if (a.expired) "已过期" else "有效"}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        val ast = dlStates[Downloader.sanitize("${a.name}.zip")]
                                        if (ast != null && ast.active) {
                                            Text("${ast.status} · ${ast.percent}% · ${humanSize(ast.speed)}/s",
                                                fontSize = 10.sp, color = GH.blue)
                                            Text(downloadAmountText(ast), fontSize = 10.sp, color = GH.blue)
                                        }
                                    }
                                    IconButton({
                                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("链接", a.archive_download_url ?: ""))
                                        scope.launch { snackbar.showSnackbar("已复制产物链接（需登录令牌）") }
                                    }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    IconButton(onClick = {
                                        Toast.makeText(ctx, "开始下载产物 ${a.name}…", Toast.LENGTH_SHORT).show()
                                        Downloader.startDownload(ctx, a.archive_download_url ?: return@IconButton, "${a.name}.zip", Cfg.token, useMirror = false,
                                            expectedSize = a.size_in_bytes,
                                            onDone = { },
                                            onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
                                    }) { Icon(Icons.Filled.Download, null, Modifier.size(18.dp), tint = GH.blue) }
                                    IconButton(onClick = {
                                        scope.launch {
                                            try { Api.deleteArtifact(owner, name, a.id); load() }
                                            catch (e: Exception) { snackbar.showSnackbar("删除失败：${e.message}") }
                                        }
                                    }) { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = GH.red) }
                                }
                                Divider1()
                            }
                        }
                        item {
                            Text("任务（${jobs.size}）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(14.dp, 8.dp))
                        }
                        items(jobs, key = { it.id }) { job ->
                            Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == job.id) null else job.id }.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(GH.statusColor(job.status, job.conclusion)))
                                    Spacer(Modifier.width(7.dp))
                                    Text(job.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (job.status == "in_progress") "已运行 ${durationText(job.started_at, null)}" else durationText(job.started_at, job.completed_at),
                                        fontSize = 10.sp, color = if (job.status == "in_progress") GH.yellow else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(3.dp))
                                Text("${job.status}${job.conclusion?.let { " · $it" } ?: ""} · ${job.runner_name ?: ""}",
                                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (expanded == job.id) {
                                    Spacer(Modifier.height(8.dp))
                                    job.steps?.forEach { s ->
                                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                                            stepSel = job to s
                                            stepLog = ""; stepLogError = null; stepLogLoading = true
                                            scope.launch {
                                                try { stepLog = Api.stepLogText(owner, name, job.id, s.number, s.name, s.started_at, s.completed_at) }
                                                catch (e: Exception) { stepLogError = e.message }
                                                stepLogLoading = false
                                            }
                                        }.padding(vertical = 5.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp))
                                                .background(GH.statusColor(s.status, s.conclusion)))
                                            Spacer(Modifier.width(6.dp))
                                            Text("${s.number}. ${s.name}", fontSize = 11.sp, modifier = Modifier.weight(1f),
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(durationText(s.started_at, s.completed_at), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (job.steps.isNullOrEmpty()) Text("这个任务没有步骤信息", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            Toast.makeText(ctx, "开始下载任务日志…", Toast.LENGTH_SHORT).show()
                                            Downloader.startDownload(ctx,
                                                "https://api.github.com/repos/$owner/$name/actions/jobs/${job.id}/logs",
                                                "job-${job.id}-${job.name.take(12)}.log", Cfg.token, useMirror = false,
                                                source = "工作流日志", repo = "$owner/$name",
                                                onDone = { },
                                                onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
                                        }) { Icon(Icons.Filled.Download, null, Modifier.size(14.dp)); Text("下载日志", fontSize = 10.sp) }
                                        OutlinedButton(onClick = {
                                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("日志链接",
                                                "https://api.github.com/repos/$owner/$name/actions/jobs/${job.id}/logs"))
                                            scope.launch { snackbar.showSnackbar("已复制任务日志链接（需登录令牌访问）") }
                                        }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp)); Text("复制链接", fontSize = 10.sp) }
                                    }
                                }
                            }
                            Divider1()
                        }
                        item { Spacer(Modifier.height(30.dp)) }
                    }
                }
            }
        }
    }

    stepSel?.let { (job, step) ->
        AlertDialog(
            onDismissRequest = { stepSel = null },
            title = { Text("步骤 ${step.number} · ${step.name}", fontSize = 14.sp) },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    Text("${step.status}${step.conclusion?.let { " · $it" } ?: ""} · ${durationText(step.started_at, step.completed_at)}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    when {
                        stepLogLoading -> LoadingBox("正在读取这一步的日志…")
                        stepLogError != null -> {
                            val em = messageOf(stepLogError)
                            val expired = em.contains("90 天") || em.contains("清理") || em.contains("410")
                            Text(if (expired) em else "读取失败：$em", fontSize = 11.sp,
                                color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else GH.red)
                        }
                        stepLog.isBlank() -> Text("这一步没有日志输出", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            Text(stepLog, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Toast.makeText(ctx, "开始下载任务日志…", Toast.LENGTH_SHORT).show()
                    Downloader.startDownload(ctx,
                        "https://api.github.com/repos/$owner/$name/actions/jobs/${job.id}/logs",
                        "job-${job.id}-${job.name.take(12)}.log", Cfg.token, useMirror = false,
                                                source = "工作流日志", repo = "$owner/$name",
                        onDone = { },
                        onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
                }) { Text("下载完整日志", fontSize = 12.sp) }
            },
            dismissButton = { TextButton({ stepSel = null }) { Text("关闭") } }
        )
    }
}
