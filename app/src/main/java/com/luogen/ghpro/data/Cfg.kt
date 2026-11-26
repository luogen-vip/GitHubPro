package com.luogen.ghpro.data

import android.content.Context
import android.content.SharedPreferences

object Cfg {
    private const val NAME = "ghpro_cfg"
    private lateinit var sp: SharedPreferences

    const val DEFAULT_MIRRORS = "https://gh-proxy.com/|https://ghfast.top/|https://ghproxy.net/"

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (!sp.getBoolean("mirrors_seeded", false)) {
            sp.edit().putString("mirrors", DEFAULT_MIRRORS).putBoolean("mirrors_seeded", true).apply()
        }
        // v1.0.9 及更早版本把“纯浏览”也记成最近使用；升级到 1.0.11 清一次旧活动时间，
        // 之后只有新建/跑任务/发版/上传/Star/关注/议题等真实操作才会排到最上面。
        if (!sp.getBoolean("activity_clean_v11", false)) {
            sp.edit().remove("repo_activity").putBoolean("activity_clean_v11", true).apply()
        }
        // 旧默认镜像里有 3 个已失效（gitmirror / moeyy / llkk），只清理这几个，用户自己加的不动
        if (!sp.getBoolean("mirrors_clean_v12", false)) {
            val dead = setOf("https://hub.gitmirror.com/", "https://github.moeyy.xyz/", "https://gh.llkk.cc/")
            val oldAll = (sp.getString("mirrors", DEFAULT_MIRRORS) ?: "").split("|")
            val known = setOf("https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/") + dead
            val cur = oldAll.filter { it.isNotBlank() && it.trimEnd('/') + "/" !in dead }
            // 如果只是旧的 6 个默认镜像，就按实测最快的顺序重置；用户自己加的镜像保持原样
            val value = if (cur.isNotEmpty() && cur.all { it.trimEnd('/') + "/" in known }) DEFAULT_MIRRORS
                        else cur.ifEmpty { DEFAULT_MIRRORS.split("|") }.joinToString("|")
            sp.edit().putString("mirrors", value)
                .putBoolean("mirrors_clean_v12", true).apply()
        }
        // 1.0.12 起默认 64 线程（可调到 128），多线程和加速镜像默认开启
        if (!sp.getBoolean("download_cfg_v12", false)) {
            sp.edit().putInt("threads", 64).putBoolean("mt", true).putBoolean("use_mirror", true)
                .putBoolean("download_cfg_v12", true).apply()
        }
    }

    var token: String?
        get() = sp.getString("token", null)
        set(v) {
            sp.edit().putString("token", v).apply()
            Api.token = v
        }

    var login: String?
        get() = sp.getString("login", null)
        set(v) { sp.edit().putString("login", v).apply() }

    /** 已 Star / 正在关注 的仓库，本地持久化：进详情页时星星状态不会先闪成未 Star。 */
    private fun starSet(): MutableSet<String> =
        (sp.getStringSet("starred_repos", emptySet()) ?: emptySet()).toMutableSet()

    fun isStarredCached(full: String): Boolean = starSet().contains(full)
    fun replaceStarred(full: Set<String>) = sp.edit().putStringSet("starred_repos", full).apply()

    /** 上次同步时真实 Star 名单；仓库被作者删除/转私有后，下一次同步就能发现并通知。 */
    var starredSnapshot: Set<String>
        get() = sp.getStringSet("starred_snapshot_v12", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("starred_snapshot_v12", v).apply()

    fun setStarredCached(full: String, on: Boolean) {
        val s = starSet()
        if (on) s.add(full) else s.remove(full)
        sp.edit().putStringSet("starred_repos", s).apply()
    }

    var userName: String?
        get() = sp.getString("uname", null)
        set(v) { sp.edit().putString("uname", v).apply() }

    var avatar: String?
        get() = sp.getString("avatar", null)
        set(v) { sp.edit().putString("avatar", v).apply() }

    var oauthClientId: String?
        get() = sp.getString("oauth_cid", null)
        set(v) { sp.edit().putString("oauth_cid", v).apply() }

    var mirrors: MutableSet<String>
        get() = (sp.getString("mirrors", DEFAULT_MIRRORS) ?: "").split("|").filter { it.isNotBlank() }.toMutableSet()
        set(v) { sp.edit().putString("mirrors", v.joinToString("|")).apply() }

    var useMirror: Boolean
        get() = sp.getBoolean("use_mirror", true)
        set(v) { sp.edit().putBoolean("use_mirror", v).apply() }

    var multiThread: Boolean
        get() = sp.getBoolean("mt", true)
        set(v) { sp.edit().putBoolean("mt", v).apply() }

    var downThreads: Int
        get() = sp.getInt("threads", 64)
        set(v) { sp.edit().putInt("threads", v).apply() }

    var pollSeconds: Int
        get() = sp.getInt("poll_s", 20)
        set(v) { sp.edit().putInt("poll_s", v).apply() }

    /** 单独关掉通知的仓库，默认全部开启。 */
    private fun mutedSet(): MutableSet<String> =
        (sp.getStringSet("muted_repos", emptySet()) ?: emptySet()).toMutableSet()

    fun isRepoMuted(full: String): Boolean = mutedSet().contains(full)

    fun setRepoMuted(full: String, muted: Boolean) {
        val s = mutedSet()
        if (muted) s.add(full) else s.remove(full)
        sp.edit().putStringSet("muted_repos", s).apply()
    }

    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) { sp.edit().putBoolean("notify", v).apply() }

    var watchEnabled: Boolean
        get() = sp.getBoolean("watch_enabled", false)
        set(v) { sp.edit().putBoolean("watch_enabled", v).apply() }

    var autoRefresh: Boolean
        get() = sp.getBoolean("auto_refresh", true)
        set(v) { sp.edit().putBoolean("auto_refresh", v).apply() }

    var themeMode: Int   // 0 跟随系统 1 深色 2 浅色
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

    var lastSeenNotificationIds: Set<String>
        get() = sp.getStringSet("seen_notif", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("seen_notif", v.toSet()).apply() }

    var snapshots: String
        get() = sp.getString("snapshots", "{}") ?: "{}"
        set(v) { sp.edit().putString("snapshots", v).apply() }

    var watchRepos: MutableSet<String>
        get() = sp.getStringSet("watch_repos", emptySet())?.toMutableSet() ?: mutableSetOf()
        set(v) { sp.edit().putStringSet("watch_repos", v.toSet()).apply() }


    var crashCount: Int
        get() = sp.getInt("crash_count", 0)
        set(v) { sp.edit().putInt("crash_count", v).apply() }

    var safeMode: Boolean
        get() = sp.getBoolean("safe_mode", false)
        set(v) { sp.edit().putBoolean("safe_mode", v).apply() }

    var crashRepo: String
        get() = sp.getString("crash_repo", "luogen-vip/GitHubPro") ?: "luogen-vip/GitHubPro"
        set(v) { sp.edit().putString("crash_repo", v).apply() }


    var recentRepos: List<String>
        get() = (sp.getString("recent_repos", "") ?: "").split("|").filter { it.isNotBlank() }
        set(v) { sp.edit().putString("recent_repos", v.take(20).joinToString("|")).apply() }

    fun touchRepo(full: String) {
        val cur = recentRepos
        val next = (listOf(full) + cur.filter { it != full }).take(20)
        recentRepos = next
        // 只有真实操作才记时间；单纯打开浏览不参与排序
        val now = System.currentTimeMillis()
        val old = (sp.getString("repo_activity", "") ?: "").split("|").filter { it.isNotBlank() }
        val mine = "$full@$now"
        sp.edit().putString("repo_activity",
            (listOf(mine) + old.filter { !it.startsWith("$full@") }).take(40).joinToString("|")).apply()
        Bus.activityChanged()
    }

    fun activityAt(full: String): Long {
        val raw = (sp.getString("repo_activity", "") ?: "").split("|").firstOrNull { it.startsWith("$full@") } ?: return 0L
        return raw.substringAfterLast('@').toLongOrNull() ?: 0L
    }

    fun clearAll() {
        val keepMirror = sp.getString("mirrors", DEFAULT_MIRRORS)
        val cid = oauthClientId
        sp.edit().clear().apply()
        sp.edit().putString("mirrors", keepMirror ?: DEFAULT_MIRRORS)
            .putString("oauth_cid", cid)
            .putBoolean("mirrors_seeded", true).apply()
        Api.token = null
    }

    /** 上次真实下载成功的镜像排到第一，避免每次都先撞上刚好变慢的那一个。 */
    var preferredMirror: String
        get() = sp.getString("preferred_mirror", "") ?: ""
        set(v) = sp.edit().putString("preferred_mirror", v).apply()

    fun mirrorOf(url: String): List<String> {
        val out = ArrayList<String>()
        if (!useMirror) return out
        val pref = preferredMirror.trimEnd('/')
        mirrors.sortedByDescending { it.trimEnd('/') == pref && pref.isNotBlank() }
            .forEach { m -> if (m.isNotBlank()) out.add(m.trimEnd('/') + "/" + url) }
        return out
    }
}
