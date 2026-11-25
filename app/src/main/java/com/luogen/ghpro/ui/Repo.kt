package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
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
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.User
import com.luogen.ghpro.download.Downloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(nav: NavController, owner: String, name: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf<Repo?>(null) }
    var languages by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var starred by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var peopleDialog by remember { mutableStateOf<Int?>(null) }  // 0 star 1 fork 2 watch 3 contributors
    var msg by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val tick by Bus.tick.collectAsState()

    suspend fun load() {
        try {
            val r = Api.repo(owner, name)
            repo = r
            error = null
            Cfg.watchRepos.let { s -> if (!s.contains(r.full_name)) { s.add(r.full_name); Cfg.watchRepos = s } }
            runCatching { starred = Api.isStarred(owner, name) }
            runCatching { watching = Api.isWatching(owner, name) }
            runCatching { languages = Api.languages(owner, name) }
        } catch (e: Exception) {
            error = e.message
        } finally { loading = false }
    }

    LaunchedEffect(owner, name) { load() }
    LaunchedEffect(tick) { if (tick > 0) runCatching { repo = Api.repo(owner, name) } }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(25_000L)
            if (Cfg.autoRefresh) {
                runCatching {
                    repo = Api.repo(owner, name)
                    runCatching { languages = Api.languages(owner, name) }
                }
            }
        }
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
                    IconButton(onClick = { scope.launch { load() } }) { Icon(Icons.Filled.Visibility, "刷新") }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("在浏览器打开") }, leadingIcon = { Icon(Icons.Filled.OpenInNew, null) }, onClick = {
                                showMenu = false; openUrl(ctx, repo?.html_url)
                            })
                            DropdownMenuItem(text = { Text("下载源码 ZIP") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = {
                                showMenu = false
                                val r = repo ?: return@DropdownMenuItem
                                scope.launch {
                                    runCatching {
                                        Downloader.download(ctx,
                                            "https://github.com/${r.full_name}/archive/refs/heads/${r.default_branch ?: "main"}.zip",
                                            "${r.name}-${r.default_branch ?: "main"}.zip")
                                    }.onSuccess { msg = "已下载到 ${it.name}" }.onFailure { msg = "下载失败：${it.message}" }
                                    msg?.let { snackbar.showSnackbar(it) }
                                }
                            })
                            DropdownMenuItem(text = { Text("复刻 Fork 到我的账号") }, onClick = {
                                showMenu = false
                                scope.launch {
                                    runCatching { Api.fork(owner, name) }
                                        .onSuccess { snackbar.showSnackbar("已复刻到 ${it.full_name}"); msg = it.full_name }
                                        .onFailure { snackbar.showSnackbar("复刻失败：${it.message}") }
                                }
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

            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.full_name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (r.isPrivate) TinyTag("私有", GH.yellow)
                }
                r.description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { scope.launch {
                            val target = !starred
                            runCatching { Api.star(owner, name, target) }
                                .onSuccess { starred = target; repo = Api.repo(owner, name) }
                                .onFailure { snackbar.showSnackbar("操作失败：${it.message}") }
                        } },
                        label = { Text(if (starred) "已 Star ${compactNum(r.stargazers_count)}" else "Star ${compactNum(r.stargazers_count)}", fontSize = 12.sp) },
                        leadingIcon = { Icon(if (starred) Icons.Filled.Star else Icons.Filled.StarBorder, null, Modifier.size(15.dp), tint = GH.yellow) }
                    )
                    Spacer(Modifier.width(6.dp))
                    AssistChip(
                        onClick = { scope.launch {
                            val target = !watching
                            runCatching { Api.watch(owner, name, target) }
                                .onSuccess { watching = target }
                                .onFailure { snackbar.showSnackbar("操作失败：${it.message}") }
                        } },
                        label = { Text(if (watching) "已关注" else "关注", fontSize = 12.sp) }
                    )
                    Spacer(Modifier.width(6.dp))
                    AssistChip(onClick = { peopleDialog = 3 }, label = { Text("贡献者", fontSize = 12.sp) })
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatCol(compactNum(r.forks_count), "复刻", Modifier.weight(1f)) { peopleDialog = 1 }
                    StatCol(compactNum(r.stargazers_count), "Star", Modifier.weight(1f)) { peopleDialog = 0 }
                    StatCol(compactNum(r.subscribers_count), "关注", Modifier.weight(1f)) { peopleDialog = 2 }
                    StatCol(compactNum(r.open_issues_count), "议题", Modifier.weight(1f)) { tab = 2 }
                }
                if (languages.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LanguageBar(languages)
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

            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> OverviewTab(nav, owner, name, r)
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

    if (confirmDelete) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除仓库") },
            text = {
                Column {
                    Text("这会从 GitHub 服务器上真正删除 ${owner}/${name}，不可恢复。请输入仓库名确认：", fontSize = 13.sp)
                    OutlinedTextField(input, { input = it }, label = { Text(name) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = input == name, onClick = {
                    scope.launch {
                        runCatching { Api.deleteRepo(owner, name) }
                            .onSuccess {
                                confirmDelete = false
                                Bus.poke()
                                snackbar.showSnackbar("已删除")
                                nav.popBackStack()
                            }
                            .onFailure { snackbar.showSnackbar("删除失败：${it.message}") }
                    }
                }) { Text("确认删除", color = GH.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }

    peopleDialog?.let { kind ->
        PeopleDialog(nav, owner, name, kind) { peopleDialog = null }
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
fun LanguageBar(languages: Map<String, Long>) {
    val total = languages.values.sum().coerceAtLeast(1)
    val entries = languages.entries.take(6)
    Column {
        Row(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            entries.forEach { (k, v) ->
                Box(Modifier.weight(v.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(GH.langColor(k)))
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            entries.forEach { (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(GH.langColor(k)))
                    Spacer(Modifier.width(3.dp))
                    Text("$k ${String.format("%.1f", v * 100.0 / total)}%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun OverviewTab(nav: NavController, owner: String, name: String, r: Repo) {
    val ctx = LocalContext.current
    var readme by remember { mutableStateOf<String?>(null) }
    var readmeLoading by remember { mutableStateOf(false) }

    LaunchedEffect(owner, name) {
        readmeLoading = true
        readme = runCatching { Api.fileContent(owner, name, "README.md", r.default_branch) }
            .recoverCatching { Api.fileContent(owner, name, "readme.md", r.default_branch) }
            .getOrNull()
        readmeLoading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        KeyValue("分支", r.default_branch ?: "main", onClick = { })
        KeyValue("语言", r.language ?: "—")
        KeyValue("许可", r.license?.name ?: "未声明")
        KeyValue("创建", fullTime(r.created_at))
        KeyValue("最近更新", "${timeAgo(r.updated_at)} · ${fullTime(r.updated_at)}")
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                nav.navigate("user/${r.owner?.login}")
            }) { Text("作者主页", fontSize = 12.sp) }
            OutlinedButton(onClick = { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                runCatching {
                    Downloader.download(ctx, "https://github.com/${r.full_name}/archive/refs/heads/${r.default_branch ?: "main"}.zip",
                        "${r.name}-${r.default_branch ?: "main"}.zip")
                }
            } }) { Text("下载源码", fontSize = 12.sp) }
            OutlinedButton(onClick = { openUrl(ctx, r.html_url) }) { Text("浏览器", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Divider1()
        Spacer(Modifier.height(10.dp))
        Text("README", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        when {
            readmeLoading -> LoadingBox()
            readme.isNullOrBlank() -> EmptyBox("这个仓库还没有 README")
            else -> Text(readme!!.take(8000), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(30.dp))
    }
}


@Composable
fun CommitsTab(nav: NavController, owner: String, name: String, repo: Repo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<com.luogen.ghpro.data.GitCommit>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    var branch by remember { mutableStateOf(repo.default_branch ?: "main") }
    var branches by remember { mutableStateOf(listOf(repo.default_branch ?: "main")) }
    var menu by remember { mutableStateOf(false) }

    suspend fun load(p: Int, replace: Boolean) {
        if (p == 1) loading = true
        try {
            val pg = Api.commits(owner, name, branch, p)
            list = if (replace) pg.items else list + pg.items
            hasNext = pg.hasNext; page = p; error = null
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    LaunchedEffect(branch) { load(1, true) }
    LaunchedEffect(Unit) {
        runCatching { Api.branches(owner, name).map { it.name } }.onSuccess { if (it.isNotEmpty()) branches = it }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(GH.surface2).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { menu = true }) {
                    Text(branch, fontSize = 12.sp); Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    branches.forEach { b -> DropdownMenuItem(text = { Text(b, fontSize = 12.sp) }, onClick = { branch = b; menu = false }) }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${list.size} 条提交", fontSize = 10.sp, color = GH.muted)
        }
        when {
            loading && list.isEmpty() -> LoadingBox()
            error != null -> ErrorBox(error) { scope.launch { load(1, true) } }
            list.isEmpty() -> EmptyBox("没有提交记录")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.sha }) { c ->
                    Row(Modifier.fillMaxWidth().clickable {
                        openUrl(ctx, c.html_url)
                    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.author?.avatar_url ?: c.committer?.avatar_url, 26)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.commit?.message?.lineSequence()?.firstOrNull() ?: "(无提交说明)",
                                fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${c.author?.login ?: c.commit?.author?.name ?: ""} · ${timeAgo(c.commit?.author?.date)} · ${c.sha.take(7)}",
                                fontSize = 10.sp, color = GH.muted)
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
    var list by remember { mutableStateOf<List<User>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(owner, name) {
        list = runCatching { Api.contributors(owner, name) }.getOrDefault(emptyList())
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
                Text("${compactNum(u.contributions)} 次提交", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Divider1()
        }
    }
}

@Composable
fun PeopleDialog(nav: NavController, owner: String, name: String, kind: Int, onClose: () -> Unit) {
    var list by remember { mutableStateOf<List<Any>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(kind) {
        list = runCatching {
            when (kind) {
                0 -> Api.stargazers(owner, name, 1).items
                1 -> Api.forks(owner, name).items
                2 -> Api.watchers(owner, name).items
                else -> Api.contributors(owner, name)
            } as List<Any>
        }.getOrDefault(emptyList())
        loading = false
    }
    val title = when (kind) { 0 -> "点过 Star 的人"; 1 -> "复刻过的人"; 2 -> "关注这个仓库的人"; else -> "贡献者" }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, fontSize = 15.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (loading) LoadingBox()
                else if (list.isEmpty()) EmptyBox("暂无数据")
                else list.forEach { item ->
                    val (login, avatar, extra) = when (item) {
                        is User -> Triple(item.login, item.avatar_url, "${compactNum(item.contributions)} 次贡献")
                        is Repo -> Triple(item.full_name, item.owner?.avatar_url, "${item.stargazers_count} star")
                        else -> Triple("", null, "")
                    }
                    Row(Modifier.fillMaxWidth().clickable {
                        onClose(); if (!login.contains("/")) nav.navigate("user/$login")
                        else nav.navigate("repo/${login.split("/")[0]}/${login.split("/")[1]}")
                    }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(avatar, 28)
                        Spacer(Modifier.width(8.dp))
                        Text(login, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(extra, fontSize = 10.sp, color = GH.muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }
    )
}
