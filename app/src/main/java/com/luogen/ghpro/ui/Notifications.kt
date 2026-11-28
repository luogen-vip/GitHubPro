package com.luogen.ghpro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.delay
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
        "release" -> "release"
        "release_asset" -> "release_asset"
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotificationsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf(UiCache.notifAll) }
    val firstSnap = UiCache.notifSnaps[all]
    var list by remember { mutableStateOf<List<Notification>>(firstSnap?.list ?: emptyList()) }
    var loading by remember { mutableStateOf(firstSnap == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var clockMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState(firstSnap?.index ?: 0, firstSnap?.offset ?: 0)
    val tick by com.luogen.ghpro.data.Bus.tick.collectAsState()

    // “刚刚 / X 分钟前 / 今天 HH:mm”必须随时间自己走；跨过午夜后自动变成“昨天 HH:mm”。
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            clockMs = System.currentTimeMillis()
        }
    }

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

    /** 退出选择模式：不管是长按进来的，还是点“选择”进来的，都回到没出现复选框的状态。 */
    fun exitSelecting() {
        selecting = false
        selected = emptySet()
        snackbar.currentSnackbarData?.dismiss()
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
        exitSelecting()
        savePos()
        scope.launch { snackbar.showSnackbar("已从本机清除 ${ids.size} 条通知") }
    }

    /** 立即进入选择模式：长按某条时直接把这一条勾上；只切 UI，不等待网络。 */
    fun enterSelecting(first: Notification? = null) {
        selected = first?.let { setOf(it.id) } ?: emptySet()
        selecting = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                if (first == null) "选择模式已开启：点通知前的方框勾选"
                else "已选中 1 条：可继续点方框，或点顶部全选"
            )
        }
    }

    fun toggleSelected(n: Notification) {
        if (n.id in selected) {
            // 取消勾选最后一条，直接退出选择模式，不留下一个只剩复选框的空状态。
            if (selected.size <= 1) exitSelecting()
            else selected = selected - n.id
        } else {
            selected = selected + n.id
        }
    }

    /** 长按任意通知进入选择；已经在选择模式时，长按等同于再勾上这一条。 */
    fun longPressNotification(n: Notification) {
        if (!selecting) {
            enterSelecting(n)
        } else {
            selected = selected + n.id
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    /** 单击通知：普通模式打开详情；选择模式只切换勾选，不跳页。 */
    fun clickNotification(n: Notification) {
        if (selecting) {
            toggleSelected(n)
            return
        }
        // 先把当前位置存下来，再从“全部”点进某条：返回后仍是全部页签 + 原位置
        savePos()
        Cfg.notifReadIds = Cfg.notifReadIds + n.id
        if (all) {
            list = list.map { if (it.id == n.id) it.copy(unread = false) else it }
        } else {
            // 未读页签：点过就立即从“未读”列表消失，返回时也不会又冒出来
            list = list.filter { it.id != n.id }
        }
        UiCache.notifSnaps[all] = NotifSnap(list, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        if (!n.id.startsWith("local:")) {
            scope.launch { runCatching { Api.markThreadRead(n.id) } }
        }
        val url = n.subject?.url ?: ""
        val repo = n.repository?.full_name ?: ""
        val target = notificationTarget(n)
        if (target != null) nav.navigate(target)
        else if (repo.isNotBlank()) nav.navigate("repo/$repo")
        else openUrl(ctx, url)
    }

    /**
     * 全部标记为已读必须“先变 UI、后同步服务端”。
     * 旧实现先 await Api.markAllRead()，网络慢时用户点 3~4 次都看不到变化；
     * 这里先写本机已读掩码并立刻清空未读页签，再在后台同步 GitHub。
     */
    fun markAllReadNow() {
        val targets = list.filter { it.unread }
        if (targets.isEmpty()) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("没有未读通知")
            }
            return
        }
        val ids = targets.map { it.id }.toSet()
        Cfg.notifReadIds = Cfg.notifReadIds + ids

        // 当前页签立即变成已读/清空，不等网络。
        if (all) {
            list = list.map { if (it.id in ids) it.copy(unread = false) else it }
            UiCache.notifSnaps[false] = NotifSnap(emptyList(), 0, 0)
        } else {
            list = emptyList()
            UiCache.notifSnaps[false] = NotifSnap(emptyList(), 0, 0)
            UiCache.notifSnaps[true]?.let { snap ->
                UiCache.notifSnaps[true] = snap.copy(
                    list = snap.list.map { if (it.id in ids) it.copy(unread = false) else it }
                )
            }
        }
        savePos()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("已全部标记为已读")
        }
        scope.launch {
            try {
                Api.markAllRead()
            } catch (e: Exception) {
                // 本机已经读掉，不把列表退回“未读”让用户又点一次；只提示服务端没同步上。
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("本机已标记为已读，GitHub 同步失败：${e.message}")
            }
        }
    }

    /** 选择模式顶部“全选/取消全选”；只对当前页签的可见通知生效。 */
    fun selectVisible() {
        val visible = list.map { it.id }.toSet()
        if (visible.isEmpty()) { exitSelecting(); return }
        // 点“取消全选”把最后一条也取消后，同样直接退出选择模式。
        if (visible.all { it in selected }) exitSelecting() else selected = visible
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
            // 本机已读掩码也要重新套一遍，否则“全部已读”后切到全部页签又会看到旧未读标记。
            val read = Cfg.notifReadIds
            val locals = localAsNotifications(all)
            val remote = snap.list.filter { !it.id.startsWith("local:") }
                .map { if (it.id in read) it.copy(unread = false) else it }
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

    BackHandler(enabled = selecting) { exitSelecting() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selecting) "已选 ${selected.size} 条" else if (all) "全部通知" else "未读通知", fontSize = 15.sp)
                },
                navigationIcon = {
                    IconButton({
                        if (selecting) exitSelecting()
                        else { savePos(); nav.popBackStack() }
                    }) { Icon(Icons.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (selecting) {
                        TextButton(
                            enabled = list.isNotEmpty(),
                            onClick = { selectVisible() }
                        ) {
                            Text(if (selected.size == list.size && list.isNotEmpty()) "取消全选" else "全选", fontSize = 12.sp)
                        }
                        TextButton(
                            enabled = selected.isNotEmpty(),
                            onClick = { clearSelected() }
                        ) { Text("清除所选", fontSize = 12.sp, color = GH.red) }
                        IconButton({ exitSelecting() }) {
                            Icon(Icons.Filled.Close, "退出选择")
                        }
                    } else {
                        IconButton({ scope.launch { load(true) } }) { Icon(Icons.Filled.Refresh, "刷新通知") }
                        IconButton({ enterSelecting() }, enabled = list.isNotEmpty()) {
                            Icon(Icons.Filled.Check, "选择通知")
                        }
                        IconButton({ confirmClearAll = true }) { Icon(Icons.Filled.Delete, "清除全部通知") }
                        IconButton({ markAllReadNow() }) { Icon(Icons.Filled.DoneAll, "全部标记为已读") }
                    }
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
            if (selecting) {
                Text(
                    "选择模式：点通知前的方框勾选，顶部可全选或清除所选",
                    fontSize = 10.sp,
                    color = GH.blue,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
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
                                .combinedClickable(
                                    onClick = { clickNotification(n) },
                                    onLongClick = { longPressNotification(n) }
                                ).padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (selecting) {
                                Checkbox(
                                    checked = n.id in selected,
                                    onCheckedChange = { toggleSelected(n) }
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
                                    Text(
                                        listOfNotNull(repo.takeIf { it.isNotBlank() }, reasonCn(n.reason).takeIf { it.isNotBlank() })
                                            .joinToString(" · "),
                                        fontSize = 10.sp,
                                        color = if (repo.isNotBlank()) GH.blue else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).then(
                                            if (repo.isBlank() || selecting) Modifier
                                            else Modifier.combinedClickable(
                                                onClick = {
                                                    // 单独点仓库名只进仓库首页；点其他区域仍然进具体议题/PR/提交。
                                                    savePos()
                                                    nav.navigate("repo/$repo")
                                                },
                                                onLongClick = { longPressNotification(n) }
                                            )
                                        )
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        notifTime(n.updated_at, clockMs),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
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
