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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Discussion
import com.luogen.ghpro.data.Repo
import kotlinx.coroutines.launch

@Composable
fun DiscussionsTab(nav: NavController, owner: String, name: String, repo: Repo) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Discussion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        try {
            list = Api.discussions(owner, name)
            error = null
            msg = null
        } catch (e: Exception) {
            error = e.message
            msg = when {
                e.message?.contains("discussions", true) == true -> "这个仓库没有开启 Discussions 讨论区（仓库设置里可开启）"
                else -> null
            }
        }
        loading = false
    }
    LaunchedEffect(owner, name) { load() }

    when {
        loading -> LoadingBox()
        error != null -> Column {
            ErrorBox(error) { scope.launch { load() } }
            msg?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
        }
        list.isEmpty() -> EmptyBox("还没有讨论，去网页端开启 Discussions 后即可在这里看到")
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

    suspend fun load() {
        try {
            d = Api.discussionDetail(owner, name, number)
            error = if (d == null) "没有找到这个讨论" else null
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    LaunchedEffect(number) { load() }

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
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
                        d!!.category?.let { Text("${it.emoji ?: ""} ${it.name}", fontSize = 11.sp, color = GH.blue) }
                        Text(d!!.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("${d!!.author?.login ?: ""} · ${timeAgo(d!!.createdAt)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text(d!!.body ?: (d!!.body ?: ""), fontSize = 13.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(14.dp))
                        Text("回复（${d!!.comments?.totalCount ?: 0}）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        d!!.comments?.nodes?.forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Avatar(c.author?.avatarUrl, 24)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(c.author?.login ?: "", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.width(5.dp))
                                        Text(timeAgo(c.createdAt), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (c.isAnswer) { Spacer(Modifier.width(5.dp)); TinyTag("答案", GH.green) }
                                    }
                                    Text(c.body ?: "", fontSize = 12.sp, lineHeight = 18.sp)
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
