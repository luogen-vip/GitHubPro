package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<User?>(null) }
    var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }   // 0 我的仓库 1 已 Star
    var sort by remember { mutableStateOf("updated") }
    var lang by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var langMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val tick by Bus.tick.collectAsState()
    val safeMode = Cfg.safeMode

    suspend fun load(p: Int, replace: Boolean) {
        if (p == 1) loading = true else loadingMore = true
        error = null
        try {
            val pg = if (tab == 1) Api.starred(p) else Api.myRepos(p, 60, sort)
            repos = if (replace) pg.items else repos + pg.items
            hasNext = pg.hasNext
            page = p
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
            loadingMore = false
        }
    }

    suspend fun refreshSilently() {
        runCatching {
            if (page > 1) {
                val all = ArrayList<Repo>()
                var p = 1
                var more = true
                while (more && p <= page) {
                    val pg = if (tab == 1) Api.starred(p) else Api.myRepos(p, 60, sort)
                    all.addAll(pg.items)
                    more = pg.hasNext
                    p++
                }
                repos = all
            } else {
                val pg = if (tab == 1) Api.starred(1) else Api.myRepos(1, 60, sort)
                repos = pg.items
                hasNext = pg.hasNext
            }
        }
    }

    LaunchedEffect(tab, sort) { load(1, true) }

    LaunchedEffect(tick) { if (tick > 0 && !safeMode && Cfg.autoRefresh) refreshSilently() }

    LaunchedEffect(Unit) {
        if (me == null) runCatching { Api.me() }.onSuccess { me = it }
        if (safeMode) return@LaunchedEffect
        while (isActive) {
            delay(30_000L)
            if (Cfg.autoRefresh) {
                runCatching { if (me == null) me = Api.me() }
                refreshSilently()
            }
        }
    }

    // 滚动到底部自动加载更多：用 snapshotFlow 读取布局状态，避免在组合阶段读取 layoutInfo
    LaunchedEffect(listState, hasNext, safeMode) {
        if (safeMode) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (hasNext && !loadingMore && !loading && last >= repos.size - 4) {
                    load(page + 1, false)
                }
            }
    }

    val shown = if (lang == null) repos else repos.filter { it.language == lang }
    val languages = repos.mapNotNull { it.language }.distinct().sorted()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(me?.avatar_url ?: Cfg.avatar, 32)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(me?.name ?: me?.login ?: Cfg.userName ?: "GitHub", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("@" + (me?.login ?: Cfg.login ?: ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { nav.navigate("search") }) { Icon(Icons.Filled.Search, "搜索") }
                        Box {
                            IconButton(onClick = { nav.navigate("notifications") }) { Icon(Icons.Filled.Notifications, "通知") }
                        }
                        IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "设置") }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("我的仓库") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("已 Star") })
                        Spacer(Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { sortMenu = true }) {
                                Text(
                                    when (sort) { "updated" -> "最近更新"; "pushed" -> "最近推送"; "full_name" -> "名称"; else -> "Star 最多" },
                                    fontSize = 12.sp
                                )
                                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf("updated" to "最近更新", "pushed" to "最近推送", "full_name" to "名称", "stars" to "Star 最多").forEach { (k, v) ->
                                    DropdownMenuItem(text = { Text(v) }, onClick = { sort = k; sortMenu = false })
                                }
                            }
                        }
                        Box {
                            TextButton(onClick = { langMenu = true }) {
                                Text(lang ?: "语言", fontSize = 12.sp)
                                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                            }
                            DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                                DropdownMenuItem(text = { Text("全部语言") }, onClick = { lang = null; langMenu = false })
                                languages.forEach { l ->
                                    DropdownMenuItem(text = { Text(l) }, onClick = { lang = l; langMenu = false })
                                }
                            }
                        }
                    }
                    if (me != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text("${me!!.public_repos} 个公开仓库", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("  ·  ${me!!.followers} 关注者  ·  ${me!!.following} 正在关注",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("create") }, containerColor = GH.blue, contentColor = Color.White) {
                Icon(Icons.Filled.Add, "新建仓库")
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            state = listState
        ) {
            if (safeMode) item {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(GH.yellow.copy(alpha = 0.12f))
                        .border(1.dp, GH.yellow.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text("安全模式已开启", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GH.yellow)
                        Spacer(Modifier.height(3.dp))
                        Text("上次运行出现异常，已自动关闭头像加载与自动刷新。如果一切正常，可去「设置 → 诊断」关闭安全模式。",
                            fontSize = 11.sp, color = GH.muted, lineHeight = 16.sp)
                    }
                }
            }
            if (loading && repos.isEmpty()) item { LoadingBox("正在加载仓库…") }
            error?.let { e -> item { ErrorBox(e) { scope.launch { load(1, true) } } } }
            items(shown, key = { "${it.id}-${it.full_name}" }) { repo ->
                if (safeMode) SafeRepoRow(repo) { nav.navigate("repo/${repo.owner?.login ?: Cfg.login}/${repo.name}") }
                else RepoRow(repo) { nav.navigate("repo/${repo.owner?.login ?: Cfg.login}/${repo.name}") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }
            if (!loading && shown.isEmpty()) item {
                EmptyBox(if (tab == 1) "还没有 Star 过的仓库" else "还没有仓库，点右下角新建一个吧")
            }
            if (loadingMore) item { LoadingBox("加载更多…") }
            if (!hasNext && repos.isNotEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                    Text("已经到底了 · 共 ${repos.size} 个", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun RepoRow(repo: Repo, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(repo.full_name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            if (repo.isPrivate) {
                Spacer(Modifier.width(6.dp))
                TinyTag("私有", GH.yellow)
            }
            if (repo.fork) {
                Spacer(Modifier.width(4.dp))
                TinyTag("复刻", GH.muted)
            }
            if (repo.archived) {
                Spacer(Modifier.width(4.dp))
                TinyTag("已归档", GH.muted)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = GH.yellow)
            Spacer(Modifier.width(3.dp))
            Text(compactNum(repo.stargazers_count), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        repo.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            repo.language?.let { l ->
                Box(Modifier.size(9.dp).clip(CircleShape).background(GH.langColor(l)))
                Spacer(Modifier.width(4.dp))
                Text(l, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
            }
            Text("⑂ ${repo.forks_count}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text("更新时间 ${timeAgo(repo.updated_at ?: repo.pushed_at)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (repo.open_issues_count > 0) {
                Spacer(Modifier.width(10.dp))
                Text("议题 ${repo.open_issues_count}", fontSize = 11.sp, color = GH.orange)
            }
        }
    }
}


/** 安全模式下的极简行：不加载任何网络图片，只用文字和颜色块。 */
@Composable
fun SafeRepoRow(repo: Repo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(repo.full_name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            repo.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = GH.yellow)
        Spacer(Modifier.width(3.dp))
        Text(compactNum(repo.stargazers_count), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
