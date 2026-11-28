package com.luogen.ghpro.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
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
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun IssueListTab(nav: NavController, owner: String, name: String) {
    val scope = rememberCoroutineScope()
    val repoKey = "$owner/$name"
    var state by remember { mutableStateOf(UiCache.issueFilter[repoKey] ?: "open") }
    fun listKey() = "$owner/$name|$state"
    var list by remember { mutableStateOf(UiCache.repoIssues[listKey()] ?: emptyList()) }
    var loading by remember { mutableStateOf(list.isEmpty() && UiCache.issueSyncAt[listKey()] == null) }
    val listState = rememberLazyListState(
        UiCache.issueIndex[repoKey] ?: 0,
        UiCache.issueOffset[repoKey] ?: 0
    )
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    val issuePoke by Bus.issuePoke.collectAsState()
    val issueLocalPoke by Bus.issueLocalPoke.collectAsState()

    // 从哪个筛选点进去，回来就还是哪个筛选、哪个位置。
    DisposableEffect(repoKey) {
        onDispose {
            UiCache.issueFilter[repoKey] = state
            UiCache.issueIndex[repoKey] = listState.firstVisibleItemIndex
            UiCache.issueOffset[repoKey] = listState.firstVisibleItemScrollOffset
        }
    }
    LaunchedEffect(listState, repoKey) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (i, o) ->
                UiCache.issueFilter[repoKey] = state
                UiCache.issueIndex[repoKey] = i
                UiCache.issueOffset[repoKey] = o
            }
    }

    suspend fun load(p: Int, replace: Boolean, silent: Boolean = false) {
        if (p == 1 && !silent) loading = true
        try {
            val pg = Api.issues(owner, name, state, p)
            val fetched = pg.items.filter { it.pull_request == null }
            val now = System.currentTimeMillis()
            val local = UiCache.issueOverrides.entries
                .filter { it.key.startsWith("$owner/$name#") && now - it.value.second in 0..10_000L }
                .map { it.value.first }
            fun merge(items: List<Issue>): List<Issue> {
                var out = items
                local.forEach { o ->
                    out = out.filter { it.number != o.number }
                    if (state == "all" || state == o.state) out = listOf(o) + out
                }
                return out
            }
            val pageItems = merge(fetched)
            list = if (replace) pageItems else list + fetched
            hasNext = pg.hasNext
            page = p
            if (replace) UiCache.repoIssues[listKey()] = list
            UiCache.issueSyncAt[listKey()] = System.currentTimeMillis()
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (!silent) error = e.message }
        loading = false
    }

    fun pick(s: String) {
        if (s == state) return
        state = s
        UiCache.issueFilter[repoKey] = s
        UiCache.issueIndex[repoKey] = 0
        UiCache.issueOffset[repoKey] = 0
        val cached = UiCache.repoIssues["$owner/$name|$s"]
        list = cached.orEmpty()
        loading = cached == null && UiCache.issueSyncAt["$owner/$name|$s"] == null
        error = null
        page = 1
        hasNext = false
        scope.launch { listState.scrollToItem(0) }
    }

    LaunchedEffect(state, issuePoke, issueLocalPoke) {
        UiCache.issueFilter[repoKey] = state
        val cached = UiCache.repoIssues[listKey()]
        val syncAt = UiCache.issueSyncAt[listKey()]
        // 缓存已经因乐观操作更新（例如刚关闭的议题已从“打开中”移除），先立刻换上，不能等网络
        list = cached.orEmpty()
        loading = cached == null && syncAt == null
        error = null
        // 自己的关闭/重开刚做了乐观更新：先让列表用本地缓存；本地操作成功前不要拉服务端旧数据
        if (issueLocalPoke > 0 && issuePoke <= issueLocalPoke) {
            val age = System.currentTimeMillis() - issueLocalPoke
            if (age in 0..1500L) delay(1500L - age)
        }
        // 缓存只负责秒开；每次进入都立刻后台补最新，不再等 30 秒。
        if (cached == null || syncAt == null) load(1, true)
        else load(1, true, silent = true)
    }
    // 页面停留时后台补最新；不阻塞列表，也不改变当前滚动位置。
    LaunchedEffect(state) {
        while (isActive) {
            delay(2_000L)
            if (page == 1 && !loading) {
                val at = UiCache.issueSyncAt[listKey()] ?: 0L
                if (System.currentTimeMillis() - at >= 2_000L) runCatching { load(1, true, silent = true) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state == "open", onClick = { pick("open") }, label = { Text("打开中", fontSize = 12.sp) })
            Spacer(Modifier.width(5.dp))
            FilterChip(selected = state == "closed", onClick = { pick("closed") }, label = { Text("已关闭", fontSize = 12.sp) })
            Spacer(Modifier.width(5.dp))
            FilterChip(selected = state == "all", onClick = { pick("all") }, label = { Text("全部", fontSize = 12.sp) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { nav.navigate("newissue/$owner/$name") },
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(Icons.Filled.Add, "新建议题", Modifier.size(15.dp), tint = GH.blue)
                Text("新建", fontSize = 11.sp)
            }
        }
        when {
            loading && list.isEmpty() -> LoadingBox()
            error != null -> ErrorBox(error) { scope.launch { load(1, true) } }
            list.isEmpty() -> EmptyBox(if (state == "all") "没有议题" else "没有${if (state == "open") "打开中" else "已关闭"}的议题")
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
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

private val replyMarker = Regex("""<!--\s*ghpro-reply-to:(\d+)\s*-->""")
private data class ThreadedComment(val comment: Comment, val depth: Int)

private fun commentParent(c: Comment): Long? =
    replyMarker.find(c.body ?: "")?.groupValues?.getOrNull(1)?.toLongOrNull()

private fun commentClean(c: Comment): String =
    replyMarker.replace(c.body ?: "", "").trim()

private fun commentThread(list: List<Comment>): List<ThreadedComment> {
    val ids = list.map { it.id }.toSet()
    val children = list.filter { commentParent(it) != null }.groupBy { commentParent(it)!! }
    val out = ArrayList<ThreadedComment>()
    val seen = HashSet<Long>()
    fun walk(c: Comment, depth: Int) {
        if (!seen.add(c.id)) return
        out.add(ThreadedComment(c, minOf(depth, 4)))
        children[c.id]?.forEach { walk(it, depth + 1) }
    }
    list.filter { commentParent(it) == null || commentParent(it) !in ids }.forEach { walk(it, 0) }
    list.filter { it.id !in seen }.forEach { walk(it, 0) }
    return out
}

@Composable
private fun IssueCommentRow(
    nav: NavController,
    c: Comment,
    depth: Int,
    selected: Boolean,
    onReply: () -> Unit
) {
    val login = c.user?.login.orEmpty()
    Row(Modifier.fillMaxWidth().padding(start = (depth * 16).dp, top = 6.dp, bottom = 6.dp)
        .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)) else Modifier)) {
        Avatar(
            c.user?.avatar_url, 24,
            Modifier.clickable(enabled = login.isNotBlank()) {
                if (login.isNotBlank()) nav.navigate("user/$login")
            },
            name = login
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    login, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(enabled = login.isNotBlank()) {
                        if (login.isNotBlank()) nav.navigate("user/$login")
                    }
                )
                Spacer(Modifier.width(6.dp))
                Text(timeAgo(c.created_at), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (depth > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("回复", fontSize = 9.sp, color = GH.blue)
                }
            }
            Spacer(Modifier.height(3.dp))
            val body = commentClean(c)
            if (body.isNotBlank()) {
                TranslateBlock(
                    text = body,
                    style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                    compact = true
                )
            }
            TextButton(onClick = onReply, modifier = Modifier.height(24.dp)) { Text("回复", fontSize = 10.sp) }
        }
    }
    Divider1()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var reactions by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var reactionBusy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    var sending by remember { mutableStateOf(false) }
    var actionMsg by remember { mutableStateOf<String?>(null) }
    var opSeq by remember { mutableStateOf(0) }
    var lockOpSeq by remember { mutableStateOf(0) }
    var pendingOps by remember { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val commentBring = remember { BringIntoViewRequester() }
    val ikey = "$owner/$name#$number"
    val scrollState = rememberScrollState()
    var restored by remember(number) { mutableStateOf(false) }
    LaunchedEffect(restored, loading) {
        if (issue != null && !loading && !restored) {
            // 等顶层信息和评论完成一次布局再恢复，否则会被夹到 0。
            delay(80)
            scrollState.scrollTo(UiCache.issueScroll[ikey] ?: 0)
            restored = true
        }
    }
    LaunchedEffect(scrollState, restored) {
        if (restored) snapshotFlow { scrollState.value }.collect { UiCache.issueScroll[ikey] = it }
    }
    // 输入时让评论框自己滚进可视区：键盘弹出、文字换行变高都不会被盖住。
    LaunchedEffect(text) { if (text.isNotEmpty()) runCatching { commentBring.bringIntoView() } }

    suspend fun load() {
        try {
            issue = Api.issue(owner, name, number)
            comments = Api.issueComments(owner, name, number)
            reactions = runCatching { Api.issueReactions(owner, name, number) }.getOrDefault(emptyList())
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        loading = false
    }

    fun cacheIssue(up: Issue, adjustCount: Boolean = true) {
        UiCache.issueOverrides["$owner/$name#${up.number}"] = up to System.currentTimeMillis()
        UiCache.repoIssues.keys.filter { it.startsWith("$owner/$name|") }.forEach { k ->
            val st = k.substringAfterLast('|')
            val old = UiCache.repoIssues[k].orEmpty()
            val rest = old.filter { it.number != up.number }
            UiCache.repoIssues[k] = if (st == "all" || st == up.state) listOf(up) + rest else rest
        }
        if (adjustCount) UiCache.repoData["$owner/$name"]?.let { r ->
            if (up.state == "open") {
                UiCache.repoData["$owner/$name"] = r.copy(open_issues_count = r.open_issues_count + 1)
                IssueCounts.adjust("$owner/$name", 1)
            } else {
                UiCache.repoData["$owner/$name"] = r.copy(open_issues_count = (r.open_issues_count - 1).coerceAtLeast(0))
                IssueCounts.adjust("$owner/$name", -1)
            }
        }
    }

    LaunchedEffect(number) {
        if (UiCache.repoData["$owner/$name"] == null) runCatching { UiCache.repoData["$owner/$name"] = Api.repo(owner, name) }
        load()
        while (isActive) {
            delay(1_000L)
            if (pendingOps == 0) {
                runCatching {
                    val fresh = Api.issue(owner, name, number)
                    val ov = UiCache.issueOverrides["$owner/$name#$number"]
                    if (ov != null && System.currentTimeMillis() - ov.second in 0..10_000L) {
                        // 本地刚点了关闭/重开/锁定，服务端可能还返回旧状态，先用本地乐观状态
                        issue = fresh.copy(state = ov.first.state, state_reason = ov.first.state_reason,
                            locked = ov.first.locked)
                    } else issue = fresh
                    comments = Api.issueComments(owner, name, number)
                    reactions = runCatching { Api.issueReactions(owner, name, number) }.getOrDefault(reactions)
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
                    Column(Modifier.clickable { nav.navigate("repo/$owner/$name") }) {
                        Text("议题 #$number", fontSize = 15.sp, maxLines = 1, softWrap = false)
                        Text("$owner/$name", fontSize = 10.sp, color = GH.blue,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                },
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
                                val optimistic = old.copy(state = if (targetOpen) "open" else "closed",
                                    state_reason = if (targetOpen) null else "completed")
                                issue = optimistic
                                cacheIssue(optimistic)
                                Bus.issueLocalChanged()
                                quickToast(if (targetOpen) "已重新打开" else "已关闭")
                                pendingOps++
                                Bus.actions.launch {
                                    kotlinx.coroutines.delay(150L)
                                    if (seq != opSeq) { pendingOps = (pendingOps - 1).coerceAtLeast(0); return@launch }
                                    try {
                                        val up = Api.patchIssue(owner, name, number,
                                            if (targetOpen) mapOf("state" to "open")
                                            else mapOf("state" to "closed", "state_reason" to "completed"))
                                        if (seq == opSeq) { issue = up; cacheIssue(up, adjustCount = false) }
                                        Cfg.touchRepo("$owner/$name")
                                        runCatching { UiCache.repoData["$owner/$name"] = Api.repo(owner, name) }
                                        Bus.issuesChanged()
                                    } catch (e: Exception) {
                                        if (seq == opSeq) { issue = old; cacheIssue(old, adjustCount = true) }
                                        Bus.issuesChanged()
                                        quickToast("失败：${e.message}")
                                    }
                                    pendingOps = (pendingOps - 1).coerceAtLeast(0)
                                }
                            }) {
                                Icon(if (isOpen) Icons.Filled.Check else Icons.Filled.Refresh, null, Modifier.size(16.dp))
                                Text(if (isOpen) "关闭" else "重开", fontSize = 12.sp)
                            }
                        }
                        if (canPush) {
                            // 图标和文字都表示“当前状态”，不是“点击后要做什么”：
                            // 已锁定=闭合锁+已锁定；未锁定=打开锁+未锁定。点按后提示新的状态。
                            TextButton(
                                onClick = {
                                    val old = issueNow
                                    val targetLocked = !old.locked
                                    val seq = ++lockOpSeq
                                    val optimisticLock = old.copy(locked = targetLocked)
                                    issue = optimisticLock
                                    UiCache.issueOverrides["$owner/$name#$number"] = optimisticLock to System.currentTimeMillis()
                                    quickToast(if (targetLocked) "已锁定讨论" else "已解锁讨论")
                                    pendingOps++
                                    Bus.actions.launch {
                                        kotlinx.coroutines.delay(150L)
                                        if (seq != lockOpSeq) { pendingOps = (pendingOps - 1).coerceAtLeast(0); return@launch }
                                        try {
                                            Api.lockIssue(owner, name, number, targetLocked)
                                            if (seq == lockOpSeq) {
                                                UiCache.issueOverrides["$owner/$name#$number"] = optimisticLock to System.currentTimeMillis()
                                                Cfg.touchRepo("$owner/$name")
                                                Bus.issuesChanged()
                                            }
                                        } catch (e: Exception) {
                                            if (seq == lockOpSeq) {
                                                issue = old
                                                UiCache.issueOverrides["$owner/$name#$number"] = old to System.currentTimeMillis()
                                                quickToast("失败：${e.message}")
                                            }
                                        }
                                        pendingOps = (pendingOps - 1).coerceAtLeast(0)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Icon(
                                    if (issueNow.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    if (issueNow.locked) "当前已锁定，点击解锁" else "当前未锁定，点击锁定",
                                    Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(if (issueNow.locked) "已锁定" else "未锁定", fontSize = 10.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            when {
                loading -> LoadingBox()
                error != null -> ErrorBox(error) { scope.launch { load() } }
                issue != null -> {
                    // 整页一起滚动：顶部信息仍保留在最上面，往上滑后正文/评论能占满全屏，
                    // 不再被固定头部压成一条很小的可滑动区域。
                    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 14.dp, vertical = 6.dp)) {
                        TranslateBlock(
                            text = issue!!.title,
                            style = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp),
                            compact = true,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateChip(issue!!.state)
                            Spacer(Modifier.width(8.dp))
                            Text("#${issue!!.number} · ${issue!!.user?.login} · ${timeAgo(issue!!.created_at)}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        issue!!.labels?.takeIf { it.isNotEmpty() }?.let {
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) { it.forEach { l -> LabelChip(l) } }
                        }
                        // 真实 reactions：点一下会调用 GitHub 添加/删除，不是本地假计数。
                        val emojiMap = listOf(
                            "+1" to "👍", "-1" to "👎", "laugh" to "😄", "hooray" to "🎉",
                            "confused" to "😕", "heart" to "❤️", "rocket" to "🚀", "eyes" to "👀"
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            emojiMap.forEach { (content, emoji) ->
                                val count = reactions.count { it.get("content")?.asString == content }
                                val mine = reactions.any {
                                    it.get("content")?.asString == content && it.getAsJsonObject("user")?.get("login")?.asString == Cfg.login
                                }
                                FilterChip(
                                    selected = mine,
                                    enabled = !reactionBusy,
                                    onClick = {
                                        reactionBusy = true
                                        Bus.actions.launch {
                                            try {
                                                reactions = Api.toggleIssueReaction(owner, name, number, content)
                                                Cfg.touchRepo("$owner/$name")
                                            } catch (e: Exception) { quickToast("表态失败：${e.message}") }
                                            finally { reactionBusy = false }
                                        }
                                    },
                                    label = { Text("$emoji $count", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                )
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        TranslateBlock(issue!!.body ?: "（没有正文）",
                            style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                            auto = Cfg.translateAuto)
                        Spacer(Modifier.height(14.dp))
                        Divider1()
                        Spacer(Modifier.height(10.dp))
                        Text("讨论（${comments.size}）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        commentThread(comments).forEach { tc ->
                            IssueCommentRow(nav, tc.comment, tc.depth, replyTo?.id == tc.comment.id) { replyTo = tc.comment }
                        }
                        Spacer(Modifier.height(10.dp))
                        replyTo?.let { target ->
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("正在回复 @${target.user?.login ?: ""}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { replyTo = null }) { Text("取消", fontSize = 11.sp) }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth()
                            .bringIntoViewRequester(commentBring)
                            .onFocusChanged { if (it.isFocused) scope.launch { runCatching { commentBring.bringIntoView() } } },
                            label = { Text(if (replyTo == null) "写下你的评论…" else "回复 @${replyTo?.user?.login ?: ""}…") }, minLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = text.isNotBlank() && !sending, onClick = {
                                Bus.actions.launch {
                                    sending = true
                                    val target = replyTo
                                    try {
                                        val raw = text.trim()
                                        val body = if (target != null) {
                                            val quote = commentClean(target).lines().take(4).joinToString("\n> ")
                                            "> $quote\n\n$raw\n\n<!-- ghpro-reply-to:${target.id} -->"
                                        } else raw
                                        val posted = Api.addComment(owner, name, number, body)
                                        text = ""
                                        replyTo = null
                                        // 先把刚发出的评论直接插进列表，页面立刻看到；后台轮询再校准
                                        comments = comments + posted
                                        Cfg.touchRepo("$owner/$name")
                                        Bus.issuesChanged()
                                        quickToast("已发表评论")
                                    } catch (e: Exception) { quickToast("失败：${e.message}") }
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
                                Cfg.touchRepo("$owner/$name")
                                UiCache.issueOverrides["$owner/$name#${created.number}"] = created to System.currentTimeMillis()
                                UiCache.repoIssues.keys.filter { it.startsWith("$owner/$name|") }.forEach { k ->
                                    val st = k.substringAfterLast('|')
                                    val oldList = UiCache.repoIssues[k].orEmpty().filter { it.number != created.number }
                                    UiCache.repoIssues[k] = if (st == "all" || st == created.state) listOf(created) + oldList else oldList
                                }
                                UiCache.repoData["$owner/$name"]?.let { r ->
                                    if (created.state == "open") {
                                        UiCache.repoData["$owner/$name"] = r.copy(open_issues_count = r.open_issues_count + 1)
                                        IssueCounts.adjust("$owner/$name", 1)
                                    }
                                }
                                Bus.issueLocalChanged()
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
