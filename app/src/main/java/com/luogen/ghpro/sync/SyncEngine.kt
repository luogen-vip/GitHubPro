package com.luogen.ghpro.sync

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.GhEvent
import com.luogen.ghpro.data.LocalNotice
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.Run
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SyncEngine {

    private val lock = Mutex()

    data class Change(val title: String, val body: String, val kind: String, val route: String? = null)

    private fun repoRoute(full: String): String = "repo/${full.trim('/')}"

    private suspend fun commitRoute(full: String, branch: String?): String {
        val parts = full.split("/")
        if (parts.size != 2) return repoRoute(full)
        val sha = runCatching { Api.commits(parts[0], parts[1], branch, 1).items.firstOrNull()?.sha }.getOrNull()
        return if (!sha.isNullOrBlank()) "commit/$full/$sha" else repoRoute(full)
    }

    private fun notificationRoute(n: com.luogen.ghpro.data.Notification): String? {
        val full = n.repository?.full_name ?: return null
        val url = n.subject?.url ?: return repoRoute(full)
        Regex("/repos/([^/]+)/([^/]+)/(issues|pulls|discussions)/(\\d+)").find(url)?.let { m ->
            val (o, r, kind, num) = m.destructured
            val routeKind = when (kind) { "issues" -> "issue"; "pulls" -> "pr"; else -> "discussion" }
            return "$routeKind/$o/$r/$num"
        }
        Regex("/repos/([^/]+)/([^/]+)/releases/(\\d+)").find(url)?.let { m ->
            val (o, r, id) = m.destructured
            return "release/$o/$r/$id"
        }
        Regex("/repos/([^/]+)/([^/]+)/commits/([0-9a-fA-F]+)").find(url)?.let { m ->
            val (o, r, sha) = m.destructured
            return "commit/$o/$r/$sha"
        }
        Regex("/repos/([^/]+)/([^/]+)/actions/runs/(\\d+)").find(url)?.let { m ->
            val (o, r, id) = m.destructured
            return "run/$o/$r/$id"
        }
        return repoRoute(full)
    }

    private fun snapRoot(): JsonObject {
        val s = Cfg.snapshots
        return try {
            if (s.isBlank()) JsonObject() else JsonParser.parseString(s).asJsonObject
        } catch (e: Exception) { JsonObject() }
    }

    private fun save(root: JsonObject) {
        Cfg.snapshots = root.toString()
    }

    /**
     * 一次真实同步：拉取通知 / 我的仓库 Star 变化 / 关注仓库的工作流状态变化。
     */
    suspend fun syncOnce(ctx: Context, notify: Boolean = true): List<Change> = lock.withLock {
        syncOnceLocked(ctx, notify)
    }

    private suspend fun syncOnceLocked(ctx: Context, notify: Boolean = true): List<Change> {
        val token = Cfg.token ?: return emptyList()
        Api.token = token
        val changes = ArrayList<Change>()
        val apiReleaseRoutes = HashSet<String>()
        val root = snapRoot()
        val repoSnap = root.getAsJsonObject("repos") ?: JsonObject().also { root.add("repos", it) }
        val runSnap = root.getAsJsonObject("runs") ?: JsonObject().also { root.add("runs", it) }
        val repoChecked = root.getAsJsonObject("repoChecked") ?: JsonObject().also { root.add("repoChecked", it) }
        val runChecked = root.getAsJsonObject("runChecked") ?: JsonObject().also { root.add("runChecked", it) }
        val myReposNow = HashSet<String>()
        // 先取一次服务端真实订阅仓库；通知过滤和后面的发行版/工作流轮询共用，避免重复请求。
        val serverWatchNames = runCatching { Api.watchedRepos(1).items.map { it.full_name } }.getOrNull()

        // 1. 通知
        try {
            val list = Api.notifications(all = false)
            // 记录 GitHub 通知接口已经覆盖的发行版路由，避免下面轮询再生成一条重复的本地通知。
            list.filter { it.subject?.type == "Release" }.forEach { n ->
                notificationRoute(n)?.let { apiReleaseRoutes.add(it) }
            }
            val seen = Cfg.lastSeenNotificationIds
            val firstRun = seen.isEmpty()
            val fresh = list.filter { n ->
                if (n.id in seen) return@filter false
                val repo = n.repository?.full_name ?: ""
                if (repo.isNotBlank() && Cfg.isRepoMuted(repo)) return@filter false
                // GitHub 会把“在某条议题/PR/发行版下参与过”标成 subscribed；用户没订阅仓库时，
                // 这些通知不再冒充“你关注的仓库”。自己的仓库仍然保留。
                if (n.reason == "subscribed" && serverWatchNames != null && repo.isNotBlank() &&
                    repo !in serverWatchNames && repo.substringBefore('/') != Cfg.login) return@filter false
                true
            }
            val newSeen = (seen + list.map { it.id }).toList().takeLast(300).toSet()
            Cfg.lastSeenNotificationIds = newSeen
            if (notify && fresh.isNotEmpty() && !firstRun) {
                fresh.take(5).forEach { n ->
                    val repo = n.repository?.full_name ?: ""
                    val subjectType = n.subject?.type ?: ""
                    val prNum = Regex("/pulls/(\\d+)").find(n.subject?.url ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                    var prMerged = false
                    var prState = ""
                    if (subjectType == "PullRequest" && (n.reason == "state_change" || n.reason == "merge") && prNum != null && repo.isNotBlank()) {
                        val parts = repo.split("/")
                        if (parts.size == 2) {
                            runCatching { Api.pull(parts[0], parts[1], prNum) }.getOrNull()?.let {
                                prMerged = it.merged || !it.merged_at.isNullOrBlank()
                                prState = it.state ?: ""
                            }
                        }
                    }
                    val title = when {
                        n.reason == "review_requested" && subjectType == "PullRequest" -> "👀 有人请你审查 PR"
                        subjectType == "PullRequest" && prMerged -> "✅ Pull Request 已合并"
                        subjectType == "PullRequest" && n.reason == "state_change" && prState == "closed" -> "Pull Request 已关闭"
                        subjectType == "PullRequest" && n.reason == "state_change" -> "Pull Request 状态更新"
                        subjectType == "PullRequest" && n.reason == "subscribed" -> "新 Pull Request"
                        subjectType == "Issue" -> "新议题"
                        subjectType == "Discussion" -> "新讨论"
                        subjectType == "Release" -> "新发行版"
                        subjectType == "WorkflowRun" -> "工作流通知"
                        subjectType == "RepositoryVulnerabilityAlert" -> "安全提醒"
                        else -> "GitHub 通知"
                    }
                    val body = "${n.subject?.title ?: ""}\n$repo  ·  ${com.luogen.ghpro.ui.reasonCn(n.reason)}"
                    val route = notificationRoute(n)
                    if (subjectType == "Release" && repo.isNotBlank()) {
                        val parts = repo.split("/")
                        val relId = Regex("/releases/(\\d+)").find(n.subject?.url ?: "")
                            ?.groupValues?.getOrNull(1)?.toLongOrNull()
                        val rel = if (parts.size == 2 && relId != null) {
                            runCatching { Api.release(parts[0], parts[1], relId) }.getOrNull()
                        } else null
                        if (rel != null) {
                            Notifier.release(ctx, repo, rel.tag_name, rel.name, rel.assets ?: emptyList(), route)
                        } else {
                            Notifier.generic(ctx, title, body, route)
                        }
                    } else {
                        Notifier.generic(ctx, title, body, route)
                    }
                    changes.add(Change(title, body, when {
                        subjectType == "PullRequest" && prMerged -> "pull_merge"
                        subjectType == "PullRequest" -> "pull_state"
                        else -> "notification"
                    }, route))
                }
            }
        } catch (e: Exception) { }

        // 1.5 已 Star 的仓库被作者删除 / 转私有 / 改名：真实复查，发现就通知，不是靠列表消失猜
        try {
            val starredNow = ArrayList<String>()
            var sp = 1
            while (sp <= 3) {
                val pg = runCatching { Api.starred(sp) }.getOrNull() ?: break
                starredNow.addAll(pg.items.map { it.full_name })
                if (!pg.hasNext) break
                sp++
            }
            val starredSet = starredNow.toSet()
            val old = Cfg.starredSnapshot
            if (old.isNotEmpty()) {
                (old - starredSet).filter { !Cfg.isRepoMuted(it) }.take(3).forEach { gone ->
                    val parts = gone.split("/")
                    if (parts.size == 2) {
                        val still = runCatching { Api.repo(parts[0], parts[1]) }
                        val e = still.exceptionOrNull()
                        if (still.isSuccess) {
                            val fresh = still.getOrNull()
                            if (fresh?.full_name != null && fresh.full_name != gone) {
                                val body = "$gone 已改名为 ${fresh.full_name}"
                                if (notify) Notifier.generic(ctx, "仓库改名了", body, repoRoute(fresh?.full_name ?: gone))
                                changes.add(Change("仓库改名了", body, "repo_rename", repoRoute(fresh?.full_name ?: gone)))
                            }
                        } else if (e != null && (e.message?.contains("404") == true || e.message?.contains("Not Found", true) == true)) {
                            val body = "$gone 已经不在 GitHub 上了（被作者删除或改成了私有仓库），已从 Star 列表移除"
                            if (notify && !Cfg.isRepoMuted(gone)) Notifier.generic(ctx, "Star 的仓库已消失", body)
                            if (!Cfg.isRepoMuted(gone)) changes.add(Change("Star 的仓库已消失", body, "repo_gone"))
                        }
                    }
                }
            }
            Cfg.starredSnapshot = starredSet
            Cfg.replaceStarred(starredSet)
        } catch (e: Exception) { }

        // 2. 我的仓库 Star / 更新时间变化
        try {
            val repos = ArrayList<Repo>()
            var mp = 1
            while (mp <= 3) {
                val pg = Api.myRepos(mp, 100)
                repos.addAll(pg.items)
                if (!pg.hasNext) break
                mp++
            }
            repos.forEach { r ->
                val key = r.full_name
                myReposNow.add(key)
                val old = repoSnap.getAsJsonObject(key)
                if (old != null) {
                    val oldStars = old.get("stars")?.asInt ?: 0
                    if (r.stargazers_count > oldStars) {
                        val body = "${r.full_name} 收到新的 Star，现在共 ${r.stargazers_count} 个"
                        if (!Cfg.isRepoMuted(r.full_name)) {
                            if (notify) Notifier.generic(ctx, "⭐ 新增 ${r.stargazers_count - oldStars} 个 Star", body, repoRoute(r.full_name))
                            changes.add(Change("⭐ 新增 Star", body, "star", repoRoute(r.full_name)))
                        }
                    }
                    val oldPushed = old.get("pushed")?.asString
                    if (oldPushed != null && r.pushed_at != null && oldPushed != r.pushed_at) {
                        val body = "${r.full_name} 有新提交推送到 ${r.default_branch ?: "main"}"
                        val route = commitRoute(r.full_name, r.default_branch)
                        if (!Cfg.isRepoMuted(r.full_name)) {
                            if (notify) Notifier.generic(ctx, "仓库有更新", body, route)
                            changes.add(Change("仓库有更新", body, "push", route))
                        }
                    }
                }
                val o = JsonObject().apply {
                    addProperty("stars", r.stargazers_count)
                    addProperty("pushed", r.pushed_at ?: "")
                    addProperty("updated", r.updated_at ?: "")
                }
                repoSnap.add(key, o)
            }
        } catch (e: Exception) { }

        // 3. 工作流：关注/Star 的仓库 + 自己参与（含别人仓库）的最近仓库
        fun handleRun(full: String, run: Run) {
            val key = run.id.toString()
            val old = runSnap.getAsJsonObject(key)
            val oldStatus = old?.get("status")?.asString
            val oldConclusion = old?.get("conclusion")?.asString?.takeIf { it != "null" }
            val finished = run.conclusion != null && run.status == "completed"
            val justFinished = oldStatus != null && oldConclusion.isNullOrBlank() && finished
            // 第一次同步/新仓库：只提醒最近完成的，避免把历史工作流全部弹一遍。
            val firstSeenFinished = old == null && finished && recentIso(run.created_at, 30 * 60_000L)
            if (justFinished || firstSeenFinished) {
                val ok = run.conclusion == "success"
                val title = if (ok) "✅ 工作流成功 · $full" else "❌ 工作流${cnConclusion(run.conclusion)} · $full"
                val body = "${run.display_title ?: run.name ?: ""}\n分支 ${run.head_branch ?: "-"} · 耗时 ${com.luogen.ghpro.ui.durationText(run.run_started_at, run.updated_at)}"
                val route = "run/$full/${run.id}"
                val muted = Cfg.isRepoMuted(full)
                if (notify && !muted) Notifier.actionDone(ctx, title, body, full, run.html_url, route)
                if (!muted) changes.add(Change(title, body, "run", route))
            } else if (old == null && (run.status == "in_progress" || run.status == "queued") && recentIso(run.created_at, 2 * 60 * 60_000L)) {
                val title = "🚀 工作流开始 · $full"
                val body = "${run.display_title ?: run.name ?: ""} 已开始运行"
                val route = "run/$full/${run.id}"
                if (!Cfg.isRepoMuted(full)) {
                    changes.add(Change(title, body, "run_start", route))
                    if (notify) Notifier.generic(ctx, title, body, route)
                }
            }
            val o2 = JsonObject().apply {
                addProperty("status", run.status)
                addProperty("conclusion", run.conclusion ?: "")
                addProperty("repo", full)
                addProperty("name", run.display_title ?: run.name ?: "")
            }
            runSnap.add(key, o2)
        }

        try {
            // 只轮询服务端真实订阅的仓库：Star、自己建的仓库、历史播种都不再自动当成“已关注”。
            // GitHub 的 /notifications 已覆盖所有仓库通知；这里只补订阅仓库的发行版/提交轮询。
            val watch = serverWatchNames.orEmpty().distinct().take(60)
            watch.forEach { full ->
                val parts = full.split("/")
                if (parts.size != 2) return@forEach
                val (o, r) = parts
                val muted = Cfg.isRepoMuted(full)
                val now = System.currentTimeMillis()
                val repoDue = now - (repoChecked.get(full)?.asLong ?: 0L) >= 120_000L
                if (!muted && repoDue) {
                    repoChecked.addProperty(full, now)
                    val repo = runCatching { Api.repo(o, r) }.getOrNull()
                    val rel = runCatching { Api.releases(o, r).items.firstOrNull { !it.draft } }.getOrNull()
                    val oldRepo = repoSnap.getAsJsonObject(full)
                    if (repo != null && oldRepo != null) {
                        val oldPushed = oldRepo.get("pushed")?.asString
                        if (oldPushed != null && repo.pushed_at != null && oldPushed != repo.pushed_at) {
                            val body = "$full 有新的代码推送到 ${repo.default_branch ?: "main"}"
                            val route = commitRoute(full, repo.default_branch)
                            Notifier.generic(ctx, "仓库代码有更新", body, route)
                            changes.add(Change("仓库代码有更新", body, "push", route))
                        }
                        val oldTag = oldRepo.get("release")?.asString
                        val newTag = rel?.tag_name ?: ""
                        val assets = rel?.assets?.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } } ?: emptyList()
                        if (oldTag != null && oldTag != newTag && newTag.isNotBlank()) {
                            val apk = assets.firstOrNull { it.endsWith(".apk", true) }
                            val fileLine = when {
                                apk != null -> "\nAPK 文件：$apk" + if (assets.size > 1) " 等 ${assets.size} 个文件" else ""
                                assets.isNotEmpty() -> "\n文件：" + assets.take(4).joinToString("、")
                                else -> ""
                            }
                            val body = "$full 发布了新发行版 $newTag$fileLine"
                            val route = rel?.id?.takeIf { it > 0 }?.let { "release/$full/$it" } ?: repoRoute(full)
                            if (route !in apiReleaseRoutes) {
                                Notifier.release(ctx, full, newTag, rel?.name, rel?.assets ?: emptyList(), route)
                            }
                            changes.add(Change("新发行版 $newTag · $full", body, "release", route))
                        } else {
                            // 同一个发行版后来又传附件：也要提醒，并点名新增文件
                            val hadAssets = oldRepo.has("assets")
                            val oldAssets = oldRepo.get("assets")?.asString ?: ""
                            val newAssets = assets.joinToString("|")
                            if (oldTag != null && hadAssets && newTag.isNotBlank() && oldAssets != newAssets) {
                                val oldList = oldAssets.split("|").filter { it.isNotBlank() }
                                val added = assets.filter { it !in oldList }
                                val body = if (added.isEmpty()) {
                                    "$full 的发行版 $newTag 附件有变化"
                                } else {
                                    "$full 的发行版 $newTag 新增文件：" + added.take(5).joinToString("、")
                                }
                                val route = rel?.id?.takeIf { it > 0 }?.let { "release/$full/$it" } ?: repoRoute(full)
                                if (route !in apiReleaseRoutes) {
                                    val addedAssets = rel?.assets?.filter { it.name in added } ?: emptyList()
                                    Notifier.release(ctx, full, newTag, rel?.name, rel?.assets ?: emptyList(), route,
                                        isAssetUpdate = true, addedAssets = addedAssets)
                                }
                                val title = if (added.isEmpty()) "发行版 $newTag 文件有变化 · $full"
                                else "发行版 $newTag 新增文件 · $full"
                                changes.add(Change(title, body, "release_asset", route))
                            }
                        }
                    }
                    if (repo != null) {
                        val o3 = JsonObject().apply {
                            addProperty("stars", repo.stargazers_count)
                            addProperty("pushed", repo.pushed_at ?: "")
                            addProperty("updated", repo.updated_at ?: "")
                            addProperty("release", rel?.tag_name ?: "")
                            addProperty("assets", rel?.assets?.mapNotNull { it.name }?.joinToString("|") ?: "")
                        }
                        repoSnap.add(full, o3)
                    }
                }
            }

            // 工作流只为服务端真实订阅的仓库轮询；自己的仓库也不再自动算关注。
            // 每轮最多查 10 个到期仓库，每个仓库 60 秒最多查一次，避免一次打几十个请求把加载拖慢。
            val nowRuns = System.currentTimeMillis()
            val dueRuns = watch.distinct()
                .filter { nowRuns - (runChecked.get(it)?.asLong ?: 0L) >= 60_000L }
                .take(10)
            dueRuns.forEach { full ->
                runChecked.addProperty(full, nowRuns)
                val p = full.split("/")
                if (p.size == 2) {
                    val runs = runCatching { Api.runs(p[0], p[1], 1).first }.getOrDefault(emptyList())
                    runs.take(3).forEach { run -> handleRun(full, run) }
                }
            }
        } catch (e: Exception) { }

        if (notify && changes.isNotEmpty()) {
            val stamp = System.currentTimeMillis()
            changes.forEachIndexed { i, c ->
                // GitHub notifications 接口已经会展示的事件不再重复记一份，避免通知页出现两条。
                // 发行版例外：接口没有覆盖时，轮询到的真实发行版也要能在通知页看到；接口已覆盖才跳过。
                if (c.kind in setOf("notification", "repo_gone", "repo_rename", "pull_state", "pull_merge")) return@forEachIndexed
                if ((c.kind == "release" || c.kind == "release_asset") && c.route != null && c.route in apiReleaseRoutes) return@forEachIndexed
                Cfg.addLocalNotice(LocalNotice(
                    id = "local:$stamp:$i:${c.kind}",
                    title = c.title,
                    body = c.body,
                    route = c.route,
                    kind = c.kind,
                    time = stamp
                ))
            }
        }
        // 不再把“我的仓库 + 已 Star 的仓库”播种成监听；通知必须基于服务端真实订阅。
        save(root)
        if (changes.isNotEmpty()) Bus.poke()
        return changes
    }

    private fun recentIso(iso: String?, windowMs: Long): Boolean {
        if (iso.isNullOrBlank()) return false
        return try {
            val sec = Duration.between(Instant.parse(iso), Instant.now()).seconds
            sec in 0..(windowMs / 1000L)
        } catch (e: Exception) { false }
    }

    fun cnConclusion(c: String?): String = when (c) {
        "success" -> "成功"
        "failure" -> "失败"
        "cancelled" -> "已取消"
        "timed_out" -> "超时"
        "skipped" -> "跳过"
        "startup_failure" -> "启动失败"
        else -> c ?: "完成"
    }

    suspend fun events(login: String): List<GhEvent> = try { Api.userEvents(login) } catch (e: Exception) { emptyList() }
}

object SyncScheduler {
    const val PERIODIC = "ghpro_periodic_sync"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        try {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req
            )
        } catch (e: Exception) { }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val token = Cfg.token
        if (token.isNullOrBlank()) return Result.success()
        return try {
            kotlinx.coroutines.runBlocking { SyncEngine.syncOnce(applicationContext, true) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

class ActionDoneReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val i = Intent(context, com.luogen.ghpro.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("open_repo", intent?.getStringExtra("repo"))
        }
        context?.startActivity(i)
    }
}
