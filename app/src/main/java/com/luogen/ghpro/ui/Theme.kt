package com.luogen.ghpro.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object GH {
    val bg = Color(0xFF0D1117)
    val surface = Color(0xFF161B22)
    val surface2 = Color(0xFF21262D)
    val border = Color(0xFF30363D)
    val text = Color(0xFFE6EDF3)
    val muted = Color(0xFF8B949E)
    val blue = Color(0xFF2F81F7)
    val green = Color(0xFF3FB950)
    val red = Color(0xFFF85149)
    val yellow = Color(0xFFD29922)
    val purple = Color(0xFFA371F7)
    val pink = Color(0xFFDB61A2)
    val orange = Color(0xFFF0883E)
    val cyan = Color(0xFF39C5CF)

    fun langColor(lang: String?): Color = when (lang?.lowercase()) {
        "kotlin" -> Color(0xFFA97BFF)
        "java" -> Color(0xFFB07219)
        "javascript" -> Color(0xFFF1E05A)
        "typescript" -> Color(0xFF3178C6)
        "python" -> Color(0xFF3572A5)
        "go" -> Color(0xFF00ADD8)
        "rust" -> Color(0xFFDEA584)
        "c" -> Color(0xFF555555)
        "c++", "cpp" -> Color(0xFFF34B7D)
        "c#" -> Color(0xFF178600)
        "html" -> Color(0xFFE34C26)
        "css" -> Color(0xFF563D7C)
        "shell", "bash" -> Color(0xFF89E051)
        "swift" -> Color(0xFFFFAC45)
        "dart" -> Color(0xFF00B4AB)
        "ruby" -> Color(0xFF701516)
        "php" -> Color(0xFF4F5D95)
        "scala" -> Color(0xFFC22D40)
        "dockerfile" -> Color(0xFF384D54)
        "lua" -> Color(0xFF000080)
        "perl" -> Color(0xFF0298C3)
        "haskell" -> Color(0xFF5E5086)
        "vue" -> Color(0xFF41B883)
        "jupyter notebook" -> Color(0xFFDA5B0B)
        "objective-c" -> Color(0xFF438EFF)
        "makefile" -> Color(0xFF427819)
        "powershell" -> Color(0xFF012456)
        "clojure" -> Color(0xFFDB5855)
        "elixir" -> Color(0xFF6E4A7E)
        "zig" -> Color(0xFFEC915C)
        "nim" -> Color(0xFFFFC200)
        "tex" -> Color(0xFF3D6117)
        else -> blue
    }

    fun statusColor(status: String?, conclusion: String?): Color {
        if (status == "in_progress") return yellow
        if (status == "queued" || status == "pending" || status == "waiting" || status == "requested") return muted
        return when (conclusion) {
            "success" -> green
            "failure", "timed_out", "startup_failure" -> red
            "cancelled", "skipped", "neutral" -> muted
            "action_required" -> yellow
            else -> muted
        }
    }
}

private val DarkScheme = darkColorScheme(
    primary = GH.blue,
    onPrimary = Color.White,
    secondary = GH.purple,
    background = GH.bg,
    onBackground = GH.text,
    surface = GH.surface,
    onSurface = GH.text,
    surfaceVariant = GH.surface2,
    onSurfaceVariant = GH.muted,
    outline = GH.border,
    error = GH.red,
    tertiary = GH.green
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0969DA),
    onPrimary = Color.White,
    secondary = Color(0xFF8250DF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFF6F8FA),
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEAEEF2),
    onSurfaceVariant = Color(0xFF59636E),
    outline = Color(0xFFD1D9E0),
    error = Color(0xFFCF222E)
)

@Composable
fun GHTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        1 -> true
        2 -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
