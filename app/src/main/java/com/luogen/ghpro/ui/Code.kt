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
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
    val snackbar = remember { SnackbarHostState() }
    var pendingUpload by remember { mutableStateOf<Uri?>(null) }
    var uploadTarget by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
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
        catch (e: Exception) { if (!silent) error = e.message }
        loading = false
    }

    androidx.activity.compose.BackHandler(enabled = selecting || path.isNotEmpty()) {
        if (selecting) { selecting = false; selected.clear() }
        else path = path.substringBeforeLast('/', "")
    }
    LaunchedEffect(branch, path) {
        UiCache.codePath["$owner/$name"] = path
        val cached = UiCache.codeListing["$owner/$name|$branch|$path"]
        if (cached != null) { listing = cached; loading = false; load(silent = true) } else load()
    }
    // 代码页实时刷新：别人新建/删除/修改文件，几秒内自动出现
    LaunchedEffect(branch, path) {
        while (isActive) {
            delay(3_000L)
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
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(l.items.sortedWith(compareBy({ it.type != "dir" }, { it.name })), key = { it.sha + it.path }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (selecting) {
                                    if (selected.contains(item.path)) selected.remove(item.path) else selected.add(item.path)
                                } else if (item.type == "dir") path = item.path
                                else nav.navigate("file?owner=$owner&repo=$name&branch=${Uri.encode(branch)}&path=${Uri.encode(item.path)}&isNew=0")
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
                    Bus.actions.launch {
                        uploading = true
                        try {
                            Toast.makeText(ctx, "开始上传…", Toast.LENGTH_SHORT).show()
                            val tmp = java.io.File(ctx.cacheDir, "ghpro_upload_source.bin")
                            withContext(Dispatchers.IO) {
                                ctx.contentResolver.openInputStream(pu)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                                    ?: throw java.io.IOException("读不到这个文件")
                            }
                            val size = tmp.length()
                            if (size > 2L * 1024 * 1024 * 1024) throw java.io.IOException("单个文件最大 2GB")
                            if (size > 30L * 1024 * 1024) {
                                Toast.makeText(ctx, "大文件走 Git LFS（${size / 1024 / 1024} MB），请稍等…", Toast.LENGTH_LONG).show()
                                val oid = Api.lfsUpload(owner, name, tmp)
                                Api.lfsCommit(owner, name, target, branch, oid, size, "upload ${target.substringAfterLast('/')} via LFS")
                            } else {
                                val bytes = withContext(Dispatchers.IO) { tmp.readBytes() }
                                val sha = runCatching { Api.fileItem(owner, name, target, branch).sha }.getOrNull()
                                Api.putFile(owner, name, target, "upload ${target.substringAfterLast('/')}",
                                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), sha, branch)
                            }
                            tmp.delete()
                            UiCache.codeListing.keys.filter { it.startsWith("$owner/$name|") }.forEach { UiCache.codeListing.remove(it) }
                            UiCache.fileContent.remove("$owner/$name|$branch|$target")
                            Cfg.touchRepo("$owner/$name")
                            Toast.makeText(ctx, "已上传 $target", Toast.LENGTH_LONG).show()
                            pendingUpload = null
                            uploadTarget = ""
                            load(true)
                        } catch (e: Exception) {
                            java.io.File(ctx.cacheDir, "ghpro_upload_source.bin").delete()
                            Toast.makeText(ctx, "上传失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                        uploading = false
                    }
                }) { Text(if (uploading) "上传中…" else "上传", fontSize = 13.sp) }
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
                    // 先本地移除，界面立即消失；后台真实提交期间禁止自动刷新回填
                    listing = snapshot?.copy(items = snapshot.items.filter { it.path !in paths })
                    selecting = false
                    selected.clear()
                    Bus.actions.launch {
                        try {
                            val deleted = Api.deletePaths(owner, name, paths, branch)
                            if (deleted <= 0) throw java.io.IOException("没有找到要删除的文件，可能已经被删除")
                            Cfg.touchRepo("$owner/$name")
                            UiCache.codeListing.keys.filter { it.startsWith("$owner/$name|") }.forEach { UiCache.codeListing.remove(it) }
                            // 已经真实提交成功：保持本地移除，不再显示旧列表；再用静默请求校准一次
                            UiCache.deletingCode.remove(deleteKey)
                            deleting = false
                            Toast.makeText(ctx, "已真实删除 $deleted 个文件", Toast.LENGTH_LONG).show()
                            // 不再立刻请求旧缓存列表：删除成功后保持本地已移除的状态
                            // 下次进入目录/手动刷新自然会从服务器取到最新结果
                        } catch (e: Exception) {
                            // 只有真的失败才恢复原列表，并明确提示文件还在
                            listing = snapshot
                            UiCache.deletingCode.remove(deleteKey)
                            deleting = false
                            Toast.makeText(ctx, "删除失败：${e.message}（文件仍在）", Toast.LENGTH_LONG).show()
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
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var isFile by remember { mutableStateOf(true) }
    var lfsRef by remember { mutableStateOf<LfsRef?>(null) }
    var knownSize by remember { mutableStateOf(0L) }
    var previewKind by remember { mutableStateOf("text") }
    var imageUrl by remember { mutableStateOf<String?>(null) }
    var fontFamily by remember { mutableStateOf<FontFamily?>(null) }
    val snackbar = remember { SnackbarHostState() }

    fun load(silent: Boolean = false) {
        if (isNew) { loading = false; return }
        scope.launch {
            if (!silent) { loading = true; previewKind = "text"; imageUrl = null; fontFamily = null }
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
            catch (e: Exception) { error = e.message }
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
            delay(3_000L)
            if (previewKind == "text" && !editing && !loading && "$owner/$name|$branch" !in UiCache.deletingCode) runCatching { load(true) }
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
                        IconButton({ downloadFile(path.substringAfterLast('/')) }) { Icon(Icons.Filled.Download, "下载") }
                        if (!isNew) {
                            IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "删除", tint = GH.red) }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
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
                            value = content, onValueChange = { content = it },
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(10.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            placeholder = { Text("文件内容…", fontSize = 12.sp) }
                        )
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                            if (content.length > 200_000) {
                                Text("文件较大，下面只预览前 20 万字符；点右上角下载可查看完整文件。",
                                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            // 末尾换行在编辑器里会多出一个空行，但 Compose 的 Text 不会把它画出来，
                            // 于是行号会比代码多 1~2 个。这里按“真正能看见的代码行”统一两边。
                            val display = safePreview(content.replace("\r\n", "\n").replace('\r', '\n'), 200_000)
                                .trimEnd('\n')
                            val shown = display.ifEmpty { " " }
                            val lineCount = shown.count { it == '\n' } + 1
                            val codeStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(end = 8.dp)) {
                                    // 行号列和代码列使用同一 TextStyle、同一行高，行数也只按 shown 算。
                                    Text((1..lineCount).joinToString("\n") { "$it" },
                                        style = codeStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        softWrap = false)
                                }
                                Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                                    Text(shown, style = codeStyle, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
            if (editing) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { content = original; editing = false }) { Text("取消", fontSize = 12.sp) }
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
                TextButton(onClick = {
                    confirmDelete = false
                    val delKey = "$owner/$name|$branch"
                    // 先把界面和缓存里的文件移除并返回列表，真实删除在后台继续，不让用户等网络
                    UiCache.fileContent.remove(fileKey)
                    UiCache.codeListing.keys.filter { it.startsWith("$owner/$name|") }.forEach { k ->
                        val d = UiCache.codeListing[k] ?: return@forEach
                        UiCache.codeListing[k] = d.copy(items = d.items.filter { it.path != path })
                    }
                    UiCache.deletingCode.add(delKey)
                    nav.popBackStack()
                    val appCtx = ctx.applicationContext
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            val realSha = sha ?: Api.fileItem(owner, name, path, branch).sha
                            Api.deleteFile(owner, name, path, "delete $path", realSha, branch)
                            Cfg.touchRepo("$owner/$name")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(appCtx, "已删除 $path", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(appCtx, "删除失败：${e.message}（文件仍在）", Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            UiCache.deletingCode.remove(delKey)
                        }
                    }
                }) { Text("确认删除", color = GH.red) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } }
        )
    }
}
