package com.luogen.ghpro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.download.DlRecord
import com.luogen.ghpro.download.Downloader
import java.io.File

private val CATS = listOf("全部", "应用", "图片", "视频", "音频", "压缩包", "镜像", "文档", "其他")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val ver by Downloader.historyVersion.collectAsState()
    val states by Downloader.state.collectAsState()
    val active = states.values.filter { it.active }
    val failed = states.values.filter { !it.active && it.status == "失败" }
    var cat by rememberSaveable { mutableStateOf("全部") }
    var list by remember { mutableStateOf<List<DlRecord>>(emptyList()) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ver) { list = Downloader.history(ctx) }

    fun copy(text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("链接", text))
        Toast.makeText(ctx, "已复制下载链接", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("下载管理", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("共 ${list.size} 个文件 · 保存在 下载/GitHubPro", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton({ list = Downloader.history(ctx); Toast.makeText(ctx, "已刷新", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Filled.Refresh, "刷新") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CATS.forEach { c ->
                    FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c, fontSize = 11.sp) })
                }
            }
            val shown = if (cat == "全部") list else list.filter { Downloader.categoryOf(it.name) == cat }
            LazyColumn(Modifier.fillMaxSize()) {
                    if (failed.isNotEmpty()) {
                        item {
                            Text("下载失败 ${failed.size}（可断点重试）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = GH.red)
                        }
                        items(failed, key = { "failed-${it.fileName}" }) { d ->
                            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TinyTag("失败", GH.red)
                                    Spacer(Modifier.width(6.dp))
                                    Text(d.fileName, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(if (d.done > 0) "已保留 ${humanSize(d.done)}" else "", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                d.error?.let {
                                    Text(it, fontSize = 10.sp, color = GH.red, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 3.dp))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (!d.url.isNullOrBlank()) TextButton({
                                        Downloader.startDownload(ctx, d.url!!, d.fileName, Cfg.token, d.useMirror,
                                            source = "重试下载")
                                        Toast.makeText(ctx, "已继续下载（支持断点就接着下）", Toast.LENGTH_SHORT).show()
                                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                        Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp)); Text("断点重试", fontSize = 11.sp)
                                    }
                                    TextButton({ Downloader.clearState(d.fileName) },
                                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                                        Text("清除提示", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Divider1()
                        }
                    }
                    if (active.isNotEmpty()) {
                        item {
                            Text("正在下载 ${active.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                        items(active, key = { "active-${it.fileName}" }) { d ->
                            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TinyTag("进行中", GH.blue)
                                    Spacer(Modifier.width(6.dp))
                                    Text(d.fileName, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(if (d.total > 0) "${d.percent}%" else d.status, fontSize = 11.sp, color = GH.blue)
                                    TextButton({ Downloader.cancelDownload(d.fileName) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        Text("取消", fontSize = 11.sp, color = GH.red)
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { if (d.total > 0) d.percent / 100f else 0f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                )
                                Text(listOfNotNull(d.status, d.speed.takeIf { it > 0 }?.let { humanSize(it) + "/s" }, Downloader.repoOf(d.fileName)).joinToString(" · "),
                                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (shown.isEmpty() && active.isEmpty() && failed.isEmpty()) {
                        item { EmptyBox("还没有下载记录。下载发行版里的附件后会出现在这里") }
                    }
                    items(shown, key = { it.id + it.time }) { r ->
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TinyTag(Downloader.categoryOf(r.name), GH.blue)
                                Spacer(Modifier.width(6.dp))
                                Text(r.name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(3.dp))
                            Text("${humanSize(r.size)} · ${timeAgo(java.time.Instant.ofEpochMilli(r.time).toString())} · 来源 ${r.source}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!r.repo.isNullOrBlank() || !r.release.isNullOrBlank()) {
                                Text("来自 " + listOfNotNull(r.repo, r.release).joinToString(" · "), fontSize = 10.sp, color = GH.blue,
                                    modifier = Modifier.clickable { r.repo?.let { UiCache.repoTab[it] = if (!r.release.isNullOrBlank()) 5 else 1; nav.navigate("repo/$it") } })
                            }
                            Spacer(Modifier.height(6.dp))
                            FlowRow(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val local = r.localPath?.let { File(it) }
                                if (local != null && local.exists()) {
                                    TextButton(onClick = {
                                        // APK 走安装器；图片/视频/文本在 App 内全屏预览，其余交系统
                                        PreviewBus.open(ctx, local)
                                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                        Icon(Icons.Filled.OpenInNew, null, Modifier.size(14.dp)); Text("打开", fontSize = 11.sp)
                                    }
                                }
                                if (!r.repo.isNullOrBlank()) {
                                    TextButton({
                                        r.repo?.let { UiCache.repoTab[it] = if (!r.release.isNullOrBlank()) 5 else 1; nav.navigate("repo/$it") }
                                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                        Icon(Icons.Filled.OpenInNew, null, Modifier.size(14.dp)); Text("定位仓库", fontSize = 11.sp)
                                    }
                                }
                                TextButton({ copy(r.url) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp)); Text("复制链接", fontSize = 11.sp)
                                }
                                TextButton({
                                    Downloader.retryDownload(ctx, r,
                                        onDone = { })
                                    Toast.makeText(ctx, "已开始重新下载", Toast.LENGTH_SHORT).show()
                                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Filled.Download, null, Modifier.size(14.dp)); Text("重新下载", fontSize = 11.sp)
                                }
                            }
                            FlowRow(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton({ Downloader.removeRecord(ctx, r.id, false) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("删除记录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton({
                                    Downloader.removeRecord(ctx, r.id, true)
                                    Toast.makeText(ctx, "已删除记录和本地文件", Toast.LENGTH_SHORT).show()
                                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Filled.Delete, null, Modifier.size(14.dp), tint = GH.red)
                                    Text("连文件删除", fontSize = 11.sp, color = GH.red)
                                }
                            }
                        }
                        Divider1()
                    }
                }
        }
    }
}
