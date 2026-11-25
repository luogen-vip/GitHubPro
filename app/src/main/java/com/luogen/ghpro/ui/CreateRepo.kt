package com.luogen.ghpro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRepoScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(false) }
    var autoInit by remember { mutableStateOf(true) }
    var homepage by remember { mutableStateOf("") }
    var gitignore by remember { mutableStateOf("") }
    var license by remember { mutableStateOf("") }
    var hasIssues by remember { mutableStateOf(true) }
    var hasWiki by remember { mutableStateOf(false) }
    var hasProjects by remember { mutableStateOf(true) }
    var hasDiscussions by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

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
                                    homepage.trim().ifBlank { null }, gitignore.trim().ifBlank { null }, license.trim().ifBlank { null },
                                    hasIssues, hasWiki, hasProjects, hasDiscussions)
                                Bus.poke()
                                snackbar.showSnackbar("仓库 ${repo.full_name} 已在 GitHub 上创建")
                                nav.navigate("repo/${repo.owner?.login ?: Cfg.login}/${repo.name}") {
                                    popUpTo("home")
                                }
                            } catch (e: Exception) { snackbar.showSnackbar("创建失败：${e.message}") }
                            busy = false
                        }
                    }) { Text("创建") }
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
            SwitchRow("私有仓库（只有你能看到）", private) { private = it }
            SwitchRow("初始化 README / .gitignore / 许可证", autoInit) { autoInit = it }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(homepage, { homepage = it }, Modifier.fillMaxWidth(), label = { Text("主页 URL（可选）") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(gitignore, { gitignore = it }, Modifier.fillMaxWidth(),
                label = { Text("gitignore 模板，如 Android / Java / Kotlin（可选）") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(license, { license = it }, Modifier.fillMaxWidth(),
                label = { Text("许可证模板，如 mit / apache-2.0（可选）") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            Text("功能开关", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            SwitchRow("启用议题 Issues", hasIssues) { hasIssues = it }
            SwitchRow("启用 Wiki", hasWiki) { hasWiki = it }
            SwitchRow("启用 Projects", hasProjects) { hasProjects = it }
            SwitchRow("启用 Discussions 讨论区", hasDiscussions) { hasDiscussions = it }
            Spacer(Modifier.height(14.dp))
            Text("创建后可在 GitHub 网页端、手机端和本 App 里立刻看到这个仓库。", fontSize = 11.sp, color = GH.muted)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRepoScreen(nav: NavController, owner: String, name: String) {
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf<Repo?>(null) }
    var newName by remember { mutableStateOf(name) }
    var desc by remember { mutableStateOf("") }
    var homepage by remember { mutableStateOf("") }
    var topics by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(false) }
    var archived by remember { mutableStateOf(false) }
    var hasIssues by remember { mutableStateOf(true) }
    var hasWiki by remember { mutableStateOf(false) }
    var hasProjects by remember { mutableStateOf(true) }
    var hasDiscussions by remember { mutableStateOf(false) }
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
                                Api.patchRepo(owner, newName.trim(),
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
                                if (topics.isNotBlank()) {
                                    runCatching { Api.setTopics(owner, newName.trim(), topics.split(",").map { it.trim() }.filter { it.isNotBlank() }) }
                                }
                                Bus.poke()
                                snackbar.showSnackbar("已保存，GitHub 上已生效")
                                nav.popBackStack()
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
