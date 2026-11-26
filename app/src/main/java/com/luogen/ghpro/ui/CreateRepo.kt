package com.luogen.ghpro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRepoScreen(nav: NavController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var private by rememberSaveable { mutableStateOf(false) }
    var autoInit by rememberSaveable { mutableStateOf(true) }
    var homepage by rememberSaveable { mutableStateOf("") }
    var topics by rememberSaveable { mutableStateOf("") }
    var gitignore by rememberSaveable { mutableStateOf("") }
    var license by rememberSaveable { mutableStateOf("") }
    var customLicense by rememberSaveable { mutableStateOf("") }
    var showCustomLicense by rememberSaveable { mutableStateOf(false) }
    var hasIssues by rememberSaveable { mutableStateOf(true) }
    var hasWiki by rememberSaveable { mutableStateOf(false) }
    var hasProjects by rememberSaveable { mutableStateOf(true) }
    var hasDiscussions by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var licMenu by remember { mutableStateOf(false) }
    var igMenu by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(FALLBACK_LICENSES) }
    var ignores by remember { mutableStateOf(FALLBACK_IGNORES) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        runCatching { Api.licenses() }.onSuccess { l ->
            if (l.isNotEmpty()) licenses = (l.map { it.key to it.name } + FALLBACK_LICENSES).distinctBy { it.first }
        }
        runCatching { Api.gitignoreTemplates() }.onSuccess { g ->
            if (g.isNotEmpty()) ignores = (g + FALLBACK_IGNORES).distinct()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("新建仓库", fontSize = 15.sp) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val repo = Api.createRepo(name.trim(), desc.trim(), private, autoInit,
                                    homepage.trim().ifBlank { null }, gitignore.ifBlank { null }, license.ifBlank { null },
                                    hasIssues, hasWiki, hasProjects, hasDiscussions)
                                Cfg.touchRepo(repo.full_name)
                                val wl = Cfg.watchRepos; wl.add(repo.full_name); Cfg.watchRepos = wl
                                UiCache.invalidateHome()
                                Bus.poke()
                                android.widget.Toast.makeText(ctx, "仓库 ${repo.full_name} 创建成功", android.widget.Toast.LENGTH_LONG).show()
                                nav.popBackStack()
                                // topics / 自定义 LICENSE 放到后台补写，不再卡住建库后的等待
                                val ownerLogin = repo.owner?.login ?: ""
                                val ts = topics.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
                                val lic = customLicense
                                val repoName = repo.name
                                val branch = repo.default_branch
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                                    if (ts.isNotEmpty()) runCatching { Api.setTopics(ownerLogin, repoName, ts) }
                                    if (lic.isNotBlank()) runCatching {
                                        Api.putFile(ownerLogin, repoName, "LICENSE", "Add LICENSE",
                                            android.util.Base64.encodeToString(lic.toByteArray(), android.util.Base64.NO_WRAP), null, branch)
                                    }
                                    Bus.poke()
                                }
                            } catch (e: Exception) {
                                snackbar.showSnackbar("创建失败：${e.message}")
                                android.widget.Toast.makeText(ctx, "创建失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                            busy = false
                        }
                    }) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("创建")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(14.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("仓库名称 *") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("描述") }, minLines = 2)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(homepage, { homepage = it }, Modifier.fillMaxWidth(), label = { Text("主页 URL（可选）") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(topics, { topics = it }, Modifier.fillMaxWidth(),
                label = { Text("Topics 标签（逗号分隔，如 android,kotlin,music）") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            SwitchRow("私有仓库（只有你能看到）", private) { private = it }
            SwitchRow("初始化 README（建议开启）", autoInit) { autoInit = it }

            Spacer(Modifier.height(12.dp))
            Text("模板（可选，真实写入仓库）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))

            // 许可证下拉
            Box {
                OutlinedCard(
                    onClick = { licMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("许可证 License", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(license.ifBlank { "不添加" }, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(expanded = licMenu, onDismissRequest = { licMenu = false }) {
                    DropdownMenuItem(text = { Text("不添加") }, onClick = { license = ""; licMenu = false })
                    licenses.forEach { (k, v) ->
                        DropdownMenuItem(text = { Text(v, fontSize = 13.sp) }, onClick = { license = k; licMenu = false })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // .gitignore 下拉
            Box {
                OutlinedCard(
                    onClick = { igMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(".gitignore 模板", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(gitignore.ifBlank { "不添加" }, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(expanded = igMenu, onDismissRequest = { igMenu = false }) {
                    DropdownMenuItem(text = { Text("不添加") }, onClick = { gitignore = ""; igMenu = false })
                    ignores.forEach { g ->
                        DropdownMenuItem(text = { Text(g, fontSize = 13.sp) }, onClick = { gitignore = g; igMenu = false })
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SwitchRow("自定义许可证正文（手动填写）", showCustomLicense) { showCustomLicense = it }
            if (showCustomLicense) {
                OutlinedTextField(customLicense, { customLicense = it }, Modifier.fillMaxWidth(),
                    label = { Text("粘贴 MIT 等许可证全文，创建后会写成 LICENSE 文件") }, minLines = 4)
                Spacer(Modifier.height(6.dp))
                Text("提示：留空就用上面的模板；填写后会以 LICENSE 文件真实提交。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(12.dp))
            Text("功能开关", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            SwitchRow("启用议题 Issues", hasIssues) { hasIssues = it }
            SwitchRow("启用 Wiki", hasWiki) { hasWiki = it }
            SwitchRow("启用 Projects", hasProjects) { hasProjects = it }
            SwitchRow("启用 Discussions 讨论区", hasDiscussions) { hasDiscussions = it }
            Spacer(Modifier.height(14.dp))
            Text("创建成功后会给提示，并回到首页，新仓库会显示在最上面。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(40.dp))
        }
    }
}

val FALLBACK_LICENSES = listOf(
    "mit" to "MIT License",
    "apache-2.0" to "Apache License 2.0",
    "gpl-3.0" to "GNU GPLv3",
    "agpl-3.0" to "GNU AGPLv3",
    "lgpl-3.0" to "GNU LGPLv3",
    "mpl-2.0" to "Mozilla Public License 2.0",
    "bsd-3-clause" to "BSD 3-Clause",
    "bsd-2-clause" to "BSD 2-Clause",
    "unlicense" to "The Unlicense",
    "cc0-1.0" to "CC0 1.0",
    "epl-2.0" to "EPL 2.0"
)

val FALLBACK_IGNORES = listOf("Android", "Kotlin", "Java", "Python", "Node", "JavaScript", "TypeScript",
    "Go", "Rust", "Swift", "C++", "C", "Gradle", "JetBrains", "VisualStudio", "Xcode", "macOS", "Linux", "Windows")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRepoScreen(nav: NavController, owner: String, name: String) {
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf<Repo?>(null) }
    var newName by remember { mutableStateOf(name) }
    var desc by rememberSaveable { mutableStateOf("") }
    var homepage by rememberSaveable { mutableStateOf("") }
    var topics by rememberSaveable { mutableStateOf("") }
    var private by rememberSaveable { mutableStateOf(false) }
    var archived by remember { mutableStateOf(false) }
    var hasIssues by rememberSaveable { mutableStateOf(true) }
    var hasWiki by rememberSaveable { mutableStateOf(false) }
    var hasProjects by rememberSaveable { mutableStateOf(true) }
    var hasDiscussions by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(owner, name) {
        runCatching { Api.repo(owner, name) }.onSuccess {
            repo = it
            desc = it.description ?: ""
            homepage = it.homepage ?: ""
            topics = it.topics?.joinToString(", ") ?: ""
            private = it.isPrivate
            archived = it.archived
            hasIssues = it.has_issues
            hasWiki = it.has_wiki
            hasProjects = it.has_projects
            hasDiscussions = it.has_discussions
        }
        loading = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("编辑 $owner/$name", fontSize = 14.sp) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(enabled = !busy && repo != null, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                if (newName != name) Api.renameRepo(owner, name, newName.trim())
                                val saved = Api.patchRepo(owner, newName.trim(),
                                    mapOf(
                                        "description" to desc,
                                        "homepage" to homepage,
                                        "private" to private,
                                        "archived" to archived,
                                        "has_issues" to hasIssues,
                                        "has_wiki" to hasWiki,
                                        "has_projects" to hasProjects,
                                        "has_discussions" to hasDiscussions
                                    ))
                                // 保存成功后立刻写回内存缓存：返回仓库页马上就是新开关，不靠再刷新几次
                                val savedKey = (saved.owner?.login ?: owner) + "/" + saved.name
                                UiCache.repoData[savedKey] = saved
                                if (newName != name) UiCache.repoData.remove("$owner/$name")
                                if (!saved.has_discussions) UiCache.repoDiscussions.remove(savedKey)
                                Bus.repoChanged()
                                if (topics.isNotBlank()) {
                                    runCatching { Api.setTopics(owner, saved.name, topics.split(",").map { it.trim() }.filter { it.isNotBlank() }) }
                                }
                                Bus.poke()
                                // 只让“已保存并生效”停留到能看清，随后立刻关闭编辑页；
                                // 不再等 Snackbar 默认的几秒自动消失，避免保存按钮点了像没反应。
                                snackbar.currentSnackbarData?.dismiss()
                                scope.launch { snackbar.showSnackbar("已保存并生效", duration = SnackbarDuration.Short) }
                                delay(700)
                                snackbar.currentSnackbarData?.dismiss()
                                if (newName != name) {
                                    // 改名后旧 URL 已经不存在，直接替换掉旧仓库页，避免返回后 404
                                    nav.popBackStack("repo/$owner/$name", inclusive = true)
                                    nav.navigate("repo/$owner/${saved.name}")
                                } else {
                                    nav.popBackStack()
                                }
                            } catch (e: Exception) { snackbar.showSnackbar("保存失败：${e.message}") }
                            busy = false
                        }
                    }) { Text("保存") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        if (loading) { LoadingBox(); return@Scaffold }
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(14.dp)) {
            OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), label = { Text("仓库名称（改名会改变 URL）") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("描述") }, minLines = 2)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(homepage, { homepage = it }, Modifier.fillMaxWidth(), label = { Text("主页") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(topics, { topics = it }, Modifier.fillMaxWidth(), label = { Text("Topics（逗号分隔）") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            SwitchRow("私有", private) { private = it }
            SwitchRow("归档（只读）", archived) { archived = it }
            SwitchRow("Issues", hasIssues) { hasIssues = it }
            SwitchRow("Wiki", hasWiki) { hasWiki = it }
            SwitchRow("Projects", hasProjects) { hasProjects = it }
            SwitchRow("Discussions", hasDiscussions) { hasDiscussions = it }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("删除这个仓库（真实删除）", color = GH.red)
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmDelete) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("彻底删除仓库") },
            text = {
                Column {
                    Text("将调用 GitHub API 永久删除 $owner/$name，仓库、代码、议题、发行版全部消失，无法恢复。", fontSize = 12.sp)
                    OutlinedTextField(input, { input = it }, label = { Text("输入仓库名确认") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = input == name, onClick = {
                    scope.launch {
                        try {
                            Api.deleteRepo(owner, name)
                            confirmDelete = false
                            Bus.poke()
                            snackbar.showSnackbar("已从 GitHub 彻底删除")
                            nav.navigate("home") { popUpTo("home") { inclusive = true } }
                        } catch (e: Exception) { snackbar.showSnackbar("删除失败：${e.message}") }
                    }
                }) { Text("确认删除", color = GH.red) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } }
        )
    }
}
