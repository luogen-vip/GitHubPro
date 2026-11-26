package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import android.widget.Toast
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.Page
import com.luogen.ghpro.data.User
import com.luogen.ghpro.download.Downloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(nav: NavController, owner: String, name: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = "$owner/$name"
    var repo by remember { mutableStateOf(UiCache.repoData[key]) }
    var languages by remember { mutableStateOf(UiCache.repoLangs[key] ?: emptyMap()) }
    var starred by remember { mutableStateOf(UiCache.repoStarred[key] ?: Cfg.isStarredCached(key)) }
    var starSeq by remember { mutableStateOf(0) }
    var watchSeq by remember { mutableStateOf(0) }
    var statPending by remember { mutableStateOf(0) }
    var starReady by remember { mutableStateOf(true) }
    var watching by remember { mutableStateOf(UiCache.repoWatching[key] ?: false) }
    var loading by remember { mutableStateOf(UiCache.repoData[key] == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(UiCache.repoTab[key] ?: 0) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var latestReleaseAt by remember { mutableStateOf<String?>(null) }
    var refreshSeq by remember { mutableStateOf(0) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var peopleDialog by rememberSaveable { mutableStateOf(-1) }
    var showDesc by rememberSaveable { mutableStateOf(false) }  // 0 star 1 fork 2 watch 3 contributors
    var msg by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var lastToast by remember { mutableStateOf<Toast?>(null) }
    fun quickToast(msg: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    suspend fun load() {
        try {
            val r = Api.repo(owner, name)
            repo = r
            UiCache.repoData[key] = r
            error = null
            loading = false
            runCatching {
                val st = Api.isStarred(owner, name)
                starred = st
                starReady = true
                UiCache.repoStarred[key] = st
                Cfg.setStarredCached(key, st)
            }
            runCatching { watching = Api.isWatching(owner, name); UiCache.repoWatching[key] = watching }
            runCatching { languages = Api.languages(owner, name); UiCache.repoLangs[key] = languages }
            runCatching { Api.releases(owner, name).items.firstOrNull()?.published_at?.let { v -> latestReleaseAt = v } }
        } catch (e: Exception) {
            error = if (e.message?.contains("404") == true || e.message?.contains("Not Found", true) == true)
                "仓库不存在：作者可能已经删除、改名，或把它改成了私有仓库。\n如果它原本在你的 Star 列表里，会自动收到“仓库已消失”的通知。"
            else e.message
        } finally { loading = false }
    }

    // 有缓存就直接用缓存，不再自动重复请求；需要最新数据用右上角刷新
    LaunchedEffect(owner, name) {
        if (UiCache.repoData[key] == null) load()
        else {
            repo = UiCache.repoData[key]; loading = false
            // 缓存可能不是最新：进页面时把 Star / 关注状态重新核对一遍（失败就继续用缓存，不挡页面）
            scope.launch {
                runCatching { Api.repo(owner, name) }.onSuccess { fresh ->
                    // 缓存只负责秒开；进页面后立刻用服务端统计覆盖，Star/关注/复刻/议题数都是真值
                    if (statPending == 0) { repo = fresh; UiCache.repoData[key] = fresh }
                }
                runCatching { Api.isStarred(owner, name) }.onSuccess {
                    starred = it; starReady = true; UiCache.repoStarred[key] = it; Cfg.setStarredCached(key, it)
                }
                runCatching { Api.isWatching(owner, name) }.onSuccess {
                    watching = it; UiCache.repoWatching[key] = it
                }
            }
        }
    }
    LaunchedEffect(key) { IssueCounts.ensure(key) }
    LaunchedEffect(tab) { UiCache.repoTab[key] = tab }
    val issueVer by Bus.issuePoke.collectAsState()
    val issueLocalVer by Bus.issueLocalPoke.collectAsState()
    LaunchedEffect(issueVer, issueLocalVer) {
        UiCache.repoData[key]?.let { repo = it }
    }
    // 仓库统计（Star/复刻/关注/议题）自动追最新，别人操作也能看到
    LaunchedEffect(owner, name) {
        while (isActive) {
            delay(3_000L)
            if (!loading && statPending == 0) runCatching {
                val fresh = Api.repo(owner, name)
                repo = fresh
                UiCache.repoData[key] = fresh
            }
        }
    }

    fun startZipDownload() {
        val r = repo ?: return
        val branch = r.default_branch ?: "main"
        val fname = "${r.name}-$branch.zip"
        downloading = fname
        Cfg.touchRepo(r.full_name)
        Toast.makeText(ctx, "开始下载源码 ZIP…", Toast.LENGTH_SHORT).show()
        Downloader.startDownload(ctx, "https://github.com/${r.full_name}/archive/refs/heads/$branch.zip", fname,
            source = "源码 ZIP", repo = r.full_name,
            onDone = { },
            onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
        downloading = null
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(owner, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    if (!starReady) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    } else IconButton(onClick = {
                        val base = UiCache.repoData[key] ?: repo ?: return@IconButton
                        val target = !starred
                        val seq = ++starSeq
                        starred = target
                        UiCache.repoStarred[key] = target
                        Cfg.setStarredCached(key, target)
                        val optimistic = base.copy(stargazers_count = (base.stargazers_count + if (target) 1 else -1).coerceAtLeast(0))
                        repo = optimistic
                        UiCache.repoData[key] = optimistic
                        if (UiCache.homeTab == 1) {
                            UiCache.homeRepos = if (target)
                                (UiCache.homeRepos.filter { it.full_name != key } + optimistic)
                            else UiCache.homeRepos.filter { it.full_name != key }
                            Bus.starChanged()
                        }
                        quickToast(if (target) "已 Star" else "已取消 Star")
                        statPending++
                        Bus.actions.launch {
                            try {
                                delay(150L)
                                if (seq != starSeq) return@launch
                                runCatching { Api.star(owner, name, target) }
                                    .onSuccess {
                                        Cfg.touchRepo(key)
                                        val wl = Cfg.watchRepos
                                        if (target) wl.add(key) else wl.remove(key)
                                        Cfg.watchRepos = wl
                                    }
                                    .onFailure {
                                        if (seq == starSeq) {
                                            starred = !target; UiCache.repoStarred[key] = !target; Cfg.setStarredCached(key, !target)
                                            repo = base; UiCache.repoData[key] = base
                                            if (UiCache.homeTab == 1) {
                                                UiCache.homeRepos = if (target) UiCache.homeRepos.filter { it.full_name != key }
                                                                      else (UiCache.homeRepos.filter { it.full_name != key } + base)
                                                Bus.starChanged()
                                            }
                                            quickToast("操作失败：${it.message}")
                                        }
                                    }
                            } finally { statPending = (statPending - 1).coerceAtLeast(0) }
                        }
                    }) { Icon(if (starred) Icons.Filled.Star else Icons.Filled.StarBorder, "Star", tint = GH.yellow) }
                    IconButton(onClick = {
                        val base = UiCache.repoData[key] ?: repo ?: return@IconButton
                        val target = !watching
                        val seq = ++watchSeq
                        watching = target
                        UiCache.repoWatching[key] = target
                        val optimistic = base.copy(subscribers_count = (base.subscribers_count + if (target) 1 else -1).coerceAtLeast(0))
                        repo = optimistic
                        UiCache.repoData[key] = optimistic
                        quickToast(if (target) "已关注：仓库有动态会收到通知" else "已取消关注")
                        statPending++
                        Bus.actions.launch {
                            try {
                                delay(150L)
                                if (seq != watchSeq) return@launch
                                runCatching { Api.watch(owner, name, target) }
                                    .onSuccess { Cfg.touchRepo(key) }
                                    .onFailure {
                                        if (seq == watchSeq) {
                                            watching = !target; UiCache.repoWatching[key] = !target
                                            repo = base; UiCache.repoData[key] = base
                                            quickToast("操作失败：${it.message}")
                                        }
                                    }
                            } finally { statPending = (statPending - 1).coerceAtLeast(0) }
                        }
                    }) { Icon(if (watching) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "关注这个仓库") }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("刷新最新状态") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = {
                                showMenu = false
                                UiCache.repoData.remove(key); UiCache.repoRuns.remove(key); UiCache.repoIssues.remove(key)
                                UiCache.repoPulls.remove(key); UiCache.repoReleases.remove(key); UiCache.repoCommits.remove(key)
                                UiCache.codeListing.keys.filter { it.startsWith("$key|") }.forEach { UiCache.codeListing.remove(it) }
                                UiCache.fileContent.keys.filter { it.startsWith("$key|") }.forEach { UiCache.fileContent.remove(it) }
                                refreshSeq++
                                scope.launch { load(); Toast.makeText(ctx, "已刷新当前页面", Toast.LENGTH_SHORT).show() }
                            })
                            DropdownMenuItem(text = { Text("在浏览器打开") }, leadingIcon = { Icon(Icons.Filled.OpenInNew, null) }, onClick = {
                                showMenu = false; openUrl(ctx, repo?.html_url)
                            })
                            DropdownMenuItem(text = { Text("下载源码 ZIP") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = {
                                showMenu = false
                                startZipDownload()
                            })
                            DropdownMenuItem(text = { Text("复刻 Fork 到我的账号") }, onClick = {
                                showMenu = false
                                Bus.actions.launch {
                                    runCatching { Api.fork(owner, name) }
                                        .onSuccess {
                                            Cfg.touchRepo(key)
                                            repo = repo?.copy(forks_count = (repo?.forks_count ?: 0) + 1)
                                            repo?.let { UiCache.repoData[key] = it }
                                            snackbar.showSnackbar("已复刻到 ${it.full_name}"); msg = it.full_name
                                        }
                                        .onFailure { snackbar.showSnackbar("复刻失败：${it.message}") }
                                }
                            })
                            DropdownMenuItem(text = { Text(if (Cfg.isRepoMuted(key)) "开启此仓库通知" else "关闭此仓库通知") }, onClick = {
                                showMenu = false
                                val muted = !Cfg.isRepoMuted(key)
                                Cfg.setRepoMuted(key, muted)
                                Toast.makeText(ctx, if (muted) "已关闭 $key 的通知" else "已开启 $key 的通知", Toast.LENGTH_SHORT).show()
                            })
                            if (repo?.permissions?.push == true) {
                                DropdownMenuItem(text = { Text("编辑仓库设置") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = {
                                    showMenu = false; nav.navigate("edit/$owner/$name")
                                })
                            }
                            if (repo?.permissions?.admin == true) {
                                DropdownMenuItem(text = { Text("删除仓库") }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = GH.red) }, onClick = {
                                    showMenu = false; confirmDelete = true
                                })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (loading && repo == null) { LoadingBox(); return@Column }
            error?.let { e -> ErrorBox(e) { scope.launch { load() } }; return@Column }
            val r = repo ?: return@Column

            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 14.dp, vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(r.owner?.avatar_url, 22, Modifier.clickable { r.owner?.login?.let { nav.navigate("user/$it") } }, name = r.owner?.login)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.full_name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { r.owner?.login?.let { nav.navigate("user/$it") } })
                        Text("作者 ${r.owner?.login ?: owner}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.isPrivate) TinyTag("私有", GH.yellow)
                }
                r.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.clickable { showDesc = true }) {
                        Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (desc.length > 60) {
                            Spacer(Modifier.width(4.dp))
                            Text("全文", fontSize = 10.sp, color = GH.blue)
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("Star ${r.stargazers_count}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { peopleDialog = 0 }.padding(end = 4.dp))
                    Text("· 复刻 ${r.forks_count}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { peopleDialog = 1 }.padding(end = 4.dp))
                    Text("· 关注 ${r.subscribers_count}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { peopleDialog = 2 }.padding(end = 4.dp))
                    val issueCount = IssueCounts.get(r.full_name) ?: r.open_issues_count
                    Text("· 议题 $issueCount", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val top = languages.entries.maxByOrNull { it.value }
                    Text(if (top != null && languages.isNotEmpty())
                        "主要语言 " + top.key + " " + String.format("%.1f", top.value * 100.0 / languages.values.sum().coerceAtLeast(1)) + "%"
                    else "这个仓库还没有语言统计", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tab == 0) {
                    val langsShown = languages.ifEmpty { r.language?.let { mapOf(it to 1L) } ?: emptyMap() }
                    if (langsShown.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        LanguageBar(langsShown, legend = false)
                    } else {
                        Spacer(Modifier.height(13.dp))
                    }
                }
            }

            ScrollableTabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = GH.blue,
                edgePadding = 4.dp,
                divider = { Divider1() }
            ) {
                listOf("概览", "代码", "议题", "拉取", "工作流", "发行版", "提交", "讨论", "贡献").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 12.sp) })
                }
            }

            ActiveDownloadStrip(repo?.full_name)

            Box(Modifier.weight(1f)) {
                key(refreshSeq) {
                when (tab) {
                    0 -> OverviewTab(nav, owner, name, r, languages, latestReleaseAt)
                    1 -> CodeTab(nav, owner, name, r)
                    2 -> IssueListTab(nav, owner, name)
                    3 -> PullListTab(nav, owner, name)
                    4 -> ActionsTab(nav, owner, name, r)
                    5 -> ReleasesTab(nav, owner, name, r)
                    6 -> CommitsTab(nav, owner, name, r)
                    7 -> DiscussionsTab(nav, owner, name, r)
                    8 -> ContributorsTab(nav, owner, name)
                }
                }
            }
        }
    }

    if (confirmDelete) {
        var input by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除仓库") },
            text = {
                Column {
                    Text("这会从 GitHub 服务器上真正删除 ${owner}/${name}，不可恢复。请输入仓库名确认：", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(input, { input = it }, label = { Text(name) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = input.isNotBlank() && input != name)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { input = name }) { Text("一键填入「$name」", fontSize = 12.sp) }
                        TextButton(onClick = {
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            val txt = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                            if (txt.isNotEmpty()) input = txt
                        }) { Text("粘贴", fontSize = 12.sp) }
                        if (input.isNotBlank() && input != name) {
                            Text("名称不一致，无法删除", fontSize = 11.sp, color = GH.red)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = input == name, onClick = {
                    scope.launch {
                        runCatching { Api.deleteRepo(owner, name) }
                            .onSuccess {
                                confirmDelete = false
                                runCatching { val wl = Cfg.watchRepos; wl.remove("$owner/$name"); Cfg.watchRepos = wl }
                                UiCache.invalidateHome()
                                UiCache.invalidateRepo("$owner/$name")
                                Bus.poke()
                                Toast.makeText(ctx, "仓库 $owner/$name 已删除", Toast.LENGTH_LONG).show()
                                nav.popBackStack()
                            }
                            .onFailure { snackbar.showSnackbar("删除失败：${it.message}") }
                    }
                }) { Text("确认删除", color = GH.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }

    if (peopleDialog >= 0) {
        PeopleDialog(nav, owner, name, peopleDialog) { peopleDialog = -1 }
    }

    if (showDesc) {
        AlertDialog(
            onDismissRequest = { showDesc = false },
            title = { Text("仓库完整描述", fontSize = 14.sp) },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        Text(repo?.description ?: "", fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            },
            confirmButton = { TextButton({ showDesc = false }) { Text("关闭") } },
            dismissButton = { TextButton({ copyText(ctx, repo?.description ?: "", "已复制仓库描述") }) { Text("复制") } }
        )
    }
}

@Composable
fun StatCol(num: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(num, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LanguageBar(languages: Map<String, Long>, legend: Boolean = true) {
    val total = languages.values.sum().coerceAtLeast(1)
    val entries = languages.entries.take(6)
    Column {
        Row(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            entries.forEach { (k, v) ->
                Box(Modifier.weight(v.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(GH.langColor(k)))
            }
        }
        if (legend) {
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            entries.forEach { (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(GH.langColor(k)))
                    Spacer(Modifier.width(3.dp))
                    Text("$k ${String.format("%.1f", v * 100.0 / total)}%", fontSize = 10.sp, maxLines = 1,
                        softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
    }
}

@Composable
fun OverviewTab(nav: NavController, owner: String, name: String, r: Repo, languages: Map<String, Long>, latestReleaseAt: String? = null) {
    val ctx = LocalContext.current
    var readme by remember { mutableStateOf<String?>(null) }
    var readmeLoading by remember { mutableStateOf(false) }
    var readmeSha by remember { mutableStateOf<String?>(null) }
    var showEdit by rememberSaveable { mutableStateOf(false) }
    var editText by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(owner, name) {
        readmeLoading = true
        readme = runCatching { Api.fileContent(owner, name, "README.md", r.default_branch) }
            .recoverCatching { Api.fileContent(owner, name, "readme.md", r.default_branch) }
            .getOrNull()
        readmeSha = runCatching { Api.fileItem(owner, name, "README.md", r.default_branch).sha }.getOrNull()
        readmeLoading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        if (languages.isNotEmpty()) {
            Text("语言占比", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            LanguageBar(languages)
            Spacer(Modifier.height(10.dp))
        }
        KeyValue("分支", r.default_branch ?: "main", onClick = { })
        KeyValue("语言", r.language ?: "—")
        KeyValue("许可", r.license?.name ?: "未声明")
        KeyValue("创建", fullTime(r.created_at))
        val latest = listOfNotNull(r.updated_at, r.pushed_at, latestReleaseAt).maxOrNull()
        KeyValue("最近更新", "${timeAgo(latest ?: r.updated_at)} · ${fullTime(latest ?: r.updated_at)}")
        KeyValue("最近推送", timeAgo(r.pushed_at))
        KeyValue("体积", humanSize(r.size * 1024))
        KeyValue("主页", r.homepage, onClick = { openUrl(ctx, r.homepage) })
        r.topics?.takeIf { it.isNotEmpty() }?.let { ts ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ts.take(6).forEach { t -> TinyTag(t, GH.blue) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                nav.navigate("user/${r.owner?.login}")
            }) { Text("作者主页", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                val branch = r.default_branch ?: "main"
                val fname = "${r.name}-$branch.zip"
                Toast.makeText(ctx, "开始下载源码 ZIP…", Toast.LENGTH_SHORT).show()
                Downloader.startDownload(ctx, "https://github.com/${r.full_name}/archive/refs/heads/$branch.zip", fname,
                    source = "源码 ZIP", repo = r.full_name,
                    onDone = { },
                    onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
            }) { Text("下载源码", fontSize = 12.sp) }
            OutlinedButton(onClick = { openUrl(ctx, r.html_url) }) { Text("浏览器", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Divider1()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("README", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                editText = readme ?: ""
                showEdit = true
                if (readmeSha == null) {
                    // 拿 sha 供更新使用
                }
            }) { Text("编辑 README", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(6.dp))
        when {
            readmeLoading -> LoadingBox()
            readme.isNullOrBlank() -> EmptyBox("这个仓库还没有 README，点右上角「编辑 README」可以直接创建")
            else -> MdText(readme!!.take(40000), baseSize = 13)
        }
        Spacer(Modifier.height(30.dp))
    }

    if (showEdit) {
        AlertDialog(
            onDismissRequest = { if (!saving) showEdit = false },
            title = { Text(if (readme.isNullOrBlank()) "创建 README" else "编辑 README", fontSize = 15.sp) },
            text = {
                Column {
                    OutlinedTextField(editText, { editText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                        label = { Text("支持 Markdown") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    Spacer(Modifier.height(6.dp))
                    Text("保存后会直接提交到 ${r.default_branch ?: "main"} 分支，刷新即可看到效果。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val branch = r.default_branch ?: "main"
                            val path = if (readme.isNullOrBlank()) "README.md" else "README.md"
                            val sha = readmeSha ?: runCatching { Api.fileItem(owner, name, path, branch).sha }.getOrNull()
                            Api.putFile(owner, name, path, if (readme.isNullOrBlank()) "Create README.md" else "Update README.md",
                                android.util.Base64.encodeToString(editText.toByteArray(), android.util.Base64.NO_WRAP), sha, branch)
                            readme = editText
                            readmeSha = null
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(ctx, "README 已保存", Toast.LENGTH_SHORT).show()
                                showEdit = false
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(ctx, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(if (saving) "保存中…" else "保存") }
            },
            dismissButton = { TextButton({ showEdit = false }) { Text("取消") } }
        )
    }
}


@Composable
fun CommitsTab(nav: NavController, owner: String, name: String, repo: Repo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var branch by remember { mutableStateOf(repo.default_branch ?: "main") }
    fun ck() = "$owner/$name|$branch"
    var list by remember { mutableStateOf(UiCache.repoCommits[ck()] ?: emptyList()) }
    var loading by remember { mutableStateOf(list.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    var branches by remember { mutableStateOf(listOf(repo.default_branch ?: "main")) }
    var menu by remember { mutableStateOf(false) }

    suspend fun load(p: Int, replace: Boolean, silent: Boolean = false) {
        if (p == 1 && !silent) loading = true
        try {
            val pg = Api.commits(owner, name, branch, p)
            list = if (replace) pg.items else list + pg.items
            if (replace) UiCache.repoCommits[ck()] = list
            hasNext = pg.hasNext; page = p; error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        loading = false
    }
    LaunchedEffect(branch) {
        val cached = UiCache.repoCommits[ck()]
        if (cached != null && cached.isNotEmpty()) { list = cached; loading = false; load(1, true, silent = true) } else load(1, true)
    }
    LaunchedEffect(Unit) {
        runCatching { Api.branches(owner, name).map { it.name } }.onSuccess { if (it.isNotEmpty()) branches = it }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { menu = true }) {
                    Text(branch, fontSize = 12.sp); Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    branches.forEach { b -> DropdownMenuItem(text = { Text(b, fontSize = 12.sp) }, onClick = { branch = b; menu = false }) }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${list.size} 条提交", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            loading && list.isEmpty() -> LoadingBox()
            error != null -> ErrorBox(error) { scope.launch { load(1, true) } }
            list.isEmpty() -> EmptyBox("没有提交记录")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.sha }) { c ->
                    Row(Modifier.fillMaxWidth().clickable {
                        nav.navigate("commit/$owner/$name/${c.sha}")
                    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.author?.avatar_url ?: c.committer?.avatar_url, 26)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.commit?.message?.lineSequence()?.firstOrNull() ?: "(无提交说明)",
                                fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${c.author?.login ?: c.commit?.author?.name ?: ""} · ${timeAgo(c.commit?.author?.date)} · ${c.sha.take(7)}",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Divider1()
                }
                if (hasNext) item {
                    TextButton(onClick = { scope.launch { load(page + 1, false) } }, modifier = Modifier.fillMaxWidth()) { Text("加载更多", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
fun ContributorsTab(nav: NavController, owner: String, name: String) {
    val ck = "$owner/$name"
    var list by remember { mutableStateOf(UiCache.repoContributors[ck] ?: emptyList()) }
    var loading by remember { mutableStateOf(list.isEmpty()) }
    LaunchedEffect(owner, name) {
        if (list.isEmpty()) {
            list = runCatching { Api.contributors(owner, name) }.getOrDefault(emptyList())
            UiCache.repoContributors[ck] = list
        }
        loading = false
    }
    if (loading) { LoadingBox(); return }
    if (list.isEmpty()) { EmptyBox("暂无贡献者数据"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { u ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate("user/${u.login}") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(u.avatar_url, 34)
                Spacer(Modifier.width(10.dp))
                Text(u.login, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${u.contributions} 次提交", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Divider1()
        }
    }
}

@Composable
fun PeopleDialog(nav: NavController, owner: String, name: String, kind: Int, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Any>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    suspend fun fetch(p: Int) {
        if (kind == 3) {
            if (p > 1) return
            val items = runCatching { Api.contributors(owner, name) }.getOrDefault(emptyList())
            list = items
            hasMore = false
        } else {
            var fetched: List<Any> = emptyList()
            var more = false
            when (kind) {
                0 -> {
                    val pg = try { Api.stargazers(owner, name, p) }
                    catch (e: Exception) { if (p == 1) Api.stargazersGraphql(owner, name) else Page(emptyList(), false) }
                    fetched = pg.items; more = pg.hasNext
                }
                1 -> { val pg = Api.forks(owner, name, p); fetched = pg.items; more = pg.hasNext }
                else -> { val pg = Api.watchers(owner, name, p); fetched = pg.items; more = pg.hasNext }
            }
            list = if (p == 1) fetched else list + fetched
            hasMore = more
        }
        page = p
    }

    LaunchedEffect(kind) {
        loading = true
        err = null
        try {
            fetch(1)
            if (hasMore) fetch(2)
        } catch (e: Exception) { err = e.message }
        loading = false
    }

    val title = when (kind) { 0 -> "点过 Star 的人"; 1 -> "复刻过的仓库"; 2 -> "关注这个仓库的人"; else -> "贡献者" }
    val emptyText = when (kind) {
        0 -> "GitHub 没有公开这个仓库的 Star 名单（官方接口受限），自己的仓库通常可以查看"
        1 -> "还没有人复刻这个仓库"
        2 -> "还没有人关注这个仓库（关注数可能为 0）"
        else -> "暂无贡献者数据"
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, fontSize = 15.sp) },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                if (loading) LoadingBox()
                else if (err != null) EmptyBox(when {
                    kind == 2 -> "GitHub 只允许仓库管理员查看“关注者”名单，别人的仓库看不到具体是谁；关注人数仍然会显示。"
                    kind == 0 -> "GitHub 没有公开这个仓库的 Star 名单（接口返回受限）。这不是网络问题，自己的仓库通常可以正常查看。"
                    else -> "读取失败：$err"
                })
                else if (list.isEmpty()) EmptyBox(emptyText)
                else {
                    Text("已加载 ${list.size} 个" + if (hasMore) "（还有更多，可继续加载）" else "（已全部显示）",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                    list.forEach { item ->
                        val (login, avatar, extra) = when (item) {
                            is User -> Triple(item.login, item.avatar_url,
                                if (kind == 3) "${item.contributions} 次贡献" else if (kind == 2) "关注者" else "Star 用户")
                            is Repo -> Triple(item.full_name, item.owner?.avatar_url, "${item.stargazers_count} star")
                            else -> Triple("", null, "")
                        }
                        Row(Modifier.fillMaxWidth().clickable {
                            onClose(); if (!login.contains("/")) nav.navigate("user/$login")
                            else nav.navigate("repo/${login.split("/")[0]}/${login.split("/")[1]}")
                        }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(avatar, 28)
                            Spacer(Modifier.width(8.dp))
                            Text(login, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(extra, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (hasMore) {
                        TextButton(enabled = !loadingMore, onClick = {
                            scope.launch {
                                loadingMore = true
                                runCatching { fetch(page + 1) }.onFailure { err = it.message }
                                loadingMore = false
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (loadingMore) "加载中…" else "加载更多", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }
    )
}


@Composable
fun ActiveDownloadStrip(repoFilter: String? = null) {
    val map by Downloader.state.collectAsState()
    val active = map.values.filter {
        it.active &&
            (repoFilter == null || Downloader.repoOf(it.fileName) == repoFilter)
    }
    if (active.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        active.take(2).forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.fileName, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (d.total > 0) "${d.percent}%" else d.status, fontSize = 11.sp, color = GH.blue)
                IconButton({ Downloader.cancelDownload(d.fileName) }, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Close, "取消下载", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LinearProgressIndicator(
                progress = { if (d.total > 0) d.percent / 100f else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
            )
        }
    }
}
