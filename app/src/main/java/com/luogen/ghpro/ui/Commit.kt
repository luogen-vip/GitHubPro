package com.luogen.ghpro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.GitCommit
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitDetailScreen(nav: NavController, owner: String, name: String, sha: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var c by remember { mutableStateOf<GitCommit?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load() {
        try {
            c = Api.commitDetail(owner, name, sha)
            error = null
        } catch (e: Exception) {
            error = e.message
        }
        loading = false
    }
    LaunchedEffect(sha) { load() }

    fun copy(text: String, tip: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("复制", text))
        Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("提交 ${sha.take(7)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("$owner/$name", fontSize = 11.sp, color = GH.muted)
                    }
                },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton({ copy(sha, "已复制提交 SHA") }) { Icon(Icons.Filled.ContentCopy, "复制 SHA") }
                    IconButton({ openUrl(ctx, c?.html_url) }) { Icon(Icons.Filled.OpenInNew, "浏览器打开") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        when {
            loading -> LoadingBox()
            error != null -> ErrorBox(error) { scope.launch { load() } }
            c == null -> EmptyBox("找不到这个提交")
            else -> {
                val cm = c!!
                LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                    item {
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
                            MdText(cm.commit?.message ?: "(无提交说明)", baseSize = 14)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(cm.author?.avatar_url, 28)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(cm.commit?.author?.name ?: cm.author?.login ?: "", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text("${cm.commit?.author?.date ?: ""}", fontSize = 10.sp, color = GH.muted)
                                }
                                Spacer(Modifier.weight(1f))
                                Text("+${cm.stats?.additions ?: 0}", fontSize = 11.sp, color = GH.green)
                                Spacer(Modifier.width(6.dp))
                                Text("-${cm.stats?.deletions ?: 0}", fontSize = 11.sp, color = GH.red)
                            }
                        }
                        Divider1()
                    }
                    item {
                        Text("改动文件（${cm.files?.size ?: 0}）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(14.dp, 10.dp))
                    }
                    items(cm.files ?: emptyList(), key = { it.filename }) { f ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.filename, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text("+${f.additions}", fontSize = 10.sp, color = GH.green)
                                Spacer(Modifier.width(5.dp))
                                Text("-${f.deletions}", fontSize = 10.sp, color = GH.red)
                                IconButton({ copy(f.raw_url ?: "", "已复制文件链接") }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp), tint = GH.muted)
                                }
                            }
                            if (!f.patch.isNullOrBlank()) {
                                Box(
                                    Modifier.fillMaxWidth().padding(top = 5.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(GH.surface2)
                                        .horizontalScroll(rememberScrollState())
                                        .padding(8.dp)
                                ) {
                                    Text(f.patch!!, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                        lineHeight = 15.sp, color = GH.text)
                                }
                            } else {
                                Text("（二进制文件或无文本差异）", fontSize = 10.sp, color = GH.muted)
                            }
                        }
                        Divider1()
                    }
                    item { Spacer(Modifier.height(30.dp)) }
                }
            }
        }
    }
}
