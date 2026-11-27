package com.luogen.ghpro.ui

import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.ContentItem
import com.luogen.ghpro.data.DirListing
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.download.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
fun CodeTab(nav: NavController, owner: String, name: String, repo: Repo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var branch by remember { mutableStateOf(repo.default_branch ?: "main") }
    var branches by remember { mutableStateOf(listOf<String>()) }
    var branchMenu by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf(UiCache.codePath["$owner/$name"] ?: "") }
    var listing by remember { mutableStateOf(UiCache.codeListing["$owner/$name|${repo.default_branch ?: "main"}|${UiCache.codePath["$owner/$name"] ?: ""}"]) }
    var loading by remember { mutableStateOf(listing == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scrollReady by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    fun codeScrollKey() = "$owner/$name|$branch|$path"
    fun saveCodeScroll() {
        UiCache.codeScroll[codeScrollKey()] = listState.firstVisibleItemIndex
        UiCache.codeScrollOffset[codeScrollKey()] = listState.firstVisibleItemScrollOffset
    }
    val snackbar = remember { SnackbarHostState() }
    var pendingUpload by remember { mutableStateOf<Uri?>(null) }
    var uploadTarget by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var uploadPercent by remember { mutableStateOf(0) }
    var uploadSent by remember { mutableStateOf(0L) }
    var uploadTotalBytes by remember { mutableStateOf(0L) }
    var uploadFileBytes by remember { mutableStateOf(0L) }
    var showUploadBar by remember { mutableStateOf(true) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var confirmBulk by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val deleteKey = "$owner/$name|$branch"
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            var name = "file.bin"
            runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
                }
            }
            uploadTarget = (if (path.isBlank()) name else "$path/$name")
            pendingUpload = uri
        }
    }

    suspend fun load(silent: Boolean = false) {
        // 删除提交进行中：自动刷新先停，避免旧目录把刚删掉的文件又“闪现”回来
        if (deleteKey in UiCache.deletingCode) { loading = false; return }
        if (!silent) loading = true
        error = null
        try {
            listing = Api.contents(owner, name, path, branch)
            listing?.let { UiCache.codeListing["$owner/$name|$branch|$path"] = it }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val api = e as? com.luogen.ghpro.data.ApiException
            if (!silent && api?.code == 404 && path.isBlank()) {
                val body = api.body.orEmpty()
                val looksEmpty = body.contains("empty", true) || body.contains("No commit found", true)
                if (looksEmpty) {
                    // GitHub 空仓库的 contents 接口返回 404 + “This repository is empty.”，这不是权限错误。
                    listing = DirListing(false, emptyList(), null)
                    error = null
                    loading = false
                    return
                }
                // 分支名可能不是 main/dev：用仓库真实默认分支再试一次，避免把“分支不存在”误报成无权限。
                val info = runCatching { Api.repo(owner, name) }.getOrNull()
                val realBranch = info?.default_branch?.takeIf { it.isNotBlank() }
                if (realBranch != null && realBranch != branch) {
                    branch = realBranch; error = null; loading = false
                    return
                }
                // 仓库能读到但 commits 为空（GitHub 对空仓库返回 409）就显示空目录，不弹红字。
                val commits = runCatching { Api.commits(owner, name, realBranch ?: branch, 1) }
                val commitError = commits.exceptionOrNull()?.message.orEmpty()
                val emptyRepo = (commits.isSuccess && commits.getOrNull()?.items.isNullOrEmpty()) ||
                    commitError.contains("409") || commitError.contains("empty", true)
                if (emptyRepo) {
                    listing = DirListing(false, emptyList(), null)
                    error = null
                } else if (info != null) {
                    error = "这个分支暂时没有可读取的代码；如果仓库是空的，会显示空目录。点重试再拉一次"
                } else {
                    error = e.message ?: "内容不存在或无权限"
                }
            } else if (!silent) {
                error = e.message
            }
        }
        loading = false
    }

    androidx.activity.compose.BackHandler(enabled = selecting || path.isNotEmpty()) {
        if (selecting) { selecting = false; selected.clear() }
        else { saveCodeScroll(); path = path.substringBeforeLast('/', "") }
    }
    LaunchedEffect(branch, path) {
        UiCache.codePath["$owner/$name"] = path
        scrollReady = false
        val key = codeScrollKey()
        val idx = UiCache.codeScroll[key] ?: 0
        val off = UiCache.codeScrollOffset[key] ?: 0
        // 先恢复一次（列表已有缓存时立刻生效）；网络刷新完再恢复一次（首次进入文件夹时生效）。
        if (idx > 0 || off > 0) runCatching { listState.scrollToItem(idx, off) }
        val cached = UiCache.codeListing["$owner/$name|$branch|$path"]
        if (cached != null) {
            listing = cached; loading = false
            scrollReady = true
            load(silent = true)
        } else {
            load()
            if (idx > 0 || off > 0) runCatching { listState.scrollToItem(idx, off) }
            scrollReady = true
        }
    }
    // 记住每个目录的滚动位置：点文件/文件夹再返回，回到原来那一行，不从顶部重来。
    LaunchedEffect(branch, path, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (i, o) ->
                if (!scrollReady) return@collect
                val key = codeScrollKey()
                UiCache.codeScroll[key] = i
                UiCache.codeScrollOffset[key] = o
            }
    }
    // 代码页实时刷新：别人新建/删除/修改文件，约 1.5 秒内自动出现；
    // 静默刷新，不闪 loading、不重置滚动位置。
    LaunchedEffect(branch, path) {
        while (isActive) {
            delay(1_500L)
            if (!selecting && !uploading && !deleting && deleteKey !in UiCache.deletingCode) runCatching { load(silent = true) }
        }
    }
    LaunchedEffect(Unit) {
        branches = runCatching { Api.branches(owner, name).map { it.name } }.getOrDefault(emptyList())
            .ifEmpty { listOf(repo.default_branch ?: "main") }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { branchMenu = true }) {
                    Text(branch, fontSize = 12.sp, maxLines = 1)
                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(branchMenu, { branchMenu = false }) {
                    branches.forEach { b ->
                        DropdownMenuItem(text = { Text(b, fontSize = 12.sp) }, onClick = { branch = b; path = ""; branchMenu = false })
                    }
                }
            }
            Text("/" + path.ifBlank { "" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (path.isNotEmpty()) {
                TextButton(onClick = { path = path.substringBeforeLast('/', "") }) { Text("上级", fontSize = 11.sp) }
            }
            IconButton({ selecting = !selecting; selected.clear() }) {
                Icon(Icons.Filled.CheckCircle, "多选", tint = if (selecting) GH.blue else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ uploadPicker.launch("*/*") }, enabled = !uploading) {
                Icon(Icons.Filled.Upload, "上传文件", tint = GH.blue)
            }
            IconButton(onClick = {
                nav.navigate("file?owner=$owner&repo=$name&branch=${Uri.encode(branch)}&path=${Uri.encode(if (path.isBlank()) "" else "$path/")}&isNew=1")
            }) { Icon(Icons.Filled.Add, "新建文件", tint = GH.green) }
        }

        if (uploading && showUploadBar) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("正在上传 ${uploadTarget.ifBlank { "文件" }}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { uploadPercent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
                        Text(if (uploadTotalBytes > 0)
                            "$uploadPercent% · 已发送 ${humanSize(uploadSent)} / ${humanSize(uploadTotalBytes)}"
                        else "正在准备 ${humanSize(uploadFileBytes)}…",
                            fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                    TextButton(onClick = { showUploadBar = false; Toast.makeText(ctx, "进度已隐藏，上传仍在继续", Toast.LENGTH_SHORT).show() }) {
                        Text("隐藏", fontSize = 11.sp)
                    }
                }
            }
        }

        if (deleting) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在从 GitHub 真实删除，请稍候…", fontSize = 11.sp, color = GH.red)
            }
        }

        if (selecting) {
            val all = listing?.items ?: emptyList()
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${selected.size} 项", fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (selected.size == all.size) selected.clear() else { selected.clear(); selected.addAll(all.map { it.path }) }
                }) { Text(if (selected.size == all.size) "取消全选" else "全选", fontSize = 11.sp) }
                TextButton(enabled = selected.isNotEmpty(), onClick = { confirmBulk = true }) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(15.dp), tint = GH.red)
                    Text("删除", fontSize = 11.sp, color = GH.red)
                }
                TextButton({ selecting = false; selected.clear() }) { Text("退出", fontSize = 11.sp) }
            }
        }
        when {
            loading -> LoadingBox()
            error != null -> ErrorBox(error) { scope.launch { load() } }
            else -> {
                val l = listing
                if (l == null || l.items.isEmpty()) EmptyBox("空目录")
                else LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(l.items.sortedWith(compareBy({ it.type != "dir" }, { it.name })), key = { it.sha + it.path }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (selecting) {
                                    if (selected.contains(item.path)) selected.remove(item.path) else selected.add(item.path)
                                } else if (item.type == "dir") { saveCodeScroll(); path = item.path }
                                else { saveCodeScroll(); nav.navigate("file?owner=$owner&repo=$name&branch=${Uri.encode(branch)}&path=${Uri.encode(item.path)}&isNew=0") }
                            }.padding(horizontal = 10.dp, vertical = if (selecting) 6.dp else 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selecting) {
                                Checkbox(checked = selected.contains(item.path), onCheckedChange = { checked ->
                                    if (checked) selected.add(item.path) else selected.remove(item.path)
                                }, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Icon(
                                if (item.type == "dir") Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                null, Modifier.size(18.dp),
                                tint = if (item.type == "dir") GH.blue else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(item.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (item.type != "dir") Text(humanSize(item.size), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Divider1()
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                Toast.makeText(ctx, "开始下载整包源码…", Toast.LENGTH_SHORT).show()
                                Downloader.startDownload(ctx, "https://github.com/$owner/$name/archive/refs/heads/$branch.zip",
                                    "$name-$branch.zip", source = "源码 ZIP", repo = "$owner/$name",
                                    onDone = { },
                                    onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
                            }) { Icon(Icons.Filled.Download, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("整包下载", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }

    val pu = pendingUpload
    if (pu != null) {
        AlertDialog(
            onDismissRequest = { if (!uploading) pendingUpload = null },
            title = { Text("上传文件", fontSize = 15.sp) },
            text = {
                Column {
                    Text("文件会直接提交到当前分支 $branch。路径可手动修改，留空则默认放在根目录。\n单文件最大 2GB：30MB 以内走普通提交，超过 30MB 自动走 Git LFS（受账号 LFS 配额限制）。", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(uploadTarget, { uploadTarget = it }, label = { Text("完整路径（含文件名）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = uploadTarget.isNotBlank() && !uploading, onClick = {
                    val target = uploadTarget.trim().trim('/')
                    if (target.isBlank()) return@TextButton
                    val chosen = pu ?: return@TextButton
                    // 立即关掉选择弹窗，进度条改为页面顶部的非模态小条；上传继续，不阻塞浏览。
                    pendingUpload = null
                    showUploadBar = true
                    Bus.actions.launch {
                        uploading = true
                        showUploadBar = true
                        uploadPercent = 0; uploadSent = 0L; uploadTotalBytes = 0L; uploadFileBytes = 0L
                        try {
                            Toast.makeText(ctx, "开始上传…", Toast.LENGTH_SHORT).show()
                            val tmp = java.io.File(ctx.cacheDir, "ghpro_upload_source.bin")
                            withContext(Dispatchers.IO) {
                                ctx.contentResolver.openInputStream(chosen)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                                    ?: throw java.io.IOException("读不到这个文件")
                            }
                            val size = tmp.length()
                            uploadFileBytes = size
                            if (size > 2L * 1024 * 1024 * 1024) throw java.io.IOException("单个文件最大 2GB")
                            if (size > 30L * 1024 * 1024) {
                                Toast.makeText(ctx, "大文件走 Git LFS（${size / 1024 / 1024} MB），请稍等…", Toast.LENGTH_LONG).show()
                                val oid = Api.lfsUpload(owner, name, tmp) { sent, total ->
                                    uploadSent = sent; uploadTotalBytes = total
                                    uploadPercent = if (total > 0) ((sent * 100) / total).toInt() else 0
                                }
                                Api.lfsCommit(owner, name, target, branch, oid, size, "upload ${target.substringAfterLast('/')} via LFS")
                            } else {
                                val bytes = withContext(Dispatchers.IO) { tmp.readBytes() }
                                val sha = runCatching { Api.fileItem(owner, name, target, branch).sha }.getOrNull()
                                Api.putFile(owner, name, target, "upload ${target.substringAfterLast('/')}",
                                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), sha, branch) { sent, total ->
                                    uploadSent = sent; uploadTotalBytes = total
                                    uploadPercent = if (total > 0) ((sent * 100) / total).toInt() else 0
                                }
                            }
                            tmp.delete()
                            UiCache.codeListing.keys.filter { it.startsWith("$owner/$name|") }.forEach { UiCache.codeListing.remove(it) }
                            UiCache.fileContent.remove("$owner/$name|$branch|$target")
                            Cfg.touchRepo("$owner/$name")
                            uploadPercent = 100
                            showUploadBar = false
                            Toast.makeText(ctx, "已上传 $target", Toast.LENGTH_LONG).show()
                            pendingUpload = null
                            uploadTarget = ""
                            load(true)
                        } catch (e: Exception) {
                            java.io.File(ctx.cacheDir, "ghpro_upload_source.bin").delete()
                            Toast.makeText(ctx, "上传失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                        uploading = false
                        showUploadBar = false
                        uploadSent = 0L; uploadTotalBytes = 0L; uploadPercent = 0
                    }
                }) { Text(if (uploading) "上传中 $uploadPercent%" else "上传", fontSize = 13.sp) }
            },
            dismissButton = { TextButton({ if (!uploading) pendingUpload = null }) { Text("取消") } }
        )
    }

    if (confirmBulk) {
        val targets = listing?.items?.filter { selected.contains(it.path) } ?: emptyList()
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text("删除 ${targets.size} 项", fontSize = 15.sp) },
            text = { Text("会从 GitHub 真实删除选中的文件和文件夹；选中文件夹会递归删除里面所有文件。删除后立即生效，不可恢复。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulk = false
                    val snapshot = listing
                    val paths = targets.map { it.path }
                    if (paths.isEmpty()) return@TextButton
                    deleting = true
                    UiCache.deletingCode.add(deleteKey)
                    // 不再先假装删掉：列表保持原样并显示“正在删除”，GitHub 真实提交成功后才消失；
                    // 失败就原地不动，避免用户看到“空目录又回滚”的假象。
                    selecting = false
                    selected.clear()
                    Bus.actions.launch {
                        try {
                            val deleted = Api.deletePaths(owner, name, paths, branch)
                            if (deleted <= 0) throw java.io.IOException("没有找到要删除的文件，可能已经被删除")
                            Cfg.touchRepo("$owner/$name")
                            UiCache.codeListing.keys.filter { it.startsWith("$owner/$name|") }.forEach { UiCache.codeListing.remove(it) }
                            listing = snapshot?.copy(items = snapshot.items.filter { it.path !in paths })
                            deleting = false
                            Toast.makeText(ctx, "已真实删除 $deleted 个文件", Toast.LENGTH_LONG).show()
                            // 服务器提交成功，再静默拉一次校准；失败也不会把文件“变回来”。
                            runCatching { load(silent = true) }
                        } catch (e: Exception) {
                            listing = snapshot
                            deleting = false
                            Toast.makeText(ctx, "删除失败：${e.message}（文件仍在）", Toast.LENGTH_LONG).show()
                        } finally {
                            UiCache.deletingCode.remove(deleteKey)
                        }
                    }
                }) { Text("确认删除", color = GH.red) }
            },
            dismissButton = { TextButton({ confirmBulk = false }) { Text("取消") } }
        )
    }

}

private data class LfsRef(val oid: String, val size: Long)

private fun parseLfsPointer(text: String): LfsRef? {
    if (!text.startsWith("version https://git-lfs.github.com/spec/v1")) return null
    val oid = Regex("(?m)^oid sha256:([0-9a-f]{64})$").find(text)?.groupValues?.getOrNull(1) ?: return null
    val size = Regex("(?m)^size (\\d+)$").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    return LfsRef(oid, size)
}

private fun looksBinary(bytes: ByteArray): Boolean {
    val n = minOf(bytes.size, 4096)
    if (n == 0) return false
    var bad = 0
    for (i in 0 until n) {
        val b = bytes[i].toInt() and 0xFF
        if (b == 0 || (b < 9) || (b in 14..31)) bad++
    }
    return bad * 100 > n * 5
}

private fun safePreview(text: String, limit: Int): String {
    if (text.length <= limit) return text
    var end = limit
    // 不在 UTF-16 代理对中间切断，避免把 emoji 截成乱码
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end -= 1
    return text.substring(0, end)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileScreen(nav: NavController, owner: String, name: String, branch: String, path: String, isNew: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileKey = "$owner/$name|$branch|$path"
    var content by remember { mutableStateOf(UiCache.fileContent[fileKey] ?: "") }
    var original by remember { mutableStateOf(UiCache.fileContent[fileKey] ?: "") }
    var sha by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf(path) }
    var editing by remember { mutableStateOf(isNew) }
    var loading by remember { mutableStateOf(!isNew && UiCache.fileContent[fileKey] == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fatal404 by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var deletingFile by remember { mutableStateOf(false) }
    var isFile by remember { mutableStateOf(true) }
    var lfsRef by remember { mutableStateOf<LfsRef?>(null) }
    var knownSize by remember { mutableStateOf(0L) }
    var previewKind by remember { mutableStateOf("text") }
    var imageUrl by remember { mutableStateOf<String?>(null) }
    var fontFamily by remember { mutableStateOf<FontFamily?>(null) }
    val snackbar = remember { SnackbarHostState() }
    // 文件内搜索：只搜当前文件的内容，精确子串匹配；查看/编辑共用同一份命中列表。
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchIndex by remember { mutableStateOf(0) }
    var editorValue by remember { mutableStateOf(TextFieldValue(content)) }
    var codeWrap by remember { mutableStateOf(Cfg.codeWrap) }
    val wrapState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val editorFocus = remember { FocusRequester() }
    val codeScroll = rememberScrollState()
    val codeHScroll = rememberScrollState()
    val density = LocalDensity.current
    val lineHeightPx = with(density) { 17.sp.toPx() }
    val previewLineCount = remember(content) {
        safePreview(content.replace("\r\n", "\n").replace('\r', '\n'), 200_000)
            .trimEnd('\n').ifEmpty { " " }.count { it == '\n' } + 1
    }
    val searchHits = remember(content, searchQuery) {
        if (searchQuery.isBlank()) emptyList() else searchMatches(content, searchQuery)
    }
    fun jumpSearch(delta: Int) {
        if (searchQuery.isBlank()) { Toast.makeText(ctx, "先输入要搜索的内容", Toast.LENGTH_SHORT).show(); return }
        if (searchHits.isEmpty()) { Toast.makeText(ctx, "没有找到“$searchQuery”", Toast.LENGTH_SHORT).show(); return }
        val next = ((searchIndex + delta) % searchHits.size + searchHits.size) % searchHits.size
        searchIndex = next
        val hit = searchHits[next]
        if (editing) {
            editorValue = editorValue.copy(selection = TextRange(hit, hit + searchQuery.length))
            runCatching { editorFocus.requestFocus() }
        } else {
            val line = content.take(hit).count { it == '\n' }
            if (codeWrap) scope.launch { wrapState.animateScrollToItem(line.coerceIn(0, (previewLineCount - 1).coerceAtLeast(0))) }
            else scope.launch { codeScroll.animateScrollTo((line * lineHeightPx).toInt()) }
        }
    }
    LaunchedEffect(content) {
        if (!editing && editorValue.text != content) editorValue = TextFieldValue(content)
    }

    fun load(silent: Boolean = false) {
        if (isNew) { loading = false; return }
        scope.launch {
            if (!silent) { loading = true; error = null; fatal404 = false; previewKind = "text"; imageUrl = null; fontFamily = null }
            try {
                val l = Api.contents(owner, name, path, branch)
                val f = l.file
                if (f == null || l.items.size > 1) { isFile = false; error = "这是一个目录，请返回列表" }
                else {
                    sha = f.sha
                    fileName = f.name
                    knownSize = f.size
                    val ext = f.name.substringAfterLast('.', "").lowercase()
                    // 图片直接走 raw 地址给 Coil，不再把二进制当文本解码；
                    // 字体先下载到缓存再 Typeface 预览。两者都只请求 contents 一次。
                    if (ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") && f.size in 1..20_000_000) {
                        val raw = f.download_url ?: "https://raw.githubusercontent.com/$owner/$name/${Uri.encode(branch)}/${Api.encPath(path)}"
                        imageUrl = raw
                        previewKind = "image"
                        isFile = true
                        fontFamily = null
                        error = null
                        lfsRef = null
                        loading = false
                        return@launch
                    }
                    if (ext in setOf("ttf", "otf") && f.size in 1..20_000_000) {
                        val bytes = Api.fileRaw(owner, name, path, branch)
                        val ptr = parseLfsPointer(String(bytes, Charsets.UTF_8))
                        if (ptr != null) {
                            lfsRef = ptr
                            error = "这是 Git LFS 大文件（${ptr.size / 1024 / 1024} MB），不能当字体预览，请点“直接下载文件”"
                            loading = false
                            return@launch
                        }
                        val cache = java.io.File(ctx.cacheDir, "font-preview-${f.sha.take(12)}.${ext}")
                        if (!cache.exists() || cache.length() != f.size.toLong()) cache.writeBytes(bytes)
                        fontFamily = FontFamily(Typeface.createFromFile(cache))
                        imageUrl = null
                        previewKind = "font"
                        isFile = true
                        error = null
                        lfsRef = null
                        loading = false
                        return@launch
                    }
                    previewKind = "text"
                    imageUrl = null
                    fontFamily = null
                    isFile = true
                    val text = if (f.size <= 20_000_000) {
                        // contents 接口在 1MB 以内已经带回 base64 内容，直接用，
                        // 不再第二次请求同一个文件；大文件才走 raw。
                        val bytes = if (f.content != null && f.encoding == "base64" && f.size <= 1_000_000) {
                            android.util.Base64.decode(f.content.replace("\n", ""), android.util.Base64.DEFAULT)
                        } else if (f.size <= 1_000_000) {
                            Api.fileContent(owner, name, path, branch).toByteArray(Charsets.UTF_8)
                        } else Api.fileRaw(owner, name, path, branch)
                        if (looksBinary(bytes)) throw java.io.IOException("这是二进制文件（${f.size / 1024} KB），App 不能当文本显示；可以点右上角下载，下载完成后用系统打开")
                        String(bytes, Charsets.UTF_8)
                    } else {
                        throw java.io.IOException("文件较大（${f.size / 1024 / 1024} MB），不自动加载，请点“直接下载文件”")
                    }
                    val ptr = parseLfsPointer(text)
                    if (ptr != null) {
                        lfsRef = ptr
                        error = "这是 Git LFS 大文件（${ptr.size / 1024 / 1024} MB），不能当源码显示，请点“直接下载文件”"
                        loading = false
                        return@launch
                    }
                    lfsRef = null
                    error = null
                    isFile = true
                    content = text
                    original = text
                    UiCache.fileContent[fileKey] = text
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (e is com.luogen.ghpro.data.ApiException && e.code == 404) {
                    // 当前分支没有这个文件时，自动到默认分支再找一次：很多 404 是
                    // 从旧分支的列表点进来、文件后来只保留在默认分支上。
                    val def = runCatching { Api.repo(owner, name).default_branch }.getOrNull()
                    if (def != null && def != branch &&
                        runCatching { Api.contents(owner, name, path, def) }.getOrNull()?.file != null) {
                        Toast.makeText(ctx, "当前分支没有这个文件，已切到默认分支 $def", Toast.LENGTH_SHORT).show()
                        fatal404 = true
                        nav.navigate("file?owner=${Uri.encode(owner)}&repo=${Uri.encode(name)}&branch=${Uri.encode(def)}&path=${Uri.encode(path)}&isNew=0")
                        loading = false
                        return@launch
                    }
                    // 404 不再只丢一句“内容不存在或无权限”：把路径/分支和可做的操作说清楚，
                    // 同时停止每 3 秒自动重试，避免红色报错反复闪。
                    fatal404 = true
                    error = "文件不存在或没有权限\n路径：$path\n分支：$branch\n\n" +
                        "可能是文件已删除或改名、当前分支里没有这个文件，" +
                        "也可能是文件名含 +、% 等特殊字符。\n" +
                        "可以返回上一级刷新文件列表确认；或点“直接下载文件”试原始地址。"
                } else error = e.message
            }
            loading = false
        }
    }

    LaunchedEffect(path) {
        previewKind = "text"; imageUrl = null; fontFamily = null
        if (!isNew) {
            val cached = UiCache.fileContent[fileKey]
            if (cached != null) { content = cached; original = cached; loading = false; runCatching { load(true) } } else load()
        }
    }
    LaunchedEffect(path) {
        if (!isNew) while (isActive) {
            delay(2_000L)
            if (previewKind == "text" && !editing && !loading && !fatal404 && "$owner/$name|$branch" !in UiCache.deletingCode) runCatching { load(true) }
        }
    }

    fun downloadFile(fileName: String) {
        val ref = lfsRef
        if (ref != null) {
            Toast.makeText(ctx, "正在解析 Git LFS 大文件地址…", Toast.LENGTH_SHORT).show()
            scope.launch {
                try {
                    val href = Api.lfsDownloadHref(owner, name, ref.oid, ref.size)
                    Downloader.startDownload(ctx, href, fileName, source = "仓库文件", repo = "$owner/$name",
                        expectedSize = ref.size,
                        onDone = { },
                        onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
                } catch (e: Exception) { Toast.makeText(ctx, "下载失败：${e.message}", Toast.LENGTH_LONG).show() }
            }
            return
        }
        Toast.makeText(ctx, "开始下载 $fileName…", Toast.LENGTH_SHORT).show()
        Downloader.startDownload(ctx, "https://raw.githubusercontent.com/$owner/$name/$branch/${Api.encPath(path)}", fileName,
            source = "仓库文件", repo = "$owner/$name", expectedSize = knownSize,
            onDone = { },
            onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(fileName.substringAfterLast('/'), fontSize = 14.sp, maxLines = 1)
                        Text("$owner/$name · $branch", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    if (isFile && !loading) {
                        if (editing) {
                            IconButton({
                                if (fileName.isBlank() || fileName.endsWith("/")) { scope.launch { snackbar.showSnackbar("请输入完整文件名（当前只填了文件夹路径）") }; return@IconButton }
                                scope.launch {
                                    try {
                                        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
                                        Api.putFile(owner, name, fileName,
                                            message.ifBlank { if (isNew) "create $fileName" else "update $fileName" },
                                            b64, sha, branch)
                                        snackbar.showSnackbar("已提交到 GitHub")
                                        Cfg.touchRepo("$owner/$name")
                                        editing = false; original = content
                                        load()
                                    } catch (e: Exception) { snackbar.showSnackbar("保存失败：${e.message}") }
                                }
                            }) { Icon(Icons.Filled.Save, "保存", tint = GH.green) }
                        } else {
                            if (previewKind == "text") IconButton({ editing = true }) { Icon(Icons.Filled.Edit, "编辑") }
                        }
                        if (previewKind == "text") IconButton({
                            searchOpen = !searchOpen
                            if (!searchOpen) { searchQuery = ""; searchIndex = 0 }
                        }) { Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, "文件内搜索") }
                        IconButton({ downloadFile(path.substringAfterLast('/')) }) { Icon(Icons.Filled.Download, "下载") }
                        if (!isNew) {
                            IconButton(enabled = !deletingFile, onClick = { confirmDelete = true }) {
                                if (deletingFile) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Filled.Delete, "删除", tint = GH.red)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (previewKind == "text" && searchOpen && !loading && error == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it; searchIndex = 0 },
                        modifier = Modifier.weight(1f).height(48.dp).focusRequester(searchFocus),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                        placeholder = { Text("搜索此文件内容…", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(16.dp)) }
                    )
                    Text(if (searchQuery.isBlank()) "输入关键词" else "${searchHits.size} 个匹配",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp))
                    IconButton({ jumpSearch(-1) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.KeyboardArrowUp, "上一个", Modifier.size(18.dp))
                    }
                    IconButton({ jumpSearch(1) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.KeyboardArrowDown, "下一个", Modifier.size(18.dp))
                    }
                }
            }
            when {
                loading -> LoadingBox()
                error != null -> Column {
                    ErrorBox(error)
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { load() }) { Text("重新加载") }
                        OutlinedButton(onClick = { downloadFile(path.substringAfterLast('/')) }) { Text("直接下载文件") }
                    }
                }
                else -> {
                    if (previewKind == "image" && imageUrl != null) {
                        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = ImageRequest.Builder(ctx).data(imageUrl)
                                    .apply { Cfg.token?.let { addHeader("Authorization", "Bearer $it") } }
                                    .crossfade(true).build(),
                                contentDescription = fileName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else if (previewKind == "font" && fontFamily != null) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                            Text("字体预览", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("Aa 字体预览 0123456789", fontFamily = fontFamily, fontSize = 28.sp, lineHeight = 38.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(fileName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else if (editing) {
                        OutlinedTextField(
                            value = fileName, onValueChange = { fileName = it },
                            label = { Text("文件路径（如 src/Main.kt）") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = message, onValueChange = { message = it },
                            label = { Text("提交说明（Commit message）") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = editorValue,
                            onValueChange = { editorValue = it; content = it.text },
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(10.dp).focusRequester(editorFocus),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                            visualTransformation = SyntaxTransformation(fileName, if (searchOpen) searchQuery else ""),
                            placeholder = { Text("文件内容…", fontSize = 12.sp) }
                        )
                    } else {
                        // 自动换行是默认开启的，并持久化；换行模式逐行渲染，行号与代码首行严格同行。
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (codeWrap) "自动换行：开" else "自动换行：关", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Switch(checked = codeWrap, onCheckedChange = { codeWrap = it; Cfg.codeWrap = it },
                                modifier = Modifier.height(30.dp))
                        }
                        val display = safePreview(content.replace("\r\n", "\n").replace('\r', '\n'), 200_000)
                            .trimEnd('\n')
                        val shown = display.ifEmpty { " " }
                        val lines = shown.split('\n')
                        val codeStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        if (content.length > 200_000) {
                            Text("文件较大，下面只预览前 20 万字符；点右上角下载可查看完整文件。",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                        }
                        if (codeWrap) {
                            LazyColumn(state = wrapState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                                items(lines.size) { idx ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        Text("${idx + 1}",
                                            style = codeStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.widthIn(min = 32.dp).padding(end = 8.dp),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                            softWrap = false)
                                        Text(highlightCode(lines[idx].ifEmpty { " " }, fileName, if (searchOpen) searchQuery else ""),
                                            style = codeStyle, softWrap = true, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        } else {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(codeScroll).padding(8.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(end = 8.dp)) {
                                        Text((1..lines.size).joinToString("\n") { "$it" },
                                            style = codeStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            softWrap = false)
                                    }
                                    Box(Modifier.weight(1f).horizontalScroll(codeHScroll)) {
                                        Text(highlightCode(shown, fileName, if (searchOpen) searchQuery else ""),
                                            style = codeStyle, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (editing) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        content = original
                        editorValue = TextFieldValue(original)
                        editing = false
                    }) { Text("取消", fontSize = 12.sp) }
                    Text("已修改 ${(content.length - original.length)} 字符", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除文件") },
            text = { Text("将从仓库中删除 $path 并创建一个提交，确定吗？") },
            confirmButton = {
                TextButton(enabled = !deletingFile, onClick = {
                    confirmDelete = false
                    deletingFile = true
                    val delKey = "$owner/$name|$branch"
                    UiCache.deletingCode.add(delKey)
                    // 等 GitHub 真正删除成功后再返回并移除缓存；失败就留在当前文件页，
                    // 用户能看到文件仍在，不会出现“先消失又回来”。
                    scope.launch {
                        try {
                            val realSha = sha ?: Api.fileItem(owner, name, path, branch).sha
                            Api.deleteFile(owner, name, path, "delete $path", realSha, branch)
                            Cfg.touchRepo("$owner/$name")
                            UiCache.fileContent.remove(fileKey)
                            UiCache.codeListing.keys.filter { it.startsWith("$owner/$name|") }.forEach { k ->
                                val d = UiCache.codeListing[k] ?: return@forEach
                                UiCache.codeListing[k] = d.copy(items = d.items.filter { it.path != path })
                            }
                            Toast.makeText(ctx, "已真实删除 $path", Toast.LENGTH_LONG).show()
                            nav.popBackStack()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "删除失败：${e.message}（文件仍在）", Toast.LENGTH_LONG).show()
                        } finally {
                            deletingFile = false
                            UiCache.deletingCode.remove(delKey)
                        }
                    }
                }) { Text("确认删除", color = GH.red) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } }
        )
    }
}
