package com.luogen.ghpro.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.luogen.ghpro.data.Cfg

// Navigation 可能已经解码一次；文件名里的 + 会被 URLDecoder 当空格，
// 含 % 的名字则会直接抛异常。这里先保护 +，失败就保留原值，避免“文件不存在”。
private fun navArgDecode(raw: String): String = try {
    java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
} catch (e: Exception) { raw }

@Composable
fun NavRoot(onThemeChange: () -> Unit) {
    val nav = rememberNavController()
    val pendingRoute by DeepLinkBus.target.collectAsState()
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        DeepLinkBus.target.value = null
        // 通知点击统一在这里跳：仓库/提交/发行版/工作流/议题/PR；不重置已有返回栈
        runCatching { nav.navigate(route) { launchSingleTop = true } }
    }
    NavHost(nav, startDestination = if (Cfg.token.isNullOrBlank()) "login" else "home") {
        composable("login") {
            LoginScreen { nav.navigate("home") { popUpTo("login") { inclusive = true } } }
        }
        composable("home") { HomeScreen(nav) }
        composable("settings") { SettingsScreen(nav, onThemeChange) }
        composable("search") { SearchScreen(nav) }
        composable("featured") { FeaturedScreen(nav) }
        composable("downloads") { DownloadsScreen(nav) }
        composable("favorites") { FavoritesScreen(nav) }
        composable("more") { UserMoreScreen(nav) }
        composable("subs") { SubscriptionsScreen(nav) }
        composable("more/{owner}/{repo}") { e ->
            RepoMoreScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "")
        }
        composable("notifications") { NotificationsScreen(nav) }
        composable("create") { CreateRepoScreen(nav) }
        composable("repo/{owner}/{repo}") { e ->
            RepoScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "")
        }
        composable("release/{owner}/{repo}/{id}") { e ->
            val o = e.arguments?.getString("owner") ?: ""
            val r = e.arguments?.getString("repo") ?: ""
            val id = e.arguments?.getString("id")?.toLongOrNull() ?: 0L
            if (id > 0) UiCache.pendingRelease = id
            UiCache.repoTab["$o/$r"] = 5
            RepoScreen(nav, o, r)
        }
        composable("releaseTag/{owner}/{repo}/{tag}") { e ->
            val o = e.arguments?.getString("owner") ?: ""
            val r = e.arguments?.getString("repo") ?: ""
            UiCache.pendingReleaseTag = e.arguments?.getString("tag")
            UiCache.repoTab["$o/$r"] = 5
            RepoScreen(nav, o, r)
        }
        composable("edit/{owner}/{repo}") { e ->
            EditRepoScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "")
        }
        // 首页的“关注者/正在关注”要直接打开对应名单；之前导航到 user/{login}/0，
        // 但这里只有 user/{login}，多出来的路径段匹配不到，点一下会直接崩溃。
        composable("user/{login}/{people}") { e ->
            UserScreen(nav,
                e.arguments?.getString("login") ?: "",
                e.arguments?.getString("people")?.toIntOrNull() ?: -1)
        }
        composable("user/{login}") { e ->
            UserScreen(nav, e.arguments?.getString("login") ?: "")
        }
        composable("issue/{owner}/{repo}/{number}") { e ->
            IssueDetailScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "",
                e.arguments?.getString("number")?.toIntOrNull() ?: 0)
        }
        composable("newissue/{owner}/{repo}") { e ->
            NewIssueScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "")
        }
        composable("pr/{owner}/{repo}/{number}") { e ->
            PullDetailScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "",
                e.arguments?.getString("number")?.toIntOrNull() ?: 0)
        }
        composable("commit/{owner}/{repo}/{sha}") { e ->
            CommitDetailScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "",
                e.arguments?.getString("sha") ?: "")
        }
        composable("run/{owner}/{repo}/{runId}") { e ->
            RunDetailScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "",
                e.arguments?.getString("runId")?.toLongOrNull() ?: 0L)
        }
        composable("discussion/{owner}/{repo}/{number}") { e ->
            DiscussionDetailScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "",
                e.arguments?.getString("number")?.toIntOrNull() ?: 0)
        }
        composable(
            route = "file?owner={owner}&repo={repo}&branch={branch}&path={path}&isNew={isNew}",
            arguments = listOf(
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
                navArgument("isNew") { type = NavType.StringType; defaultValue = "0" }
            )
        ) { e ->
            FileScreen(
                nav,
                e.arguments?.getString("owner") ?: "",
                e.arguments?.getString("repo") ?: "",
                navArgDecode(e.arguments?.getString("branch") ?: "main"),
                navArgDecode(e.arguments?.getString("path") ?: ""),
                (e.arguments?.getString("isNew") ?: "0") == "1"
            )
        }
    }
}
