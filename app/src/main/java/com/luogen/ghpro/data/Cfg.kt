package com.luogen.ghpro.data

import android.content.Context
import android.content.SharedPreferences

object Cfg {
    private const val NAME = "ghpro_cfg"
    private lateinit var sp: SharedPreferences

    const val DEFAULT_MIRRORS = "https://ghfast.top/|https://gh-proxy.com/|https://ghproxy.net/|https://hub.gitmirror.com/|https://github.moeyy.xyz/|https://gh.llkk.cc/"

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (!sp.getBoolean("mirrors_seeded", false)) {
            sp.edit().putString("mirrors", DEFAULT_MIRRORS).putBoolean("mirrors_seeded", true).apply()
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
        get() = sp.getInt("threads", 8)
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

    fun mirrorOf(url: String): List<String> {
        val out = ArrayList<String>()
        if (!useMirror) return out
        mirrors.forEach { m -> if (m.isNotBlank()) out.add(m.trimEnd('/') + "/" + url) }
        return out
    }
}
