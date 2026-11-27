package com.luogen.ghpro.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.User
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class RepoSub(val repo: Repo, val at: Long?)
private data class UserSub(val user: User, val at: Long?)

private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun whenText(ms: Long?): String =
    if (ms == null || ms <= 0L) "订阅时间未记录" else "订阅于 " + timeFmt.format(Date(ms))

private fun updatedText(iso: String?): String =
    if (iso.isNullOrBlank()) "更新时间未知" else iso.replace("T", " ").take(16)

private fun repoKey(r: Repo): String = r.full_name

private fun sortLabel(kind: Int, sort: String): String = when (sort) {
    "time_desc" -> "最新订阅"
    "time_asc" -> "最早订阅"
    "name" -> "名称 A-Z"
    "updated" -> "最近更新"
    "stars" -> if (kind == 0) "Star 最多" else "关注者最多"
    else -> "最新订阅"
}

private fun sortOptions(kind: Int): List<Pair<String, String>> = listOf(
    "time_desc" to "最新订阅",
    "time_asc" to "最早订阅",
    "name" to "名称 A-Z",
    "updated" to "最近更新",
    "stars" to if (kind == 0) "Star 最多" else "关注者最多"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(UiCache.subsTab.coerceIn(0, 1)) }
    var sort by remember { mutableStateOf(UiCache.subsSort) }
    var sortMenu by remember { mutableStateOf(false) }
    var repos by remember { mutableStateOf(UiCache.subsRepos) }
    var users by remember { mutableStateOf(UiCache.subsUsers) }
    var loading by remember { mutableStateOf(if (tab == 0) UiCache.subsRepos.isEmpty() else UiCache.subsUsers.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localRev by remember { mutableStateOf(0) }
    var cancelJob by remember { mutableStateOf<Job?>(null) }
    var snackJob by remember { mutableStateOf<Job?>(null) }
    var justRestored by remember { mutableStateOf<Set<String>>(emptySet()) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = UiCache.subsScrollIndex,
        initialFirstVisibleItemScrollOffset = UiCache.subsScrollOffset
    )
    val watchVer by Bus.watchPoke.collectAsState()
    val followVer by Bus.followPoke.collectAsState()
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 || listState.firstVisibleItemScrollOffset > 600 } }

    DisposableEffect(Unit) {
        onDispose {
            UiCache.subsTab = tab
            UiCache.subsSort = sort
            UiCache.subsRepos = repos
            UiCache.subsUsers = users
            UiCache.subsScrollIndex = listState.firstVisibleItemIndex
            UiCache.subsScrollOffset = listState.firstVisibleItemScrollOffset
        }
    }
    LaunchedEffect(tab) { UiCache.subsTab = tab; selected = emptySet(); justRestored = emptySet() }
    LaunchedEffect(sort) { UiCache.subsSort = sort }

    suspend fun loadRepos() {
        val all = ArrayList<Repo>()
        var page = 1
        while (page <= 20) {
            val pg = Api.watchedRepos(page)
            all.addAll(pg.items)
            if (!pg.hasNext) break
            page++
        }
        // GitHub 的订阅列表有短暂服务端延迟：刚取消的仓库可能还会被返回一次。
        // 以本地刚操作的真实状态为准，避免列表在“消失后滑一下又回来”。
        val out = ArrayList<Repo>()
        for (r in all.distinctBy { it.id }) {
            if (UiCache.repoWatchOverride(r.full_name) == false) continue
            out.add(r)
        }
        // 撤销/本机刚订阅成功、但服务端列表还没刷新时，先用本地快照补上。
        UiCache.repoWatchSnap.values.forEach { r ->
            if (UiCache.repoWatchOverride(r.full_name) == true && out.none { it.full_name == r.full_name }) out.add(r)
        }
        repos = out.distinctBy { it.id }
        UiCache.subsRepos = repos
    }

    suspend fun loadUsers() {
        val me = Cfg.login.orEmpty()
        if (me.isBlank()) throw IllegalStateException("还没有登录账号")
        val all = ArrayList<User>()
        var page = 1
        while (page <= 20) {
            val pg = Api.following(me, page)
            all.addAll(pg.items)
            if (!pg.hasNext) break
            page++
        }
        val out = ArrayList<User>()
        for (u in all.distinctBy { it.id }) {
            if (UiCache.followOverride(u.login) == false) continue
            out.add(u)
        }
        UiCache.userFollowSnap.values.forEach { u ->
            if (UiCache.followOverride(u.login) == true && out.none { it.login == u.login }) out.add(u)
        }
        users = out.distinctBy { it.id }
        UiCache.subsUsers = users
    }

    LaunchedEffect(tab, refresh, watchVer, followVer) {
        // 仓库页/资料页操作会发 Bus 事件；页面在后台也重新取一次真值，回到这里不显示旧状态。
        loading = true
        error = null
        runCatching { if (tab == 0) loadRepos() else loadUsers() }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
        localRev++
    }

    val repoRows = remember(repos, sort, localRev, justRestored) {
        val times = Cfg.watchTimes
        val rows = repos.map { RepoSub(it, times[it.full_name]) }
        val pin = compareBy<RepoSub> { if (it.repo.full_name in justRestored) 0 else 1 }
        when (sort) {
            "time_asc" -> rows.sortedWith(pin.thenBy { it.at ?: Long.MAX_VALUE }.thenBy { it.repo.full_name.lowercase() })
            "name" -> rows.sortedWith(pin.thenBy { it.repo.full_name.lowercase() })
            "updated" -> rows.sortedWith(pin.thenByDescending { it.repo.updated_at ?: "" }.thenByDescending { it.at ?: 0L })
            "stars" -> rows.sortedWith(pin.thenByDescending { it.repo.stargazers_count }.thenBy { it.repo.full_name.lowercase() })
            else -> rows.sortedWith(pin.thenByDescending { it.at ?: Long.MIN_VALUE }.thenByDescending { it.repo.updated_at ?: "" })
        }
    }
    val userRows = remember(users, sort, localRev, justRestored) {
        val times = Cfg.followTimes
        val rows = users.map { UserSub(it, times[it.login]) }
        val pin = compareBy<UserSub> { if (it.user.login in justRestored) 0 else 1 }
        when (sort) {
            "time_asc" -> rows.sortedWith(pin.thenBy { it.at ?: Long.MAX_VALUE }.thenBy { it.user.login.lowercase() })
            "name" -> rows.sortedWith(pin.thenBy { it.user.login.lowercase() })
            "updated" -> rows.sortedWith(pin.thenByDescending { it.user.updated_at ?: "" }.thenByDescending { it.at ?: 0L })
            "stars" -> rows.sortedWith(pin.thenByDescending { it.user.followers }.thenBy { it.user.login.lowercase() })
            else -> rows.sortedWith(pin.thenByDescending { it.at ?: Long.MIN_VALUE }.thenByDescending { it.user.updated_at ?: "" })
        }
    }
    val visibleKeys = if (tab == 0) repoRows.map { repoKey(it.repo) } else userRows.map { it.user.login }
    val allSelected = visibleKeys.isNotEmpty() && selected.size == visibleKeys.size

    fun chooseSort(k: String) {
        sort = k
        sortMenu = false
        scope.launch { listState.scrollToItem(0) }
    }

    fun cancelSelected() {
        val keys = selected
        if (keys.isEmpty()) return
        if (tab == 0) {
            val snap = repoRows.filter { it.repo.full_name in keys }
            repos = repos.filter { it.full_name !in keys }
            selected = emptySet()
            snap.forEach { UiCache.setRepoWatch(it.repo.full_name, false); UiCache.repoWatchSnap.remove(it.repo.full_name) }
            localRev++
            Bus.watchChanged()
            snackJob?.cancel()
            val job = Bus.actions.launch {
                var failed = ArrayList<RepoSub>()
                for (s in snap) {
                    try {
                        Api.watch(s.repo.full_name.substringBefore('/'), s.repo.name, false)
                        Cfg.markWatch(s.repo.full_name, false)
                        UiCache.setRepoWatch(s.repo.full_name, false)
                    } catch (e: Exception) { failed.add(s) }
                }
                if (failed.isNotEmpty()) {
                    failed.forEach { UiCache.setRepoWatch(it.repo.full_name, true); UiCache.repoWatchSnap[it.repo.full_name] = it.repo }
                    repos = (repos + failed.map { it.repo }).distinctBy { it.id }
                    localRev++
                    Toast.makeText(ctx, "部分取消失败：${failed.firstOrNull()?.repo?.full_name}", Toast.LENGTH_SHORT).show()
                }
                Bus.watchChanged()
            }
            cancelJob = job
            snackJob = scope.launch {
                val r = snackbar.showSnackbar("已取消订阅 ${snap.size} 个仓库", actionLabel = "撤销", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) {
                    val restored = snap.map { it.repo.full_name }.toSet()
                    repos = (snap.map { it.repo } + repos).distinctBy { it.id }
                    selected = emptySet()
                    justRestored = restored
                    snap.forEach { UiCache.setRepoWatch(it.repo.full_name, true); UiCache.repoWatchSnap[it.repo.full_name] = it.repo }
                    localRev++
                    Bus.watchChanged()
                    scope.launch { listState.scrollToItem(0) }
                    scope.launch {
                        delay(7_000L)
                        if (justRestored == restored) justRestored = emptySet()
                    }
                    Bus.actions.launch {
                        cancelJob?.join()
                        for (s in snap) {
                            try {
                                Api.watch(s.repo.full_name.substringBefore('/'), s.repo.name, true)
                                Cfg.markWatch(s.repo.full_name, true, s.at ?: System.currentTimeMillis())
                                UiCache.setRepoWatch(s.repo.full_name, true)
                            } catch (e: Exception) {
                                UiCache.setRepoWatch(s.repo.full_name, false)
                                UiCache.repoWatchSnap.remove(s.repo.full_name)
                                repos = repos.filter { it.full_name != s.repo.full_name }
                                localRev++
                                Toast.makeText(ctx, "撤销失败：${s.repo.full_name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        Bus.watchChanged()
                    }
                }
            }
        } else {
            val snap = userRows.filter { it.user.login in keys }
            users = users.filter { it.login !in keys }
            selected = emptySet()
            snap.forEach { UiCache.setFollow(it.user.login, false); UiCache.userFollowSnap.remove(it.user.login) }
            localRev++
            Bus.followChanged()
            snackJob?.cancel()
            val job = Bus.actions.launch {
                var failed = ArrayList<UserSub>()
                for (s in snap) {
                    try {
                        Api.follow(s.user.login, false)
                        Cfg.markFollow(s.user.login, false)
                    } catch (e: Exception) { failed.add(s) }
                }
                if (failed.isNotEmpty()) {
                    failed.forEach { UiCache.setFollow(it.user.login, true); UiCache.userFollowSnap[it.user.login] = it.user }
                    users = (users + failed.map { it.user }).distinctBy { it.id }
                    localRev++
                    Toast.makeText(ctx, "部分取消失败：${failed.firstOrNull()?.user?.login}", Toast.LENGTH_SHORT).show()
                }
                Bus.followChanged()
            }
            cancelJob = job
            snackJob = scope.launch {
                val r = snackbar.showSnackbar("已取消关注 ${snap.size} 个用户", actionLabel = "撤销", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) {
                    val restored = snap.map { it.user.login }.toSet()
                    users = (snap.map { it.user } + users).distinctBy { it.id }
                    selected = emptySet()
                    justRestored = restored
                    snap.forEach { UiCache.setFollow(it.user.login, true); UiCache.userFollowSnap[it.user.login] = it.user }
                    localRev++
                    Bus.followChanged()
                    scope.launch { listState.scrollToItem(0) }
                    scope.launch {
                        delay(7_000L)
                        if (justRestored == restored) justRestored = emptySet()
                    }
                    Bus.actions.launch {
                        cancelJob?.join()
                        for (s in snap) {
                            try {
                                Api.follow(s.user.login, true)
                                Cfg.markFollow(s.user.login, true, s.at ?: System.currentTimeMillis())
                                UiCache.setFollow(s.user.login, true)
                            } catch (e: Exception) {
                                UiCache.setFollow(s.user.login, false)
                                UiCache.userFollowSnap.remove(s.user.login)
                                users = users.filter { it.login != s.user.login }
                                localRev++
                                Toast.makeText(ctx, "撤销失败：${s.user.login}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        Bus.followChanged()
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("订阅管理", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("仓库和用户分开管理 · 取消可撤销", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { androidx.compose.material3.IconButton({ nav.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, "返回")
                } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (showTop && visibleKeys.isNotEmpty()) {
                SmallFab { scope.launch { listState.animateScrollToItem(0) } }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                Tab(tab == 0, { tab = 0; scope.launch { listState.scrollToItem(0) } },
                    text = { Text("仓库订阅 ${repos.size}", fontSize = 12.sp) })
                Tab(tab == 1, { tab = 1; scope.launch { listState.scrollToItem(0) } },
                    text = { Text("用户关注 ${users.size}", fontSize = 12.sp) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Text("排序：${sortLabel(tab, sort)} ▾", fontSize = 11.sp, color = GH.blue,
                        maxLines = 1, modifier = Modifier.clickable { sortMenu = true }.padding(vertical = 3.dp))
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        sortOptions(tab).forEach { (k, label) ->
                            DropdownMenuItem(text = { Text(label, fontSize = 12.sp) }, onClick = { chooseSort(k) })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("${visibleKeys.size} 个", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if ((if (tab == 0) repoRows.any { it.at == null } else userRows.any { it.at == null }) &&
                (if (tab == 0) repoRows.isNotEmpty() else userRows.isNotEmpty())) {
                Text("旧订阅时间 GitHub 不返回；只有在本机操作过的订阅才记录具体时间",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp))
            }
            if (visibleKeys.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allSelected, onCheckedChange = {
                        selected = if (allSelected) emptySet() else visibleKeys.toSet()
                    })
                    Text("全选", fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("已选 ${selected.size}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = selected.isNotEmpty(), onClick = { cancelSelected() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp)) {
                        Text("取消${if (tab == 0) "订阅" else "关注"}", fontSize = 11.sp, color = GH.red)
                    }
                }
                Divider()
            }
            when {
                loading && visibleKeys.isEmpty() -> LoadingBox()
                error != null && visibleKeys.isEmpty() -> ErrorBox(error) { refresh++ }
                visibleKeys.isEmpty() -> EmptyBox(if (tab == 0) "还没有订阅任何仓库" else "还没有关注任何用户")
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    if (tab == 0) {
                        items(repoRows, key = { it.repo.id }) { s ->
                            val checked = s.repo.full_name in selected
                            Row(Modifier.fillMaxWidth().clickable {
                                val o = s.repo.owner?.login.orEmpty()
                                nav.navigate("repo/$o/${s.repo.name}")
                            }.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = { on ->
                                    selected = if (on) selected + s.repo.full_name else selected - s.repo.full_name
                                })
                                Icon(Icons.Filled.Bookmark, null, Modifier.size(18.dp), tint = GH.blue)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.repo.full_name, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.repo.description ?: "没有填写描述", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row {
                                        Text("★ ${s.repo.stargazers_count}", fontSize = 9.sp, color = GH.yellow)
                                        Spacer(Modifier.width(7.dp))
                                        Text("更新 ${updatedText(s.repo.updated_at)}", fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(whenText(s.at), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Divider()
                        }
                    } else {
                        items(userRows, key = { it.user.id }) { s ->
                            val checked = s.user.login in selected
                            Row(Modifier.fillMaxWidth().clickable { nav.navigate("user/${s.user.login}") }
                                .padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = { on ->
                                    selected = if (on) selected + s.user.login else selected - s.user.login
                                })
                                Avatar(s.user.avatar_url, 28, name = s.user.login)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.user.login, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.user.name ?: "没有填写昵称", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${s.user.followers} 关注者 · 更新 ${updatedText(s.user.updated_at)}",
                                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(whenText(s.at), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Divider()
                        }
                    }
                }
            }
        }
    }
}
