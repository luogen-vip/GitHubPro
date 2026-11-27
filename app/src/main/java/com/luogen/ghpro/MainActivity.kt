package com.luogen.ghpro

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.ui.GHTheme
import com.luogen.ghpro.ui.NavRoot
import com.luogen.ghpro.ui.FilePreviewDialog
import com.luogen.ghpro.ui.PreviewBus
import com.luogen.ghpro.ui.DeepLinkBus
import com.luogen.ghpro.ui.NetworkPromptHost
import com.luogen.ghpro.data.NetworkTools
import com.luogen.ghpro.data.NetPrompts
import com.luogen.ghpro.data.NetPromptKind
import com.luogen.ghpro.download.Downloader

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun openRouteFromRepo(repo: String?): String? {
        if (!repo.isNullOrBlank()) return "repo/${repo.trim('/')}"
        return null
    }

    private fun routeFromUrl(url: String?): String? {
        val u = url ?: return null
        val run = Regex("github\\.com/([^/]+)/([^/]+)/actions/runs/(\\d+)").find(u)
        if (run != null) {
            val (o, r, id) = run.destructured
            return "run/$o/$r/$id"
        }
        val commit = Regex("github\\.com/([^/]+)/([^/]+)/commit/([0-9a-fA-F]+)").find(u)
        if (commit != null) {
            val (o, r, sha) = commit.destructured
            return "commit/$o/$r/$sha"
        }
        val release = Regex("github\\.com/([^/]+)/([^/]+)/releases/tag/([^/?#]+)").find(u)
        if (release != null) {
            val (o, r, tag) = release.destructured
            return "releaseTag/$o/$r/$tag"
        }
        val issue = Regex("github\\.com/([^/]+)/([^/]+)/(issues|pull)/(\\d+)").find(u)
        if (issue != null) {
            val (o, r, kind, n) = issue.destructured
            return "${if (kind == "issues") "issue" else "pr"}/$o/$r/$n"
        }
        return null
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val file = intent.getStringExtra("open_file")
        if (!file.isNullOrBlank()) {
            val f = File(file)
            if (f.exists()) PreviewBus.open(this, f)
            intent.removeExtra("open_file")
            return
        }
        val explicit = intent.getStringExtra("open_route")
        val route = when {
            !explicit.isNullOrBlank() -> explicit
            else -> routeFromUrl(intent.getStringExtra("open_url"))
                ?: openRouteFromRepo(intent.getStringExtra("open_repo"))
        }
        if (!route.isNullOrBlank()) DeepLinkBus.open(route)
        intent.removeExtra("open_route")
        intent.removeExtra("open_url")
        intent.removeExtra("open_repo")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) {
            try { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (e: Exception) { }
        }
        setContent {
            var themeMode by remember { mutableStateOf(Cfg.themeMode) }
            GHTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        LaunchedEffect(Unit) {
                            if (Cfg.mobileBrowseWarn) {
                                val info = NetworkTools.info(this@MainActivity)
                                if (info.kind == NetworkTools.Kind.CELLULAR) NetPrompts.showBrowse()
                            }
                        }
                        NavRoot(onThemeChange = { themeMode = Cfg.themeMode })
                        val preview by PreviewBus.target.collectAsState()
                        preview?.let { file ->
                            FilePreviewDialog(file) { PreviewBus.target.value = null }
                        }
                        NetworkPromptHost(onCancelDownload = { name -> Downloader.cancelDownload(name) })
                    }
                }
            }
        }
    }
}
