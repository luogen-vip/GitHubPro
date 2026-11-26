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

/**
 * 页面级内存缓存：从详情页返回、切到别的 App 再回来、横竖屏切换时，
 * 列表数据与滚动位置都还在，不会再重新请求、也不会跳回顶部。
 * 进程被系统回收时缓存自然失效，最多重新加载一次。
 */
object UiCache {

    // ---------------- 首页 ----------------
    var homeLoaded = false
    var homeTab = 0
    var homeSort = "updated"
    var homeMe: User? = null
    var homeRepos: List<Repo> = emptyList()
    var homePage = 1
    var homeHasNext = false
    var homeIndex = 0
    var homeOffset = 0

    fun invalidateHome() {
        homeLoaded = false
        homeRepos = emptyList()
        homePage = 1
        homeHasNext = false
        homeIndex = 0
        homeOffset = 0
    }

    // ---------------- 仓库详情 ----------------
    val repoData = HashMap<String, Repo>()
    val repoLangs = HashMap<String, Map<String, Long>>()
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
    val userData = HashMap<String, User>()
    val userRepos = HashMap<String, List<Repo>>()
    val userTab = HashMap<String, Int>()
    val userIndex = HashMap<String, Int>()

    // ---------------- 代码浏览 ----------------
    val codePath = HashMap<String, String>()          // owner/name|branch -> 当前目录
    val codeListing = HashMap<String, com.luogen.ghpro.data.DirListing>() // owner/name|branch|path
    val fileContent = HashMap<String, String>()       // owner/name|branch|path
    val codeScroll = HashMap<String, Int>()

    // ---------------- 仓库各 Tab 列表 ----------------
    val repoRuns = HashMap<String, List<Run>>()
    val runData = HashMap<Long, Run>()
    val runJobs = HashMap<Long, List<Job>>()
    val runArtifacts = HashMap<Long, List<Artifact>>()
    val repoIssues = HashMap<String, List<Issue>>()
    val repoPulls = HashMap<String, List<PullRequest>>()
    val repoReleases = HashMap<String, List<Release>>()
    val repoContributors = HashMap<String, List<User>>()
    val repoDiscussions = HashMap<String, List<Discussion>>()
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
