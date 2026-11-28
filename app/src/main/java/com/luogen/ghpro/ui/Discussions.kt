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
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Discussion
import com.luogen.ghpro.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun DiscussionsTab(nav: NavController, owner: String, name: String, repo: Repo) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val dk = "$owner/$name"
    var list by remember { mutableStateOf(UiCache.repoDiscussions[dk] ?: emptyList()) }
    var loading by remember { mutableStateOf(list.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var canDiscuss by remember { mutableStateOf(repo.has_discussions) }

    // 编辑仓库设置后 repo 会立刻换成新对象：这里同帧刷新开关状态。
    // 关闭时马上显示“未开启”，不再反复请求两三次才变。
    LaunchedEffect(repo.has_discussions, owner, name) {
        canDiscuss = repo.has_discussions
        if (!repo.has_discussions) {
            list = emptyList()
            error = null
            msg = null
            loading = false
            UiCache.repoDiscussions.remove(dk)
        }
    }

    suspend fun load(showLoading: Boolean = true, silent: Boolean = false) {
        if (showLoading) loading = true
        try {
            val page = Api.discussions(owner, name)
            canDiscuss = page.enabled
            list = page.items
            if (page.enabled) UiCache.repoDiscussions[dk] = list else UiCache.repoDiscussions.remove(dk)
            error = null
            msg = if (!page.enabled) "GitHub 返回：这个仓库还没有开启 Discussions" else null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            // 静默轮询失败时保留当前列表；只有首屏没有数据才把错误铺满页面。
            if (!silent || list.isEmpty()) error = e.message
            msg = null
        }
        loading = false
    }
    LaunchedEffect(owner, name, repo.has_discussions) {
        // 有缓存也先展示，紧接着静默复查；停留期间每 4 秒补一次最新讨论。
        if (!repo.has_discussions) {
            loading = false
            return@LaunchedEffect
        }
        load(showLoading = list.isEmpty())
        while (isActive) {
            delay(2_000L)
            load(showLoading = false, silent = true)
        }
    }

    when {
        !canDiscuss -> Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("这个仓库还没有开启 Discussions", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("开启后，讨论会显示在这里；和“议题”不是同一个功能。议题用来报 Bug / 提需求，讨论更像论坛帖子。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button({ openUrl(ctx, (repo.html_url ?: "https://github.com/$dk") + "/settings") }) {
                Text("去网页端开启", fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ scope.launch { load() } }) { Text("我已开启，重新检查", fontSize = 12.sp) }
            msg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        loading -> LoadingBox()
        error != null -> Column {
            ErrorBox(error) { scope.launch { load() } }
            msg?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
        }
        list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有讨论", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Discussions 已开启，只是还没有人发帖。网页端新建的讨论刷新后会出现在这里。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ scope.launch { load() } }) { Text("刷新讨论", fontSize = 12.sp) }
            Spacer(Modifier.height(8.dp))
            TextButton({ openUrl(ctx, (repo.html_url ?: "https://github.com/$dk") + "/discussions") }) {
                Text("去网页端看讨论区", fontSize = 12.sp)
            }
            msg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 10.sp,
                    color = if (canDiscuss) MaterialTheme.colorScheme.onSurfaceVariant else GH.red)
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.number }) { d ->
                Column(Modifier.fillMaxWidth().clickable { nav.navigate("discussion/$owner/$name/${d.number}") }.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        d.category?.emoji?.let { Text("$it ", fontSize = 13.sp) }
                        Text(d.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("#${d.number} · ${d.author?.login ?: ""} · ${timeAgo(d.createdAt)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.Comment, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${d.comments?.totalCount ?: 0}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (d.answerChosenAt != null) {
                        Spacer(Modifier.height(3.dp))
                        TinyTag("已有采纳的回答", GH.green)
                    }
                }
                Divider1()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscussionDetailScreen(nav: NavController, owner: String, name: String, number: Int) {
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<Discussion?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val dkey = "$owner/$name#$number"
    val scrollState = rememberScrollState()
    var restored by remember(number) { mutableStateOf(false) }

    LaunchedEffect(d, restored) {
        if (d != null && !restored) {
            // 等正文/评论完成一次布局再恢复，否则滚动值会被内容高度夹到 0。
            delay(80)
            scrollState.scrollTo(UiCache.discussionScroll[dkey] ?: 0)
            restored = true
        }
    }
    LaunchedEffect(scrollState, restored) {
        // 恢复完成前不要监听：初次组合时 0 会立刻把缓存里的旧位置覆盖掉。
        if (restored) snapshotFlow { scrollState.value }.collect { UiCache.discussionScroll[dkey] = it }
    }

    suspend fun load(silent: Boolean = false) {
        try {
            d = Api.discussionDetail(owner, name, number)
            error = if (d == null) "没有找到这个讨论" else null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            // 已有内容时轮询失败不能把正文清成错误页。
            if (!silent || d == null) error = e.message
        }
        loading = false
    }
    LaunchedEffect(number) {
        load()
        while (isActive) {
            delay(2_000L)
            load(silent = true)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("讨论 #$number", fontSize = 15.sp) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> LoadingBox()
                error != null -> ErrorBox(error)
                d != null -> {
                    Column(Modifier.weight(1f).verticalScroll(scrollState).padding(14.dp)) {
                        d!!.category?.let { Text("${it.emoji ?: ""} ${it.name}", fontSize = 11.sp, color = GH.blue) }
                        if (d!!.title.isNotBlank()) {
                            TranslateBlock(
                                text = d!!.title,
                                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp),
                                compact = true
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        val postAuthor = d!!.author?.login.orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(
                                d!!.author?.avatarUrl, 24,
                                Modifier.clickable(enabled = postAuthor.isNotBlank()) {
                                    if (postAuthor.isNotBlank()) nav.navigate("user/$postAuthor")
                                },
                                name = postAuthor
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                postAuthor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable(enabled = postAuthor.isNotBlank()) {
                                    if (postAuthor.isNotBlank()) nav.navigate("user/$postAuthor")
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(timeAgo(d!!.createdAt), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(10.dp))
                        val body = d!!.body.orEmpty()
                        if (body.isNotBlank()) {
                            TranslateBlock(
                                text = body,
                                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                                compact = true
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("回复（${d!!.comments?.totalCount ?: 0}）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        d!!.comments?.nodes?.forEach { c ->
                            val commentAuthor = c.author?.login.orEmpty()
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Avatar(
                                    c.author?.avatarUrl, 24,
                                    Modifier.clickable(enabled = commentAuthor.isNotBlank()) {
                                        if (commentAuthor.isNotBlank()) nav.navigate("user/$commentAuthor")
                                    },
                                    name = commentAuthor
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            commentAuthor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                            modifier = Modifier.clickable(enabled = commentAuthor.isNotBlank()) {
                                                if (commentAuthor.isNotBlank()) nav.navigate("user/$commentAuthor")
                                            }
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(timeAgo(c.createdAt), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (c.isAnswer) { Spacer(Modifier.width(5.dp)); TinyTag("答案", GH.green) }
                                    }
                                    val commentBody = c.body.orEmpty()
                                    if (commentBody.isNotBlank()) {
                                        TranslateBlock(
                                            text = commentBody,
                                            style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                                            compact = true
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("回复讨论…") }, minLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = text.isNotBlank(), onClick = {
                                scope.launch {
                                    try {
                                        Api.addDiscussionComment(owner, name, number, text)
                                        text = ""
                                        load()
                                        snackbar.showSnackbar("已回复")
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
