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
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.RepoOwner
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class FSort(val key: String, val label: String)

/** 磁盘里的精选快照；只存 GitHub Search 真正返回过的仓库，绝不存内置假数据。 */
private data class FeaturedDisk(val at: Long = 0L, val total: Long = 0L, val items: List<Repo> = emptyList())

private fun loadFeaturedDisk(sort: String): FeaturedSnap? = runCatching {
    val json = Cfg.featuredCache(sort) ?: return null
    val d = Gson().fromJson(json, FeaturedDisk::class.java) ?: return null
    if (d.items.isEmpty()) null else FeaturedSnap(d.items, d.total, d.at, 0, 0)
}.getOrNull()

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
        val mem = UiCache.featuredSnaps[UiCache.featuredSort]?.takeIf { it.list.isNotEmpty() }
        // 冷启动没有内存快照时，先用上次真实搜索落盘的列表，Search API 再后台校准。
        mem ?: loadFeaturedDisk(UiCache.featuredSort)?.also { UiCache.featuredSnaps[UiCache.featuredSort] = it }
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

    suspend fun load(silent: Boolean = false) {
        if (silent && loading) return
        val gen = ++loadGen
        val s = sort
        if (!silent) loading = true
        try {
            val q = if (s == "updated") "stars:>500 pushed:>2020-01-01" else "stars:>1000"
            val r = Api.searchReposFeatured(q, s, perPage = 30)
            if (gen != loadGen || s != sort) return
            val items = when (s) {
                "updated" -> r.items.sortedByDescending { it.pushed_at ?: it.updated_at ?: "" }
                "forks" -> r.items.sortedByDescending { it.forks_count }
                else -> r.items.sortedByDescending { it.stargazers_count }
            }
            if (items.isEmpty()) {
                if (!silent || list.isEmpty()) {
                    list = emptyList()
                    total = 0
                    lastSync = 0
                    offlineNote = null
                }
                error = "GitHub 搜索这次没有返回仓库，可能被限流；点重试或稍后刷新"
                loading = false
                return
            }
            list = items
            total = r.total_count
            error = null
            offlineNote = null
            lastSync = System.currentTimeMillis()
            // 落盘真实结果：下次冷启动先显示，不再让用户干等 1–3 分钟。
            runCatching {
                Cfg.saveFeaturedCache(s, Gson().toJson(FeaturedDisk(lastSync, total, items)))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == loadGen && s == sort) {
                val msg = e.message ?: "网络请求失败"
                if (!silent || list.isEmpty()) {
                    // 绝不再塞内置假仓库；真实搜索失败就明确显示失败和重试。
                    list = emptyList()
                    total = 0
                    lastSync = 0
                    error = msg
                    offlineNote = null
                } else error = msg
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
            ?: loadFeaturedDisk(sort)?.also { UiCache.featuredSnaps[sort] = it }
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
            // 只用 GitHub 搜索接口真实返回的仓库；接口失败就显示失败/重试，不显示内置仓库列表。
            list = emptyList(); total = 0; lastSync = 0
            loading = true; error = null; offlineNote = null
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
                    Text("轻量接口通常几秒出结果；网络慢会自动换成备用搜索", fontSize = 10.sp,
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


