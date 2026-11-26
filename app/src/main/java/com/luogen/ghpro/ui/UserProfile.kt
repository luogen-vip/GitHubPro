package com.luogen.ghpro.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.User
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(nav: NavController, login: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var u by remember { mutableStateOf(UiCache.userData[login]) }
    var repos by remember { mutableStateOf(UiCache.userRepos[login] ?: emptyList()) }
    var following by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(u == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(UiCache.userTab[login] ?: 0) }
    var peopleDialog by remember { mutableStateOf(0) }
    var showPeople by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<User>>(emptyList()) }
    var events by remember { mutableStateOf<List<com.luogen.ghpro.data.GhEvent>>(emptyList()) }
    var eventsLoading by remember { mutableStateOf(false) }
    var eventsFor by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val isMe = Cfg.login == login

    suspend fun load() {
        try {
            u = Api.user(login)
            repos = Api.userRepos(login, 1, 60).items
            u?.let { UiCache.userData[login] = it }
            UiCache.userRepos[login] = repos
            following = runCatching { Api.isFollowing(login) }.getOrDefault(false)
            error = null
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    LaunchedEffect(login) { load() }
    LaunchedEffect(tab) { UiCache.userTab[login] = tab }
    LaunchedEffect(tab, login) {
        if (tab == 1 && eventsFor != login) {
            eventsLoading = true
            events = runCatching { Api.userEvents(login) }.getOrDefault(emptyList())
            eventsFor = login
            eventsLoading = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(login, fontSize = 15.sp) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = { IconButton({ openUrl(ctx, u?.html_url) }) { Icon(Icons.Filled.OpenInNew, "浏览器") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> LoadingBox()
                error != null -> ErrorBox(error) { scope.launch { load() } }
                u != null -> {
                    val listState = rememberLazyListState(UiCache.userIndex[login] ?: 0)
                    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 || listState.firstVisibleItemScrollOffset > 600 } }
                    LaunchedEffect(listState) {
                        snapshotFlow { listState.firstVisibleItemIndex }
                            .distinctUntilChanged()
                            .collect { UiCache.userIndex[login] = it }
                    }
                    Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        item {
                            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(u!!.avatar_url, 46)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(u!!.name ?: u!!.login, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text("@" + u!!.login, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        u!!.bio?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (!isMe) OutlinedButton(onClick = {
                                        val target = !following
                                        following = target
                                        u = u?.copy(followers = (u!!.followers + if (target) 1 else -1).coerceAtLeast(0))
                                        Toast.makeText(ctx, if (target) "已关注" else "已取消关注", Toast.LENGTH_SHORT).show()
                                        scope.launch {
                                            try { Api.follow(login, target) }
                                            catch (e: Exception) {
                                                following = !target
                                                u = u?.copy(followers = (u!!.followers + if (!target) 1 else -1).coerceAtLeast(0))
                                                Toast.makeText(ctx, "操作失败：${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                        Text(if (following) "已关注" else "关注", fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("${u!!.public_repos} 仓库", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable { tab = 0 })
                                    Text("${u!!.followers} 关注者", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable { peopleDialog = 0; showPeople = true })
                                    Text("${u!!.following} 正在关注", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable { peopleDialog = 1; showPeople = true })
                                    Spacer(Modifier.weight(1f))
                                    Text("GitHub 主页", fontSize = 11.sp, color = GH.blue,
                                        modifier = Modifier.clickable { openUrl(ctx, u!!.html_url) })
                                }
                                val extras = listOfNotNull(
                                    u!!.company?.takeIf { it.isNotBlank() }?.let { "公司：$it" },
                                    u!!.location?.takeIf { it.isNotBlank() }?.let { "位置：$it" },
                                    u!!.blog?.takeIf { it.isNotBlank() }?.let { "博客：$it" },
                                    "加入于 ${fullTime(u!!.created_at)}"
                                )
                                if (extras.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(extras.joinToString("  ·  "), fontSize = 10.sp, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                                Tab(tab == 0, { tab = 0 }, text = { Text("仓库 ${u!!.public_repos}", fontSize = 12.sp) })
                                Tab(tab == 1, { tab = 1 }, text = { Text("动态", fontSize = 12.sp) })
                            }
                        }
                        if (tab == 0) {
                            items(repos, key = { it.id }) { r ->
                                RepoRow(r) { nav.navigate("repo/${r.owner?.login}/${r.name}") }
                                Divider1()
                            }
                            if (repos.isEmpty()) item { EmptyBox("没有公开仓库") }
                        } else {
                            if (eventsLoading) item { LoadingBox() }
                            else if (events.isEmpty()) item { EmptyBox("暂无公开动态") }
                            items(events, key = { it.id }) { e ->
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(
                                        when (e.type) {
                                            "PushEvent" -> "推送了代码"
                                            "CreateEvent" -> "创建了分支/仓库/标签"
                                            "IssuesEvent" -> "操作了议题"
                                            "PullRequestEvent" -> "操作了拉取请求"
                                            "WatchEvent" -> "点了 Star"
                                            "ForkEvent" -> "复刻了仓库"
                                            "ReleaseEvent" -> "发布了发行版"
                                            "IssueCommentEvent" -> "评论了议题"
                                            else -> e.type ?: "动态"
                                        }, fontSize = 12.sp, fontWeight = FontWeight.Medium
                                    )
                                    Text(e.repo?.full_name ?: "", fontSize = 10.sp, color = GH.blue,
                                        modifier = Modifier.clickable { e.repo?.let { nav.navigate("repo/${it.full_name}") } })
                                    Text(timeAgo(e.created_at), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Divider1()
                            }
                        }
                    }
                    if (showTop) {
                        SmallFab(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    }
                    }
                }
            }
        }
    }

    if (showPeople) {
        var loadingPeople by remember { mutableStateOf(true) }
        LaunchedEffect(peopleDialog, showPeople) {
            if (showPeople) {
                people = runCatching {
                    if (peopleDialog == 0) Api.followers(login, 1).items else Api.following(login, 1).items
                }.getOrDefault(emptyList())
                loadingPeople = false
            }
        }
        AlertDialog(
            onDismissRequest = { showPeople = false },
            title = { Text(if (peopleDialog == 0) "关注者" else "正在关注", fontSize = 15.sp) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (loadingPeople) LoadingBox() else if (people.isEmpty()) EmptyBox("暂无")
                    else people.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { showPeople = false; nav.navigate("user/${p.login}") }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.avatar_url, 26); Spacer(Modifier.width(8.dp))
                            Text(p.login, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showPeople = false }) { Text("关闭") } }
        )
    }
}

@Composable
fun EventsTab(login: String, nav: NavController) {
    var events by remember { mutableStateOf<List<com.luogen.ghpro.data.GhEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(login) {
        events = runCatching { Api.userEvents(login) }.getOrDefault(emptyList())
        loading = false
    }
    if (loading) { LoadingBox(); return }
    if (events.isEmpty()) { EmptyBox("暂无公开动态"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(events, key = { it.id }) { e ->
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(
                    when (e.type) {
                        "PushEvent" -> "推送了代码"
                        "CreateEvent" -> "创建了分支/仓库/标签"
                        "IssuesEvent" -> "操作了议题"
                        "PullRequestEvent" -> "操作了拉取请求"
                        "WatchEvent" -> "点了 Star"
                        "ForkEvent" -> "复刻了仓库"
                        "ReleaseEvent" -> "发布了发行版"
                        "IssueCommentEvent" -> "评论了议题"
                        else -> e.type ?: "动态"
                    }, fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
                Text(e.repo?.full_name ?: "", fontSize = 10.sp, color = GH.blue,
                    modifier = Modifier.clickable { e.repo?.let { nav.navigate("repo/${it.full_name}") } })
                Text(timeAgo(e.created_at), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Divider1()
        }
    }
}
