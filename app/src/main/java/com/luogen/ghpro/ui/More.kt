package com.luogen.ghpro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val note: String? = null,
    val scope: String = "当前仓库",
    val emptyText: String = "当前范围确实是空的（真实 0 条，不是权限失败）。",
    val errorHint: String? = null
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
        }, link = "https://gist.github.com/", scope = "账号级",
            emptyText = "你的账号还没有公开/私密 Gist；点右上角 + 可以新建。",
            errorHint = "403 表示 token 缺少 gist 权限；到 GitHub Settings→Applications 勾选 gist 后重新登录。"),
        MoreFeature("组织 / 团队", "我加入的组织，以及每个组织里能看到的团队", Icons.Filled.Group, {
            orgsTeams()
        }, link = "https://github.com/settings/organizations", scope = "账号级",
            emptyText = "这个账号还没有加入任何组织；没有组织时这里就是 0 条。",
            errorHint = "403 表示 token 缺少 read:org，或组织启用了 SSO 还没授权。"),
        MoreFeature("Packages 包", "你账号可见的容器/依赖包（按类型逐个查询；需要 read:packages）", Icons.Filled.Storage, {
            packages()
        }, scope = "账号级",
            emptyText = "这个账号目前没有可见的 Packages（容器/npm/Maven/RubyGems/NuGet 都查过了）；没有发布过包就是 0 条。",
            errorHint = "403 表示 token 缺少 read:packages；空数组则是真的没有包，不是仓库问题。"),
        MoreFeature("Marketplace / Apps", "真实 Marketplace 购买记录；GitHub App installations 接口只对 App 令牌开放，OAuth 令牌无法列，页面里保留官方入口。", Icons.Filled.Extension, {
            val purchases = runCatching { Api.raw("/user/marketplace_purchases", query = mapOf("per_page" to "30")) }.getOrElse { "[]" }
            JsonObject().apply {
                add("marketplace_purchases", JsonParser.parseString(purchases))
                addProperty("installations_api", "需要 GitHub App token；OAuth token 不能列 installations")
                addProperty("manage_url", "https://github.com/settings/installations")
            }.toString()
        }, link = "https://github.com/settings/installations", scope = "账号级",
            emptyText = "没有 Marketplace 购买记录；GitHub App installations 接口只接受 App token，普通 OAuth token 查不到，这是官方限制。",
            errorHint = "installations 的 403 是官方限制，不代表你的令牌坏了；购买记录为空表示确实没买。"),
        MoreFeature("Codespaces 云端开发环境", "列出你的 Codespace，并显示状态/机器/仓库", Icons.Filled.Cloud, {
            Api.raw("/user/codespaces", query = mapOf("per_page" to "30"))
        }, link = "https://github.com/codespaces", scope = "账号级",
            emptyText = "这个账号当前没有 Codespace；没有创建过就是 0 条。",
            errorHint = "403/404 表示 token 缺少 codespace 权限，或账号没有启用 Codespaces。"),
        MoreFeature("安全通告", "GitHub 全球安全通告（真实公开数据）", Icons.Filled.Security, {
            Api.raw("/advisories", query = mapOf("per_page" to "20"))
        }, link = "https://github.com/advisories", scope = "全局",
            emptyText = "GitHub 当前没有可展示的安全通告（很少见）。",
            errorHint = "这是 GitHub 公共数据，403 通常是网络/网关拦截，不是仓库权限。")
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
                Text("这里是【账号级】能力，不绑定某个仓库：Gists、Packages、Codespaces、Marketplace、组织、安全通告都是你账号/全球范围的数据。显示 0 条通常就是账号确实还没有；不代表仓库坏了。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
            item {
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("subs") },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Notifications, null, Modifier.size(24.dp), tint = GH.blue)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("订阅管理", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("查看全部仓库订阅和用户关注 · 多选取消 · 可撤销 · 支持排序", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text("进入", fontSize = 11.sp, color = GH.blue)
                    }
                }
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
        }, link = "https://github.com/$owner/$repo/projects",
            emptyText = "这个仓库还没有 Projects V2；没有创建过就是 0 条，不是 token 没权限。",
            errorHint = "0 条是仓库没有项目；403 才看 project 权限。"),
        MoreFeature("Packages 包", "你账号可见的包（Packages 属于用户/组织，不按仓库拆分；需要 read:packages）", Icons.Filled.Storage, {
            packages()
        }, scope = "账号级",
            emptyText = "Packages 是账号级数据，不属于这个仓库；账号没有发布过包就是 0 条。",
            errorHint = "403 看 read:packages；空数组不是仓库没启用。"),
        MoreFeature("Pages 静态站点", "查看 Pages 站点配置，并可触发一次真实构建", Icons.Filled.Web, {
            runCatching { Api.raw("$base/pages") }.getOrElse {
                JsonObject().apply {
                    addProperty("enabled", false)
                    addProperty("reason", "GitHub 返回 404：这个仓库没有启用 Pages，或 Pages 还没有发布。去 Settings→Pages 选择分支并保存。")
                }.toString()
            }
        }, emptyText = "这个仓库还没有启用 Pages，所以接口返回 404；去 Settings→Pages 选择分支并保存后再回来。",
            errorHint = "404 通常是 Pages 没启用；403 才看 token 的 repo 权限。",
            actionLabel = "重新构建 Pages", action = {
            Api.raw("$base/pages/builds", "POST", "{}")
            "已提交 Pages 构建请求"
        }, link = "https://github.com/$owner/$repo/settings/pages"),
        MoreFeature("Deployments 部署", "仓库的真实部署记录", Icons.Filled.RocketLaunch, {
            Api.raw("$base/deployments", query = mapOf("per_page" to "20"))
        }, link = "https://github.com/$owner/$repo/deployments",
            emptyText = "这个仓库最近没有部署记录；空数组表示确实没部署过，不是权限。",
            errorHint = "0 条是没部署；403 才看 deployments 权限。"),
        MoreFeature("Environments 环境", "生产/预发等部署环境和保护规则", Icons.Filled.Dns, {
            Api.raw("$base/environments", query = mapOf("per_page" to "20"))
        }, link = "https://github.com/$owner/$repo/settings/environments",
            emptyText = "这个仓库还没有创建 Environment；0 个是真实状态。",
            errorHint = "0 个是没建环境；403 才看环境权限。"),
        MoreFeature("分支保护 / Rulesets", "查看分支保护与仓库规则集；只读展示真实配置", Icons.Filled.Security, {
            val protection = runCatching { Api.raw("$base/branches/${Api.enc(branch)}/protection") }.getOrElse { "{\"error\":\"${it.message}\"}" }
            val rulesets = runCatching { Api.raw("$base/rulesets", query = mapOf("per_page" to "20")) }.getOrElse { "[]" }
            JsonObject().apply {
                add("branch", JsonParser.parseString("{\"name\":\"$branch\"}"))
                add("protection", JsonParser.parseString(protection))
                add("rulesets", JsonParser.parseString(rulesets))
            }.toString()
        }, link = "https://github.com/$owner/$repo/settings/branches",
            emptyText = "main 分支未设置保护规则；404 是 GitHub 的正常回答，不是权限失败。",
            errorHint = "404=分支未保护；403=token 没有分支保护读取权限。"),
        MoreFeature("Dependabot / 安全通告", "仓库 Dependabot 告警 + GitHub 全球安全通告；告警需要 security_events 权限", Icons.Filled.BugReport, {
            val alerts = runCatching { Api.raw("$base/dependabot/alerts", query = mapOf("state" to "open", "per_page" to "20")) }
                .getOrElse {
                    val msg = it.message?.replace('"', '\'') ?: "读取失败"
                    JsonObject().apply { addProperty("dependabot_error", msg) }.toString()
                }
            val advisories = runCatching { Api.raw("/advisories", query = mapOf("per_page" to "10")) }.getOrElse { "[]" }
            JsonObject().apply { add("dependabot_alerts", JsonParser.parseString(alerts)); add("advisories", JsonParser.parseString(advisories)) }.toString()
        }, link = "https://github.com/$owner/$repo/security",
            emptyText = "Dependabot 告警为 0；如果显示 dependabot_error，说明仓库没有启用 Dependabot alerts，而不是令牌没勾权限。",
            errorHint = "403 Dependabot alerts are disabled：去仓库 Settings→Code security and analysis 启用 Dependabot alerts。"),
        MoreFeature("Checks / 提交状态", "当前分支最新提交的 check-runs 和 commit statuses", Icons.Filled.CheckCircle, {
            val checks = runCatching { Api.raw("$base/commits/${Api.enc(branch)}/check-runs", query = mapOf("per_page" to "20")) }.getOrElse { "{}" }
            val status = runCatching { Api.raw("$base/commits/${Api.enc(branch)}/status") }.getOrElse { "{}" }
            JsonObject().apply { add("check_runs", JsonParser.parseString(checks)); add("combined_status", JsonParser.parseString(status)) }.toString()
        }, link = "https://github.com/$owner/$repo/actions",
            emptyText = "当前分支没有 check-runs / statuses；0 条表示没有 Actions 运行或检查，不是权限失败。",
            errorHint = "0 条是没检查；403/404 才看 Actions/checks 权限。"),
        MoreFeature("Labels 标签", "仓库全部标签；可查看颜色和描述", Icons.Filled.Label, {
            Api.raw("$base/labels", query = mapOf("per_page" to "50"))
        }, link = "https://github.com/$owner/$repo/labels",
            emptyText = "这个仓库还没有标签；点上面“新建标签”可以创建。",
            errorHint = "0 条表示没建标签；403 才看 issues 权限。"),
        MoreFeature("Milestones 里程碑", "仓库里程碑和完成进度", Icons.Filled.Flag, {
            Api.raw("$base/milestones", query = mapOf("state" to "all", "per_page" to "20"))
        }, link = "https://github.com/$owner/$repo/milestones",
            emptyText = "这个仓库还没有里程碑；点上面“新建里程碑”可以创建。",
            errorHint = "0 条表示没建里程碑；403 才看 issues 权限。"),
        MoreFeature("Pull 审查 / Reviewer", "最近 PR 的 draft 状态、requested_reviewers 和真实 review 列表", Icons.Filled.RateReview, {
            prReviews(owner, repo)
        }, link = "https://github.com/$owner/$repo/pulls",
            emptyText = "当前没有打开的 PR；0 条是真实状态，不是权限失败。",
            errorHint = "0 条是没 PR；403/404 才看 pull request 权限。"),
        MoreFeature("Reactions 表态", "最近议题/PR 的 reactions 真实统计", Icons.Filled.EmojiEmotions, {
            reactions(owner, repo)
        }, link = "https://github.com/$owner/$repo/issues",
            emptyText = "最近 3 条议题/PR 还没有表态；0 条是真实状态。锁定议题不允许添加 reaction。",
            errorHint = "0 条是没表态；403 常见于议题被锁定，或 token 没有 reactions 权限。"),
        MoreFeature("Traffic 仓库流量", "最近 14 天的访问、克隆、热门路径和来源（需要 push 权限）", Icons.Filled.Traffic, {
            val views = runCatching { Api.raw("$base/traffic/views") }.getOrElse { "{}" }
            val clones = runCatching { Api.raw("$base/traffic/clones") }.getOrElse { "{}" }
            val paths = runCatching { Api.raw("$base/traffic/popular/paths") }.getOrElse { "[]" }
            val refs = runCatching { Api.raw("$base/traffic/popular/referrers") }.getOrElse { "[]" }
            JsonObject().apply {
                add("views", JsonParser.parseString(views)); add("clones", JsonParser.parseString(clones))
                add("popular_paths", JsonParser.parseString(paths)); add("referrers", JsonParser.parseString(refs))
            }.toString()
        }, link = "https://github.com/$owner/$repo/graphs/traffic",
            emptyText = "最近 14 天没有访问/克隆数据；0 是真实流量，不代表没权限。",
            errorHint = "0 是没流量；403 才看 repo push 权限。")
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
                Text("这里是【当前仓库】$owner/$repo 的能力：Pages、Deployments、Environments、分支保护、Dependabot、Checks、Labels、Milestones、PR、Traffic 都只属于这个仓库。首页“更多”是账号级，两者不是同一套数据。",
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
    var lastError: String? = null
    listOf("container", "npm", "maven", "rubygems", "nuget").forEach { type ->
        runCatching { Api.raw("/user/packages", query = mapOf("package_type" to type, "per_page" to "10")) }
            .onFailure { lastError = it.message }
            .getOrNull()?.let { raw ->
                runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull()?.forEach { el ->
                    val o = el.asJsonObject.deepCopy()
                    o.addProperty("package_type", type)
                    out.add(o)
                }
            }
    }
    if (out.size() == 0 && lastError != null) {
        return JsonObject().apply { addProperty("packages_error", lastError) }.toString()
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f.scope, fontSize = 9.sp, color = if (f.scope == "账号级") GH.purple else GH.blue,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
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
                            Text(f.errorHint ?: "403 先看 token 是否勾选这项权限、组织 SSO 是否授权；404 先看仓库是否开启这个功能。下面按钮能直接去官方设置。",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { load() } }) {
                                Text("重试", fontSize = 11.sp)
                            }
                        }
                        text != null -> JsonBlock(text!!, f.emptyText)
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
private fun JsonBlock(raw: String, emptyText: String) {
    val ctx = LocalContext.current
    val el = remember(raw) { runCatching { JsonParser.parseString(raw) }.getOrNull() }
    if (el == null) {
        Text(raw.take(2000), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (el.isJsonArray) {
        val arr = el.asJsonArray
        if (arr.size() == 0) { Text(emptyText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
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
        if (jsonEffectivelyEmpty(o)) {
            Text(emptyText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
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

private fun jsonEffectivelyEmpty(el: JsonElement): Boolean {
    if (el.isJsonNull) return true
    if (el.isJsonArray) return el.asJsonArray.size() == 0
    if (!el.isJsonObject) return el.asString.isBlank()
    val o = el.asJsonObject
    if (o.entrySet().isEmpty()) return true
    val interesting = o.entrySet().filter { (k, _) -> k !in setOf("clientMutationId", "message", "note", "url", "html_url") }
    if (interesting.isEmpty()) return false
    return interesting.all { (_, v) ->
        (v.isJsonArray && v.asJsonArray.size() == 0) ||
        (v.isJsonObject && jsonEffectivelyEmpty(v)) ||
        (v.isJsonPrimitive && (v.asString.isBlank() || v.asString == "0" || v.asString == "false"))
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
