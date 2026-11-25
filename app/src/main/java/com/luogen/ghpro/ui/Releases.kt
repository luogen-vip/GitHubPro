package com.luogen.ghpro.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Asset
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.Release
import com.luogen.ghpro.download.Downloader
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ReleasesTab(nav: NavController, owner: String, name: String, repo: Repo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Release>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var uploadTarget by remember { mutableStateOf<Release?>(null) }

    suspend fun load(p: Int, replace: Boolean) {
        if (p == 1) loading = true
        try {
            val pg = Api.releases(owner, name, p)
            list = if (replace) pg.items else list + pg.items
            hasNext = pg.hasNext; page = p; error = null
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    LaunchedEffect(Unit) { load(1, true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val rel = uploadTarget ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val f = File(ctx.cacheDir, "upload.tmp")
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                val n = uri.lastPathSegment?.substringAfterLast('/') ?: "asset.bin"
                Api.uploadReleaseAsset(owner, name, rel.id, f, n)
                f.delete()
                snackbar.showSnackbar("已上传 $n")
                load(1, true)
            } catch (e: Exception) { snackbar.showSnackbar("上传失败：${e.message}") }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("发行版（${list.size}）", fontSize = 12.sp, color = GH.muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text("新建发行版", fontSize = 12.sp)
                }
            }
            when {
                loading && list.isEmpty() -> LoadingBox()
                error != null -> ErrorBox(error) { scope.launch { load(1, true) } }
                list.isEmpty() -> EmptyBox("还没有发行版")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { rel ->
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { expanded = if (expanded == rel.id) null else rel.id }) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(rel.name?.takeIf { it.isNotBlank() } ?: rel.tag_name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (rel.prerelease) { Spacer(Modifier.width(5.dp)); TinyTag("预发行", GH.purple) }
                                        if (rel.draft) { Spacer(Modifier.width(5.dp)); TinyTag("草稿", GH.yellow) }
                                        if (rel.tag_name.startsWith("v")) { Spacer(Modifier.width(5.dp)); TinyTag("正式版", GH.green) }
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text("${rel.tag_name} · ${timeAgo(rel.published_at ?: rel.created_at)} · ${rel.assets?.size ?: 0} 个文件 · ${rel.author?.login ?: ""}",
                                        fontSize = 10.sp, color = GH.muted)
                                }
                                Icon(if (expanded == rel.id) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                                    tint = GH.muted, modifier = Modifier.size(18.dp))
                            }
                            if (expanded == rel.id) {
                                if (!rel.body.isNullOrBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(rel.body!!.take(3000), fontSize = 12.sp, lineHeight = 18.sp)
                                }
                                Spacer(Modifier.height(8.dp))
                                rel.assets?.forEach { a ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(a.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${humanSize(a.size)} · 已下载 ${a.download_count} 次", fontSize = 10.sp, color = GH.muted)
                                        }
                                        IconButton(onClick = {
                                            scope.launch {
                                                try {
                                                    Downloader.download(ctx, a.browser_download_url ?: return@launch, a.name)
                                                    snackbar.showSnackbar("已下载 ${a.name}")
                                                } catch (e: Exception) { snackbar.showSnackbar("下载失败：${e.message}") }
                                            }
                                        }) { Icon(Icons.Filled.Download, null, Modifier.size(18.dp), tint = GH.blue) }
                                        if (repo.permissions?.push == true) {
                                            IconButton(onClick = {
                                                scope.launch {
                                                    try { Api.deleteAsset(owner, name, a.id); load(1, true); snackbar.showSnackbar("已删除附件") }
                                                    catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                                }
                                            }) { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = GH.red) }
                                        }
                                    }
                                    Divider1()
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { uploadTarget = rel; picker.launch("*/*") }) {
                                        Icon(Icons.Filled.Upload, null, Modifier.size(15.dp)); Text("上传附件", fontSize = 11.sp)
                                    }
                                    if (repo.permissions?.push == true) {
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                try { Api.deleteRelease(owner, name, rel.id); load(1, true); snackbar.showSnackbar("已删除发行版") }
                                                catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                            }
                                        }) { Text("删除发行版", fontSize = 11.sp, color = GH.red) }
                                    }
                                    OutlinedButton(onClick = { openUrl(ctx, rel.html_url) }) { Text("浏览器", fontSize = 11.sp) }
                                }
                            }
                        }
                        Divider1()
                    }
                    if (hasNext) item {
                        TextButton(onClick = { scope.launch { load(page + 1, false) } }, modifier = Modifier.fillMaxWidth()) { Text("加载更多", fontSize = 12.sp) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showCreate) {
        var tag by remember { mutableStateOf("") }
        var rname by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        var draft by remember { mutableStateOf(false) }
        var pre by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建发行版") },
            text = {
                Column {
                    OutlinedTextField(tag, { tag = it }, label = { Text("标签（如 v1.0.0）") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(rname, { rname = it }, label = { Text("标题") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(body, { body = it }, label = { Text("说明") }, minLines = 2)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft, { draft = it }); Text("草稿", fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Checkbox(pre, { pre = it }); Text("预发行", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = tag.isNotBlank(), onClick = {
                    scope.launch {
                        try {
                            Api.createRelease(owner, name, tag, rname, body, draft, pre, repo.default_branch)
                            showCreate = false; load(1, true); snackbar.showSnackbar("已创建发行版")
                        } catch (e: Exception) { snackbar.showSnackbar("创建失败：${e.message}") }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton({ showCreate = false }) { Text("取消") } }
        )
    }
}
