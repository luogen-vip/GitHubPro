package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Comment
import com.luogen.ghpro.data.PRFile
import com.luogen.ghpro.data.PullRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

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
    var showCreate by remember { mutableStateOf(false) }

    suspend fun load(p: Int, replace: Boolean, silent: Boolean = false) {
        if (p == 1 && !silent) loading = true
        try {
            val pg = Api.pulls(owner, name, state, p)
            list = if (replace) pg.items else list + pg.items
            hasNext = pg.hasNext; page = p
            if (replace) UiCache.repoPulls[listKey()] = list
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (!silent) error = e.message }
        loading = false
    }
    LaunchedEffect(state) {
        val cached = UiCache.repoPulls[listKey()]
        if (cached != null && cached.isNotEmpty()) { list = cached; loading = false; load(1, true, silent = true) }
        else load(1, true)
    }
    LaunchedEffect(state) {
        while (isActive) {
            delay(3_000L)
            if (page == 1 && !loading) runCatching { load(1, true, silent = true) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = state == "open", onClick = { state = "open" }, label = { Text("打开中", fontSize = 12.sp) })
                FilterChip(selected = state == "closed", onClick = { state = "closed" }, label = { Text("已关闭", fontSize = 12.sp) })
                FilterChip(selected = state == "all", onClick = { state = "all" }, label = { Text("全部", fontSize = 12.sp) })
            }
            Spacer(Modifier.width(8.dp))
            // 固定高度、单行不换字：不会再把整行撑成两行高，也不会把“新建 PR”挤成竖排
            FilledTonalButton(
                onClick = { showCreate = true },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text("新建 PR", fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
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
    if (showCreate) {
        CreatePrDialog(owner, name, { showCreate = false }) { pr ->
            UiCache.repoPulls.clear()
            showCreate = false
            nav.navigate("pr/$owner/$name/${pr.number}")
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
    var mergeMethod by remember { mutableStateOf("merge") }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load() {
        try {
            pr = Api.pull(owner, name, number)
            files = runCatching { Api.prFiles(owner, name, number) }.getOrDefault(emptyList())
            comments = runCatching { Api.prComments(owner, name, number) }.getOrDefault(emptyList())
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        loading = false
    }
    LaunchedEffect(number) { load() }
    LaunchedEffect(number) {
        while (isActive) {
            delay(3_000L)
            if (!busy) runCatching { load() }
        }
    }

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
                    val canPush = UiCache.repoData["$owner/$name"]?.permissions?.let { it.push || it.admin } == true
                    val canClosePr = canPush || pr!!.user?.login == Cfg.login
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
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (pr!!.state == "open") {
                                if (canPush) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("merge" to "合并", "squash" to "压缩", "rebase" to "变基").forEach { (k, v) ->
                                        FilterChip(selected = mergeMethod == k, onClick = { mergeMethod = k },
                                            label = { Text(v, fontSize = 10.sp) })
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (canPush) Button(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        try { Api.mergePr(owner, name, number, null, mergeMethod); snackbar.showSnackbar("已合并"); load() }
                                        catch (e: Exception) { snackbar.showSnackbar("合并失败：${e.message}") }
                                        busy = false
                                    }
                                }) { Icon(Icons.Filled.CallMerge, null, Modifier.size(16.dp)); Text("合并", fontSize = 12.sp) }
                                if (canClosePr) OutlinedButton(enabled = !busy, onClick = {
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
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (canClosePr) OutlinedButton(onClick = {
                                    scope.launch { runCatching { Api.reopenPr(owner, name, number) }; load() }
                                }) { Text("重新打开", fontSize = 12.sp) }
                                }
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePrDialog(owner: String, name: String, onDismiss: () -> Unit, onCreated: (PullRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    val login = Cfg.login
    var useFork by remember { mutableStateOf(login != null && login != owner) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(UiCache.repoData["$owner/$name"]?.default_branch ?: "main") }
    var headBranch by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(false) }
    var baseBranches by remember { mutableStateOf<List<String>>(emptyList()) }
    var headBranches by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val headOwner = if (useFork) (login ?: owner) else owner

    LaunchedEffect(owner, name) {
        runCatching { Api.branches(owner, name) }.onSuccess { baseBranches = it.map { b -> b.name } }
    }
    LaunchedEffect(headOwner, useFork) {
        runCatching { Api.branches(headOwner, name) }.onSuccess { headBranches = it.map { b -> b.name } }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("新建 Pull Request", fontSize = 15.sp) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                Text("把你的分支提交给 $owner/$name，作者可以审核并合并。", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !useFork, onClick = { useFork = false }, label = { Text("当前仓库分支", fontSize = 10.sp) })
                    if (login != null && login != owner) {
                        FilterChip(selected = useFork, onClick = { useFork = true }, label = { Text("我的复刻 @$login", fontSize = 10.sp) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(base, { base = it.trim() }, Modifier.fillMaxWidth(),
                    label = { Text("目标分支 base（合并到）") }, singleLine = true)
                if (baseBranches.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        baseBranches.take(12).forEach { b ->
                            FilterChip(selected = base == b, onClick = { base = b }, label = { Text(b, fontSize = 9.sp) })
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(headBranch, { headBranch = it.trim() }, Modifier.fillMaxWidth(),
                    label = { Text(if (useFork) "来源分支 head（@$headOwner 的分支）" else "来源分支 head") }, singleLine = true)
                if (headBranches.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        headBranches.take(12).forEach { b ->
                            FilterChip(selected = headBranch == b, onClick = { headBranch = b }, label = { Text(b, fontSize = 9.sp) })
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                if (useFork) Text("跨复刻提交时，GitHub 实际发送 head=@$headOwner:$headBranch；分支必须先推到你的复刻仓库。",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(),
                    label = { Text("标题") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(),
                    label = { Text("说明（可选）") }, minLines = 3, maxLines = 8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("创建为草稿", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(checked = draft, onCheckedChange = { draft = it })
                }
                error?.let { Text(it, fontSize = 11.sp, color = GH.red) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && title.isNotBlank() && base.isNotBlank() && headBranch.isNotBlank(), onClick = {
                scope.launch {
                    busy = true; error = null
                    try {
                        val head = if (useFork) "$headOwner:$headBranch" else headBranch
                        val pr = Api.createPull(owner, name, title.trim(), head, base.trim(), body.trim(), draft)
                        onCreated(pr)
                    } catch (e: Exception) {
                        error = "创建失败：${e.message}"
                    }
                    busy = false
                }
            }) { Text(if (busy) "提交中…" else "提交合并请求", fontSize = 12.sp) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}
