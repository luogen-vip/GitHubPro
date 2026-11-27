package com.luogen.ghpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * 翻译失败会把真实错误显示出来，不偷偷回退成假内容。目标语言可直接在这里改。
 */
@Composable
fun TranslateBlock(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    auto: Boolean = Cfg.translateAuto,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    showControls: Boolean = true
) {
    val scope = rememberCoroutineScope()
    var target by remember(text) { mutableStateOf(Cfg.translateTarget) }
    var result by remember(text) { mutableStateOf(TranslateApi.cached(text.trim(), Cfg.translateTarget)) }
    var loading by remember(text) { mutableStateOf(false) }
    var error by remember(text) { mutableStateOf<String?>(null) }
    var showOriginal by remember(text) { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val guessed = remember(text) { TranslateApi.guessLanguage(text) }
    val same = guessed != null && TranslateApi.sameLanguage(text, target)

    suspend fun perform(to: String) {
        val src = text.trim()
        if (src.isBlank()) return
        if (TranslateApi.sameLanguage(src, to)) {
            error = "原文已经是${TranslateApi.label(to)}，不需要强行翻译"
            return
        }
        loading = true
        error = null
        try {
            result = TranslateApi.translate(src, to)
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
        if (auto && text.isNotBlank() && !same && result == null) perform(target)
    }

    if (picker) {
        LanguagePickerDialog(selected = target, onPick = { code ->
            picker = false
            target = code
            Cfg.translateTarget = code
            result = null
            showOriginal = false
            error = null
            if (!auto) scope.launch { perform(code) }
        }, onDismiss = { picker = false })
    }

    Column(modifier) {
        Text(
            text = if (!showOriginal && result != null) result!!.text else text,
            style = style,
            maxLines = maxLines,
            overflow = overflow
        )
        if (showControls && text.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val action = when {
                    same -> "已是${TranslateApi.label(target)}"
                    loading -> "翻译中"
                    result != null && showOriginal -> "显示译文"
                    result != null -> "显示原文"
                    error != null -> "重试"
                    else -> "翻译"
                }
                Text(action, fontSize = 10.sp, color = if (same) MaterialTheme.colorScheme.onSurfaceVariant else GH.blue,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.clickable(enabled = !loading && !same) {
                        if (result != null) showOriginal = !showOriginal else scope.launch { perform(target) }
                    }.padding(horizontal = 2.dp, vertical = 2.dp))
                Text("原文：${guessed?.let { TranslateApi.label(it) } ?: "自动识别"}", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                Text("目标：${TranslateApi.label(target)} ▾", fontSize = 9.sp, color = GH.blue,
                    maxLines = 1, softWrap = false, modifier = Modifier.clickable { picker = true })
                result?.let { r ->
                    val det = r.detected?.let { TranslateApi.label(it) }
                    Text((det?.let { "译自 $it · " } ?: "") + r.provider, fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
            if (error != null && !same) {
                Text("失败：$error", fontSize = 9.sp, color = GH.red,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LanguagePickerDialog(selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("翻译成哪种语言", fontSize = 15.sp) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(TranslateApi.langs, key = { it.code }) { l ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(l.code) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(l.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(l.code, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (l.code == selected) Text("  ✓", fontSize = 13.sp, color = GH.blue)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", fontSize = 12.sp) } }
    )
}
