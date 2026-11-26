package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
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
import com.luogen.ghpro.data.Notification
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<Notification>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load() {
        loading = true
        try {
            list = Api.notifications(all)
            error = null
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    LaunchedEffect(all) { load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (all) "全部通知" else "未读通知", fontSize = 15.sp) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton({
                        scope.launch {
                            try { Api.markAllRead(); load(); snackbar.showSnackbar("已全部标记为已读") }
                            catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
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
                Text("${list.size} 条", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                loading -> LoadingBox()
                error != null -> ErrorBox(error) { scope.launch { load() } }
                list.isEmpty() -> EmptyBox("没有通知（GitHub 通知需要你在网页端 Watch 仓库）")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { n ->
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (n.unread) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)
                                .clickable {
                                    scope.launch {
                                        runCatching { Api.markThreadRead(n.id) }
                                        val url = n.subject?.url ?: ""
                                        val repo = n.repository?.full_name ?: ""
                                        val m = Regex("repos/(.+?)/(.+?)/(issues|pulls)/").find(url)
                                        if (m != null) {
                                            val (o, r, kind) = m.destructured
                                            val num = url.substringAfterLast('/').toIntOrNull()
                                            if (num != null) nav.navigate(if (kind == "issues") "issue/$o/$r/$num" else "pr/$o/$r/$num")
                                        } else if (repo.isNotBlank()) {
                                            nav.navigate("repo/$repo")
                                        } else {
                                            openUrl(ctx, n.subject?.url)
                                        }
                                        load()
                                    }
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
                            Text("${n.repository?.full_name ?: ""} · ${n.reason ?: ""} · ${timeAgo(n.updated_at)}",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Divider1()
                    }
                }
            }
        }
    }
}

