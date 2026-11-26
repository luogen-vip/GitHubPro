package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.CodeItem
import com.luogen.ghpro.data.Issue
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.User
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

val LANG_OPTIONS = listOf("Kotlin", "Java", "Python", "JavaScript", "TypeScript", "Go", "Rust", "C", "C++", "C#",
    "Swift", "Dart", "PHP", "Ruby", "HTML", "Shell", "Lua", "Scala", "Objective-C", "Vue")

private data class SortChip(val key: String?, val label: String)

private val SORTS = listOf(
    SortChip(null, "最佳匹配"),
    SortChip("stars", "Star 最多"),
    SortChip("updated", "最近更新"),
    SortChip("forks", "复刻最多")
)

@Composable
private fun RoundedSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    onClear: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (focused) GH.blue else MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(17.dp), tint = if (focused) GH.blue else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(GH.blue),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(20.dp).clip(CircleShape).clickable { onClear() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "清空", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SearchScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(UiCache.searchQuery) }
    var type by remember { mutableStateOf(UiCache.searchType) }
    var lang by remember { mutableStateOf(UiCache.searchLang) }
    var sort by remember { mutableStateOf(UiCache.searchSort) }
    var repos by remember { mutableStateOf(UiCache.searchRepos) }
    var users by remember { mutableStateOf(UiCache.searchUsers) }
    var codes by remember { mutableStateOf(UiCache.searchCodes) }
    var issues by remember { mutableStateOf(UiCache.searchIssues) }
    var total by remember { mutableStateOf(UiCache.searchTotal) }
    var page by remember { mutableStateOf(UiCache.searchPage) }
    var hasNext by remember { mutableStateOf(UiCache.searchHasNext) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }
    var browsing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState(UiCache.searchIndex, UiCache.searchOffset)

    fun cacheWrite() {
        UiCache.searchQuery = q
        UiCache.searchType = type
        UiCache.searchLang = lang
        UiCache.searchSort = sort
        UiCache.searchRepos = repos
        UiCache.searchUsers = users
        UiCache.searchCodes = codes
        UiCache.searchIssues = issues
        UiCache.searchTotal = total
        UiCache.searchPage = page
        UiCache.searchHasNext = hasNext
        UiCache.searchKey = "$type|$lang|$sort|$q"
        UiCache.searchRan = true
    }

    fun clearAll() {
        repos = emptyList(); users = emptyList(); codes = emptyList(); issues = emptyList()
        total = 0; page = 1; hasNext = false
    }

    fun orderRepos(list: List<Repo>): List<Repo> = when (sort) {
        "stars" -> list.sortedByDescending { it.stargazers_count }
        "forks" -> list.sortedByDescending { it.forks_count }
        "updated" -> list.sortedByDescending { it.pushed_at ?: it.updated_at ?: "" }
        else -> list
    }

    suspend fun runFresh(text: String, resetScroll: Boolean) {
        if (text.isBlank()) { clearAll(); cacheWrite(); return }
        loading = true
        error = null
        try {
            if (type == 0) {
                val fullQ = buildString {
                    append(text)
                    if (!lang.isNullOrBlank()) append(" language:\"").append(lang).append("\"")
                }
                val r = Api.searchRepos(fullQ, 1, sort, if (sort != null) "desc" else null, perPage = 100)
                val looksLikeUser = text.trim().length > 2 && !text.contains(" ") && text.trim().matches(Regex("[A-Za-z0-9._-]+"))
                val own = if (looksLikeUser) runCatching {
                    Api.userRepos(text.trim(), 1, 100, if (sort == "updated") "pushed" else "updated").items
                }.getOrDefault(emptyList()) else emptyList()
                val names = r.items.map { it.full_name }.toSet()
                repos = orderRepos(own + r.items.filter { it.full_name !in own.map { o -> o.full_name } })
                total = r.total_count + own.count { it.full_name !in names }
                hasNext = r.items.size >= 100
            } else if (type == 1) {
                val r = Api.searchUsers(text, 1, 100)
                users = r.items; total = r.total_count; hasNext = r.items.size >= 100
                // 搜索接口返回的用户对象没有仓库数量，补全前几个真实数量
                users = users.mapIndexed { i, u ->
                    if (i < 8 && u.public_repos == 0) {
                        runCatching { Api.user(u.login) }.getOrNull()
                            ?.let { full -> u.copy(public_repos = full.public_repos, followers = full.followers) } ?: u
                    } else u
                }
            } else if (type == 2) {
                val r = Api.searchCode(text, 1, 100)
                codes = r.items; total = r.total_count; hasNext = r.items.size >= 100
            } else {
                val r = Api.searchIssues(text, 1, 100)
                issues = r.items; total = r.total_count; hasNext = r.items.size >= 100
            }
            page = 1
            if (resetScroll) {
                listState.scrollToItem(0)
                UiCache.searchIndex = 0; UiCache.searchOffset = 0
            }
            cacheWrite()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    suspend fun loadMore() {
        if (loadingMore || loading || !hasNext || q.isBlank()) return
        loadingMore = true
        try {
            val text = q
            val next = page + 1
            if (type == 0) {
                val fullQ = buildString {
                    append(text)
                    if (!lang.isNullOrBlank()) append(" language:\"").append(lang).append("\"")
                }
                val r = Api.searchRepos(fullQ, next, sort, if (sort != null) "desc" else null, perPage = 100)
                repos = orderRepos(repos + r.items); hasNext = r.items.size >= 100
            } else if (type == 1) {
                val r = Api.searchUsers(text, next, 100)
                users = users + r.items; hasNext = r.items.size >= 100
            } else if (type == 2) {
                val r = Api.searchCode(text, next, 100)
                codes = codes + r.items; hasNext = r.items.size >= 100
            } else {
                val r = Api.searchIssues(text, next, 100)
                issues = issues + r.items; hasNext = r.items.size >= 100
            }
            page = next
            cacheWrite()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            loadingMore = false
        }
    }

    // 输入 400ms 防抖；切分类 / 语言 / 排序立刻重搜
    var lastFilterKey by remember { mutableStateOf("$type|$lang|$sort") }
    var firstPass by remember { mutableStateOf(true) }
    LaunchedEffect(q, type, lang, sort, retryTick) {
        val key = "$type|$lang|$sort"
        if (firstPass) {
            firstPass = false
            // 从详情页返回：缓存里的搜索词、分类、语言、排序、结果、位置全都对得上，就直接还原，不再重搜也不回顶
            if (UiCache.searchRan && UiCache.searchKey == "$key|$q") {
                lastFilterKey = key
                return@LaunchedEffect
            }
        }
        val filtersChanged = key != lastFilterKey
        lastFilterKey = key
        if (q.isBlank()) { clearAll(); cacheWrite(); return@LaunchedEffect }
        delay(if (filtersChanged) 0 else 220)
        runFresh(q, resetScroll = true)
    }

    // 首次进入：没有缓存则给一个推荐列表
    LaunchedEffect(Unit) {
        if (!UiCache.searchRan && q.isBlank() && repos.isEmpty()) {
            browsing = true
            runCatching { Api.searchRepos("stars:>5000", 1, "stars", "desc", 50) }
                .onSuccess { repos = it.items; total = it.total_count }
            browsing = false
        }
    }

    // 保存滚动位置
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { pair -> UiCache.searchIndex = pair.first; UiCache.searchOffset = pair.second }
    }

    // 滚动到底自动加载更多
    LaunchedEffect(listState, hasNext) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (hasNext && last >= currentCount(repos, users, codes, issues) - 4) loadMore() }
    }

    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 || listState.firstVisibleItemScrollOffset > 600 } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (showTop) SmallFab { scope.launch { listState.animateScrollToItem(0) } }
        },
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") }
                    RoundedSearchField(q, { q = it }, "搜索仓库 / 用户 / 代码 / 议题", { q = "" })
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("仓库", "用户", "代码", "议题").forEachIndexed { i, t ->
                    FilterChip(selected = type == i, onClick = { type = i }, label = { Text(t, fontSize = 12.sp) })
                }
            }
            if (type == 0) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    FilterChip(selected = lang == null, onClick = { lang = null }, label = { Text("全部语言", fontSize = 11.sp) })
                    LANG_OPTIONS.forEach { l ->
                        FilterChip(selected = lang == l, onClick = { lang = if (lang == l) null else l },
                            label = { Text(l, fontSize = 11.sp) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    SORTS.forEach { s ->
                        FilterChip(selected = sort == s.key, onClick = { sort = s.key }, label = { Text(s.label, fontSize = 11.sp) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(
                    when {
                        loading -> "搜索中…"
                        error != null -> "搜索失败"
                        browsing -> "热门仓库推荐"
                        total > 0 -> "共 ${compactNumLong(total)} 条结果，已加载 ${currentCount(repos, users, codes, issues)}"
                        q.isBlank() -> "输入关键词即可实时搜索"
                        else -> "没有结果"
                    },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                when {
                    error != null -> item {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(messageOf(error), fontSize = 12.sp, color = GH.red)
                            TextButton({ retryTick++ }) { Text("重试") }
                        }
                    }
                    loading && currentCount(repos, users, codes, issues) == 0 -> item { LoadingBox("正在搜索…") }
                }
                when (type) {
                        0 -> items(repos, key = { "${it.id}-${it.full_name}" }) { r ->
                            RepoRow(r) { nav.navigate("repo/${r.full_name}") }
                        }
                        1 -> items(users, key = { it.id }) { u ->
                            Card(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                                    .clickable { nav.navigate("user/${u.login}") },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(u.avatar_url, 38)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(u.login, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        u.bio?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                    Text(if (u.public_repos > 0) "${u.public_repos} 仓库" else "查看主页", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        2 -> items(codes, key = { (it.sha ?: "") + it.path }) { c ->
                            Card(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable {
                                    val o = c.repository?.owner?.login ?: ""
                                    nav.navigate("repo/$o/${c.repository?.name}")
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(c.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(c.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    c.repository?.let { Text(it.full_name, fontSize = 11.sp, color = GH.blue) }
                                }
                            }
                        }
                        else -> items(issues, key = { it.id }) { i ->
                            Card(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                            ) {
                                IssueRow(i) {
                                    val rp = i.repository_url?.substringAfter("/repos/") ?: return@IssueRow
                                    nav.navigate("issue/$rp/${i.number}")
                                }
                            }
                        }
                    }
                    if (loadingMore) item { LoadingBox("加载更多…") }
                    if (!loading && !hasNext && currentCount(repos, users, codes, issues) > 0) item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("已全部加载（GitHub 搜索最多返回前 1000 条）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
    }

private fun currentCount(r: List<Repo>, u: List<User>, c: List<CodeItem>, i: List<Issue>): Int = when {
    r.isNotEmpty() -> r.size
    u.isNotEmpty() -> u.size
    c.isNotEmpty() -> c.size
    else -> i.size
}

private fun compactNumLong(v: Long): String = when {
    v >= 100_000_000 -> String.format("%.1f亿", v / 100_000_000.0)
    v >= 10_000 -> String.format("%.1f万", v / 10_000.0)
    else -> v.toString()
}
