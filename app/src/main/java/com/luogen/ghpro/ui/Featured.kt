package com.luogen.ghpro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable { mutableStateOf("stars") }
    var list by remember { mutableStateOf<List<Repo>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastSync by remember { mutableStateOf(0L) }

    suspend fun load(silent: Boolean = false) {
        if (!silent) loading = true
        try {
            val q = if (sort == "updated") "stars:>500 pushed:>2020-01-01" else "stars:>1000"
            val r = Api.searchRepos(q, 1, sort, "desc", perPage = 60)
            list = when (sort) {
                "updated" -> r.items.sortedByDescending { it.pushed_at ?: it.updated_at ?: "" }
                "forks" -> r.items.sortedByDescending { it.forks_count }
                else -> r.items.sortedByDescending { it.stargazers_count }
            }
            total = r.total_count
            error = null
            lastSync = System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { if (!silent) error = e.message }
        loading = false
    }

    LaunchedEffect(sort) { load() }

    // 每 40 秒自动刷新，排名变化自己更新
    LaunchedEffect(sort) {
        while (isActive) {
            delay(40_000L)
            runCatching { load(true) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                actions = { IconButton({ scope.launch { load() } }) { Icon(Icons.Filled.Refresh, "刷新") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FEATURED_SORTS.forEach { s ->
                    FilterChip(selected = sort == s.key, onClick = { sort = s.key }, label = { Text(s.label, fontSize = 12.sp) })
                }
            }
            when {
                loading && list.isEmpty() -> LoadingBox("正在挑选高质量仓库…")
                error != null && list.isEmpty() -> ErrorBox(error) { scope.launch { load() } }
                list.isEmpty() -> EmptyBox("暂时没有取到精选仓库")
                else -> LazyColumn(Modifier.fillMaxSize()) {
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
