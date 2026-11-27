package com.luogen.ghpro.ui

import com.luogen.ghpro.data.CodeItem
import com.luogen.ghpro.data.GitCommit
import com.luogen.ghpro.data.Issue
import com.luogen.ghpro.data.PullRequest
import com.luogen.ghpro.data.Release
import com.luogen.ghpro.data.Repo
import com.luogen.ghpro.data.Run
import com.luogen.ghpro.data.Job
import com.luogen.ghpro.data.Artifact
import com.luogen.ghpro.data.User
import com.luogen.ghpro.data.Discussion
import com.luogen.ghpro.data.Notification

/**
 * 页面级内存缓存：从详情页返回、切到别的 App 再回来、横竖屏切换时，
 * 列表数据与滚动位置都还在，不会再重新请求、也不会跳回顶部。
 * 进程被系统回收时缓存自然失效，最多重新加载一次。
 */
data class HomeSnap(val repos: List<Repo>, val page: Int, val hasNext: Boolean)

data class NotifSnap(val list: List<Notification>, val index: Int, val offset: Int)
data class FeaturedSnap(val list: List<Repo>, val total: Long, val lastSync: Long, val index: Int, val offset: Int)

private fun <K, V> lruCache(max: Int) = object : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
}

object UiCache {

    // ---------------- 通知 ----------------
    var notifAll = false
    val notifSnaps = HashMap<Boolean, NotifSnap>()

    // ---------------- 精选仓库 ----------------
    var featuredSort = "stars"
    val featuredSnaps = HashMap<String, FeaturedSnap>()

    // ---------------- 正在删除 ----------------
    /** "owner/name|branch" -> 正在提交删除；自动刷新先让路，避免旧列表把文件“闪回来” */
    val deletingCode = HashSet<String>()

    // ---------------- 订阅管理 ----------------
    var subsTab = 0
    var subsSort = "time_desc"
    var subsScrollIndex = 0
    var subsScrollOffset = 0

    // ---------------- 首页 ----------------
    var homeLoaded = false
    var homeTab = 0
    var homeSort = "updated"
    var homeMe: User? = null
    var homeRepos: List<Repo> = emptyList()
    val homeSnaps = HashMap<String, HomeSnap>()   // "tab|sort" -> 上次列表，切 tab 秒显示
    var homePage = 1
    var homeHasNext = false
    var homeIndex = 0
    var homeOffset = 0

    fun invalidateHome() {
        homeLoaded = false
        homeRepos = emptyList()
        homeSnaps.clear()
        homePage = 1
        homeHasNext = false
        homeIndex = 0
        homeOffset = 0
    }

    // ---------------- 仓库详情 ----------------
    val repoData = lruCache<String, Repo>(40)
    val repoLangs = lruCache<String, Map<String, Long>>(40)
    val repoStarred = HashMap<String, Boolean>()
    val repoWatching = HashMap<String, Boolean>()
    val repoTab = HashMap<String, Int>()

    fun invalidateRepo(full: String) {
        repoData.remove(full)
        repoLangs.remove(full)
        repoStarred.remove(full)
        repoWatching.remove(full)
    }

    // ---------------- 用户主页 ----------------
    val userData = lruCache<String, User>(30)
    val userRepos = lruCache<String, List<Repo>>(30)
    val userTab = HashMap<String, Int>()
    val userIndex = HashMap<String, Int>()

    // ---------------- 代码浏览 ----------------
    val codePath = HashMap<String, String>()          // owner/name|branch -> 当前目录
    val codeListing = lruCache<String, com.luogen.ghpro.data.DirListing>(24) // owner/name|branch|path
    val fileContent = lruCache<String, String>(16)       // owner/name|branch|path
    val codeScroll = HashMap<String, Int>()
    val codeScrollOffset = HashMap<String, Int>()

    // ---------------- 仓库各 Tab 列表 ----------------
    val repoRuns = lruCache<String, List<Run>>(24)
    val runData = lruCache<Long, Run>(80)
    val runJobs = lruCache<Long, List<Job>>(50)
    val runArtifacts = lruCache<Long, List<Artifact>>(50)
    val repoIssues = lruCache<String, List<Issue>>(24)
    val issueOverrides = HashMap<String, Pair<Issue, Long>>()   // owner/name#number -> 本地乐观状态（10 秒内不被服务端旧数据覆盖）
    val repoPulls = lruCache<String, List<PullRequest>>(24)
    val repoReleases = lruCache<String, List<Release>>(24)
    val repoContributors = lruCache<String, List<User>>(24)
    val repoDiscussions = lruCache<String, List<Discussion>>(24)
    var pendingRelease: Long? = null
    var pendingReleaseTag: String? = null
    val repoCommits = HashMap<String, List<GitCommit>>()
    val repoIndex = HashMap<String, Int>()   // "tab|repo" -> 滚动位置

    // ---------------- 搜索 ----------------
    var searchRan = false
    var searchKey = ""
    var searchQuery = ""
    var searchType = 0
    var searchLang: String? = null
    var searchSort: String? = null
    var searchRepos: List<Repo> = emptyList()
    var searchUsers: List<User> = emptyList()
    var searchCodes: List<CodeItem> = emptyList()
    var searchIssues: List<Issue> = emptyList()
    var searchTotal: Long = 0
    var searchPage = 1
    var searchHasNext = false
    var searchIndex = 0
    var searchOffset = 0
}
