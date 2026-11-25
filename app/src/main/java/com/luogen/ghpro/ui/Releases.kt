package com.luogen.ghpro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Asset
import com.luogen.ghpro.data.Bus
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
    var expanded by rememberSaveable { mutableStateOf(-1L) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var uploadTargetId by rememberSaveable { mutableStateOf(-1L) }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load(p: Int, replace: Boolean, silent: Boolean = false) {
        if (p == 1 && !silent) loading = true
        try {
            val pg = Api.releases(owner, name, p)
            list = if (replace) pg.items else list + pg.items
            hasNext = pg.hasNext; page = p; error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { if (!silent) error = e.message }
        loading = false
    }
    LaunchedEffect(Unit) { load(1, true) }

    fun copy(text: String, tip: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("复制", text))
        Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uploadTargetId < 0) return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val rel = list.firstOrNull { it.id == uploadTargetId } ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val f = File(ctx.cacheDir, "upload.tmp")
                val n = uri.lastPathSegment?.substringAfterLast('/') ?: "asset.bin"
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                snackbar.showSnackbar("正在上传 $n …")
                val ret = Api.uploadReleaseAsset(owner, name, rel.id, f, n)
                val id = ret.get("id")?.asLong ?: 0L
                val url = ret.get("browser_download_url")?.asString ?: ""
                val sz = ret.get("size")?.asLong ?: f.length()
                f.delete()
                // 立刻把附件塞进当前列表，不用切页也能看到
                list = list.map { r ->
                    if (r.id == rel.id) r.copy(assets = (r.assets ?: emptyList()) + Asset(id = id, name = n, size = sz, download_count = 0, browser_download_url = url)) else r
                }
                expanded = rel.id
                snackbar.showSnackbar("已上传 $n")
                Toast.makeText(ctx, "附件 $n 上传成功", Toast.LENGTH_SHORT).show()
                load(1, true, silent = true)
            } catch (e: Exception) {
                snackbar.showSnackbar("上传失败：${e.message}")
            }
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
                error != null && list.isEmpty() -> ErrorBox(error) { scope.launch { load(1, true) } }
                list.isEmpty() -> EmptyBox("还没有发行版")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { rel ->
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { expanded = if (expanded == rel.id) -1L else rel.id }) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(rel.name?.takeIf { it.isNotBlank() } ?: rel.tag_name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (rel.prerelease) { Spacer(Modifier.width(5.dp)); TinyTag("预发行", GH.purple) }
                                        if (rel.draft) { Spacer(Modifier.width(5.dp)); TinyTag("草稿", GH.yellow) }
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text("${rel.tag_name} · ${timeAgo(rel.published_at ?: rel.created_at)} · ${rel.assets?.size ?: 0} 个文件 · ${rel.author?.login ?: ""}",
                                        fontSize = 10.sp, color = GH.muted)
                                }
                                IconButton({ copy(rel.html_url ?: "", "已复制发行版链接") }) {
                                    Icon(Icons.Filled.ContentCopy, "复制链接", Modifier.size(16.dp), tint = GH.muted)
                                }
                                Icon(if (expanded == rel.id) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                                    tint = GH.muted, modifier = Modifier.size(18.dp))
                            }
                            if (expanded == rel.id) {
                                if (!rel.body.isNullOrBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    MdText(rel.body!!.take(20000), baseSize = 12)
                                } else {
                                    Spacer(Modifier.height(6.dp))
                                    Text("这个发行版没有填写说明", fontSize = 11.sp, color = GH.muted)
                                }
                                Spacer(Modifier.height(8.dp))
                                val dl by Downloader.state.collectAsState()
                                rel.assets?.forEach { a ->
                                    val st = dl[a.name]
                                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(a.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${humanSize(a.size)} · 已下载 ${a.download_count} 次", fontSize = 10.sp, color = GH.muted)
                                            if (st != null && st.status !in listOf("已完成", "失败")) {
                                                LinearProgressIndicator(
                                                    progress = { if (st.total > 0) st.percent / 100f else 0f },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp))
                                            }
                                        }
                                        IconButton({ copy(a.browser_download_url ?: "", "已复制下载链接") }) {
                                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), tint = GH.muted)
                                        }
                                        IconButton(onClick = {
                                            val url = a.browser_download_url ?: return@IconButton
                                            Downloader.startDownload(ctx, url, a.name,
                                                onDone = { _ ->
                                                    val where = Downloader.stateOf(a.name)?.publicPath ?: "下载/GitHubPro/${a.name}"
                                                    Toast.makeText(ctx, "已下载到 $where", Toast.LENGTH_LONG).show()
                                                    scope.launch { snackbar.showSnackbar("已保存到 $where") }
                                                },
                                                onError = { m ->
                                                    Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show()
                                                })
                                        }) { Icon(Icons.Filled.Download, null, Modifier.size(18.dp), tint = GH.blue) }
                                        if (repo.permissions?.push == true) {
                                            IconButton(onClick = {
                                                scope.launch {
                                                    try {
                                                        Api.deleteAsset(owner, name, a.id)
                                                        list = list.map { r -> if (r.id == rel.id) r.copy(assets = r.assets?.filter { it.id != a.id }) else r }
                                                        snackbar.showSnackbar("已删除附件")
                                                        load(1, true, silent = true)
                                                    } catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                                }
                                            }) { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = GH.red) }
                                        }
                                    }
                                    Divider1()
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { uploadTargetId = rel.id; picker.launch("*/*") }) {
                                        Icon(Icons.Filled.Upload, null, Modifier.size(15.dp)); Text("上传附件", fontSize = 11.sp)
                                    }
                                    OutlinedButton(onClick = { copy(rel.html_url ?: "", "已复制发行版链接") }) {
                                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(15.dp)); Text("复制链接", fontSize = 11.sp)
                                    }
                                    OutlinedButton(onClick = { openUrl(ctx, rel.html_url) }) { Text("浏览器", fontSize = 11.sp) }
                                    if (repo.permissions?.push == true) {
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                try {
                                                    Api.deleteRelease(owner, name, rel.id)
                                                    list = list.filter { it.id != rel.id }
                                                    snackbar.showSnackbar("已删除发行版")
                                                    load(1, true, silent = true)
                                                } catch (e: Exception) { snackbar.showSnackbar("失败：${e.message}") }
                                            }
                                        }) { Text("删除发行版", fontSize = 11.sp, color = GH.red) }
                                    }
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
        var tag by rememberSaveable { mutableStateOf("") }
        var rname by rememberSaveable { mutableStateOf("") }
        var body by rememberSaveable { mutableStateOf("") }
        var draft by rememberSaveable { mutableStateOf(false) }
        var pre by rememberSaveable { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) showCreate = false },
            title = { Text("新建发行版") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(tag, { tag = it }, label = { Text("标签（如 v1.0.0）") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(rname, { rname = it }, label = { Text("标题") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(body, { body = it }, label = { Text("说明（支持 Markdown）") }, minLines = 4)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft, { draft = it }); Text("草稿", fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Checkbox(pre, { pre = it }); Text("预发行", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = tag.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val created = Api.createRelease(owner, name, tag, rname, body, draft, pre, repo.default_branch)
                            // 立刻插入列表并展开，不用切页
                            list = listOf(created) + list.filter { it.id != created.id }
                            expanded = created.id
                            showCreate = false
                            Toast.makeText(ctx, "发行版 ${created.tag_name} 已创建", Toast.LENGTH_SHORT).show()
                            snackbar.showSnackbar("发行版已创建")
                            Bus.poke()
                            load(1, true, silent = true)
                        } catch (e: Exception) {
                            snackbar.showSnackbar("创建失败：${e.message}")
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "创建中…" else "创建") }
            },
            dismissButton = { TextButton({ if (!busy) showCreate = false }) { Text("取消") } }
        )
    }
}
