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
        val root = snapRoot()
        val repoSnap = root.getAsJsonObject("repos") ?: JsonObject().also { root.add("repos", it) }
        val runSnap = root.getAsJsonObject("runs") ?: JsonObject().also { root.add("runs", it) }
        val repoChecked = root.getAsJsonObject("repoChecked") ?: JsonObject().also { root.add("repoChecked", it) }
        val runChecked = root.getAsJsonObject("runChecked") ?: JsonObject().also { root.add("runChecked", it) }
        val myReposNow = HashSet<String>()

        // 1. 通知
        try {
            val list = Api.notifications(all = false)
            val seen = Cfg.lastSeenNotificationIds
            val firstRun = seen.isEmpty()
            val fresh = list.filter { it.id !in seen && (it.repository?.full_name?.let { f -> !Cfg.isRepoMuted(f) } ?: true) }
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
                    Notifier.generic(ctx, title, body, notificationRoute(n))
                    changes.add(Change(title, body, when {
                        subjectType == "PullRequest" && prMerged -> "pull_merge"
                        subjectType == "PullRequest" -> "pull_state"
                        else -> "notification"
                    }, notificationRoute(n)))
                }
            }
        } catch (e: Exception) { }

        // 1.5 已 Star 的仓库被作者删除 / 转私有 / 改名：真实复查，发现就通知，不是靠列表消失猜
        try {
            val starredNow = Api.starred(1).items.map { it.full_name }.toSet()
            val old = Cfg.starredSnapshot
            if (old.isNotEmpty()) {
                (old - starredNow).take(3).forEach { gone ->
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
                            if (notify) Notifier.generic(ctx, "Star 的仓库已消失", body)
                            changes.add(Change("Star 的仓库已消失", body, "repo_gone"))
                        }
                    }
                }
            }
            Cfg.starredSnapshot = starredNow
            Cfg.replaceStarred(starredNow)
        } catch (e: Exception) { }

        // 2. 我的仓库 Star / 更新时间变化
        try {
            val repos = Api.myRepos(1, 100).items
            repos.forEach { r ->
                val key = r.full_name
                myReposNow.add(key)
                val old = repoSnap.getAsJsonObject(key)
                if (old != null) {
                    val oldStars = old.get("stars")?.asInt ?: 0
                    if (r.stargazers_count > oldStars) {
                        val body = "${r.full_name} 收到新的 Star，现在共 ${r.stargazers_count} 个"
                        if (notify) Notifier.generic(ctx, "⭐ 新增 ${r.stargazers_count - oldStars} 个 Star", body, repoRoute(r.full_name))
                        changes.add(Change("⭐ 新增 Star", body, "star", repoRoute(r.full_name)))
                    }
                    val oldPushed = old.get("pushed")?.asString
                    if (oldPushed != null && r.pushed_at != null && oldPushed != r.pushed_at) {
                        val body = "${r.full_name} 有新提交推送到 ${r.default_branch ?: "main"}"
                        val route = commitRoute(r.full_name, r.default_branch)
                        if (notify) Notifier.generic(ctx, "仓库有更新", body, route)
                        changes.add(Change("仓库有更新", body, "push", route))
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
                changes.add(Change(title, body, "run", route))
            } else if (old == null && (run.status == "in_progress" || run.status == "queued") && recentIso(run.created_at, 2 * 60 * 60_000L)) {
                val title = "🚀 工作流开始 · $full"
                val body = "${run.display_title ?: run.name ?: ""} 已开始运行"
                val route = "run/$full/${run.id}"
                changes.add(Change(title, body, "run_start", route))
                if (notify && !Cfg.isRepoMuted(full)) Notifier.generic(ctx, title, body, route)
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
            // 关注的仓库 + 已 Star 的仓库都要收到新发行版/新提交提醒
            val watch = (Cfg.watchRepos + Cfg.starredSnapshot).take(20)
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
                    val rel = runCatching { Api.releases(o, r).items.firstOrNull() }.getOrNull()
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
                            Notifier.generic(ctx, "新发行版 $newTag", body, route)
                            changes.add(Change("新发行版", body, "release", route))
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
                                Notifier.generic(ctx, "发行版附件更新", body, route)
                                changes.add(Change("发行版附件更新", body, "release_asset", route))
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

            // 工作流按仓库轮询：自己参与（含 collaborator/组织仓库）的仓库 + 关注仓库。
            // 每轮最多查 10 个到期仓库，每个仓库 60 秒最多查一次，避免一次打几十个请求把加载拖慢。
            val nowRuns = System.currentTimeMillis()
            val dueRuns = (watch + myReposNow).distinct()
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
                if (c.kind in setOf("notification", "repo_gone", "repo_rename", "release", "release_asset", "pull_state", "pull_merge")) return@forEachIndexed
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
