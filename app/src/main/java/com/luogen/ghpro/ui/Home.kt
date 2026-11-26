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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private class HomeAction(val icon: ImageVector, val label: String, val go: () -> Unit)

@Composable
fun HomeScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf(UiCache.homeMe) }
    var repos by remember { mutableStateOf(UiCache.homeRepos) }
    var page by remember { mutableStateOf(UiCache.homePage) }
    var hasNext by remember { mutableStateOf(UiCache.homeHasNext) }
    var loading by remember { mutableStateOf(UiCache.homeRepos.isEmpty()) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reqGen by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(UiCache.homeTab) }   // 0 我的仓库 1 已 Star
    var sort by remember { mutableStateOf(UiCache.homeSort) }
    var lang by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var langMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(UiCache.homeIndex, UiCache.homeOffset)
    val tick by Bus.tick.collectAsState()
    val starVer by Bus.starPoke.collectAsState()
    val activityVer by Bus.activityPoke.collectAsState()
    val safeMode = Cfg.safeMode
    val recent = Cfg.recentRepos.filter { Cfg.activityAt(it) > 0 }

    suspend fun load(p: Int, replace: Boolean) {
        val req = ++reqGen
        val t = tab
        val s = sort
        if (p == 1) loading = true else loadingMore = true
        error = null
        try {
            val pg = if (t == 1) Api.starred(p) else Api.myRepos(p, 60, s)
            if (req != reqGen || t != tab || s != sort) return
            repos = if (replace) pg.items else repos + pg.items
            hasNext = pg.hasNext
            page = p
            if (replace) {
                UiCache.homeLoaded = true
                UiCache.homeRepos = repos
                UiCache.homeTab = t
                UiCache.homeSort = s
            }
            UiCache.homeSnaps["$t|$s"] = HomeSnap(repos, page, hasNext)
            UiCache.homePage = page
            UiCache.homeHasNext = hasNext
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            if (req == reqGen && t == tab && s == sort) {
                error = e.message
                if (replace) UiCache.homeLoaded = false
            }
        } finally {
            if (req == reqGen) {
                loading = false
                loadingMore = false
            }
        }
    }

    suspend fun refreshSilently() {
        val req = ++reqGen
        val t = tab
        val s = sort
        val pg0 = page
        runCatching {
            if (pg0 > 1) {
                val all = ArrayList<Repo>()
                var p = 1
                var more = true
                while (more && p <= pg0) {
                    val pg = if (t == 1) Api.starred(p) else Api.myRepos(p, 60, s)
                    if (req != reqGen || t != tab || s != sort) return@runCatching
                    all.addAll(pg.items)
                    more = pg.hasNext
                    p++
                }
                repos = all
                UiCache.homeRepos = all
            } else {
                val pg = if (t == 1) Api.starred(1) else Api.myRepos(1, 60, s)
                if (req != reqGen || t != tab || s != sort) return@runCatching
                repos = pg.items
                hasNext = pg.hasNext
                UiCache.homeRepos = pg.items
                UiCache.homeHasNext = pg.hasNext
            }
            if (req == reqGen && t == tab && s == sort)
                UiCache.homeSnaps["$t|$s"] = HomeSnap(repos, page, hasNext)
        }
    }

    // 已有缓存时直接复用：从详情页返回 / 切回 App 都不会重新加载
    var lastSortSeen by remember { mutableStateOf(sort) }
    LaunchedEffect(sort) {
        if (sort != lastSortSeen) {
            lastSortSeen = sort
            listState.scrollToItem(0); UiCache.homeIndex = 0; UiCache.homeOffset = 0
        }
    }
    LaunchedEffect(tab, sort) {
        val snap = UiCache.homeSnaps["$tab|$sort"]
        if (snap != null) {
            repos = snap.repos; page = snap.page; hasNext = snap.hasNext
            loading = false; loadingMore = false
            UiCache.homeLoaded = true; UiCache.homeTab = tab; UiCache.homeSort = sort
            UiCache.homeRepos = snap.repos; UiCache.homePage = snap.page; UiCache.homeHasNext = snap.hasNext
            refreshSilently()
        } else {
            repos = emptyList(); loading = true
            load(1, true)
        }
    }

    LaunchedEffect(tick) { if (tick > 0 && !safeMode && Cfg.autoRefresh) refreshSilently() }
    // 仓库真实操作后，首页立刻按最新活动重新排序（纯浏览不会触发）
    LaunchedEffect(activityVer) { if (activityVer > 0) repos = repos.toList() }
    LaunchedEffect(starVer) {
        if (starVer > 0 && tab == 1) {
            repos = UiCache.homeRepos
            UiCache.homeSnaps["$tab|$sort"] = HomeSnap(repos, page, hasNext)
            UiCache.homeLoaded = true
        }
    }

    LaunchedEffect(Unit) {
        if (me == null) runCatching { Api.me() }.onSuccess { me = it; UiCache.homeMe = it }
        if (safeMode) return@LaunchedEffect
        while (isActive) {
            delay(5_000L)
            if (Cfg.autoRefresh && !loading && !loadingMore) {
                runCatching { if (me == null) { me = Api.me(); UiCache.homeMe = me } }
                refreshSilently()
            }
        }
    }

    // 持续保存滚动位置，返回时还原
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { pair -> UiCache.homeIndex = pair.first; UiCache.homeOffset = pair.second }
    }

    // 滚动到底部自动加载更多
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

    fun stamp(v: String?): Long {
        if (v.isNullOrBlank()) return 0L
        return runCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrDefault(0L)
    }
    fun activityScore(r: com.luogen.ghpro.data.Repo): Long =
        maxOf(Cfg.activityAt(r.full_name), stamp(r.pushed_at), stamp(r.updated_at))

    val byLang = if (lang == null) repos else repos.filter { it.language == lang }
    val sorted = when (sort) {
        "updated" -> byLang.sortedByDescending { activityScore(it) }
        "pushed" -> byLang.sortedByDescending { it.pushed_at ?: it.updated_at ?: "" }
        "full_name" -> byLang.sortedBy { it.full_name.lowercase() }
        "stars" -> byLang.sortedByDescending { it.stargazers_count }
        else -> byLang
    }
    val shown = sorted
    val languages = repos.mapNotNull { it.language }.distinct().sorted()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(me?.avatar_url ?: Cfg.avatar, 34)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(me?.name ?: me?.login ?: Cfg.userName ?: "GitHub", fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("@" + (me?.login ?: Cfg.login ?: ""), fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "设置") }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            HomeAction(Icons.Filled.Search, "搜索") { nav.navigate("search") },
                            HomeAction(Icons.Filled.Explore, "精选") { nav.navigate("featured") },
                            HomeAction(Icons.Filled.Download, "下载") { nav.navigate("downloads") },
                            HomeAction(Icons.Filled.Notifications, "通知") { nav.navigate("notifications") }
                        ).forEach { a ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { a.go() }.padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(a.icon, a.label, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(a.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(selected = tab == 0, onClick = {
                            if (tab != 0) {
                                reqGen++
                                tab = 0
                                val snap = UiCache.homeSnaps["0|$sort"]
                                if (snap != null) {
                                    repos = snap.repos; page = snap.page; hasNext = snap.hasNext; loading = false
                                    UiCache.homeLoaded = true; UiCache.homeTab = 0; UiCache.homeSort = sort
                                    UiCache.homeRepos = snap.repos; UiCache.homePage = snap.page; UiCache.homeHasNext = snap.hasNext
                                } else { repos = emptyList(); loading = true; UiCache.homeLoaded = false }
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    listState.scrollToItem(0); UiCache.homeIndex = 0; UiCache.homeOffset = 0
                                }
                            }
                        }, label = { Text("我的仓库") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = tab == 1, onClick = {
                            if (tab != 1) {
                                reqGen++
                                tab = 1
                                val snap = UiCache.homeSnaps["1|$sort"]
                                if (snap != null) {
                                    repos = snap.repos; page = snap.page; hasNext = snap.hasNext; loading = false
                                    UiCache.homeLoaded = true; UiCache.homeTab = 1; UiCache.homeSort = sort
                                    UiCache.homeRepos = snap.repos; UiCache.homePage = snap.page; UiCache.homeHasNext = snap.hasNext
                                } else { repos = emptyList(); loading = true; UiCache.homeLoaded = false }
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    listState.scrollToItem(0); UiCache.homeIndex = 0; UiCache.homeOffset = 0
                                }
                            }
                        }, label = { Text("已 Star") })
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
                                    DropdownMenuItem(text = { Text(v) }, onClick = {
                                        sort = k; sortMenu = false
                                        UiCache.homeIndex = 0; UiCache.homeOffset = 0
                                        scope.launch { kotlinx.coroutines.delay(80L); listState.scrollToItem(0) }
                                    })
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
        LazyColumn(modifier = Modifier.fillMaxSize().padding(pad), state = listState,
        contentPadding = PaddingValues(top = 2.dp, bottom = 14.dp)) {
            if (safeMode) item {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GH.yellow.copy(alpha = 0.12f))
                        .border(1.dp, GH.yellow.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text("安全模式已开启", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GH.yellow)
                        Spacer(Modifier.height(3.dp))
                        Text("已关闭头像加载与自动刷新。一切正常后可去「设置 → 诊断」关闭。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
                    }
                }
            }
            if (loading && repos.isEmpty()) item { LoadingBox("正在加载仓库…") }
            error?.let { e -> item { ErrorBox(e) { scope.launch { load(1, true) } } } }
            items(shown, key = { "${it.id}-${it.full_name}" }) { repo ->
                val isRecent = recent.firstOrNull() == repo.full_name
                val go = {
                    nav.navigate("repo/${repo.owner?.login ?: Cfg.login}/${repo.name}")
                }
                if (safeMode) SafeRepoRow(repo, isRecent, go) else RepoRow(repo, isRecent, go)
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

/** 仓库卡片：一眼分得清"这是一个仓库"。 */
@Composable
fun RepoRow(repo: Repo, recent: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(repo.owner?.avatar_url, 30)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (repo.name.isNotBlank()) repo.name else repo.full_name,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (repo.isPrivate) { Spacer(Modifier.width(6.dp)); TinyTag("私有", GH.yellow) }
                        if (repo.fork) { Spacer(Modifier.width(4.dp)); TinyTag("复刻", MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (repo.archived) { Spacer(Modifier.width(4.dp)); TinyTag("归档", MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (recent) { Spacer(Modifier.width(4.dp)); TinyTag("最近", GH.blue) }
                    }
                    Text(repo.full_name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = GH.yellow)
                    Spacer(Modifier.width(3.dp))
                    Text("${repo.stargazers_count}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            repo.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                repo.language?.let { l ->
                    Box(Modifier.size(9.dp).clip(CircleShape).background(GH.langColor(l)))
                    Spacer(Modifier.width(5.dp))
                    Text(l, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                }
                Text("⑂ ${repo.forks_count}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("更新 ${timeAgo(repo.updated_at ?: repo.pushed_at)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (repo.open_issues_count > 0) {
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(GH.orange.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("${repo.open_issues_count} 议题", fontSize = 10.sp, color = GH.orange)
                    }
                }
            }
        }
    }
}

/** 安全模式下的卡片：不加载任何网络图片。 */
@Composable
fun SafeRepoRow(repo: Repo, recent: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(GH.blue.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(repo.name.take(1).uppercase().ifBlank { "R" }, fontSize = 13.sp, color = GH.blue, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (repo.name.isNotBlank()) repo.name else repo.full_name,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(repo.full_name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = GH.yellow)
                Spacer(Modifier.width(3.dp))
                Text("${repo.stargazers_count}", fontSize = 12.sp)
            }
            repo.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
