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

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun openDownloadedFile(intent: Intent?) {
        val p = intent?.getStringExtra("open_file") ?: return
        val f = File(p)
        if (f.exists()) PreviewBus.open(this, f)
        intent?.removeExtra("open_file")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDownloadedFile(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDownloadedFile(intent)
        if (Build.VERSION.SDK_INT >= 33) {
            try { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (e: Exception) { }
        }
        setContent {
            var themeMode by remember { mutableStateOf(Cfg.themeMode) }
            GHTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        NavRoot(onThemeChange = { themeMode = Cfg.themeMode })
                        val preview by PreviewBus.target.collectAsState()
                        preview?.let { file ->
                            FilePreviewDialog(file) { PreviewBus.target.value = null }
                        }
                    }
                }
            }
        }
    }
}
