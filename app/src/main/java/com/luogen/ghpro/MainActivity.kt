package com.luogen.ghpro

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.ui.GHTheme
import com.luogen.ghpro.ui.NavRoot

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            try { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (e: Exception) { }
        }
        setContent {
            var themeMode by remember { mutableStateOf(Cfg.themeMode) }
            GHTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NavRoot(onThemeChange = { themeMode = Cfg.themeMode })
                }
            }
        }
    }
}
