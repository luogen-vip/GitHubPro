package com.luogen.ghpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.TranslateApi
import kotlinx.coroutines.launch

/**
 * 原文和译文共用同一块位置：自动翻译开着时直接显示译文，点“原文”能随时恢复；
 * 翻译失败会把真实错误显示出来，不偷偷回退成假内容。
 */
@Composable
fun TranslateBlock(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    auto: Boolean = Cfg.translateAuto,
    target: String = Cfg.translateTarget,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    showControls: Boolean = true
) {
    val scope = rememberCoroutineScope()
    var result by remember(text, target) { mutableStateOf(TranslateApi.cached(text.trim(), target)) }
    var loading by remember(text, target) { mutableStateOf(false) }
    var error by remember(text, target) { mutableStateOf<String?>(null) }
    var showOriginal by remember(text, target) { mutableStateOf(false) }

    suspend fun perform() {
        val src = text.trim()
        if (src.isBlank()) return
        if (TranslateApi.sameLanguage(src, target)) {
            error = "原文已经是${TranslateApi.label(target)}，不需要强行翻译"
            return
        }
        loading = true
        error = null
        try {
            result = TranslateApi.translate(src, target)
            showOriginal = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "翻译失败"
            result = null
        } finally {
            loading = false
        }
    }

    LaunchedEffect(text, auto, target) {
        if (auto && text.isNotBlank() && result == null) perform()
    }

    Column(modifier) {
        Text(
            text = if (!showOriginal && result != null) result!!.text else text,
            style = style,
            maxLines = maxLines,
            overflow = overflow
        )
        if (showControls && text.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val label = when {
                    loading -> "翻译中…"
                    result != null && showOriginal -> "显示译文"
                    result != null -> "显示原文"
                    else -> "翻译"
                }
                Text(label, fontSize = 10.sp, color = GH.blue,
                    modifier = Modifier.clickable {
                        when {
                            loading -> Unit
                            result != null -> showOriginal = !showOriginal
                            else -> scope.launch { perform() }
                        }
                    }.padding(horizontal = 3.dp, vertical = 2.dp))
                result?.let { r ->
                    val det = r.detected?.let { TranslateApi.label(it) }
                    Text((det?.let { "译自 $it · " } ?: "") + r.provider,
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (error != null) {
                    val notice = error!!.startsWith("原文已经是")
                    Text(if (notice) error!! else "失败：$error", fontSize = 9.sp,
                        color = if (notice) MaterialTheme.colorScheme.onSurfaceVariant else GH.red,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
