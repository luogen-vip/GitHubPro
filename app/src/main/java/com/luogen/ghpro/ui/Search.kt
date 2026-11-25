package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch

val LANG_OPTIONS = listOf("Kotlin", "Java", "Python", "JavaScript", "TypeScript", "Go", "Rust", "C", "C++", "C#",
    "Swift", "Dart", "PHP", "Ruby", "HTML", "Shell", "Lua", "Scala", "Objective-C", "Vue")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(0) } // 0 仓库 1 用户 2 代码 3 议题
    var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var codes by remember { mutableStateOf<List<CodeItem>>(emptyList()) }
    var issues by remember { mutableStateOf<List<Issue>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lang by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }

    suspend fun runSearch(text: String) {
        if (text.isBlank()) { repos = emptyList(); users = emptyList(); codes = emptyList(); issues = emptyList(); total = 0; return }
        loading = true
        error = null
        val fullQ = buildString {
            append(text)
            if (type == 0 && !lang.isNullOrBlank()) append(" language:").append(lang!!.replace(" ", "-"))
        }
        try {
            when (type) {
                0 -> { val r = Api.searchRepos(fullQ, 1, sort, if (sort != null) "desc" else null); repos = r.items; total = r.total_count }
                1 -> { val r = Api.searchUsers(text); users = r.items; total = r.total_count }
                2 -> { val r = Api.searchCode(text); codes = r.items; total = r.total_count }
                else -> { val r = Api.searchIssues(text); issues = r.items; total = r.total_count }
            }
        } catch (e: Exception) { error = e.message }
        loading = false
    }

    LaunchedEffect(q, type, lang, sort) {
        if (q.isBlank()) return@LaunchedEffect
        delay(420)
        runSearch(q)
    }

    val initialBrowse = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (initialBrowse.value && q.isBlank()) {
            browsing = true
            runCatching { Api.searchRepos("stars:>1000", 1, "stars", "desc") }
                .onSuccess { repos = it.items; total = it.total_count }
            browsing = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = q, onValueChange = { q = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("实时搜索仓库 / 用户 / 代码 / 议题…", fontSize = 12.sp) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (q.isNotBlank()) TextButton(onClick = { q = "" }) { Text("清空", fontSize = 11.sp) }
                        }
                    )
                },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("仓库", "用户", "代码", "议题").forEachIndexed { i, t ->
                    FilterChip(selected = type == i, onClick = { type = i }, label = { Text(t, fontSize = 11.sp) })
                }
            }
            if (type == 0) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FilterChip(selected = lang == null, onClick = { lang = null }, label = { Text("全部语言", fontSize = 10.sp) })
                    LANG_OPTIONS.forEach { l ->
                        FilterChip(selected = lang == l, onClick = { lang = if (lang == l) null else l },
                            label = { Text(l, fontSize = 10.sp) })
                    }
                }
                Row(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FilterChip(selected = sort == null, onClick = { sort = null }, label = { Text("最佳匹配", fontSize = 10.sp) })
                    FilterChip(selected = sort == "stars", onClick = { sort = "stars" }, label = { Text("Star 最多", fontSize = 10.sp) })
                    FilterChip(selected = sort == "updated", onClick = { sort = "updated" }, label = { Text("最近更新", fontSize = 10.sp) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
                Text(if (loading) "搜索中…" else if (total > 0) "共 $total 条结果" else "输入关键词即可实时搜索",
                    fontSize = 10.sp, color = GH.muted)
            }
            when {
                error != null -> ErrorBox(error) { scope.launch { runSearch(q) } }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    when (type) {
                        0 -> items(repos, key = { it.id }) { r ->
                            RepoRow(r) { nav.navigate("repo/${r.owner?.login}/${r.name}") }
                            Divider1()
                        }
                        1 -> items(users, key = { it.id }) { u ->
                            Row(Modifier.fillMaxWidth().clickable { nav.navigate("user/${u.login}") }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Avatar(u.avatar_url, 38)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(u.login, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    u.bio?.let { Text(it, fontSize = 11.sp, color = GH.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                                Text("${u.public_repos} 仓库", fontSize = 10.sp, color = GH.muted)
                            }
                            Divider1()
                        }
                        2 -> items(codes, key = { it.sha + it.path }) { c ->
                            Column(Modifier.fillMaxWidth().clickable {
                                val o = c.repository?.owner?.login ?: ""
                                nav.navigate("repo/$o/${c.repository?.name}")
                            }.padding(14.dp)) {
                                Text(c.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(c.path, fontSize = 10.sp, color = GH.muted)
                                c.repository?.let { Text(it.full_name, fontSize = 10.sp, color = GH.blue) }
                            }
                            Divider1()
                        }
                        else -> items(issues, key = { it.id }) { i ->
                            IssueRow(i) {
                                val rp = i.repository_url?.substringAfter("/repos/") ?: return@IssueRow
                                nav.navigate("issue/$rp/${i.number}")
                            }
                            Divider1()
                        }
                    }
                }
            }
        }
    }
}
