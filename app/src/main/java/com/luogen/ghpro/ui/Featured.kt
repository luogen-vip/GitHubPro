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
    var lastSync by remember { mutableStateOf(initialSnap?.lastSync ?: 0L) }
    var switchedSort by remember { mutableStateOf(false) }
    var loadGen by remember { mutableStateOf(0) }
    UiCache.featuredSort = sort

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
                if (!silent && list.isEmpty()) error = "这次没有取到仓库，点“重新加载”再试一次"
                return
            }
            list = items
            total = r.total_count
            error = null
            lastSync = System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == loadGen && s == sort && !silent) error = e.message ?: "网络请求失败"
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
            list = emptyList(); total = 0; lastSync = 0; loading = true; error = null
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
            when {
                loading && list.isEmpty() -> LoadingBox("正在挑选高质量仓库…")
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
