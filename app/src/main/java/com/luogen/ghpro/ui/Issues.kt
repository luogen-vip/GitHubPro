package com.luogen.ghpro.ui

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Comment
import com.luogen.ghpro.data.Issue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun IssueListTab(nav: NavController, owner: String, name: String) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("open") }
    fun listKey() = "$owner/$name|$state"
    var list by remember { mutableStateOf(UiCache.repoIssues[listKey()] ?: emptyList()) }
    var loading by remember { mutableStateOf(list.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    val issuePoke by Bus.issuePoke.collectAsState()

    suspend fun load(p: Int, replace: Boolean, silent: Boolean = false) {
        if (p == 1 && !silent) loading = true
        try {
            val pg = Api.issues(owner, name, state, p)
            list = if (replace) pg.items.filter { it.pull_request == null } else list + pg.items.filter { it.pull_request == null }
            hasNext = pg.hasNext
            page = p
            if (replace) UiCache.repoIssues[listKey()] = list
            error = null
        } catch (e: Exception) { if (!silent) error = e.message }
        loading = false
    }

    LaunchedEffect(state, issuePoke) {
        val cached = UiCache.repoIssues[listKey()]
        if (cached != null && cached.isNotEmpty()) { list = cached; loading = false; load(1, true, silent = true) }
        else load(1, true)
    }
    // 页面停留时自动拉取：别人新建/关闭议题，几秒内就能看到
    LaunchedEffect(state) {
        while (isActive) {
            delay(4_000L)
            if (page == 1 && !loading) runCatching { load(1, true, silent = true) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state == "open", onClick = { state = "open" }, label = { Text("打开中", fontSize = 12.sp) })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = state == "closed", onClick = { state = "closed" }, label = { Text("已关闭", fontSize = 12.sp) })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = state == "all", onClick = { state = "all" }, label = { Text("全部", fontSize = 12.sp) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { nav.navigate("newissue/$owner/$name") }) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text("新建议题", fontSize = 12.sp)
            }
        }
        when {
            loading && list.isEmpty() -> LoadingBox()
            error != null -> ErrorBox(error) { scope.launch { load(1, true) } }
            list.isEmpty() -> EmptyBox(if (state == "all") "没有议题" else "没有${if (state == "open") "打开中" else "已关闭"}的议题")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { issue ->
                    IssueRow(issue) { nav.navigate("issue/$owner/$name/${issue.number}") }
                    Divider1()
                }
                if (hasNext) item {
                    TextButton(onClick = { scope.launch { load(page + 1, false) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("加载更多", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun IssueRow(issue: Issue, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateChip(issue.state)
            Spacer(Modifier.width(6.dp))
            Text(issue.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${issue.number}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(timeAgo(issue.created_at), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Avatar(issue.user?.avatar_url, 14)
            Spacer(Modifier.width(4.dp))
            Text(issue.user?.login ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Comment, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(issue.comments.toString(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        issue.labels?.takeIf { it.isNotEmpty() }?.let { ls ->
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ls.take(4).forEach { LabelChip(it) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueDetailScreen(nav: NavController, owner: String, name: String, number: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lastToast by remember { mutableStateOf<Toast?>(null) }
    fun quickToast(msg: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).also { it.show() }
    }
    var issue by remember { mutableStateOf<Issue?>(null) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var actionMsg by remember { mutableStateOf<String?>(null) }
    var opSeq by remember { mutableStateOf(0) }
    var lockOpSeq by remember { mutableStateOf(0) }
    var pendingOps by remember { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load() {
        try {
            issue = Api.issue(owner, name, number)
            comments = Api.issueComments(owner, name, number)
            error = null
        } catch (e: Exception) { error = e.message }
        loading = false
    }

    fun cacheIssue(up: Issue) {
        UiCache.repoIssues.keys.filter { it.startsWith("$owner/$name|") }.forEach { k ->
            val st = k.substringAfterLast('|')
            val old = UiCache.repoIssues[k].orEmpty()
            val rest = old.filter { it.number != up.number }
            UiCache.repoIssues[k] = if (st == "all" || st == up.state) listOf(up) + rest else rest
        }
        UiCache.repoData["$owner/$name"]?.let { r ->
            if (up.state == "open") UiCache.repoData["$owner/$name"] = r.copy(open_issues_count = r.open_issues_count + 1)
            else UiCache.repoData["$owner/$name"] = r.copy(open_issues_count = (r.open_issues_count - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(number) {
        if (UiCache.repoData["$owner/$name"] == null) runCatching { UiCache.repoData["$owner/$name"] = Api.repo(owner, name) }
        load()
        while (isActive) {
            delay(4_000L)
            if (pendingOps == 0) {
                runCatching {
                    val fresh = Api.issue(owner, name, number)
                    issue = fresh
                    comments = Api.issueComments(owner, name, number)
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("议题 #$number", fontSize = 15.sp) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    val issueNow = issue
                    if (issueNow != null) {
                        val canPush = UiCache.repoData["$owner/$name"]?.permissions?.let { it.push || it.admin } == true
                        val canClose = canPush || issueNow.user?.login == Cfg.login
                        val isOpen = issueNow.state == "open"
                        if (canClose) {
                            TextButton(onClick = {
                                val targetOpen = !isOpen
                                val old = issueNow
                                val seq = ++opSeq
                                // 先立刻改 UI 和提示，网络在后台真实提交
                                issue = old.copy(state = if (targetOpen) "open" else "closed",
                                    state_reason = if (targetOpen) null else "completed")
                                quickToast(if (targetOpen) "已重新打开" else "已关闭")
                                pendingOps++
                                scope.launch {
                                    kotlinx.coroutines.delay(150L)
                                    if (seq != opSeq) { pendingOps = (pendingOps - 1).coerceAtLeast(0); return@launch }
                                    try {
                                        val up = Api.patchIssue(owner, name, number,
                                            if (targetOpen) mapOf("state" to "open")
                                            else mapOf("state" to "closed", "state_reason" to "completed"))
                                        if (seq == opSeq) { issue = up; cacheIssue(up) }
                                        Bus.issuesChanged()
                                    } catch (e: Exception) {
                                        if (seq == opSeq) issue = old
                                        quickToast("失败：${e.message}")
                                    }
                                    pendingOps = (pendingOps - 1).coerceAtLeast(0)
                                }
                            }) {
                                Icon(if (isOpen) Icons.Filled.Check else Icons.Filled.LockOpen, null, Modifier.size(16.dp))
                                Text(if (isOpen) "关闭" else "重开", fontSize = 12.sp)
                            }
                        }
                        if (canPush) {
                            IconButton(onClick = {
                                val old = issueNow
                                val targetLocked = !old.locked
                                val seq = ++lockOpSeq
                                issue = old.copy(locked = targetLocked)
                                quickToast(if (targetLocked) "已锁定讨论" else "已解锁讨论")
                                pendingOps++
                                scope.launch {
                                    kotlinx.coroutines.delay(150L)
                                    if (seq != lockOpSeq) { pendingOps = (pendingOps - 1).coerceAtLeast(0); return@launch }
                                    try {
                                        Api.lockIssue(owner, name, number, targetLocked)
                                        Bus.issuesChanged()
                                    } catch (e: Exception) {
                                        issue = old
                                        quickToast("失败：${e.message}")
                                    }
                                    pendingOps = (pendingOps - 1).coerceAtLeast(0)
                                }
                            }) { Icon(if (issueNow.locked) Icons.Filled.LockOpen else Icons.Filled.Lock, "锁定讨论") }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> LoadingBox()
                error != null -> ErrorBox(error) { scope.launch { load() } }
                issue != null -> {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
                        Text(issue!!.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateChip(issue!!.state)
                            Spacer(Modifier.width(8.dp))
                            Text("#${issue!!.number} · ${issue!!.user?.login} · ${timeAgo(issue!!.created_at)}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        issue!!.labels?.takeIf { it.isNotEmpty() }?.let {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { it.forEach { l -> LabelChip(l) } }
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
                        Text(issue!!.body ?: "（没有正文）", fontSize = 13.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(14.dp))
                        Divider1()
                        Spacer(Modifier.height(10.dp))
                        Text("讨论（${comments.size}）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        comments.forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Avatar(c.user?.avatar_url, 26)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(c.user?.login ?: "", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.width(6.dp))
                                        Text(timeAgo(c.created_at), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(c.body ?: "", fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                            Divider1()
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(),
                            label = { Text("写下你的评论…") }, minLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = text.isNotBlank() && !sending, onClick = {
                                scope.launch {
                                    sending = true
                                    try {
                                        Api.addComment(owner, name, number, text)
                                        text = ""
                                        comments = Api.issueComments(owner, name, number)
                                        snackbar.showSnackbar("已发表评论")
                                    } catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                    sending = false
                                }
                            }) { Icon(Icons.Filled.Send, null, Modifier.size(16.dp)); Text("发送") }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("GitHub 不支持通过 API 删除议题，只能关闭或锁定（关闭后别人仍可查看历史）。",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewIssueScreen(nav: NavController, owner: String, name: String) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var labels by remember { mutableStateOf("") }
    var assignees by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("新建议题", fontSize = 15.sp) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(enabled = title.isNotBlank() && !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val created = Api.createIssue(owner, name, title, body,
                                    labels.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                    assignees.split(",").map { it.trim() }.filter { it.isNotBlank() })
                                Bus.issuesChanged()
                                nav.popBackStack()
                                nav.navigate("issue/$owner/$name/${created.number}")
                            } catch (e: Exception) { snackbar.showSnackbar("创建失败：${e.message}") }
                            busy = false
                        }
                    }) { Text("提交") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(14.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("标题") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().weight(1f), label = { Text("正文（支持 Markdown）") })
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(labels, { labels = it }, Modifier.fillMaxWidth(), label = { Text("标签（逗号分隔，可留空）") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(assignees, { assignees = it }, Modifier.fillMaxWidth(), label = { Text("指派给（用户名，逗号分隔）") }, singleLine = true)
            Spacer(Modifier.height(20.dp))
        }
    }
}
