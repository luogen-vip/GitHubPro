package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Comment
import com.luogen.ghpro.data.PRFile
import com.luogen.ghpro.data.PullRequest
import kotlinx.coroutines.launch

@Composable
fun PullListTab(nav: NavController, owner: String, name: String) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("open") }
    fun listKey() = "$owner/$name|$state"
    var list by remember { mutableStateOf(UiCache.repoPulls[listKey()] ?: emptyList()) }
    var loading by remember { mutableStateOf(list.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }

    suspend fun load(p: Int, replace: Boolean, silent: Boolean = false) {
        if (p == 1 && !silent) loading = true
        try {
            val pg = Api.pulls(owner, name, state, p)
            list = if (replace) pg.items else list + pg.items
            hasNext = pg.hasNext; page = p
            if (replace) UiCache.repoPulls[listKey()] = list
            error = null
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    LaunchedEffect(state) {
        val cached = UiCache.repoPulls[listKey()]
        if (cached != null && cached.isNotEmpty()) { list = cached; loading = false }
        else load(1, true)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state == "open", onClick = { state = "open" }, label = { Text("打开中", fontSize = 12.sp) })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = state == "closed", onClick = { state = "closed" }, label = { Text("已关闭", fontSize = 12.sp) })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = state == "all", onClick = { state = "all" }, label = { Text("全部", fontSize = 12.sp) })
        }
        when {
            loading && list.isEmpty() -> LoadingBox()
            error != null -> ErrorBox(error) { scope.launch { load(1, true) } }
            list.isEmpty() -> EmptyBox("没有拉取请求")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { pr ->
                    Column(Modifier.fillMaxWidth().clickable { nav.navigate("pr/$owner/$name/${pr.number}") }.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateChip(pr.state, conclusion = if (pr.merged == true) "merged" else null)
                            Spacer(Modifier.width(6.dp))
                            Text(pr.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("#${pr.number} · ${pr.user?.login} · ${timeAgo(pr.created_at)}",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(3.dp))
                        Text("${pr.head?.ref} → ${pr.base?.ref}  ·  +${pr.additions} −${pr.deletions}  ·  ${pr.changed_files} 个文件",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Divider1()
                }
                if (hasNext) item {
                    TextButton(onClick = { scope.launch { load(page + 1, false) } }, modifier = Modifier.fillMaxWidth()) { Text("加载更多", fontSize = 12.sp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullDetailScreen(nav: NavController, owner: String, name: String, number: Int) {
    val scope = rememberCoroutineScope()
    var pr by remember { mutableStateOf<PullRequest?>(null) }
    var files by remember { mutableStateOf<List<PRFile>>(emptyList()) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load() {
        try {
            pr = Api.pull(owner, name, number)
            files = runCatching { Api.prFiles(owner, name, number) }.getOrDefault(emptyList())
            comments = runCatching { Api.prComments(owner, name, number) }.getOrDefault(emptyList())
            error = null
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    LaunchedEffect(number) { load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("拉取请求 #$number", fontSize = 15.sp) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> LoadingBox()
                error != null -> ErrorBox(error) { scope.launch { load() } }
                pr != null -> {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
                        Text(pr!!.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateChip(pr!!.state, conclusion = if (pr!!.merged == true) "merged" else null)
                            Spacer(Modifier.width(8.dp))
                            Text("${pr!!.user?.login} · ${timeAgo(pr!!.created_at)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${pr!!.head?.ref} → ${pr!!.base?.ref}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (pr!!.state == "open") {
                                Button(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        try { Api.mergePr(owner, name, number, null); snackbar.showSnackbar("已合并"); load() }
                                        catch (e: Exception) { snackbar.showSnackbar("合并失败：${e.message}") }
                                        busy = false
                                    }
                                }) { Icon(Icons.Filled.CallMerge, null, Modifier.size(16.dp)); Text("合并", fontSize = 12.sp) }
                                OutlinedButton(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        try { Api.closePr(owner, name, number); snackbar.showSnackbar("已关闭"); load() }
                                        catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                        busy = false
                                    }
                                }) { Text("关闭", fontSize = 12.sp) }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        try { Api.prReview(owner, name, number, "APPROVE", "LGTM"); snackbar.showSnackbar("已批准") }
                                        catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                    }
                                }) { Text("批准", fontSize = 12.sp) }
                            } else {
                                OutlinedButton(onClick = {
                                    scope.launch { runCatching { Api.reopenPr(owner, name, number) }; load() }
                                }) { Text("重新打开", fontSize = 12.sp) }
                            }
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
                        Text(pr!!.body ?: "（无描述）", fontSize = 13.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("改动文件（${files.size}）· +${pr!!.additions} −${pr!!.deletions}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        files.forEach { f ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TinyTag(f.status ?: "modified", if (f.status == "added") GH.green else GH.orange)
                                    Spacer(Modifier.width(6.dp))
                                    Text(f.filename, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text("+${f.additions} −${f.deletions}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                f.patch?.takeIf { it.isNotBlank() }?.let { p ->
                                    Spacer(Modifier.height(3.dp))
                                    Text(p.take(1200), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                        lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Divider1()
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("评论（${comments.size}）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        comments.forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Avatar(c.user?.avatar_url, 24)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${c.user?.login} · ${timeAgo(c.created_at)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(c.body ?: "", fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("写评论…") }, minLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = text.isNotBlank(), onClick = {
                                scope.launch {
                                    try {
                                        Api.addComment(owner, name, number, text)
                                        text = ""; load(); snackbar.showSnackbar("已发送")
                                    } catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                }
                            }) { Icon(Icons.Filled.Send, null, Modifier.size(16.dp)); Text("发送") }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
