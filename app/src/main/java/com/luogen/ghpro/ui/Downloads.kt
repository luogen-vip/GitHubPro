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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

private val CATS = listOf("全部", "应用", "图片", "视频", "音频", "压缩包", "镜像", "文档", "代码", "数据", "字体", "3D模型", "种子", "其他")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val ver by Downloader.historyVersion.collectAsState()
    val states by Downloader.state.collectAsState()
    val active = states.values.filter { it.active }
        .sortedByDescending { Downloader.startedAtOf(it.fileName) }
    val failed = states.values.filter { !it.active && it.status == "失败" }
    val cancelled = states.values.filter { !it.active && it.status == "已取消" }
    var cat by rememberSaveable { mutableStateOf("全部") }
    var list by remember { mutableStateOf<List<DlRecord>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(UiCache.downloadsIndex, UiCache.downloadsOffset)
    var restored by remember { mutableStateOf(false) }
    var pendingScroll by remember { mutableStateOf<String?>(null) }
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 || listState.firstVisibleItemScrollOffset > 600 } }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ver) { list = Downloader.history(ctx) }

    // 首次真实数据到齐后再恢复位置：列表为空时直接恢复会被 LazyColumn 夹到 0。
    LaunchedEffect(list.size) {
        if (!restored && list.isNotEmpty()) {
            if (UiCache.downloadsIndex > 0 || UiCache.downloadsOffset > 0) {
                listState.scrollToItem(UiCache.downloadsIndex, UiCache.downloadsOffset)
            }
            restored = true
        }
    }
    // 点“重新下载”后，等真实下载状态出现，再精确滚到那一条的进度条。
    LaunchedEffect(active.map { it.fileName }, pendingScroll) {
        val target = pendingScroll ?: return@LaunchedEffect
        val idx = active.indexOfFirst { it.fileName == target }
        if (idx >= 0) {
            // 列表第一项就是最新下载的进度（“正在下载 N”已经移到列表外，不占可滚动位置）。
            listState.scrollToItem(idx, 0)
            // 新条目第一次测量高度后可能产生 1~2dp 的位置修正，下一帧再钉一次，避免还能向上滑一小点。
            withFrameNanos { }
            listState.scrollToItem(idx, 0)
            pendingScroll = null
        }
    }
    // 滚动位置实时存到内存：点“定位仓库”离开页面、再返回，仍停在刚才那一条。
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                UiCache.downloadsIndex = index
                UiCache.downloadsOffset = offset
            }
    }

    fun copy(text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("链接", text))
        Toast.makeText(ctx, "已复制下载链接", Toast.LENGTH_SHORT).show()
    }

    /**
     * 所有“重新下载 / 断点重试 / 继续下载”都走这里：
     * 先把目标文件名记为待定位，再启动任务；任务状态一出现就滚到它的进度条。
     */
    fun startAndLocate(name: String, start: () -> Unit) {
        val key = Downloader.sanitize(name)
        Downloader.touchStarted(key)
        pendingScroll = key
        start()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (showTop) SmallFab { scope.launch { listState.animateScrollToItem(0) } }
        },
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
            val mobileFiles = list.filter { it.mobileBytes > 0 }
            val mobileTotal = mobileFiles.sumOf { it.mobileBytes }
            if (mobileTotal > 0) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("本 App 通过移动数据下载：${mobileFiles.size} 个文件 · ${humanSize(mobileTotal)}",
                        fontSize = 10.sp, color = GH.yellow, modifier = Modifier.weight(1f))
                }
            }
            // 这条固定在列表外：列表第一项就是最新下载的进度条，定位后上方没有可滚动的标题，
            // 不会再出现“已经到最顶部了，还能向上滑一小点”的感觉。
            if (active.isNotEmpty()) {
                Text("正在下载 ${active.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    if (active.isNotEmpty()) {
                        items(active, key = { "active-${it.fileName}" }) { d ->
                            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TinyTag("进行中", GH.blue)
                                    Spacer(Modifier.width(6.dp))
                                    Text(d.fileName, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(if (d.total > 0) "${d.percent}%" else d.status, fontSize = 11.sp, color = GH.blue)
                                    TextButton({
                                        Downloader.cancelDownload(d.fileName)
                                        Toast.makeText(ctx, "已取消下载，已下载部分保留", Toast.LENGTH_SHORT).show()
                                    }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        Text("取消下载", fontSize = 11.sp, color = GH.red)
                                    }
                                    TextButton({
                                        val ok = Downloader.cancelAndDelete(ctx, d.fileName)
                                        Toast.makeText(ctx,
                                            if (ok) "已取消并删除记录和本地文件" else "已取消并删除记录（本地文件没找到）",
                                            Toast.LENGTH_SHORT).show()
                                    }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                        Text("彻底删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { if (d.total > 0) d.percent / 100f else 0f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                )
                                Text(downloadAmountText(d), fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val mirrorHost = d.mirrorUsed
                                    ?.takeIf { it != d.url }
                                    ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                                Text(listOfNotNull(
                                    d.status,
                                    d.speed.takeIf { it > 0 }?.let { humanSize(it) + "/s" },
                                    d.threads.takeIf { it > 0 }?.let { n -> if (n > 1) "$n 线程分片" else "单线程" },
                                    mirrorHost?.let { "镜像 $it" },
                                    d.mobileBytes.takeIf { it > 0 }?.let { "移动数据 ${humanSize(it)}" },
                                    Downloader.repoOf(d.fileName)
                                ).joinToString(" · "),
                                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
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
                                        startAndLocate(d.fileName) {
                                            Downloader.startDownload(ctx, d.url!!, d.fileName, Cfg.token, d.useMirror,
                                                source = "重试下载", expectedSize = d.total)
                                        }
                                        Toast.makeText(ctx, "已继续下载（支持断点就接着下），正在定位到这条进度", Toast.LENGTH_SHORT).show()
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
                    if (cancelled.isNotEmpty()) {
                        item {
                            Text("已取消 ${cancelled.size}（断点保留）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = GH.yellow)
                        }
                        items(cancelled, key = { "cancel-${it.fileName}" }) { d ->
                            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TinyTag("已取消", GH.yellow)
                                    Spacer(Modifier.width(6.dp))
                                    Text(d.fileName, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(if (d.done > 0) "已保留 ${humanSize(d.done)}" else "", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (!d.url.isNullOrBlank()) TextButton({
                                        startAndLocate(d.fileName) {
                                            Downloader.startDownload(ctx, d.url!!, d.fileName, Cfg.token, d.useMirror,
                                                source = "继续下载", expectedSize = d.total)
                                        }
                                        Toast.makeText(ctx, "正在继续下载，已定位到这条进度", Toast.LENGTH_SHORT).show()
                                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                        Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp)); Text("继续下载", fontSize = 11.sp)
                                    }
                                    TextButton({ Downloader.cancelAndDelete(ctx, d.fileName) },
                                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                                        Text("删除记录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Divider1()
                        }
                    }
                    if (shown.isEmpty() && active.isEmpty() && failed.isEmpty() && cancelled.isEmpty()) {
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
                            if (r.mobileBytes > 0) Text("其中移动数据 ${humanSize(r.mobileBytes)}", fontSize = 10.sp, color = GH.yellow)
                            if (!r.repo.isNullOrBlank() || !r.release.isNullOrBlank()) {
                                Text("来自 " + listOfNotNull(r.repo, r.release).joinToString(" · "), fontSize = 10.sp, color = GH.blue,
                                    modifier = Modifier.clickable {
                                        UiCache.downloadsIndex = listState.firstVisibleItemIndex
                                        UiCache.downloadsOffset = listState.firstVisibleItemScrollOffset
                                        r.repo?.let { UiCache.repoTab[it] = if (!r.release.isNullOrBlank()) 5 else 1; nav.navigate("repo/$it") }
                                    })
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
                                        UiCache.downloadsIndex = listState.firstVisibleItemIndex
                                        UiCache.downloadsOffset = listState.firstVisibleItemScrollOffset
                                        r.repo?.let { UiCache.repoTab[it] = if (!r.release.isNullOrBlank()) 5 else 1; nav.navigate("repo/$it") }
                                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                        Icon(Icons.Filled.OpenInNew, null, Modifier.size(14.dp)); Text("定位仓库", fontSize = 11.sp)
                                    }
                                }
                                TextButton({ copy(r.url) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp)); Text("复制链接", fontSize = 11.sp)
                                }
                                TextButton({
                                    // 先记目标再启动，避免状态先出现、pendingScroll 后赋值而错过定位。
                                    startAndLocate(r.name) {
                                        Downloader.retryDownload(ctx, r, onDone = { })
                                    }
                                    Toast.makeText(ctx, "已开始重新下载，正在定位到这条进度", Toast.LENGTH_SHORT).show()
                                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Filled.Download, null, Modifier.size(14.dp)); Text("重新下载", fontSize = 11.sp)
                                }
                            }
                            FlowRow(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton({
                                    Downloader.removeRecord(ctx, r.id, false)
                                    Toast.makeText(ctx, "已删除记录（本地文件保留）", Toast.LENGTH_SHORT).show()
                                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("删除记录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton({
                                    val ok = Downloader.removeRecord(ctx, r.id, true)
                                    Toast.makeText(ctx,
                                        if (ok) "已删除记录、本地缓存和系统下载文件" else "记录已删除，但本地文件没找到或删不掉",
                                        Toast.LENGTH_SHORT).show()
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
