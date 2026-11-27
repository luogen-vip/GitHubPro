package com.luogen.ghpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 原文和译文共用同一块位置：自动翻译开着时直接显示译文，点“原文”能随时恢复；
 * 翻译失败会把真实错误显示出来，不偷偷回退成假内容。目标语言可直接在这里改。
 *
 * 关键状态全部绑定“这段原文 + 当前目标语言”：切语言后旧译文立刻失效，
 * 不会出现“显示原文”其实是上一次译文的情况。
 */
@Composable
fun TranslateBlock(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    auto: Boolean = Cfg.translateAuto,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    showControls: Boolean = true,
    compact: Boolean = false,
    onTranslated: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val original = remember(text) { text }
    var target by remember(text, Cfg.translateTarget) { mutableStateOf(Cfg.translateTarget) }
    var result by remember(text, Cfg.translateTarget) { mutableStateOf(TranslateApi.cached(text.trim(), Cfg.translateTarget)) }
    var resultTarget by remember(text, Cfg.translateTarget) { mutableStateOf(if (result != null) Cfg.translateTarget else null) }
    var loading by remember(text, Cfg.translateTarget) { mutableStateOf(false) }
    var error by remember(text, Cfg.translateTarget) { mutableStateOf<String?>(null) }
    var showOriginal by remember(text, Cfg.translateTarget) { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var job by remember(text) { mutableStateOf<Job?>(null) }
    var reqSeq by remember(text) { mutableStateOf(0) }

    val translatable = remember(text) { TranslateApi.isTranslatable(text) }
    val guessed = remember(text) { TranslateApi.guessLanguage(text) }
    val already = guessed != null && TranslateApi.sameLanguage(text, target)
    val same = !translatable || already
    val hasResult = result != null && resultTarget == target

    fun cancel() {
        job?.cancel()
        job = null
        reqSeq++
        loading = false
        error = "已取消翻译"
    }

    fun start(to: String) {
        val src = text.trim()
        if (src.isBlank()) return
        if (!TranslateApi.isTranslatable(src)) {
            error = "代码/符号，无需翻译"
            loading = false
            return
        }
        if (TranslateApi.sameLanguage(src, to)) {
            result = null
            resultTarget = null
            error = "原文已经是${TranslateApi.label(to)}，不需要强行翻译"
            loading = false
            return
        }
        job?.cancel()
        val seq = ++reqSeq
        // 切语言时先把旧译文清空，加载期间显示真正的原文，绝不让旧语言冒充新译文。
        result = null
        resultTarget = null
        loading = true
        error = null
        job = scope.launch {
            try {
                val r = withTimeout(180_000L) {
                    TranslateApi.translate(src, to) { partial ->
                        // 不等整段翻完：第一段出来就先替换正文，长文可以边翻边读。
                        scope.launch {
                            if (seq == reqSeq && partial.isNotBlank()) {
                                result = TranslateApi.Result(partial, null, "翻译中")
                                resultTarget = to
                                showOriginal = false
                            }
                        }
                    }
                }
                if (seq == reqSeq) {
                    result = r
                    resultTarget = to
                    showOriginal = false
                    onTranslated?.invoke()
                }
            } catch (e: TimeoutCancellationException) {
                if (seq == reqSeq) {
                    result = null
                    resultTarget = null
                    error = "翻译超时：网络中断或接口太久没响应，点重试"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (seq == reqSeq) error = "已取消翻译"
                throw e
            } catch (e: Exception) {
                if (seq == reqSeq) {
                    result = null
                    resultTarget = null
                    error = e.message ?: "翻译失败"
                }
            } finally {
                if (seq == reqSeq) loading = false
            }
        }
    }

    LaunchedEffect(text, auto, target) {
        if (auto && TranslateApi.isTranslatable(text) && text.isNotBlank() && !same && !hasResult) start(target)
    }

    if (picker) {
        LanguagePickerDialog(selected = target, onPick = { code ->
            picker = false
            job?.cancel()
            target = code
            result = null
            resultTarget = null
            showOriginal = false
            error = null
            if (!auto) start(code)
        }, onDismiss = { picker = false })
    }

    Column(modifier) {
        val detectedNow = if (hasResult) result?.detected?.let { TranslateApi.labelOrNull(it) } else null
        val sourceLabel = when {
            !translatable -> "代码/符号"
            detectedNow != null -> detectedNow
            else -> TranslateApi.detectedLabel(text) ?: "自动识别"
        }
        val controls: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)
            ) {
                val actionSize = if (compact) 9.sp else 10.sp
                val metaSize = if (compact) 8.sp else 9.sp
                val action = when {
                    !translatable -> "代码/符号，无需翻译"
                    same -> "已是${TranslateApi.label(target)}"
                    loading -> "翻译中…"
                    hasResult && showOriginal -> "显示译文"
                    hasResult -> "显示原文"
                    error != null -> "重试"
                    else -> "翻译"
                }
                Text(action, fontSize = actionSize,
                    color = if (same || !translatable) MaterialTheme.colorScheme.onSurfaceVariant else GH.blue,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.clickable(enabled = translatable && !same && !loading) {
                        if (hasResult) showOriginal = !showOriginal else start(target)
                    }.padding(horizontal = 1.dp, vertical = 2.dp))
                if (loading) {
                    Text("取消", fontSize = actionSize, color = GH.red, maxLines = 1, softWrap = false,
                        modifier = Modifier.clickable { cancel() }.padding(horizontal = 1.dp, vertical = 2.dp))
                }
                Text("原文：$sourceLabel",
                    fontSize = metaSize, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                Text("目标：${TranslateApi.label(target)} ▾", fontSize = metaSize, color = GH.blue,
                    maxLines = 1, softWrap = false, modifier = Modifier.clickable {
                        job?.cancel()
                        picker = true
                    })
                if (!compact && hasResult && !same) result?.let { r ->
                    val det = TranslateApi.labelOrNull(r.detected)
                    Text((det?.let { "译自 $it · " } ?: "") + r.provider, fontSize = metaSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }

        // 发行版说明把翻译控件放在正文上方：展开就能看到，翻完不用再滑到底部找语言。
        if (compact && showControls && text.isNotBlank()) {
            controls()
            if (loading && text.length > 3_000) {
                Text("长文分段翻译中，完成前请勿退出；点“取消”可停止", fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(5.dp))
        }
        Text(
            // 目标语言跟当前原文一致，或者用户点了显示原文，或者旧译文不属于当前目标语言时，
            // 一律显示最初的原文，绝不拿上一次翻译结果冒充原文。
            text = if (same || showOriginal || !hasResult) original else result!!.text,
            style = style,
            maxLines = maxLines,
            overflow = overflow
        )
        // 分段翻译时正文会继续变长；明确告诉用户下面还会补，避免滑到底以为已经没内容。
        if (loading && hasResult && !same) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp),
                color = GH.blue
            )
            Text("还在翻译后面的段落，下面会自动补全…", fontSize = if (compact) 8.sp else 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp))
        }
        if (showControls && text.isNotBlank() && !compact) {
            Spacer(Modifier.height(3.dp))
            controls()
            if (loading && text.length > 3_000) {
                Text("长文分段翻译中，完成前请勿退出；点“取消”可停止", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showControls && error != null && error != "已取消翻译" && !same && translatable) {
            Text("失败：$error", fontSize = if (compact) 8.sp else 9.sp, color = GH.red,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
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
