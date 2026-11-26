package com.luogen.ghpro.ui

import android.net.Uri
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Save
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

    suspend fun load(silent: Boolean = false) {
        if (!silent) loading = true
        error = null
        try {
            listing = Api.contents(owner, name, path, branch)
            listing?.let { UiCache.codeListing["$owner/$name|$branch|$path"] = it }
        } catch (e: Exception) { error = e.message }
        loading = false
    }

    androidx.activity.compose.BackHandler(enabled = path.isNotEmpty()) {
        path = path.substringBeforeLast('/', "")
    }
    LaunchedEffect(branch, path) {
        UiCache.codePath["$owner/$name"] = path
        val cached = UiCache.codeListing["$owner/$name|$branch|$path"]
        if (cached != null) { listing = cached; loading = false; runCatching { load(true) } } else load()
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
            IconButton(onClick = {
                nav.navigate("file?owner=$owner&repo=$name&branch=${Uri.encode(branch)}&path=&isNew=1")
            }) { Icon(Icons.Filled.Add, "新建文件", tint = GH.green) }
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
                                if (item.type == "dir") path = item.path
                                else nav.navigate("file?owner=$owner&repo=$name&branch=${Uri.encode(branch)}&path=${Uri.encode(item.path)}&isNew=0")
                            }.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                scope.launch {
                                    runCatching {
                                        Downloader.download(ctx, "https://github.com/$owner/$name/archive/refs/heads/$branch.zip", "$name-$branch.zip")
                                    }
                                    snackbar.showSnackbar("开始下载源码包")
                                }
                            }) { Icon(Icons.Filled.Download, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("整包下载", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
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
                                if (fileName.isBlank()) { scope.launch { snackbar.showSnackbar("请输入文件名") }; return@IconButton }
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
                            Downloader.startDownload(ctx, "https://raw.githubusercontent.com/$owner/$name/$branch/${path}", fileName2,
                                onDone = {
                                    val where = Downloader.stateOf(fileName2)?.publicPath ?: "下载/GitHubPro/$fileName2"
                                    Toast.makeText(ctx, "已保存到 $where", Toast.LENGTH_LONG).show()
                                },
                                onError = { m -> Toast.makeText(ctx, "下载失败：$m", Toast.LENGTH_LONG).show() })
                        }) { Icon(Icons.Filled.Download, "下载") }
                        if (!isNew && sha != null) {
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
                            Downloader.startDownload(ctx, "https://raw.githubusercontent.com/$owner/$name/$branch/$path", fileName2,
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

    if (confirmDelete && sha != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除文件") },
            text = { Text("将从仓库中删除 $path 并创建一个提交，确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            Api.deleteFile(owner, name, path, "delete $path", sha!!, branch)
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
