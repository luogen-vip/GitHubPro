package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import kotlinx.coroutines.launch

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
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState(firstSnap?.index ?: 0, firstSnap?.offset ?: 0)

    fun savePos() {
        UiCache.notifAll = all
        UiCache.notifSnaps[all] = NotifSnap(list, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }

    suspend fun load(showLoading: Boolean = true) {
        if (showLoading) loading = true
        try {
            val data = Api.notifications(all)
            // 本地已读掩码：服务端还没同步时也不让已读通知回到“未读”
            val read = Cfg.notifReadIds
            val masked = data.map { if (it.id in read) it.copy(unread = false) else it }
            list = if (all) masked else masked.filter { it.unread }
            error = null
            UiCache.notifAll = all
            UiCache.notifSnaps[all] = NotifSnap(list, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        } catch (e: Exception) { error = e.message }
        loading = false
    }

    // 进入页面 / 切“未读、全部”时优先恢复上次快照；有快照就绝不重新请求，也不会跳回顶部
    LaunchedEffect(all) {
        UiCache.notifAll = all
        val snap = UiCache.notifSnaps[all]
        if (snap != null) {
            list = snap.list
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (all) "全部通知" else "未读通知", fontSize = 15.sp) },
                navigationIcon = { IconButton({ savePos(); nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton({ scope.launch { load(true) } }) { Icon(Icons.Filled.Refresh, "刷新通知") }
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
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (n.unread) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)
                                .clickable {
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
                                    scope.launch { runCatching { Api.markThreadRead(clicked.id) } }
                                    val url = n.subject?.url ?: ""
                                    val repo = n.repository?.full_name ?: ""
                                    val target = notificationTarget(n)
                                    if (target != null) nav.navigate(target)
                                    else if (repo.isNotBlank()) nav.navigate("repo/$repo")
                                    else openUrl(ctx, url)
                                }.padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TinyTag(
                                    when (n.subject?.type) {
                                        "Issue" -> "议题"
                                        "PullRequest" -> "拉取请求"
                                        "Discussion" -> "讨论"
                                        "Release" -> "发行版"
                                        "WorkflowRun" -> "工作流"
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
                            Text("${n.repository?.full_name ?: ""} · ${reasonCn(n.reason)} · ${timeAgo(n.updated_at)}",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Divider1()
                    }
                }
            }
        }
    }
}

/** 通知列表点击：发行版/提交/工作流/讨论/议题/PR 都直接到详情，不再只回仓库首页。 */
private fun notificationTarget(n: Notification): String? {
    val repo = n.repository?.full_name ?: return null
    val url = n.subject?.url ?: return "repo/$repo"
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
