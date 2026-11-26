package com.luogen.ghpro.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.ContentItem
import com.luogen.ghpro.data.DirListing
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.download.Downloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        if (!silent) loading = true
        error = null
        try {
            listing = Api.contents(owner, name, path, branch)
            listing?.let { UiCache.codeListing["$owner/$name|$branch|$path"] = it }
        } catch (e: Exception) { if (!silent) error = e.message }
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
            delay(6_000L)
            if (!selecting && !uploading) runCatching { load(silent = true) }
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
                                    onDone = { Toast.makeText(ctx, "源码包已保存到下载/GitHubPro", Toast.LENGTH_LONG).show() },
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
                    Text("文件会直接提交到当前分支 $branch。路径可手动修改，留空则默认放在根目录。", fontSize = 11.sp,
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
                    scope.launch {
                        uploading = true
                        try {
                            Toast.makeText(ctx, "开始上传…", Toast.LENGTH_SHORT).show()
                            val bytes = ctx.contentResolver.openInputStream(pu)?.use { it.readBytes() }
                                ?: throw java.io.IOException("读不到这个文件")
                            if (bytes.size > 30 * 1024 * 1024) throw java.io.IOException("文件超过 30MB，GitHub 单文件接口传不了，请用电脑上传")
                            val sha = runCatching { Api.fileItem(owner, name, target, branch).sha }.getOrNull()
                            Api.putFile(owner, name, target, "upload ${target.substringAfterLast('/')}",
                                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), sha, branch)
                            UiCache.codeListing.keys.filter { it.startsWith("$owner/$name|") }.forEach { UiCache.codeListing.remove(it) }
                            UiCache.fileContent.remove("$owner/$name|$branch|$target")
                            Toast.makeText(ctx, "已上传 $target", Toast.LENGTH_LONG).show()
                            pendingUpload = null
                            uploadTarget = ""
                            load(true)
                        } catch (e: Exception) {
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
                    // 先本地移除，界面立即消失；随后真实提交删除
                    listing = snapshot?.copy(items = snapshot.items.filter { it.path !in paths })
                    selecting = false
                    selected.clear()
                    scope.launch {
                        try {
                            val deleted = Api.deletePaths(owner, name, paths, branch)
                            UiCache.codeListing.keys.filter { it.startsWith("$owner/$name|") }.forEach { UiCache.codeListing.remove(it) }
                            Toast.makeText(ctx, "已真实删除 $deleted 个文件", Toast.LENGTH_LONG).show()
                            load(true)
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "删除失败：${e.message}", Toast.LENGTH_LONG).show()
                            load(true)
                        }
                    }
                }) { Text("确认删除", color = GH.red) }
            },
            dismissButton = { TextButton({ confirmBulk = false }) { Text("取消") } }
        )
    }

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
    val snackbar = remember { SnackbarHostState() }

    fun load(silent: Boolean = false) {
        if (isNew) { loading = false; return }
        scope.launch {
            if (!silent) loading = true
            try {
                val l = Api.contents(owner, name, path, branch)
                val f = l.file
                if (f == null || l.items.size > 1) { isFile = false; error = "这是一个目录，请返回列表" }
                else {
                    sha = f.sha
                    if (f.size > 1_000_000) { error = "文件超过 1MB，GitHub API 不返回内容，请直接下载" }
                    else {
                        content = Api.fileContent(owner, name, path, branch)
                        original = content
                        UiCache.fileContent[fileKey] = content
                    }
                }
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }

    LaunchedEffect(path) {
        if (!isNew) {
            val cached = UiCache.fileContent[fileKey]
            if (cached != null) { content = cached; original = cached; loading = false; runCatching { load(true) } } else load()
        }
    }
    LaunchedEffect(path) {
        if (!isNew) while (isActive) {
            delay(6_000L)
            if (!editing && !loading) runCatching { load(true) }
        }
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
                                        editing = false; original = content
                                        load()
                                    } catch (e: Exception) { snackbar.showSnackbar("保存失败：${e.message}") }
                                }
                            }) { Icon(Icons.Filled.Save, "保存", tint = GH.green) }
                        } else {
                            IconButton({ editing = true }) { Icon(Icons.Filled.Edit, "编辑") }
                        }
                        IconButton({
                            val fileName2 = path.substringAfterLast('/')
                            Toast.makeText(ctx, "开始下载 $fileName2…", Toast.LENGTH_SHORT).show()
                            Downloader.startDownload(ctx, "https://raw.githubusercontent.com/$owner/$name/$branch/${path}", fileName2,
                                source = "仓库文件", repo = "$owner/$name",
                                onDone = {
                                    val where = Downloader.stateOf(fileName2)?.publicPath ?: "下载/GitHubPro/$fileName2"
                                    Toast.makeText(ctx, "已保存到 $where", Toast.LENGTH_LONG).show()
                                },
                                onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
                        }) { Icon(Icons.Filled.Download, "下载") }
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
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                        OutlinedButton(onClick = {
                            val fileName2 = path.substringAfterLast('/')
                            Toast.makeText(ctx, "开始下载 $fileName2…", Toast.LENGTH_SHORT).show()
                            Downloader.startDownload(ctx, "https://raw.githubusercontent.com/$owner/$name/$branch/$path", fileName2,
                                source = "仓库文件", repo = "$owner/$name",
                                onDone = {
                                    val where = Downloader.stateOf(fileName2)?.publicPath ?: "下载/GitHubPro/$fileName2"
                                    Toast.makeText(ctx, "已保存到 $where", Toast.LENGTH_LONG).show()
                                },
                                onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
                        }) { Text("直接下载文件") }
                    }
                }
                else -> {
                    if (editing) {
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
                            Row {
                                Column(Modifier.padding(end = 8.dp)) {
                                    val lines = content.count { it == '\n' } + 1
                                    Text((1..minOf(lines, 5000)).joinToString("\n") { "$it" },
                                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                                }
                                Text(content.take(200_000), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
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
                    scope.launch {
                        try {
                            val realSha = sha ?: Api.fileItem(owner, name, path, branch).sha
                            Api.deleteFile(owner, name, path, "delete $path", realSha, branch)
                            confirmDelete = false
                            snackbar.showSnackbar("已删除")
                            nav.popBackStack()
                        } catch (e: Exception) { snackbar.showSnackbar("删除失败：${e.message}") }
                    }
                }) { Text("确认删除", color = GH.red) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } }
        )
    }
}
