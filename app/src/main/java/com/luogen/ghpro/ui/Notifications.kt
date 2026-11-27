package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.luogen.ghpro.data.Notification
import com.luogen.ghpro.data.NotificationSubject
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.LocalNotice
import kotlinx.coroutines.launch

/** 把 App 自己记录的工作流/PR/发行版动态转换成通知列表项，旧的 GitHub 通知接口没有这些也要能看到。 */
private fun LocalNotice.asNotification(): Notification {
    val seg = (route ?: "").split("/")
    val full = if (seg.size >= 3) "${seg[1]}/${seg[2]}" else ""
    val type = when (kind) {
        "run", "run_start" -> "WorkflowRun"
        "pull", "pull_merge", "pull_state" -> "PullRequest"
        "release", "release_asset" -> "Release"
        "push" -> "Commit"
        else -> "Notification"
    }
    val reason = when (kind) {
        "run", "run_start" -> "ci_activity"
        "pull" -> "review_requested"
        "pull_merge", "pull_state" -> "state_change"
        else -> kind
    }
    return Notification(
        id = id,
        unread = id !in Cfg.notifReadIds,
        reason = reason,
        updated_at = try { java.time.Instant.ofEpochMilli(time).toString() } catch (e: Exception) { null },
        subject = NotificationSubject(title = title, url = route?.let { "ghpro://route/$it" }, type = type),
        repository = Repo(name = full.substringAfterLast('/'), full_name = full)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf(UiCache.notifAll) }
    val firstSnap = UiCache.notifSnaps[all]
    var list by remember { mutableStateOf<List<Notification>>(firstSnap?.list ?: emptyList()) }
    var loading by remember { mutableStateOf(firstSnap == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmClearAll by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState(firstSnap?.index ?: 0, firstSnap?.offset ?: 0)
    val tick by com.luogen.ghpro.data.Bus.tick.collectAsState()

    fun localAsNotifications(includeRead: Boolean = false): List<Notification> =
        Cfg.localNotices.map { it.asNotification() }.filter { includeRead || it.unread }

    fun savePos() {
        UiCache.notifAll = all
        UiCache.notifSnaps[all] = NotifSnap(list, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }

    fun notificationTime(n: Notification): Long {
        if (n.id.startsWith("local:")) {
            return Cfg.localNotices.firstOrNull { it.id == n.id }?.time ?: 0L
        }
        val raw = n.updated_at ?: return 0L
        return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
    }

    /**
     * 所有入口都走这里：
     * 1) 已清掉的 id 永久过滤；
     * 2) 设了自动清理小时数时，超时的远端 id 写入永久隐藏，超时的本地动态直接从 Cfg 删除；
     * 3) 本地动态已删除的，即使旧快照里还有，也不再显示。
     */
    fun prune(raw: List<Notification>): List<Notification> {
        val hours = Cfg.notifAutoClearHours
        if (hours > 0) {
            val cutoff = System.currentTimeMillis() - hours.toLong() * 3_600_000L
            val expiredRemote = raw.filter {
                !it.id.startsWith("local:") && notificationTime(it).let { t -> t > 0L && t < cutoff }
            }.map { it.id }
            if (expiredRemote.isNotEmpty()) Cfg.notifHiddenIds = Cfg.notifHiddenIds + expiredRemote
            val keepLocal = Cfg.localNotices.filter { it.time >= cutoff }
            if (keepLocal.size != Cfg.localNotices.size) Cfg.localNotices = keepLocal
        }
        val hidden = Cfg.notifHiddenIds
        val clearAt = Cfg.notifClearAt
        val localIds = Cfg.localNotices.map { it.id }.toSet()
        return raw.filter { n ->
            val notCleared = clearAt <= 0L || notificationTime(n) > clearAt
            n.id !in hidden && notCleared && (!n.id.startsWith("local:") || n.id in localIds)
        }
    }

    fun visiblyHide(ids: Collection<String>) {
        val remote = ids.filterNot { it.startsWith("local:") }.toSet()
        if (remote.isNotEmpty()) Cfg.notifHiddenIds = Cfg.notifHiddenIds + remote
        val local = ids.filter { it.startsWith("local:") }.toSet()
        if (local.isNotEmpty()) Cfg.localNotices = Cfg.localNotices.filterNot { it.id in local }
    }

    fun clearOne(n: Notification) {
        visiblyHide(listOf(n.id))
        list = list.filterNot { it.id == n.id }
        selected = selected - n.id
        savePos()
        scope.launch { snackbar.showSnackbar("已从本机清除这条通知") }
    }

    fun clearSelected() {
        val ids = selected
        if (ids.isEmpty()) return
        visiblyHide(ids)
        list = list.filterNot { it.id in ids }
        selected = emptySet()
        selecting = false
        savePos()
        scope.launch { snackbar.showSnackbar("已从本机清除 ${ids.size} 条通知") }
    }

    fun clearAll() {
        // 用“清除时刻”作为分水岭：比这一刻旧的远端/本地通知永久隐藏，之后的新通知照常出现。
        // 不依赖服务端分页，不会只清掉当前页；也不会因为离开页面导致清不干净。
        val now = System.currentTimeMillis()
        Cfg.notifClearAt = now
        Cfg.localNotices = emptyList()
        list = emptyList()
        selected = emptySet()
        selecting = false
        UiCache.notifAll = all
        UiCache.notifSnaps[all] = NotifSnap(emptyList(), 0, 0)
        UiCache.notifSnaps[!all] = NotifSnap(emptyList(), 0, 0)
        scope.launch {
            runCatching { listState.scrollToItem(0) }
            snackbar.showSnackbar("已清除全部通知，刷新后也不会回来")
        }
    }

    suspend fun load(showLoading: Boolean = true) {
        if (showLoading) loading = true
        try {
            val data = Api.notifications(all)
            // 本地已读掩码：服务端还没同步时也不让已读通知回到“未读”
            val read = Cfg.notifReadIds
            val masked = data.map { if (it.id in read) it.copy(unread = false) else it }
            val locals = localAsNotifications(all)
            list = prune(if (all) locals + masked else locals + masked.filter { it.unread })
            error = null
            UiCache.notifAll = all
            UiCache.notifSnaps[all] = NotifSnap(list, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        } catch (e: Exception) {
            val locals = localAsNotifications(all)
            if (list.isEmpty()) list = prune(locals)
            error = if (locals.isEmpty()) e.message else null
        }
        loading = false
    }

    // 进入页面 / 切“未读、全部”时优先恢复上次快照；有快照就绝不重新请求，也不会跳回顶部
    LaunchedEffect(all) {
        UiCache.notifAll = all
        selecting = false
        selected = emptySet()
        val snap = UiCache.notifSnaps[all]
        if (snap != null) {
            // 快照里可能还没有刚产生的本地动态，先合并再显示，避免“后台通知了但通知页没有”。
            val locals = localAsNotifications(all)
            val remote = snap.list.filter { !it.id.startsWith("local:") }
            val merged = locals + remote
            list = prune(if (all) merged else merged.filter { it.unread })
            error = null
            loading = false
            if (snap.index > 0 || snap.offset > 0) listState.scrollToItem(snap.index, snap.offset)
        } else {
            list = emptyList()
            load(true)
        }
    }
    DisposableEffect(all) {
        val tab = all
        onDispose {
            // 必须用离开时的 tab 保存；不能用可变状态 all，否则切页时会把旧列表存进新页签
            UiCache.notifSnaps[tab] = NotifSnap(list, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
    LaunchedEffect(tick) {
        if (tick <= 0) return@LaunchedEffect
        val locals = localAsNotifications(all)
        val remote = list.filter { !it.id.startsWith("local:") }
        val merged = locals + remote
        list = prune(if (all) merged else merged.filter { it.unread })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selecting) "已选 ${selected.size} 条" else if (all) "全部通知" else "未读通知", fontSize = 15.sp)
                },
                navigationIcon = { IconButton({ savePos(); nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton({ scope.launch { load(true) } }) { Icon(Icons.Filled.Refresh, "刷新通知") }
                    IconButton({
                        if (selecting) { selecting = false; selected = emptySet() } else selecting = true
                    }) { Icon(if (selecting) Icons.Filled.Close else Icons.Filled.Check, "选择通知") }
                    IconButton({ confirmClearAll = true }) { Icon(Icons.Filled.Delete, "清除通知") }
                    IconButton({
                        scope.launch {
                            try {
                                Api.markAllRead()
                                Cfg.notifReadIds = Cfg.notifReadIds + list.map { it.id }
                                // 未读页签里“全部已读”后必须立刻清空，不能留在列表里装未读
                                list = if (all) list.map { it.copy(unread = false) } else emptyList()
                                savePos()
                                snackbar.showSnackbar("已全部标记为已读")
                            } catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                        }
                    }) { Icon(Icons.Filled.DoneAll, "全部已读") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !all, onClick = { all = false }, label = { Text("未读", fontSize = 11.sp) })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = all, onClick = { all = true }, label = { Text("全部", fontSize = 11.sp) })
                Spacer(Modifier.weight(1f))
                Text(if (all) "${list.size} 条" else "${list.count { it.unread }} 未读 / ${list.size} 条",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                loading -> LoadingBox()
                error != null -> ErrorBox(error) { scope.launch { load(true) } }
                list.isEmpty() -> EmptyBox("没有通知（GitHub 通知需要你在网页端 Watch 仓库）")
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(list, key = { it.id }) { n ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (n.unread) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)
                                .clickable {
                                    if (selecting) {
                                        selected = if (n.id in selected) selected - n.id else selected + n.id
                                    } else {
                                        // 先把当前位置存下来，再从“全部”点进某条：返回后仍是全部页签 + 原位置
                                        savePos()
                                        val clicked = n
                                        Cfg.notifReadIds = Cfg.notifReadIds + clicked.id
                                        if (all) {
                                            list = list.map { if (it.id == clicked.id) it.copy(unread = false) else it }
                                        } else {
                                            // 未读页签：点过就立即从“未读”列表消失，返回时也不会又冒出来
                                            list = list.filter { it.id != clicked.id }
                                        }
                                        UiCache.notifSnaps[all] = NotifSnap(list, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                        if (!clicked.id.startsWith("local:")) {
                                            scope.launch { runCatching { Api.markThreadRead(clicked.id) } }
                                        }
                                        val url = n.subject?.url ?: ""
                                        val repo = n.repository?.full_name ?: ""
                                        val target = notificationTarget(n)
                                        if (target != null) nav.navigate(target)
                                        else if (repo.isNotBlank()) nav.navigate("repo/$repo")
                                        else openUrl(ctx, url)
                                    }
                                }.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (selecting) {
                                Checkbox(
                                    checked = n.id in selected,
                                    onCheckedChange = { on ->
                                        selected = if (on) selected + n.id else selected - n.id
                                    }
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TinyTag(
                                        when (n.subject?.type) {
                                            "Issue" -> "议题"
                                            "PullRequest" -> "拉取请求"
                                            "Discussion" -> "讨论"
                                            "Release" -> "发行版"
                                            "WorkflowRun" -> "工作流"
                                            "Commit" -> "提交"
                                            else -> n.subject?.type ?: "通知"
                                        },
                                        color = if (n.unread) GH.blue else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(n.subject?.title ?: "", fontSize = 12.sp, fontWeight = if (n.unread) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (n.unread) Box(Modifier.size(7.dp).background(GH.blue, androidx.compose.foundation.shape.CircleShape))
                                }
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val repo = n.repository?.full_name ?: ""
                                    if (repo.isNotBlank()) {
                                        Text(repo, fontSize = 10.sp, color = GH.blue,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.then(if (selecting) Modifier else Modifier.clickable {
                                                // 单独点仓库名只进仓库首页；点其他区域仍然进具体议题/PR/提交。
                                                savePos()
                                                nav.navigate("repo/$repo")
                                            }))
                                    }
                                    Text(" · ${reasonCn(n.reason)} · ${timeAgo(n.updated_at)}",
                                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false))
                                }
                            }
                            if (!selecting) {
                                IconButton({ clearOne(n) }, Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Delete, "清除这条通知", Modifier.size(17.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Divider1()
                    }
                }
            }
            if (selecting) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selected.size} 条", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton({
                            val visible = list.map { it.id }.toSet()
                            selected = if (selected.containsAll(visible) && visible.isNotEmpty()) emptySet() else visible
                        }) { Text(if (selected.size == list.size && list.isNotEmpty()) "取消全选" else "全选", fontSize = 12.sp) }
                        TextButton(enabled = selected.isNotEmpty(), onClick = { clearSelected() }) {
                            Text("清除所选", fontSize = 12.sp, color = GH.red)
                        }
                    }
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清除全部通知？", fontSize = 15.sp) },
            text = {
                Text("会从本机彻底删除全部通知（未读和已读都包括），并写入本机删除记录；之后刷新、重进 App 都不会再显示。不会删除 GitHub 服务端记录。", fontSize = 12.sp)
            },
            confirmButton = {
                TextButton({
                    confirmClearAll = false
                    clearAll()
                }) { Text("清除全部", color = GH.red) }
            },
            dismissButton = { TextButton({ confirmClearAll = false }) { Text("取消") } }
        )
    }
}

/** 通知列表点击：发行版/提交/工作流/讨论/议题/PR 都直接到详情，不再只回仓库首页。 */
private fun notificationTarget(n: Notification): String? {
    val repo = n.repository?.full_name ?: return null
    val url = n.subject?.url ?: return "repo/$repo"
    if (url.startsWith("ghpro://route/")) return url.removePrefix("ghpro://route/")
    Regex("/repos/([^/]+)/([^/]+)/(issues|pulls|discussions)/(\\d+)").find(url)?.let { m ->
        val (o, r, kind, num) = m.destructured
        val routeKind = when (kind) { "issues" -> "issue"; "pulls" -> "pr"; else -> "discussion" }
        return "$routeKind/$o/$r/$num"
    }
    Regex("/repos/([^/]+)/([^/]+)/releases/(\\d+)").find(url)?.let { m ->
        val (o, r, id) = m.destructured
        return "release/$o/$r/$id"
    }
    Regex("/repos/([^/]+)/([^/]+)/commits/([0-9a-fA-F]+)").find(url)?.let { m ->
        val (o, r, sha) = m.destructured
        return "commit/$o/$r/$sha"
    }
    Regex("/repos/([^/]+)/([^/]+)/actions/runs/(\\d+)").find(url)?.let { m ->
        val (o, r, id) = m.destructured
        return "run/$o/$r/$id"
    }
    return "repo/$repo"
}
