package com.luogen.ghpro.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Favorites
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.User
import com.luogen.ghpro.data.UserStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private class HomeAction(val icon: ImageVector, val label: String, val go: () -> Unit)

@Composable
fun HomeScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf(UiCache.homeMe) }
    val bootSnap = UiCache.homeSnaps["${UiCache.homeTab}|${UiCache.homeSort}"]
    var repos by remember { mutableStateOf(bootSnap?.repos ?: UiCache.homeRepos) }
    var page by remember { mutableStateOf(bootSnap?.page ?: UiCache.homePage) }
    var hasNext by remember { mutableStateOf(bootSnap?.hasNext ?: UiCache.homeHasNext) }
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
    val statusVer by Bus.statusPoke.collectAsState()
    val followVer by Bus.followPoke.collectAsState()
    val safeMode = Cfg.safeMode
    val recent = Cfg.recentRepos.filter { Cfg.activityAt(it) > 0 }

    // 首页点“关注者 / 正在关注”只在首页上弹名单，背景仍是首页，不再跳到个人主页。
    var showPeople by remember { mutableStateOf(false) }
    var peopleKind by remember { mutableStateOf(0) }   // 0 关注者，1 正在关注
    var people by remember { mutableStateOf<List<User>>(emptyList()) }
    var peopleLoading by remember { mutableStateOf(false) }
    var peopleLoadingMore by remember { mutableStateOf(false) }
    var peoplePage by remember { mutableStateOf(1) }
    var peopleHasMore by remember { mutableStateOf(false) }
    var peopleError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showPeople, peopleKind) {
        if (!showPeople) return@LaunchedEffect
        peopleLoading = true; peopleError = null; people = emptyList()
        peoplePage = 1; peopleHasMore = false
        val login = me?.login ?: Cfg.login.orEmpty()
        if (login.isBlank()) { peopleLoading = false; return@LaunchedEffect }
        runCatching {
            if (peopleKind == 0) Api.followers(login, 1) else Api.following(login, 1)
        }.onSuccess { pg ->
            people = if (peopleKind == 1) {
                // GitHub 列表偶尔比刚取关的状态慢半拍；本地刚取关的人先过滤掉，刚关注的人补进来。
                val base = pg.items.filter { UiCache.followOverride(it.login) != false }
                val extra = UiCache.userFollowSnap.values.filter {
                    UiCache.followOverride(it.login) == true && base.none { b -> b.login == it.login }
                }
                (base + extra).distinctBy { it.login }
            } else pg.items
            peopleHasMore = pg.hasNext
        }.onFailure { peopleError = it.message ?: "加载失败" }
        peopleLoading = false
    }

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
    LaunchedEffect(followVer) {
        if (followVer > 0) runCatching { Api.me() }.onSuccess { me = it; UiCache.homeMe = it }
    }
    // 关注状态一变，首页弹窗里的“正在关注”立刻同步，不能等下一次重新打开才刷新。
    LaunchedEffect(followVer, showPeople, peopleKind) {
        if (showPeople && peopleKind == 1 && followVer > 0) {
            val kept = people.filter { UiCache.followOverride(it.login) != false }
            val extra = UiCache.userFollowSnap.values.filter {
                UiCache.followOverride(it.login) == true && kept.none { b -> b.login == it.login }
            }
            people = (kept + extra).distinctBy { it.login }
        }
    }
    LaunchedEffect(starVer) {
        if (starVer > 0 && tab == 1) {
            repos = UiCache.homeRepos
            UiCache.homeSnaps["$tab|$sort"] = HomeSnap(repos, page, hasNext)
            UiCache.homeLoaded = true
        }
    }

    LaunchedEffect(repos) { if (!safeMode && repos.isNotEmpty()) IssueCounts.loadBatch(repos) }

    LaunchedEffect(Unit) {
        if (!Cfg.token.isNullOrBlank()) runCatching { Api.myStatus() }.onSuccess { Cfg.saveStatus(it); Bus.statusChanged() }
        // 每次进首页都拉一次真实资料，关注者/正在关注不能一直用旧缓存。
        runCatching { Api.me() }.onSuccess { me = it; UiCache.homeMe = it }
        if (safeMode) return@LaunchedEffect
        while (isActive) {
            delay(5_000L)
            // 关注数不受“自动刷新仓库”开关影响：它必须始终反映真实数字。
            runCatching { Api.me() }.onSuccess { me = it; UiCache.homeMe = it }
            if (Cfg.autoRefresh && !loading && !loadingMore) refreshSilently()
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
    // “最近更新”只按 GitHub 的 pushed_at（真正有代码推送）排序。
    // 不能用 updated_at（改个描述/Star 设置也会变），也不能混入本机浏览/下载记录，
    // 否则会出现“没更新过的仓库显示更新于 5 分钟前并排到最上面”。
    fun pushedScore(r: com.luogen.ghpro.data.Repo): Long =
        stamp(r.pushed_at).takeIf { it > 0 } ?: stamp(r.updated_at)

    val byLang = if (lang == null) repos else repos.filter { it.language == lang }
    val sorted = when (sort) {
        "updated", "pushed" -> byLang.sortedByDescending { pushedScore(it) }
        "full_name" -> byLang.sortedBy { it.full_name.lowercase() }
        "stars" -> byLang.sortedByDescending { it.stargazers_count }
        else -> byLang
    }
    val shown = sorted
    val languages = repos.mapNotNull { it.language }.distinct().sorted()
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 || listState.firstVisibleItemScrollOffset > 600 } }

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
                            val myStatus = remember(statusVer) {
                                UserStatus(Cfg.statusEmoji, Cfg.statusMessage, Cfg.statusBusy, Cfg.statusExpiresAt,
                                    Cfg.statusOrgId, Cfg.statusOrgLogin, Cfg.statusOrgName)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(me?.name ?: me?.login ?: Cfg.userName ?: "GitHub", fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                StatusCountdown(myStatus, Modifier.padding(start = 5.dp))
                            }
                            StatusLine(
                                status = myStatus,
                                prefix = "@" + (me?.login ?: Cfg.login ?: ""),
                                showCountdown = false,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                            HomeAction(Icons.Filled.Bookmarks, "收藏") { nav.navigate("favorites") },
                            HomeAction(Icons.Filled.Notifications, "通知") { nav.navigate("notifications") },
                            HomeAction(Icons.Filled.Extension, "更多") { nav.navigate("more") }
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
                                    repos = snap.repos; page = snap.page; hasNext = snap.hasNext; loading = false; loadingMore = false
                                    UiCache.homeLoaded = true; UiCache.homeTab = 0; UiCache.homeSort = sort
                                    UiCache.homeRepos = snap.repos; UiCache.homePage = snap.page; UiCache.homeHasNext = snap.hasNext
                                } else { repos = emptyList(); loading = true; loadingMore = false; UiCache.homeLoaded = false }
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
                                    repos = snap.repos; page = snap.page; hasNext = snap.hasNext; loading = false; loadingMore = false
                                    UiCache.homeLoaded = true; UiCache.homeTab = 1; UiCache.homeSort = sort
                                    UiCache.homeRepos = snap.repos; UiCache.homePage = snap.page; UiCache.homeHasNext = snap.hasNext
                                } else { repos = emptyList(); loading = true; loadingMore = false; UiCache.homeLoaded = false }
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
                            Text("  ·  ${me!!.followers} 关注者", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { peopleKind = 0; showPeople = true })
                            Text("  ·  ${me!!.following} 正在关注", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { peopleKind = 1; showPeople = true })
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showTop) {
                    SmallFab { scope.launch { listState.animateScrollToItem(0) } }
                }
                FloatingActionButton(onClick = { nav.navigate("create") }, containerColor = GH.blue, contentColor = Color.White) {
                    Icon(Icons.Filled.Add, "新建仓库")
                }
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

    fun unfollowFromHome(p: User) {
        val before = people
        people = people.filter { it.login != p.login }
        me = me?.copy(following = ((me?.following ?: 0) - 1).coerceAtLeast(0))
        UiCache.setFollow(p.login, false)
        Bus.followChanged()
        Toast.makeText(ctx, "已取消关注 ${p.login}", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                Api.follow(p.login, false)
                Cfg.markFollow(p.login, false)
                Bus.followChanged()
            } catch (e: Exception) {
                // 失败就恢复，不能假装已经取关。
                people = before
                me = me?.copy(following = ((me?.following ?: 0) + 1))
                UiCache.setFollow(p.login, true)
                Bus.followChanged()
                Toast.makeText(ctx, "取消失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showPeople) {
        AlertDialog(
            onDismissRequest = { showPeople = false },
            title = { Text(if (peopleKind == 0) "关注者" else "正在关注", fontSize = 15.sp) },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    when {
                        peopleLoading -> LoadingBox()
                        peopleError != null -> EmptyBox(peopleError!!)
                        people.isEmpty() -> EmptyBox("暂无")
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(people, key = { it.id }) { p ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    showPeople = false
                                    nav.navigate("user/${p.login}")
                                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(p.avatar_url, 28, name = p.login)
                                    Spacer(Modifier.width(8.dp))
                                    Text(p.login, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                    if (peopleKind == 1) {
                                        TextButton(onClick = { unfollowFromHome(p) },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp)) {
                                            Text("取消关注", fontSize = 10.sp, color = GH.red)
                                        }
                                    }
                                }
                                Divider1()
                            }
                            if (peopleHasMore) item {
                                TextButton(enabled = !peopleLoadingMore, onClick = {
                                    val login = me?.login ?: Cfg.login.orEmpty()
                                    if (login.isBlank()) return@TextButton
                                    peopleLoadingMore = true
                                    scope.launch {
                                        val pg = runCatching {
                                            if (peopleKind == 0) Api.followers(login, peoplePage + 1)
                                            else Api.following(login, peoplePage + 1)
                                        }.getOrNull()
                                        if (pg != null) {
                                            val add = if (peopleKind == 1) pg.items.filter { UiCache.followOverride(it.login) != false } else pg.items
                                            people = (people + add).distinctBy { it.id }
                                            peoplePage += 1
                                            peopleHasMore = pg.hasNext
                                        }
                                        peopleLoadingMore = false
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (peopleLoadingMore) "加载中…" else "加载更多", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showPeople = false }) { Text("关闭") } }
        )
    }
}

/** 仓库卡片：一眼分得清"这是一个仓库"。 */
@Composable
fun RepoRow(repo: Repo, recent: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var showDesc by remember(repo.full_name) { mutableStateOf(false) }
    var favSaved by remember(repo.full_name) { mutableStateOf(Favorites.isSaved(repo.full_name)) }
    var favPicker by remember(repo.full_name) { mutableStateOf(false) }
    // 单击描述必须和卡片其他区域一样快，但又要保留双击看全文。
    // Compose 默认等 300ms 判断双击；这里只等 140ms，单手快速双击仍能识别，
    // 单击进仓库的延迟缩到几乎感觉不到。
    val baseVc = LocalViewConfiguration.current
    val fastVc = remember(baseVc) {
        object : ViewConfiguration by baseVc {
            override val doubleTapTimeoutMillis: Long = 140L
        }
    }
    val issueCount = IssueCounts.get(repo.full_name) ?: repo.open_issues_count
    // 卡片一出现在可视区域就精确取一次 issue-only 数量；取到后全局缓存，滚动来回都不再请求
    LaunchedEffect(repo.full_name) { IssueCounts.ensure(repo.full_name) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(repo.owner?.avatar_url, 30, name = repo.owner?.login ?: repo.full_name.substringBefore("/"))
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
                IconButton(
                    onClick = {
                        if (favSaved) {
                            Favorites.remove(repo.full_name); favSaved = false
                            Toast.makeText(ctx, "已取消收藏", Toast.LENGTH_SHORT).show()
                        } else favPicker = true
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(if (favSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "收藏",
                        Modifier.size(16.dp), tint = if (favSaved) GH.yellow else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = GH.yellow)
                    Spacer(Modifier.width(3.dp))
                    Text("${repo.stargazers_count}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            repo.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    CompositionLocalProvider(LocalViewConfiguration provides fastVc) {
                        Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
                            modifier = Modifier.weight(1f).pointerInput(repo.full_name) {
                                detectTapGestures(
                                    // 点一下描述 = 正常进仓库；点两下才弹完整描述，避免误触打断“进仓库”
                                    onTap = { onClick() },
                                    onDoubleTap = { showDesc = true }
                                )
                            })
                    }
                    if (desc.length > 46) {
                        Spacer(Modifier.width(6.dp))
                        Text("全文", fontSize = 10.sp, color = GH.blue,
                            modifier = Modifier.clickable { showDesc = true }.padding(bottom = 1.dp))
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                repo.language?.let { l ->
                    Box(Modifier.size(9.dp).clip(CircleShape).background(GH.langColor(l)))
                    Spacer(Modifier.width(5.dp))
                    Text(l, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                }
                Text("⑂ ${repo.forks_count}", fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                // 这一格负责吃掉所有剩余宽度，过长的“更新 x 分钟前”只会省略，不会把右边议题徽章挤成竖排
                Text("更新 ${timeAgo(repo.pushed_at ?: repo.updated_at)}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (issueCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(GH.orange.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("$issueCount 议题", fontSize = 10.sp, color = GH.orange,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (favPicker) {
        FavPickerDialog(fullName = repo.full_name, onDismiss = { favPicker = false }) { folderName ->
            favPicker = false; favSaved = true
            Toast.makeText(ctx, "已收藏到「$folderName」", Toast.LENGTH_SHORT).show()
        }
    }
    if (showDesc) {
        AlertDialog(
            onDismissRequest = { showDesc = false },
            title = { Text("${repo.full_name} 的仓库描述", fontSize = 14.sp) },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        Text(repo.description ?: "", fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            },
            confirmButton = { TextButton({ showDesc = false }) { Text("关闭") } },
            dismissButton = { TextButton({ copyText(ctx, repo.description ?: "", "已复制仓库描述") }) { Text("复制") } }
        )
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
