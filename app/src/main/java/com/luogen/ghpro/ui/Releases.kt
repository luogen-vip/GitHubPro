package com.luogen.ghpro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Cancel
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Asset
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.Release
import com.luogen.ghpro.download.Downloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** 取文件类型后缀；tar.gz 这类复合后缀保留完整，避免只显示 GZ。 */
private fun assetExt(name: String): String? {
    val clean = name.trim()
    val lower = clean.lowercase()
    listOf("tar.gz", "tar.xz", "tar.bz2", "tar.zst").firstOrNull { lower.endsWith(".$it") }?.let { return it.uppercase() }
    val dot = clean.lastIndexOf('.')
    if (dot <= 0 || dot >= clean.lastIndex) return null
    val ext = clean.substring(dot + 1).trim()
    if (ext.isBlank() || ext.length > 12 || ext.any { !it.isLetterOrDigit() }) return null
    return ext.uppercase()
}

@Composable
private fun AssetTypeTag(name: String) {
    val ext = assetExt(name)
    if (ext != null) {
        val color = when (ext) {
            "APK", "AAB" -> GH.green
            "ZIP", "7Z", "RAR", "TAR", "GZ", "XZ", "ZST", "TAR.GZ", "TAR.XZ", "TAR.BZ2", "TAR.ZST" -> GH.yellow
            "IMG", "ISO", "DMG", "BIN" -> GH.purple
            "PDF" -> GH.red
            "TXT", "MD", "JSON", "XML", "YAML", "YML", "CSV", "LOG" -> GH.blue
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        TinyTag(ext, color)
    }
}

@Composable
fun ReleasesTab(nav: NavController, owner: String, name: String, repo: Repo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val cacheKey = "$owner/$name"
    var list by remember { mutableStateOf(UiCache.repoReleases[cacheKey] ?: emptyList()) }
    var loading by remember { mutableStateOf(list.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(-1L) }
    val dlVer by Downloader.historyVersion.collectAsState()
    val downloadedNames = remember(dlVer) {
        Downloader.history(ctx).filter { it.repo == cacheKey }.map { it.name }.toSet()
    }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var uploadTargetId by rememberSaveable { mutableStateOf(-1L) }
    var editTarget by remember { mutableStateOf<Release?>(null) }
    var assetUploading by remember { mutableStateOf(false) }
    var assetName by remember { mutableStateOf("") }
    var assetSent by remember { mutableStateOf(0L) }
    var assetTotal by remember { mutableStateOf(0L) }
    var assetPct by remember { mutableStateOf(0) }
    var assetBarVisible by remember { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load(p: Int, replace: Boolean, silent: Boolean = false) {
        if (p == 1 && !silent) loading = true
        try {
            val pg = Api.releases(owner, name, p)
            list = if (replace) pg.items else list + pg.items
            if (replace) UiCache.repoReleases[cacheKey] = list
            hasNext = pg.hasNext; page = p; error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { if (!silent) error = e.message }
        loading = false
    }
    LaunchedEffect(Unit) {
        val cached = UiCache.repoReleases[cacheKey]
        if (cached != null && cached.isNotEmpty()) { list = cached; loading = false; load(1, true, silent = true) }
        else load(1, true)
    }
    // 从通知点进来：自动展开对应的发行版，直接看到附件/下载按钮
    LaunchedEffect(list) {
        val pid = UiCache.pendingRelease
        val ptag = UiCache.pendingReleaseTag
        if (pid != null && pid > 0) {
            if (list.any { it.id == pid }) { expanded = pid; UiCache.pendingRelease = null }
        } else if (!ptag.isNullOrBlank()) {
            val hit = list.firstOrNull { it.tag_name == ptag }
            if (hit != null) { expanded = hit.id; UiCache.pendingReleaseTag = null }
        }
    }

    // 发行版/APK 实时刷新：别人刚上传也能在几秒内出现
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_500L)
            if (page == 1 && !loading) runCatching { load(1, true, silent = true) }
        }
    }

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
                assetUploading = true; assetBarVisible = true; assetName = ""; assetSent = 0L; assetTotal = 0L; assetPct = 0
                val f = File(ctx.cacheDir, "upload.tmp")
                var n = uri.lastPathSegment?.substringAfterLast('/') ?: "asset.bin"
                runCatching {
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { n = it }
                    }
                }
                assetName = n
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                assetTotal = f.length()
                snackbar.showSnackbar("正在上传 $n …")
                val ret = Api.uploadReleaseAsset(owner, name, rel.id, f, n) { sent, total ->
                    assetSent = sent; assetTotal = total
                    assetPct = if (total > 0) ((sent * 100) / total).toInt() else 0
                }
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
            } finally {
                assetUploading = false
                assetBarVisible = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("发行版（${list.size}）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text("新建发行版", fontSize = 12.sp)
                }
            }
            if (assetUploading && assetBarVisible) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("正在上传附件 ${assetName.ifBlank { "…" }}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            LinearProgressIndicator(progress = { assetPct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
                            Text(if (assetTotal > 0)
                                "$assetPct% · ${humanSize(assetSent)} / ${humanSize(assetTotal)}"
                            else "正在准备…", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { assetBarVisible = false; Toast.makeText(ctx, "进度已隐藏，上传仍在继续", Toast.LENGTH_SHORT).show() }) {
                            Text("隐藏", fontSize = 11.sp)
                        }
                    }
                }
            }
            when {
                loading && list.isEmpty() -> LoadingBox()
                error != null && list.isEmpty() -> ErrorBox(error) { scope.launch { load(1, true) } }
                list.isEmpty() -> EmptyBox("还没有发行版")
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
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
                                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton({ copy(rel.html_url ?: "", "已复制发行版链接") }) {
                                    Icon(Icons.Filled.ContentCopy, "复制链接", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (expanded == rel.id) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                            if (expanded == rel.id) {
                                if (!rel.body.isNullOrBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    // 发行版说明也需要翻译；用紧凑控件，翻译/目标语言不挤占正文排版。
                                    val itemIndex = list.indexOfFirst { it.id == rel.id }.coerceAtLeast(0)
                                    TranslateBlock(
                                        text = rel.body!!.take(20_000),
                                        style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                                        auto = Cfg.translateAuto,
                                        compact = true,
                                        onTranslated = {
                                            // 翻译改变正文高度时 LazyColumn 的像素锚点会漂移；
                                            // 翻完把这条发行版顶部拉回来，用户不用再向上找译文。
                                            scope.launch { runCatching { listState.animateScrollToItem(itemIndex) } }
                                        }
                                    )
                                } else {
                                    Spacer(Modifier.height(6.dp))
                                    Text("这个发行版没有填写说明", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                // 下载进度统一只显示在仓库头部 ActiveDownloadStrip。
                                // 这里不再复制一份进度/打开/取消，避免下方列表被撑高、页面来回跳动。
                                rel.assets?.forEach { a ->
                                    val ext = assetExt(a.name)
                                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                        // 文件名完整换行显示，不再为了给右侧按钮留位置而省略。
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                            if (ext != null) {
                                                AssetTypeTag(a.name)
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(a.name.ifBlank { "未命名文件" }, fontSize = 12.sp, lineHeight = 16.sp,
                                                modifier = Modifier.weight(1f))
                                        }
                                        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("${humanSize(a.size)} · GitHub 累计下载 ${a.download_count} 次",
                                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            if (a.name in downloadedNames) {
                                                TinyTag("已下载", GH.green)
                                                Spacer(Modifier.width(2.dp))
                                            }
                                            IconButton({ copy(a.browser_download_url ?: "", "已复制下载链接") }) {
                                                Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            IconButton(onClick = {
                                                val url = a.browser_download_url ?: return@IconButton
                                                Toast.makeText(ctx, "开始下载 ${a.name}…", Toast.LENGTH_SHORT).show()
                                                Downloader.startDownload(ctx, url, a.name, source = "发行版附件",
                                                    repo = "$owner/$name", release = rel.tag_name,
                                                    expectedSize = a.size,
                                                    onDone = { /* 完成提示统一由白色系统通知 + 行内 100% 展示，不再弹黑色 Toast */ }, 
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
                                    }
                                    Divider1()
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(enabled = !assetUploading, onClick = { uploadTargetId = rel.id; picker.launch("*/*") }) {
                                        Icon(Icons.Filled.Upload, null, Modifier.size(15.dp)); Text("上传附件", fontSize = 11.sp)
                                    }
                                    OutlinedButton(onClick = { copy(rel.html_url ?: "", "已复制发行版链接") }) {
                                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(15.dp)); Text("复制链接", fontSize = 11.sp)
                                    }
                                    OutlinedButton(onClick = { openUrl(ctx, rel.html_url) }) { Text("浏览器", fontSize = 11.sp) }
                                    if (repo.permissions?.push == true) {
                                        OutlinedButton(onClick = { editTarget = rel }) { Text("编辑发行版", fontSize = 11.sp) }
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
                            Cfg.touchRepo(repo.full_name)
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

    editTarget?.let { rel ->
        var ename by remember(rel.id) { mutableStateOf(rel.name ?: rel.tag_name) }
        var ebody by remember(rel.id) { mutableStateOf(rel.body ?: "") }
        var edraft by remember(rel.id) { mutableStateOf(rel.draft) }
        var epre by remember(rel.id) { mutableStateOf(rel.prerelease) }
        var ebusy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!ebusy) editTarget = null },
            title = { Text("编辑发行版", fontSize = 15.sp) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(ename, { ename = it }, label = { Text("标题") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(ebody, { ebody = it }, label = { Text("说明（Markdown）") }, minLines = 5)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(edraft, { edraft = it }); Text("草稿", fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Checkbox(epre, { epre = it }); Text("预发行", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !ebusy, onClick = {
                    ebusy = true
                    scope.launch {
                        try {
                            val updated = Api.patchRelease(owner, name, rel.id, mapOf(
                                "name" to ename, "body" to ebody, "draft" to edraft, "prerelease" to epre
                            ))
                            list = list.map { if (it.id == rel.id) updated else it }
                            UiCache.repoReleases[cacheKey] = list
                            editTarget = null
                            Toast.makeText(ctx, "发行版已更新", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) { snackbar.showSnackbar("保存失败：${e.message}") }
                        finally { ebusy = false }
                    }
                }) { Text(if (ebusy) "保存中…" else "保存") }
            },
            dismissButton = { TextButton({ if (!ebusy) editTarget = null }) { Text("取消") } }
        )
    }

}
