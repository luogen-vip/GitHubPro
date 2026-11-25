package com.luogen.ghpro.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.luogen.ghpro.data.Cfg

@Composable
fun NavRoot(onThemeChange: () -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = if (Cfg.token.isNullOrBlank()) "login" else "home") {
        composable("login") {
            LoginScreen { nav.navigate("home") { popUpTo(0) { inclusive = true } } }
        }
        composable("home") { HomeScreen(nav) }
        composable("settings") { SettingsScreen(nav, onThemeChange) }
        composable("search") { SearchScreen(nav) }
        composable("notifications") { NotificationsScreen(nav) }
        composable("create") { CreateRepoScreen(nav) }
        composable("repo/{owner}/{repo}") { e ->
            RepoScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "")
        }
        composable("edit/{owner}/{repo}") { e ->
            EditRepoScreen(nav, e.arguments?.getString("owner") ?: "", e.arguments?.getString("repo") ?: "")
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
                java.net.URLDecoder.decode(e.arguments?.getString("branch") ?: "main", "UTF-8"),
                java.net.URLDecoder.decode(e.arguments?.getString("path") ?: "", "UTF-8"),
                (e.arguments?.getString("isNew") ?: "0") == "1"
            )
        }
    }
}
