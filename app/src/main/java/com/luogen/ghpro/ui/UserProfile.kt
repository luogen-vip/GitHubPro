package com.luogen.ghpro.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.font.FontFamily
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
    var selectedEvent by remember { mutableStateOf<com.luogen.ghpro.data.GhEvent?>(null) }
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
                                Column(Modifier.fillMaxWidth().clickable { selectedEvent = e }.padding(14.dp)) {
                                    Text(eventTitle(e.type), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text(e.repo?.full_name ?: "未知仓库", fontSize = 10.sp, color = GH.blue)
                                    Text("${timeAgo(e.created_at)} · 点这条查看详情", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    selectedEvent?.let { ev ->
        EventDetailDialog(ev, onOpenRepo = { full ->
            selectedEvent = null
            runCatching { nav.navigate("repo/$full") }.onFailure {
                Toast.makeText(ctx, "打不开仓库：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }, onClose = { selectedEvent = null })
    }
}

@Composable
fun EventsTab(login: String, nav: NavController) {
    var events by remember { mutableStateOf<List<com.luogen.ghpro.data.GhEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedEvent by remember { mutableStateOf<com.luogen.ghpro.data.GhEvent?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(login) {
        events = runCatching { Api.userEvents(login) }.getOrDefault(emptyList())
        loading = false
    }
    if (loading) { LoadingBox(); return }
    if (events.isEmpty()) { EmptyBox("暂无公开动态"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(events, key = { it.id }) { e ->
            Column(Modifier.fillMaxWidth().clickable { selectedEvent = e }.padding(14.dp)) {
                Text(eventTitle(e.type), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(e.repo?.full_name ?: "未知仓库", fontSize = 10.sp, color = GH.blue)
                Text("${timeAgo(e.created_at)} · 点这条查看详情", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Divider1()
        }
    }
    selectedEvent?.let { ev ->
        EventDetailDialog(ev, onOpenRepo = { full ->
            selectedEvent = null
            runCatching { nav.navigate("repo/$full") }.onFailure {
                Toast.makeText(ctx, "打不开仓库：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }, onClose = { selectedEvent = null })
    }
}


@Composable
private fun EventDetailDialog(
    e: com.luogen.ghpro.data.GhEvent,
    onOpenRepo: (String) -> Unit,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val full = e.repo?.full_name ?: ""
    val details = eventDetails(e)
    val external = eventExternalUrl(e)
    val json = runCatching {
        com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(e.payload ?: emptyMap<String, Any>())
    }.getOrDefault("")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("动态详情", fontSize = 15.sp) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(eventTitle(e.type), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("${if (full.isNotBlank()) full else "未知仓库"} · ${timeAgo(e.created_at)}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (details.isEmpty()) {
                    Text("这条动态没有更多可展开的字段，下面保留原始数据，也可以打开仓库查看。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    details.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            Text(k, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                            Text(v, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("原始数据", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Text(json.take(8000), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (json.length > 8000) Text("（只显示前 8000 字符）", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = full.isNotBlank(), onClick = { onOpenRepo(full) }) { Text("打开仓库") }
        },
        dismissButton = {
            Row {
                if (external != null) TextButton(onClick = { openUrl(ctx, external); onClose() }) { Text("GitHub 打开", fontSize = 12.sp) }
                TextButton(onClick = onClose) { Text("关闭") }
            }
        }
    )
}

private fun eventTitle(type: String?): String = when (type) {
    "PushEvent" -> "推送了代码"
    "CreateEvent" -> "创建了分支 / 仓库 / 标签"
    "IssuesEvent" -> "操作了议题"
    "PullRequestEvent" -> "操作了拉取请求"
    "PullRequestReviewEvent" -> "审查了拉取请求"
    "PullRequestReviewCommentEvent" -> "评论了拉取请求审查"
    "WatchEvent" -> "点了 Star"
    "ForkEvent" -> "复刻了仓库"
    "ReleaseEvent" -> "发布了发行版"
    "IssueCommentEvent" -> "评论了议题"
    "DeleteEvent" -> "删除了分支 / 标签"
    "MemberEvent" -> "变更了协作者"
    "PublicEvent" -> "仓库转为公开"
    "CommitCommentEvent" -> "评论了提交"
    else -> type ?: "动态"
}

private fun payloadMap(p: Any?): Map<String, Any?>? =
    (p as? Map<*, *>)?.entries?.associate { (it.key?.toString() ?: "") to it.value }

private fun nestedMap(m: Map<String, Any?>?, key: String): Map<String, Any?>? =
    payloadMap(m?.get(key))

private fun valueText(v: Any?): String? = when (v) {
    null -> null
    is String -> v.takeIf { it.isNotBlank() }
    is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    is Number, is Boolean -> v.toString()
    else -> null
}

private fun payloadString(m: Map<String, Any?>?, key: String): String? = valueText(m?.get(key))

private fun actionCn(action: String?): String = when (action) {
    "opened" -> "打开"
    "closed" -> "关闭"
    "reopened" -> "重新打开"
    "labeled" -> "添加标签"
    "unlabeled" -> "移除标签"
    "assigned" -> "指派"
    "unassigned" -> "取消指派"
    "edited" -> "编辑"
    "created" -> "创建"
    "deleted" -> "删除"
    "merged" -> "合并"
    "synchronize" -> "更新代码"
    "review_requested" -> "请求审查"
    "submitted" -> "提交审查"
    "published" -> "发布"
    else -> action ?: "更新"
}

private fun eventDetails(e: com.luogen.ghpro.data.GhEvent): List<Pair<String, String>> {
    val root = payloadMap(e.payload) ?: return emptyList()
    val out = ArrayList<Pair<String, String>>()
    val action = payloadString(root, "action")
    if (action != null) out.add("操作" to actionCn(action))
    val issue = nestedMap(root, "issue")
    if (issue != null) {
        val title = payloadString(issue, "title")
        val num = payloadString(issue, "number")
        if (title != null) out.add("议题" to (if (num != null) "$title (#$num)" else title))
        payloadString(issue, "state")?.let { out.add("状态" to it) }
        payloadString(issue, "html_url")?.let { out.add("议题链接" to it) }
    }
    val pr = nestedMap(root, "pull_request")
    if (pr != null) {
        val title = payloadString(pr, "title")
        val num = payloadString(pr, "number")
        if (title != null) out.add("拉取请求" to (if (num != null) "$title (#$num)" else title))
        payloadString(pr, "state")?.let { out.add("状态" to it) }
        valueText(pr["merged"])?.let { out.add("已合并" to if (it == "true") "是" else "否") }
        payloadString(pr, "html_url")?.let { out.add("PR 链接" to it) }
    }
    val comment = nestedMap(root, "comment")
    if (comment != null) {
        payloadString(comment, "body")?.let { out.add("评论内容" to it.take(300)) }
        payloadString(comment, "html_url")?.let { out.add("评论链接" to it) }
    }
    val release = nestedMap(root, "release")
    if (release != null) {
        payloadString(release, "name")?.let { out.add("发行版" to it) }
        payloadString(release, "tag_name")?.let { out.add("标签" to it) }
        payloadString(release, "html_url")?.let { out.add("发行版链接" to it) }
    }
    val forkee = nestedMap(root, "forkee")
    if (forkee != null) payloadString(forkee, "full_name")?.let { out.add("复刻到" to it) }
    val label = nestedMap(root, "label")
    if (label != null) payloadString(label, "name")?.let { out.add("标签" to it) }
    val assignee = nestedMap(root, "assignee")
    if (assignee != null) payloadString(assignee, "login")?.let { out.add("指派给" to it) }
    val review = nestedMap(root, "review")
    if (review != null) payloadString(review, "state")?.let { out.add("审查状态" to it) }
    payloadString(root, "ref")?.let { out.add("分支 / 标签" to it) }
    payloadString(root, "ref_type")?.let { out.add("引用类型" to it) }
    val commits = root["commits"]
    if (commits is List<*>) {
        out.add("提交数" to commits.size.toString())
        val first = payloadMap(commits.firstOrNull())
        payloadString(first, "message")?.let { msg ->
            out.add("提交说明" to (msg.lineSequence().firstOrNull()?.take(200) ?: msg.take(200)))
        }
        payloadString(first, "sha")?.let { out.add("提交 SHA" to it) }
    }
    payloadString(root, "size_distinct")?.let { out.add("不同提交" to it) }
    if (out.isEmpty()) out.add("类型" to (e.type ?: "动态"))
    return out
}

private fun eventExternalUrl(e: com.luogen.ghpro.data.GhEvent): String? {
    val root = payloadMap(e.payload)
    for (key in listOf("issue", "pull_request", "release", "comment", "review")) {
        nestedMap(root, key)?.let { payloadString(it, "html_url") }?.let { return it }
    }
    nestedMap(root, "forkee")?.let { payloadString(it, "html_url") }?.let { return it }
    return e.repo?.html_url
}
