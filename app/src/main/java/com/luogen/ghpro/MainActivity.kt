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
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Bus
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.UserStatus
import com.luogen.ghpro.ui.GHTheme
import com.luogen.ghpro.ui.NavRoot
import com.luogen.ghpro.ui.FilePreviewDialog
import com.luogen.ghpro.ui.PreviewBus
import com.luogen.ghpro.ui.DeepLinkBus
import com.luogen.ghpro.ui.NetworkPromptHost
import com.luogen.ghpro.data.NetworkTools
import com.luogen.ghpro.data.NetPrompts
import com.luogen.ghpro.download.Downloader
import com.luogen.ghpro.sync.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statusJob: Job? = null

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
            val opened = PreviewBus.open(this, f)
            if (!opened) {
                Notifier.clearDownload(this, f.name)
                android.widget.Toast.makeText(this, "文件已被删除或下载已取消，无法打开", android.widget.Toast.LENGTH_LONG).show()
            }
            intent.removeExtra("open_file")
            return
        }
        val dlUrl = intent.getStringExtra("notif_download_url")
        if (!dlUrl.isNullOrBlank()) {
            val name = intent.getStringExtra("notif_download_name")?.takeIf { it.isNotBlank() } ?: "download.bin"
            val repo = intent.getStringExtra("notif_download_repo")
            val release = intent.getStringExtra("notif_download_release")
            Downloader.startDownload(
                this, dlUrl, name, Cfg.token, useMirror = true,
                source = "发行版通知", repo = repo, release = release
            )
            DeepLinkBus.open("downloads")
            android.widget.Toast.makeText(this, "已开始下载 $name", android.widget.Toast.LENGTH_SHORT).show()
            intent.removeExtra("notif_download_url")
            intent.removeExtra("notif_download_name")
            intent.removeExtra("notif_download_repo")
            intent.removeExtra("notif_download_release")
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

    override fun onStart() {
        super.onStart()
        // 只要 App 在前台就轻量轮询“我的状态”：外部 GitHub 改/清除后，首页和设置最多约 2 秒内同步。
        statusJob?.cancel()
        statusJob = statusScope.launch {
            while (isActive) {
                if (!Cfg.token.isNullOrBlank()) {
                    runCatching { Api.myStatus() }.onSuccess { fresh ->
                        val current = UserStatus(
                            Cfg.statusEmoji, Cfg.statusMessage, Cfg.statusBusy, Cfg.statusExpiresAt,
                            Cfg.statusOrgId, Cfg.statusOrgLogin, Cfg.statusOrgName
                        )
                        if (fresh != current) {
                            Cfg.saveStatus(fresh)
                            Bus.statusChanged()
                        }
                    }
                }
                delay(2_000L)
            }
        }
    }

    override fun onStop() {
        statusJob?.cancel()
        statusJob = null
        super.onStop()
    }

    override fun onDestroy() {
        statusJob?.cancel()
        statusScope.cancel()
        super.onDestroy()
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
