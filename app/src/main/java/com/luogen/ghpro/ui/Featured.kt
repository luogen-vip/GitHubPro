package com.luogen.ghpro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.RepoOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class FSort(val key: String, val label: String)

private val FEATURED_SORTS = listOf(
    FSort("stars", "最热门"),
    FSort("updated", "最新更新"),
    FSort("forks", "复刻最多")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturedScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable { mutableStateOf(UiCache.featuredSort) }
    val initialSnap = remember {
        UiCache.featuredSnaps[UiCache.featuredSort]?.takeIf { it.list.isNotEmpty() }
    }
    var list by remember { mutableStateOf<List<Repo>>(initialSnap?.list ?: emptyList()) }
    var total by remember { mutableStateOf(initialSnap?.total ?: 0L) }
    var loading by remember { mutableStateOf(initialSnap == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var offlineNote by remember { mutableStateOf<String?>(null) }
    var lastSync by remember { mutableStateOf(initialSnap?.lastSync ?: 0L) }
    var switchedSort by remember { mutableStateOf(false) }
    var loadGen by remember { mutableStateOf(0) }
    UiCache.featuredSort = sort

    fun fallback(message: String) {
        // 搜索接口不可用时先给真实可点的仓库，不让页面一直转圈；不在这里再等第二个网络请求。
        val fb = when (sort) {
            "forks" -> FEATURED_FALLBACK.sortedByDescending { it.forks_count }
            "updated" -> FEATURED_FALLBACK
            else -> FEATURED_FALLBACK.sortedByDescending { it.stargazers_count }
        }
        list = fb
        total = fb.size.toLong()
        lastSync = System.currentTimeMillis()
        error = message
        offlineNote = "搜索接口暂时不可用：$message"
        loading = false
    }

    suspend fun load(silent: Boolean = false) {
        if (silent && loading) return
        val gen = ++loadGen
        val s = sort
        if (!silent) loading = true
        try {
            val q = if (s == "updated") "stars:>500 pushed:>2020-01-01" else "stars:>1000"
            val r = Api.searchRepos(q, 1, s, "desc", perPage = 60)
            if (gen != loadGen || s != sort) return
            val items = when (s) {
                "updated" -> r.items.sortedByDescending { it.pushed_at ?: it.updated_at ?: "" }
                "forks" -> r.items.sortedByDescending { it.forks_count }
                else -> r.items.sortedByDescending { it.stargazers_count }
            }
            if (items.isEmpty()) {
                fallback("精选接口这次没有返回仓库（可能被 GitHub 搜索限流），已先显示可用列表")
                return
            }
            list = items
            total = r.total_count
            error = null
            offlineNote = null
            lastSync = System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == loadGen && s == sort) {
                val msg = e.message ?: "网络请求失败"
                if (!silent || list.isEmpty()) fallback(msg)
                else error = msg
            }
        } finally {
            if (gen == loadGen && s == sort) loading = false
        }
    }

    val listState = rememberLazyListState(initialSnap?.index ?: 0, initialSnap?.offset ?: 0)

    fun saveFeatured() {
        UiCache.featuredSort = sort
        if (list.isNotEmpty()) {
            UiCache.featuredSnaps[sort] = FeaturedSnap(
                list, total, lastSync, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset
            )
        }
    }

    LaunchedEffect(sort) {
        UiCache.featuredSort = sort
        val snap = UiCache.featuredSnaps[sort]?.takeIf { it.list.isNotEmpty() }
        val fromSwitch = switchedSort
        switchedSort = false
        if (snap != null) {
            // 从详情页/其他 App 回来：原样恢复列表和位置；切换排序时则强制回顶部。
            list = snap.list; total = snap.total; lastSync = snap.lastSync
            loading = false; error = null
            if (fromSwitch) {
                listState.scrollToItem(0)
            } else if (listState.firstVisibleItemIndex != snap.index || listState.firstVisibleItemScrollOffset != snap.offset) {
                listState.scrollToItem(snap.index, snap.offset)
            }
            // 刚切换排序、或缓存超过 40 秒才静默校准；不遮挡、不跳位置。
            if (fromSwitch || System.currentTimeMillis() - lastSync >= 40_000L) runCatching { load(true) }
        } else {
            // 先立刻显示内置的真实仓库列表，保证任何网络情况下都不是无限转圈；
            // 后面请求成功再无缝换成 GitHub 实时精选。
            val fb = when (sort) {
                "forks" -> FEATURED_FALLBACK.sortedByDescending { it.forks_count }
                else -> FEATURED_FALLBACK.sortedByDescending { it.stargazers_count }
            }
            list = fb; total = fb.size.toLong(); lastSync = System.currentTimeMillis()
            loading = false; error = null; offlineNote = "正在连接 GitHub 精选接口，先显示内置真实仓库"
            listState.scrollToItem(0)
            load()
        }
    }
    LaunchedEffect(list) { if (list.isNotEmpty()) IssueCounts.loadBatch(list) }
    LaunchedEffect(listState, sort) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                UiCache.featuredSnaps[sort] = FeaturedSnap(list, total, lastSync, index, offset)
            }
    }
    DisposableEffect(sort) { onDispose { saveFeatured() } }

    // 每 40 秒自动刷新，排名变化自己更新
    LaunchedEffect(sort) {
        while (isActive) {
            delay(40_000L)
            if (list.isNotEmpty() && System.currentTimeMillis() - lastSync >= 40_000L) runCatching { load(true) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 || listState.firstVisibleItemScrollOffset > 600 } }
            if (showTop) SmallFab { scope.launch { listState.animateScrollToItem(0) } }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("精选仓库", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (lastSync == 0L) "正在同步" else "共 ${compactNum(total.toInt())} 个 · 每 40 秒自动更新",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = { IconButton({ scope.launch { load(); android.widget.Toast.makeText(ctx, "已刷新", android.widget.Toast.LENGTH_SHORT).show() } }) { Icon(Icons.Filled.Refresh, "刷新") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FEATURED_SORTS.forEach { s ->
                    FilterChip(selected = sort == s.key, onClick = {
                        if (sort != s.key) { switchedSort = true; sort = s.key }
                    }, label = { Text(s.label, fontSize = 12.sp) })
                }
            }
            offlineNote?.let { note ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(note, fontSize = 10.sp, color = GH.yellow, modifier = Modifier.weight(1f))
                    TextButton({ scope.launch { load() } }) { Text("重试", fontSize = 10.sp) }
                }
            }
            when {
                loading && list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("正在挑选高质量仓库…", fontSize = 12.sp)
                    Text("最多等 15 秒；超时会直接提示重试，不会一直转圈", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                }
                error != null && list.isEmpty() -> ErrorBox(error) { scope.launch { load() } }
                list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyBox("暂时没有取到精选仓库")
                    TextButton({ scope.launch { load() } }) { Text("重新加载", fontSize = 12.sp) }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(list, key = { "${it.id}-${it.full_name}" }) { r ->
                        RepoRow(r) { nav.navigate("repo/${r.full_name}") }
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("以上按 ${FEATURED_SORTS.first { it.key == sort }.label} 排序，自动更新", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}


private val FEATURED_FALLBACK: List<Repo> = listOf(
    Repo(id = 900000001L, name = "freeCodeCamp", full_name = "freeCodeCamp/freeCodeCamp", owner = RepoOwner("freeCodeCamp"), description = "freeCodeCamp.org's open-source codebase and curriculum", stargazers_count = 410000, forks_count = 39000, html_url = "https://github.com/freeCodeCamp/freeCodeCamp"),
    Repo(id = 900000002L, name = "developer-roadmap", full_name = "kamranahmedse/developer-roadmap", owner = RepoOwner("kamranahmedse"), description = "Interactive roadmaps, guides and other educational content", stargazers_count = 300000, forks_count = 39000, html_url = "https://github.com/kamranahmedse/developer-roadmap"),
    Repo(id = 900000003L, name = "free-programming-books", full_name = "EbookFoundation/free-programming-books", owner = RepoOwner("EbookFoundation"), description = "Freely available programming books", stargazers_count = 350000, forks_count = 63000, html_url = "https://github.com/EbookFoundation/free-programming-books"),
    Repo(id = 900000004L, name = "public-apis", full_name = "public-apis/public-apis", owner = RepoOwner("public-apis"), description = "A collective list of free APIs", stargazers_count = 340000, forks_count = 36000, html_url = "https://github.com/public-apis/public-apis"),
    Repo(id = 900000005L, name = "awesome-selfhosted", full_name = "awesome-selfhosted/awesome-selfhosted", owner = RepoOwner("awesome-selfhosted"), description = "A list of Free Software network services and web applications", stargazers_count = 250000, forks_count = 12000, html_url = "https://github.com/awesome-selfhosted/awesome-selfhosted"),
    Repo(id = 900000006L, name = "gitignore", full_name = "github/gitignore", owner = RepoOwner("github"), description = "A collection of useful .gitignore templates", stargazers_count = 175000, forks_count = 81000, html_url = "https://github.com/github/gitignore"),
    Repo(id = 900000007L, name = "react", full_name = "facebook/react", owner = RepoOwner("facebook"), description = "The library for web and native user interfaces", stargazers_count = 235000, forks_count = 48000, html_url = "https://github.com/facebook/react"),
    Repo(id = 900000008L, name = "next.js", full_name = "vercel/next.js", owner = RepoOwner("vercel"), description = "The React Framework", stargazers_count = 130000, forks_count = 28000, html_url = "https://github.com/vercel/next.js"),
    Repo(id = 900000009L, name = "vscode", full_name = "microsoft/vscode", owner = RepoOwner("microsoft"), description = "Visual Studio Code", stargazers_count = 170000, forks_count = 31000, html_url = "https://github.com/microsoft/vscode"),
    Repo(id = 900000010L, name = "linux", full_name = "torvalds/linux", owner = RepoOwner("torvalds"), description = "Linux kernel source tree", stargazers_count = 190000, forks_count = 56000, html_url = "https://github.com/torvalds/linux")
)
