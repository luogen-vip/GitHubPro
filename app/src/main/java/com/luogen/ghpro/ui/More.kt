package com.luogen.ghpro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Cfg
import kotlinx.coroutines.launch

private data class MoreFeature(
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val load: suspend () -> String,
    val actionLabel: String? = null,
    val action: (suspend () -> String?)? = null,
    val link: String? = null,
    val note: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserMoreScreen(nav: NavController) {
    val ctx = LocalContext.current
    var creatingGist by remember { mutableStateOf(false) }
    var gistVersion by remember { mutableStateOf(0) }
    val features = listOf(
        MoreFeature("Gists 代码片段", "列出你的全部 Gist，点条目可复制/在浏览器查看", Icons.Filled.Code, {
            Api.raw("/gists", query = mapOf("per_page" to "30"))
        }, link = "https://gist.github.com/"),
        MoreFeature("组织 / 团队", "我加入的组织，以及每个组织里能看到的团队", Icons.Filled.Group, {
            orgsTeams()
        }, link = "https://github.com/settings/organizations"),
        MoreFeature("Packages 包", "你账号可见的容器/依赖包（按类型逐个查询；需要 read:packages）", Icons.Filled.Storage, {
            packages()
        }),
        MoreFeature("Marketplace / Apps", "真实 Marketplace 购买记录；GitHub App installations 接口只对 App 令牌开放，OAuth 令牌无法列，页面里保留官方入口。", Icons.Filled.Extension, {
            val purchases = runCatching { Api.raw("/user/marketplace_purchases", query = mapOf("per_page" to "30")) }.getOrElse { "[]" }
            JsonObject().apply {
                add("marketplace_purchases", JsonParser.parseString(purchases))
                addProperty("installations_api", "需要 GitHub App token；OAuth token 不能列 installations")
                addProperty("manage_url", "https://github.com/settings/installations")
            }.toString()
        }, link = "https://github.com/settings/installations"),
        MoreFeature("Codespaces 云端开发环境", "列出你的 Codespace，并显示状态/机器/仓库", Icons.Filled.Cloud, {
            Api.raw("/user/codespaces", query = mapOf("per_page" to "30"))
        }, link = "https://github.com/codespaces"),
        MoreFeature("安全通告", "GitHub 全球安全通告（真实公开数据）", Icons.Filled.Security, {
            Api.raw("/advisories", query = mapOf("per_page" to "20"))
        }, link = "https://github.com/advisories")
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Column { Text("更多 GitHub 能力", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("全部读取真实 GitHub API · 没有模拟数据", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton({ creatingGist = true }) { Icon(Icons.Filled.Add, "新建 Gist") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("这些功能放在单独的“能力中心”，不会挤占首页/仓库页签；每个卡片都需要手动点开才请求，避免一次发太多 API。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
            items(features) { f -> MoreCard(f, gistVersion) }
        }
    }
    if (creatingGist) {
        var desc by remember { mutableStateOf("") }
        var filename by remember { mutableStateOf("snippet.md") }
        var body by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) creatingGist = false },
            title = { Text("新建 Gist") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(filename, { filename = it }, label = { Text("文件名（如 snippet.md）") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(desc, { desc = it }, label = { Text("说明（可空）") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(body, { body = it }, label = { Text("内容") }, minLines = 5)
                }
            },
            confirmButton = { TextButton(enabled = !busy && filename.isNotBlank(), onClick = {
                busy = true
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    try {
                        val files = JsonObject().apply {
                            add(filename, JsonObject().apply { addProperty("content", body) })
                        }
                        val jo = JsonObject().apply {
                            addProperty("description", desc)
                            addProperty("public", false)
                            add("files", files)
                        }
                        Api.raw("/gists", "POST", jo.toString())
                        creatingGist = false; gistVersion++
                        Toast.makeText(ctx, "Gist 已创建", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "创建失败：${e.message}", Toast.LENGTH_LONG).show()
                    } finally { busy = false }
                }
            }) { Text(if (busy) "创建中…" else "创建") } },
            dismissButton = { TextButton({ if (!busy) creatingGist = false }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoMoreScreen(nav: NavController, owner: String, repo: String) {
    val ctx = LocalContext.current
    var branch by remember { mutableStateOf("main") }
    var dataVersion by remember { mutableStateOf(0) }
    var showLabel by remember { mutableStateOf(false) }
    var showMilestone by remember { mutableStateOf(false) }
    LaunchedEffect(owner, repo) {
        runCatching { Api.repo(owner, repo).default_branch }.getOrNull()?.takeIf { it.isNotBlank() }?.let { branch = it }
    }
    val base = "/repos/$owner/$repo"
    val features = listOf(
        MoreFeature("Wiki 文档", "GitHub 官方没有开放 Wiki 的 REST 写入接口；这里提供真实 Wiki 入口和仓库 Wiki 地址，不用假页面糊弄。",
            Icons.Filled.Article, {
                JsonObject().apply {
                    addProperty("status", "GitHub REST 未提供 Wiki 读写接口")
                    addProperty("url", "https://github.com/$owner/$repo/wiki")
                    addProperty("git_url", "https://github.com/$owner/$repo.wiki.git")
                }.toString()
            }, link = "https://github.com/$owner/$repo/wiki"),
        MoreFeature("Projects 项目", "仓库的 Projects V2（GraphQL 真实数据）", Icons.Filled.AccountTree, {
            val q = "query(\$owner:String!,\$repo:String!){repository(owner:\$owner,name:\$repo){projectsV2(first:30){totalCount nodes{id number title closed public shortDescription url}}}}"
            val body = JsonObject().apply {
                addProperty("query", q)
                add("variables", JsonObject().apply { addProperty("owner", owner); addProperty("repo", repo) })
            }.toString()
            Api.raw("/graphql", "POST", body)
        }, link = "https://github.com/$owner/$repo/projects"),
        MoreFeature("Packages 包", "你账号可见的包（Packages 属于用户/组织，不按仓库拆分；需要 read:packages）", Icons.Filled.Storage, {
            packages()
        }),
        MoreFeature("Pages 静态站点", "查看 Pages 站点配置，并可触发一次真实构建", Icons.Filled.Web, {
            Api.raw("$base/pages")
        }, actionLabel = "重新构建 Pages", action = {
            Api.raw("$base/pages/builds", "POST", "{}")
            "已提交 Pages 构建请求"
        }, link = "https://github.com/$owner/$repo/settings/pages"),
        MoreFeature("Deployments 部署", "仓库的真实部署记录", Icons.Filled.RocketLaunch, {
            Api.raw("$base/deployments", query = mapOf("per_page" to "20"))
        }, link = "https://github.com/$owner/$repo/deployments"),
        MoreFeature("Environments 环境", "生产/预发等部署环境和保护规则", Icons.Filled.Dns, {
            Api.raw("$base/environments", query = mapOf("per_page" to "20"))
        }, link = "https://github.com/$owner/$repo/settings/environments"),
        MoreFeature("分支保护 / Rulesets", "查看分支保护与仓库规则集；只读展示真实配置", Icons.Filled.Security, {
            val protection = runCatching { Api.raw("$base/branches/${Api.enc(branch)}/protection") }.getOrElse { "{\"error\":\"${it.message}\"}" }
            val rulesets = runCatching { Api.raw("$base/rulesets", query = mapOf("per_page" to "20")) }.getOrElse { "[]" }
            JsonObject().apply {
                add("branch", JsonParser.parseString("{\"name\":\"$branch\"}"))
                add("protection", JsonParser.parseString(protection))
                add("rulesets", JsonParser.parseString(rulesets))
            }.toString()
        }, link = "https://github.com/$owner/$repo/settings/branches"),
        MoreFeature("Dependabot / 安全通告", "仓库 Dependabot 告警 + GitHub 全球安全通告；告警需要 security_events 权限", Icons.Filled.BugReport, {
            val alerts = runCatching { Api.raw("$base/dependabot/alerts", query = mapOf("state" to "open", "per_page" to "20")) }
                .getOrElse { "[]" }
            val advisories = runCatching { Api.raw("/advisories", query = mapOf("per_page" to "10")) }.getOrElse { "[]" }
            JsonObject().apply { add("dependabot_alerts", JsonParser.parseString(alerts)); add("advisories", JsonParser.parseString(advisories)) }.toString()
        }, link = "https://github.com/$owner/$repo/security"),
        MoreFeature("Checks / 提交状态", "当前分支最新提交的 check-runs 和 commit statuses", Icons.Filled.CheckCircle, {
            val checks = runCatching { Api.raw("$base/commits/${Api.enc(branch)}/check-runs", query = mapOf("per_page" to "20")) }.getOrElse { "{}" }
            val status = runCatching { Api.raw("$base/commits/${Api.enc(branch)}/status") }.getOrElse { "{}" }
            JsonObject().apply { add("check_runs", JsonParser.parseString(checks)); add("combined_status", JsonParser.parseString(status)) }.toString()
        }, link = "https://github.com/$owner/$repo/actions"),
        MoreFeature("Labels 标签", "仓库全部标签；可查看颜色和描述", Icons.Filled.Label, {
            Api.raw("$base/labels", query = mapOf("per_page" to "50"))
        }, link = "https://github.com/$owner/$repo/labels"),
        MoreFeature("Milestones 里程碑", "仓库里程碑和完成进度", Icons.Filled.Flag, {
            Api.raw("$base/milestones", query = mapOf("state" to "all", "per_page" to "20"))
        }, link = "https://github.com/$owner/$repo/milestones"),
        MoreFeature("Pull 审查 / Reviewer", "最近 PR 的 draft 状态、requested_reviewers 和真实 review 列表", Icons.Filled.RateReview, {
            prReviews(owner, repo)
        }, link = "https://github.com/$owner/$repo/pulls"),
        MoreFeature("Reactions 表态", "最近议题/PR 的 reactions 真实统计", Icons.Filled.EmojiEmotions, {
            reactions(owner, repo)
        }, link = "https://github.com/$owner/$repo/issues"),
        MoreFeature("Traffic 仓库流量", "最近 14 天的访问、克隆、热门路径和来源（需要 push 权限）", Icons.Filled.Traffic, {
            val views = runCatching { Api.raw("$base/traffic/views") }.getOrElse { "{}" }
            val clones = runCatching { Api.raw("$base/traffic/clones") }.getOrElse { "{}" }
            val paths = runCatching { Api.raw("$base/traffic/popular/paths") }.getOrElse { "[]" }
            val refs = runCatching { Api.raw("$base/traffic/popular/referrers") }.getOrElse { "[]" }
            JsonObject().apply {
                add("views", JsonParser.parseString(views)); add("clones", JsonParser.parseString(clones))
                add("popular_paths", JsonParser.parseString(paths)); add("referrers", JsonParser.parseString(refs))
            }.toString()
        }, link = "https://github.com/$owner/$repo/graphs/traffic")
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Column { Text("更多能力 · $owner/$repo", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("真实 GitHub API · 点开卡片才会发请求", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = { IconButton({ openUrl(ctx, "https://github.com/$owner/$repo") }) { Icon(Icons.Filled.OpenInNew, "浏览器") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("发行版编辑在“发行版”页签里；PR 评论、议题 reactions 也可以在对应详情页操作。下面这些是仓库级的扩展能力，单独放这里避免把原页签挤坏。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showLabel = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Icon(Icons.Filled.Label, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("新建标签", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { showMilestone = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Icon(Icons.Filled.Flag, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("新建里程碑", fontSize = 11.sp)
                    }
                }
            }
            items(features) { f -> MoreCard(f, dataVersion) }
        }
    }
    if (showLabel) {
        var lname by remember { mutableStateOf("") }
        var lcolor by remember { mutableStateOf("1f6feb") }
        var ldesc by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) showLabel = false },
            title = { Text("新建标签", fontSize = 15.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(lname, { lname = it }, label = { Text("名称") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(lcolor, { lcolor = it }, label = { Text("颜色（6 位十六进制，如 1f6feb）") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(ldesc, { ldesc = it }, label = { Text("描述（可空）") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(enabled = !busy && lname.isNotBlank(), onClick = {
                busy = true
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    try {
                        Api.createLabel(owner, repo, lname.trim(), lcolor.trim(), ldesc.trim())
                        showLabel = false; dataVersion++
                        Toast.makeText(ctx, "标签已创建", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) { Toast.makeText(ctx, "创建失败：${e.message}", Toast.LENGTH_LONG).show() }
                    finally { busy = false }
                }
            }) { Text(if (busy) "创建中…" else "创建") } },
            dismissButton = { TextButton({ if (!busy) showLabel = false }) { Text("取消") } }
        )
    }
    if (showMilestone) {
        var mtitle by remember { mutableStateOf("") }
        var mdesc by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) showMilestone = false },
            title = { Text("新建里程碑", fontSize = 15.sp) },
            text = {
                Column {
                    OutlinedTextField(mtitle, { mtitle = it }, label = { Text("标题") }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(mdesc, { mdesc = it }, label = { Text("描述（可空）") }, minLines = 3)
                }
            },
            confirmButton = { TextButton(enabled = !busy && mtitle.isNotBlank(), onClick = {
                busy = true
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    try {
                        Api.createMilestone(owner, repo, mtitle.trim(), mdesc.trim())
                        showMilestone = false; dataVersion++
                        Toast.makeText(ctx, "里程碑已创建", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) { Toast.makeText(ctx, "创建失败：${e.message}", Toast.LENGTH_LONG).show() }
                    finally { busy = false }
                }
            }) { Text(if (busy) "创建中…" else "创建") } },
            dismissButton = { TextButton({ if (!busy) showMilestone = false }) { Text("取消") } }
        )
    }
}

private suspend fun packages(): String {
    val out = JsonArray()
    listOf("container", "npm", "maven", "rubygems", "nuget").forEach { type ->
        runCatching { Api.raw("/user/packages", query = mapOf("package_type" to type, "per_page" to "10")) }
            .getOrNull()?.let { raw ->
                runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull()?.forEach { el ->
                    val o = el.asJsonObject.deepCopy()
                    o.addProperty("package_type", type)
                    out.add(o)
                }
            }
    }
    return out.toString()

}

private suspend fun orgsTeams(): String {
    val orgs = JsonParser.parseString(Api.raw("/user/orgs", query = mapOf("per_page" to "20"))).asJsonArray
    val out = JsonArray()
    orgs.forEach { el ->
        val o = el.asJsonObject
        val login = o.get("login")?.asString ?: return@forEach
        val item = o.deepCopy()
        val teams = runCatching { Api.raw("/orgs/${Api.enc(login)}/teams", query = mapOf("per_page" to "20")) }
            .getOrElse { "[]" }
        item.add("teams", JsonParser.parseString(teams))
        out.add(item)
    }
    return out.toString()
}

private suspend fun prReviews(owner: String, repo: String): String {
    val prs = JsonParser.parseString(Api.raw("/repos/$owner/$repo/pulls",
        query = mapOf("state" to "open", "per_page" to "3", "sort" to "updated"))).asJsonArray
    val out = JsonArray()
    prs.forEach { el ->
        val p = el.asJsonObject
        val n = p.get("number")?.asInt ?: return@forEach
        val item = p.deepCopy()
        val reviews = runCatching {
            Api.raw("/repos/$owner/$repo/pulls/$n/reviews", query = mapOf("per_page" to "20"))
        }.getOrElse { "[]" }
        item.add("reviews", JsonParser.parseString(reviews))
        out.add(item)
    }
    return out.toString()
}

private suspend fun reactions(owner: String, repo: String): String {
    val issues = JsonParser.parseString(Api.raw("/repos/$owner/$repo/issues",
        query = mapOf("state" to "all", "per_page" to "3", "sort" to "updated"))).asJsonArray
    val out = JsonArray()
    issues.forEach { el ->
        val i = el.asJsonObject
        val n = i.get("number")?.asInt ?: return@forEach
        val item = i.deepCopy()
        val r = runCatching {
            Api.raw("/repos/$owner/$repo/issues/$n/reactions", query = mapOf("per_page" to "20"),
                accept = "application/vnd.github.squirrel-girl+json")
        }.getOrElse { "[]" }
        item.add("reactions", JsonParser.parseString(r))
        out.add(item)
    }
    return out.toString()
}

@Composable
private fun MoreCard(f: MoreFeature, refresh: Int = 0) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    suspend fun load() {
        busy = true; error = null
        try { text = f.load() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { busy = false }
    }
    LaunchedEffect(open, refresh) { if (open && (text == null || refresh > 0)) load() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(f.icon, null, Modifier.size(20.dp), tint = GH.blue)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(f.desc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                Divider1()
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    f.note?.let { Text(it, fontSize = 10.sp, color = GH.yellow) }
                    when {
                        busy -> LoadingBox()
                        error != null -> Column {
                            Text("读取失败：$error", fontSize = 11.sp, color = GH.red)
                            Text("这通常表示当前 token 没有这项权限，或仓库没开启对应功能；不是假数据。",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { load() } }) {
                                Text("重试", fontSize = 11.sp)
                            }
                        }
                        text != null -> JsonBlock(text!!)
                    }
                    if (f.actionLabel != null && f.action != null) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(enabled = !actionBusy, onClick = {
                            actionBusy = true
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                try {
                                    val msg = f.action.invoke() ?: "操作已完成"
                                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                    text = null; error = null; load()
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "操作失败：${e.message}", Toast.LENGTH_LONG).show()
                                } finally { actionBusy = false }
                            }
                        }) { Text(if (actionBusy) "处理中…" else f.actionLabel, fontSize = 11.sp) }
                    }
                    if (f.link != null) {
                        TextButton({ openUrl(ctx, f.link) }) {
                            Icon(Icons.Filled.OpenInNew, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("在浏览器打开", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonBlock(raw: String) {
    val ctx = LocalContext.current
    val el = remember(raw) { runCatching { JsonParser.parseString(raw) }.getOrNull() }
    if (el == null) {
        Text(raw.take(2000), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (el.isJsonArray) {
        val arr = el.asJsonArray
        if (arr.size() == 0) { Text("没有数据（可能仓库未启用这个功能，或账号没有权限）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
        Text("共 ${arr.size()} 条", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth()) {
            arr.take(25).forEach { item ->
                val o = item.takeIf { it.isJsonObject }?.asJsonObject
                if (o == null) Text(pretty(item).take(300), fontSize = 11.sp, modifier = Modifier.padding(vertical = 3.dp))
                else {
                    val title = firstOf(o, "full_name", "name", "title", "tag_name", "login", "path", "number", "id")
                    val sub = subtitle(o)
                    val url = firstOf(o, "html_url", "url", "download_url")
                    Column(Modifier.fillMaxWidth().clickable { url?.let { openUrl(ctx, it) } }.padding(vertical = 4.dp)) {
                        Text(title ?: "(无标题)", fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (sub.isNotBlank()) Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Divider1()
                }
            }
            if (arr.size() > 25) Text("只显示前 25 条，完整数据可复制原始 JSON", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (el.isJsonObject) {
        val o = el.asJsonObject
        Column(Modifier.fillMaxWidth()) {
            o.entrySet().take(40).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(k, fontSize = 10.sp, color = GH.blue, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pretty(v).take(500), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
        }
    } else {
        Text(pretty(el), fontSize = 11.sp)
    }
    Row(Modifier.fillMaxWidth()) {
        TextButton({
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("JSON", raw))
            Toast.makeText(ctx, "已复制原始 JSON", Toast.LENGTH_SHORT).show()
        }) { Text("复制原始 JSON", fontSize = 10.sp) }
    }
}

private fun firstOf(o: JsonObject, vararg keys: String): String? {
    keys.forEach { k -> o.get(k)?.takeIf { !it.isJsonNull }?.let { v ->
        if (v.isJsonPrimitive) return v.asString
    } }
    return null
}

private fun pretty(v: JsonElement): String = when {
    v.isJsonNull -> "null"
    v.isJsonPrimitive -> v.asString
    v.isJsonArray -> "${v.asJsonArray.size()} 条"
    v.isJsonObject -> v.asJsonObject.entrySet().take(5).joinToString(" · ") { "${it.key}=${pretty(it.value).take(60)}" }
    else -> v.toString()
}

private fun subtitle(o: JsonObject): String {
    val parts = ArrayList<String>()
    firstOf(o, "description", "body", "message", "shortDescription")?.takeIf { it.isNotBlank() }?.let { parts.add(it.replace("\n", " ").take(120)) }
    firstOf(o, "state", "status", "conclusion", "draft")?.let { parts.add(it) }
    firstOf(o, "stargazers_count", "download_count", "size", "open_issues_count")?.let { parts.add(it) }
    o.get("reviews")?.takeIf { it.isJsonArray }?.let { parts.add("${it.asJsonArray.size()} 条 review") }
    o.get("requested_reviewers")?.takeIf { it.isJsonArray }?.let { parts.add("${it.asJsonArray.size()} 位 reviewer") }
    o.get("teams")?.takeIf { it.isJsonArray }?.let { parts.add("${it.asJsonArray.size()} 个团队") }
    return parts.joinToString(" · ")
}
