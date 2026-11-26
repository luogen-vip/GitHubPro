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
import com.luogen.ghpro.data.Run
import java.util.concurrent.TimeUnit

object SyncEngine {

    data class Change(val title: String, val body: String, val kind: String)

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
    suspend fun syncOnce(ctx: Context, notify: Boolean = true): List<Change> {
        val token = Cfg.token ?: return emptyList()
        Api.token = token
        val changes = ArrayList<Change>()
        val root = snapRoot()
        val repoSnap = root.getAsJsonObject("repos") ?: JsonObject().also { root.add("repos", it) }
        val runSnap = root.getAsJsonObject("runs") ?: JsonObject().also { root.add("runs", it) }

        // 1. 通知
        try {
            val list = Api.notifications(all = false)
            val seen = Cfg.lastSeenNotificationIds
            val fresh = list.filter { it.id !in seen && (it.repository?.full_name?.let { f -> !Cfg.isRepoMuted(f) } ?: true) }
            val newSeen = (seen + list.map { it.id }).toList().takeLast(300).toSet()
            Cfg.lastSeenNotificationIds = newSeen
            if (notify && fresh.isNotEmpty()) {
                fresh.take(5).forEach { n ->
                    val title = when (n.subject?.type) {
                        "Issue" -> "新议题"
                        "PullRequest" -> "新 Pull Request"
                        "Discussion" -> "新讨论"
                        "Release" -> "新发行版"
                        "WorkflowRun" -> "工作流通知"
                        "RepositoryVulnerabilityAlert" -> "安全提醒"
                        else -> "GitHub 通知"
                    }
                    val repo = n.repository?.full_name ?: ""
                    val body = "${n.subject?.title ?: ""}\n$repo  ·  ${n.reason ?: ""}"
                    Notifier.generic(ctx, title, body)
                    changes.add(Change(title, body, "notification"))
                }
            }
        } catch (e: Exception) { }

        // 2. 我的仓库 Star / 更新时间变化
        try {
            val repos = Api.myRepos(1, 100).items
            repos.forEach { r ->
                val key = r.full_name
                val old = repoSnap.getAsJsonObject(key)
                if (old != null) {
                    val oldStars = old.get("stars")?.asInt ?: 0
                    if (r.stargazers_count > oldStars) {
                        val body = "${r.full_name} 收到新的 Star，现在共 ${r.stargazers_count} 个"
                        if (notify) Notifier.generic(ctx, "⭐ 新增 ${r.stargazers_count - oldStars} 个 Star", body)
                        changes.add(Change("⭐ 新增 Star", body, "star"))
                    }
                    val oldPushed = old.get("pushed")?.asString
                    if (oldPushed != null && r.pushed_at != null && oldPushed != r.pushed_at) {
                        val body = "${r.full_name} 有新提交推送到 ${r.default_branch ?: "main"}"
                        if (notify) Notifier.generic(ctx, "仓库有更新", body)
                        changes.add(Change("仓库有更新", body, "push"))
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

        // 3. 关注仓库的工作流运行状态
        try {
            val watch = Cfg.watchRepos
            watch.take(10).forEach { full ->
                val parts = full.split("/")
                if (parts.size != 2) return@forEach
                val (o, r) = parts
                val muted = Cfg.isRepoMuted(full)
                if (!muted) {
                    val repo = runCatching { Api.repo(o, r) }.getOrNull()
                    val rel = runCatching { Api.releases(o, r).items.firstOrNull() }.getOrNull()
                    val oldRepo = repoSnap.getAsJsonObject(full)
                    if (repo != null && oldRepo != null) {
                        val oldPushed = oldRepo.get("pushed")?.asString
                        if (oldPushed != null && repo.pushed_at != null && oldPushed != repo.pushed_at) {
                            val body = "$full 有新的代码推送到 ${repo.default_branch ?: "main"}"
                            Notifier.generic(ctx, "仓库代码有更新", body)
                            changes.add(Change("仓库代码有更新", body, "push"))
                        }
                        val oldTag = oldRepo.get("release")?.asString
                        val newTag = rel?.tag_name ?: ""
                        if (oldTag != null && oldTag != newTag && newTag.isNotBlank()) {
                            val body = "$full 发布了新发行版 $newTag"
                            Notifier.generic(ctx, "新发行版 $newTag", body)
                            changes.add(Change("新发行版", body, "release"))
                        }
                    }
                    if (repo != null) {
                        val o3 = JsonObject().apply {
                            addProperty("stars", repo.stargazers_count)
                            addProperty("pushed", repo.pushed_at ?: "")
                            addProperty("updated", repo.updated_at ?: "")
                            addProperty("release", rel?.tag_name ?: "")
                        }
                        repoSnap.add(full, o3)
                    }
                }
                val runs: List<Run> = try { Api.runs(o, r, 1).first } catch (e: Exception) { emptyList() }
                runs.forEach { run ->
                    val key = run.id.toString()
                    val old = runSnap.getAsJsonObject(key)
                    val oldStatus = old?.get("status")?.asString
                    val oldConclusion = old?.get("conclusion")?.asString?.takeIf { it != "null" }
                    if (oldStatus != null && oldConclusion.isNullOrBlank() && (run.conclusion != null)) {
                        val ok = run.conclusion == "success"
                        val title = if (ok) "✅ 工作流成功" else "❌ 工作流${cnConclusion(run.conclusion)}"
                        val body = "${o}/${r} · ${run.display_title ?: run.name ?: ""}\n分支 ${run.head_branch ?: "-"} · 耗时 ${com.luogen.ghpro.ui.durationText(run.run_started_at, run.updated_at)}"
                        if (notify && !muted) Notifier.actionDone(ctx, title, body, "$o/$r", run.html_url)
                        changes.add(Change(title, body, "run"))
                    } else if (old == null) {
                        if (run.status == "in_progress" || run.status == "queued") {
                            val body = "${o}/${r} · ${run.display_title ?: run.name ?: ""} 已开始运行"
                            changes.add(Change("工作流开始", body, "run_start"))
                            if (notify && !muted) Notifier.generic(ctx, "🚀 工作流已开始", body)
                        }
                    }
                    val o2 = JsonObject().apply {
                        addProperty("status", run.status)
                        addProperty("conclusion", run.conclusion ?: "")
                        addProperty("repo", "$o/$r")
                        addProperty("name", run.display_title ?: run.name ?: "")
                    }
                    runSnap.add(key, o2)
                }
            }
        } catch (e: Exception) { }

        save(root)
        if (changes.isNotEmpty()) Bus.poke()
        return changes
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
