package com.luogen.ghpro.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.luogen.ghpro.data.Api
import com.luogen.ghpro.data.Cfg
import com.luogen.ghpro.data.NetworkTools
import com.luogen.ghpro.data.NetPrompts
import com.luogen.ghpro.data.NetChoice
import com.luogen.ghpro.data.MobileDataUsage
import com.luogen.ghpro.AppCtx
import com.luogen.ghpro.sync.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

data class DownloadState(
    val fileName: String,
    val done: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    val status: String = "准备中",
    val path: String? = null,
    val error: String? = null,
    val mirrorUsed: String? = null,
    val threads: Int = 1,
    val publicPath: String? = null,
    val localPath: String? = null,
    val url: String? = null,
    val useMirror: Boolean = true,
    val completedAt: Long = 0L,
    val mobileBytes: Long = 0L
) {
    val percent: Int get() = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
    val remaining: Long get() = if (total > 0) (total - done).coerceAtLeast(0L) else 0L
    val active: Boolean
        get() = status.startsWith("下载中") || status.startsWith("多线程下载") ||
            status.startsWith("已下载") || status.startsWith("切换下载线路") || status.startsWith("多线程失败") ||
            status in listOf("连接中", "重试中", "合并中", "准备中", "等待网络", "等待 WiFi", "检测到移动数据", "等待确认流量")
}

data class DlRecord(
    val id: String,
    val name: String,
    val url: String,
    val size: Long,
    val time: Long,
    val source: String,
    val repo: String? = null,
    val release: String? = null,
    val publicPath: String? = null,
    val localPath: String? = null,
    val mobileBytes: Long = 0L
)

private class MobilePauseException(message: String) : Exception(message)

object Downloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val state = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val historyVersion = MutableStateFlow(0)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val epochs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val repoMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val cancelFlags = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val trackedBytes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val mobilePauses = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val startedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 本次下载开始时间；下载管理用它把“刚点重新下载”的文件排到最前面。 */
    fun startedAtOf(name: String): Long = startedAt[sanitize(name)] ?: 0L

    private fun isCancelled(name: String): Boolean = cancelFlags[name] == true
    private fun isMobilePaused(name: String): Boolean = mobilePauses[name] == true

    fun repoOf(name: String): String? = repoMap[sanitize(name)]
    private fun routeOf(name: String): String = repoMap[sanitize(name)]?.let { "repo/$it" } ?: "downloads"
    private fun newEpoch(name: String): Long {
        val e = System.nanoTime()
        epochs[name] = e
        return e
    }
    private fun sameEpoch(name: String, e: Long) = e == 0L || epochs[name] == e
    private fun updateE(s: DownloadState, e: Long) { if (sameEpoch(s.fileName, e)) update(s) }
    private const val HPREFS = "ghpro_downloads"

    fun stateOf(name: String): DownloadState? = state.value[sanitize(name)]

    /** 不随页面销毁的下载作用域：切标签、切后台、分屏都不会把下载掐断。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startDownload(
        ctx: Context,
        rawUrl: String,
        fileName: String,
        token: String? = null,
        useMirror: Boolean = true,
        source: String = "文件",
        repo: String? = null,
        release: String? = null,
        expectedSize: Long = -1L,
        onDone: ((java.io.File) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): Job {
        val key = sanitize(fileName)
        jobs[key]?.takeIf { it.isActive }?.let { return it }
        startedAt[key] = System.currentTimeMillis()
        Notifier.downloadStart(key)
        cancelFlags.remove(key)
        mobilePauses.remove(key)
        MobileDataUsage.reset(key)
        trackedBytes[key] = runCatching { File(dir(ctx), key).length() }.getOrDefault(0L)
        val epoch = newEpoch(key)
        if (!repo.isNullOrBlank()) repoMap[key] = repo
        lateinit var job: Job
        job = appScope.launch {
            var warnedMobile = false
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    if (Cfg.mobileDownloadWarn && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) && !warnedMobile) {
                        warnedMobile = true
                        // 中途从 Wi‑Fi 切到流量：先暂停传输，让用户选继续 / 等 Wi‑Fi / 取消；
                        // 已写入的字节会按流量统计，断点保留。
                        mobilePauses[key] = true
                        update(DownloadState(key, status = "检测到移动数据", error = "已暂停，等待确认是否用流量继续", url = rawUrl, useMirror = useMirror))
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            Notifier.generic(ctx, "已切换到移动数据", "$fileName 正在用流量继续下载；可在下载管理取消，已下载部分会保留。")
                        }
                    }
                }
                override fun onLost(network: android.net.Network) {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        kotlinx.coroutines.delay(1500)
                        if (!networkOnline(ctx)) Notifier.generic(ctx, "网络已断开", "$fileName 已暂停，已下载的部分会保留；联网后会自动继续。")
                    }
                }
            }
            runCatching { cm?.registerDefaultNetworkCallback(cb) }
            try {
                val initial = NetworkTools.info(ctx)
                if (!initial.online) {
                    updateE(DownloadState(key, status = "等待网络", error = initial.label, url = rawUrl, useMirror = useMirror,
                        done = stateOf(key)?.done ?: 0L, total = expectedSize.takeIf { it > 0 } ?: 0L), epoch)
                    NetPrompts.showNoNetwork(key, initial.label)
                    Notifier.generic(ctx, "等待网络", "$fileName 暂时不能下载：${initial.label}\n联网后会自动开始，已下载部分会保留。")
                    if (!NetworkTools.awaitOnline(ctx)) throw kotlinx.coroutines.CancellationException("等待网络取消")
                    NetPrompts.dismissNoNetwork()
                    updateE(DownloadState(key, status = "连接中", url = rawUrl, useMirror = useMirror,
                        done = stateOf(key)?.done ?: 0L, total = expectedSize.takeIf { it > 0 } ?: 0L), epoch)
                    Notifier.generic(ctx, "网络已恢复", "$fileName 已检测到网络，正在自动开始下载。")
                }

                val now = NetworkTools.info(ctx)
                if (now.kind == NetworkTools.Kind.CELLULAR) {
                    val bytes = expectedSize.takeIf { it > 0 } ?: stateOf(key)?.total?.takeIf { it > 0 } ?: -1L
                    when (NetPrompts.askDownload(key, bytes, now.slow)) {
                        NetChoice.CONTINUE -> { mobilePauses.remove(key); warnedMobile = true }
                        NetChoice.WAIT_WIFI -> {
                            updateE(DownloadState(key, status = "等待 WiFi", error = "已暂停，连接 Wi‑Fi 后自动开始",
                                url = rawUrl, useMirror = useMirror, done = stateOf(key)?.done ?: 0L,
                                total = expectedSize.takeIf { it > 0 } ?: 0L), epoch)
                            Notifier.generic(ctx, "等待 Wi‑Fi", "$fileName 已暂停；连接 Wi‑Fi 后自动继续，已下载部分保留。")
                            if (!NetworkTools.awaitWifi(ctx)) throw kotlinx.coroutines.CancellationException("等待 WiFi 取消")
                            mobilePauses.remove(key); warnedMobile = false
                            updateE(DownloadState(key, status = "连接中", url = rawUrl, useMirror = useMirror,
                                done = stateOf(key)?.done ?: 0L, total = expectedSize.takeIf { it > 0 } ?: 0L), epoch)
                        }
                        else -> throw kotlinx.coroutines.CancellationException("用户取消流量下载")
                    }
                }
                if (now.slow && expectedSize > 20L * 1024 * 1024) {
                    Notifier.generic(ctx, "当前网络较慢", "$fileName 较大（约 ${expectedSize / 1024 / 1024}MB），当前网络带宽较低，下载可能较慢；可继续等待或取消。")
                }

                var retry = 0
                while (true) {
                    try {
                        val f = download(ctx, rawUrl, fileName, token, useMirror, epoch = epoch, expectedTotal = expectedSize)
                        val st = stateOf(fileName)
                        record(ctx, DlRecord(
                            id = java.util.UUID.randomUUID().toString(),
                            name = fileName, url = rawUrl, size = f.length(), time = System.currentTimeMillis(),
                            source = source, repo = repo, release = release,
                            publicPath = st?.publicPath, localPath = st?.localPath ?: f.absolutePath,
                            mobileBytes = MobileDataUsage.bytesFor(key)
                        ))
                        kotlinx.coroutines.withContext(Dispatchers.Main) { onDone?.invoke(f) }
                        return@launch
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: MobilePauseException) {
                        mobilePauses.remove(key)
                        val info = NetworkTools.info(ctx)
                        val bytes = expectedSize.takeIf { it > 0 } ?: stateOf(key)?.total?.takeIf { it > 0 } ?: -1L
                        updateE(DownloadState(key, status = "等待确认流量", error = "已暂停，等待确认是否用流量继续",
                            url = rawUrl, useMirror = useMirror, done = stateOf(key)?.done ?: 0L,
                            total = expectedSize.takeIf { it > 0 } ?: 0L), epoch)
                        when (NetPrompts.askDownload(key, bytes, info.slow)) {
                            NetChoice.CONTINUE -> { warnedMobile = true; mobilePauses.remove(key); continue }
                            NetChoice.WAIT_WIFI -> {
                                updateE(DownloadState(key, status = "等待 WiFi", error = "已暂停，连接 Wi‑Fi 后自动开始",
                                    url = rawUrl, useMirror = useMirror, done = stateOf(key)?.done ?: 0L,
                                    total = expectedSize.takeIf { it > 0 } ?: 0L), epoch)
                                if (!NetworkTools.awaitWifi(ctx)) throw kotlinx.coroutines.CancellationException("等待 WiFi 取消")
                                warnedMobile = false
                                continue
                            }
                            else -> throw kotlinx.coroutines.CancellationException("用户取消流量下载")
                        }
                    } catch (e: Exception) {
                        if (!NetworkTools.info(ctx).online) {
                            retry++
                            updateE(DownloadState(key, status = "等待网络", error = "网络断开，已暂停；联网后会自动继续",
                                url = rawUrl, useMirror = useMirror, done = stateOf(key)?.done ?: 0L,
                                total = expectedSize.takeIf { it > 0 } ?: 0L), epoch)
                            NetPrompts.showNoNetwork(key, "网络断开，已暂停；联网后会自动继续")
                            Notifier.generic(ctx, "网络已断开", "$fileName 已暂停；联网后会自动继续，已下载部分保留。")
                            if (!NetworkTools.awaitOnline(ctx)) throw kotlinx.coroutines.CancellationException("等待网络取消")
                            NetPrompts.dismissNoNetwork()
                            updateE(DownloadState(key, status = "连接中", url = rawUrl, useMirror = useMirror,
                                done = stateOf(key)?.done ?: 0L, total = expectedSize.takeIf { it > 0 } ?: 0L), epoch)
                            continue
                        }
                        throw e
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                update(DownloadState(key, status = "已取消", done = stateOf(key)?.done ?: 0L,
                    total = stateOf(key)?.total ?: 0L, url = rawUrl, useMirror = useMirror))
                runCatching { Notifier.downloadCanceled(ctx, key) }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onError?.invoke(e.message ?: "下载失败") }
            } finally {
                mobilePauses.remove(key)
                runCatching { cm?.unregisterNetworkCallback(cb) }
                if (jobs[key] === job) jobs.remove(key)
            }
        }
        jobs[key] = job
        return job
    }

    fun cancelDownload(name: String): Boolean {
        val key = sanitize(name)
        val prev = stateOf(key)
        cancelFlags[key] = true
        newEpoch(key)
        val j = jobs.remove(key)
        j?.cancel()
        update(prev?.copy(status = "已取消", speed = 0L, error = "已取消，已下载的部分保留，可继续下载")
            ?: DownloadState(key, status = "已取消", error = "已取消，已下载的部分保留，可继续下载"))
        runCatching { Notifier.downloadCanceled(com.luogen.ghpro.AppCtx.get(), key) }
        return j != null
    }

    fun retryDownload(ctx: Context, rec: DlRecord, onDone: ((File) -> Unit)? = null): Job =
        startDownload(ctx, rec.url, rec.name, Cfg.token, true, rec.source, rec.repo, rec.release,
            expectedSize = rec.size, onDone = onDone)

    // ---------------- 下载历史（持久化） ----------------
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(HPREFS, Context.MODE_PRIVATE)

    fun history(ctx: Context): List<DlRecord> {
        val raw = prefs(ctx).getString("list", "[]") ?: "[]"
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DlRecord(o.optString("id"), o.optString("name"), o.optString("url"),
                    o.optLong("size"), o.optLong("time"), o.optString("source", "文件"),
                    o.optString("repo").takeIf { it.isNotBlank() }, o.optString("release").takeIf { it.isNotBlank() },
                    o.optString("publicPath").takeIf { it.isNotBlank() }, o.optString("localPath").takeIf { it.isNotBlank() },
                    o.optLong("mobileBytes", 0L))
            }
        }.getOrDefault(emptyList())
    }

    fun record(ctx: Context, r: DlRecord) {
        val list = history(ctx).toMutableList()
        list.add(0, r)
        save(ctx, list.take(400))
    }

    private fun save(ctx: Context, list: List<DlRecord>) {
        val arr = org.json.JSONArray()
        list.forEach { r ->
            arr.put(org.json.JSONObject().apply {
                put("id", r.id); put("name", r.name); put("url", r.url); put("size", r.size)
                put("time", r.time); put("source", r.source); put("repo", r.repo ?: "")
                put("release", r.release ?: ""); put("publicPath", r.publicPath ?: ""); put("mobileBytes", r.mobileBytes)
                put("localPath", r.localPath ?: "")
            })
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
        historyVersion.value++
    }

    /** 真正删掉本地缓存文件 + 系统“下载/GitHubPro”里发布出去的那一份。 */
    private fun deletePublished(ctx: Context, name: String): Boolean {
        var deleted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = ctx.contentResolver
                val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val args = arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "/GitHubPro%")
                val rows = resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, sel, args)
                if (rows > 0) deleted = true
            }.onFailure {
                // 部分 ROM 需要先查 uri 再删；查不到就当不存在
                runCatching {
                    val resolver = ctx.contentResolver
                    resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        arrayOf(name), null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val id = c.getLong(0)
                            val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                            if (resolver.delete(uri, null, null) > 0) deleted = true
                        }
                    }
                }
            }
        } else {
            runCatching {
                val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GitHubPro/$name")
                if (f.exists() && f.delete()) deleted = true
            }
        }
        return deleted
    }

    private fun deleteRecordFiles(ctx: Context, r: DlRecord): Boolean {
        var deleted = false
        val local = r.localPath?.let { File(it) } ?: File(dir(ctx), sanitize(r.name))
        runCatching { if (local.exists() && local.delete()) deleted = true }
        if (deletePublished(ctx, r.name)) deleted = true
        if (sanitize(r.name) != r.name && deletePublished(ctx, sanitize(r.name))) deleted = true
        return deleted
    }

    /** 返回 true 表示至少真实删掉了一个本地文件；false 表示记录删了但磁盘上没找到/删不掉。 */
    fun removeRecord(ctx: Context, id: String, deleteFile: Boolean): Boolean {
        val list = history(ctx).toMutableList()
        val target = list.firstOrNull { it.id == id }
        val removed = if (deleteFile && target != null) deleteRecordFiles(ctx, target) else false
        target?.let { Notifier.clearDownload(ctx, it.name) }
        list.removeAll { it.id == id }
        save(ctx, list)
        return removed
    }

    fun clearRecords(ctx: Context, deleteFiles: Boolean): Boolean {
        var removed = false
        val old = history(ctx)
        if (deleteFiles) old.forEach { r -> if (deleteRecordFiles(ctx, r)) removed = true }
        Notifier.clearDownloads(ctx, old.map { it.name })
        save(ctx, emptyList())
        return removed
    }

    /** 正在下载/失败的条目：取消任务，删缓存残留 + 已发布文件 + 同名历史记录，最后清掉状态。 */
    fun cancelAndDelete(ctx: Context, name: String): Boolean {
        val safe = sanitize(name)
        cancelDownload(name)
        var removed = false
        runCatching {
            val f = File(dir(ctx), safe)
            if (f.exists() && f.delete()) removed = true
        }
        if (deletePublished(ctx, name)) removed = true
        if (deletePublished(ctx, safe)) removed = true
        val list = history(ctx).toMutableList()
        list.filter { it.name == name || it.name == safe }.forEach { r ->
            if (deleteRecordFiles(ctx, r)) removed = true
        }
        list.removeAll { it.name == name || it.name == safe }
        Notifier.clearDownload(ctx, safe)
        save(ctx, list)
        state.value = state.value - safe
        return removed
    }

    /** 进程重启后把上次没下完的条目改成「已中断」，不再卡在某个百分比。 */
    fun markInterrupted() {
        val cur = state.value.mapValues { (_, v) ->
            if (v.status in listOf("连接中", "下载中", "重试中", "合并中")) v.copy(status = "已中断（可重新下载）") else v
        }
        if (cur.isNotEmpty()) state.value = cur
    }

    fun clearState(name: String) {
        state.value = state.value - sanitize(name)
    }

    fun categoryOf(name: String): String {
        val n = name.lowercase()
        val ext = n.substringAfterLast('.', "")
        return when {
            // 应用/安装包（含安卓分卷、Windows、Linux、iOS）
            ext in setOf("apk", "xapk", "apks", "aab", "ipa", "exe", "msi", "msix", "pkg", "deb", "rpm", "obb", "jar") ||
                n.contains(".apk.") -> "应用"
            ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "heif", "avif", "tiff", "ico") -> "图片"
            ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v", "mpg", "mpeg", "ts", "ogv") -> "视频"
            ext in setOf("mp3", "flac", "wav", "m4a", "ogg", "opus", "aac", "wma", "amr", "mid", "midi") -> "音频"
            // 复合后缀/分卷：tar.gz、tar.zst、7z.001、zip.001 都算压缩包
            ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4", "lzma", "cab", "001", "002", "003") ||
                n.endsWith(".tar.gz") || n.endsWith(".tar.bz2") || n.endsWith(".tar.xz") ||
                n.endsWith(".tar.zst") || n.contains(".7z.") || n.contains(".zip.") || n.contains(".rar.") -> "压缩包"
            ext in setOf("iso", "esd", "img", "wim", "vhd", "vhdx", "dmg", "qcow2", "vmdk") -> "镜像"
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "log", "rtf",
                "odt", "ods", "odp", "epub", "mobi", "azw3", "pages", "numbers", "key") -> "文档"
            ext in setOf("kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "c", "cc", "cpp",
                "h", "hpp", "cs", "go", "rs", "rb", "php", "swift", "sh", "bash", "bat", "cmd", "ps1",
                "html", "htm", "css", "scss", "sass", "less", "vue", "gradle", "make", "mk", "wasm", "asm") -> "代码"
            ext in setOf("json", "xml", "csv", "tsv", "yaml", "yml", "toml", "ini", "conf", "properties",
                "sql", "db", "sqlite", "parquet", "avro", "ipynb", "reg", "pem", "crt", "cer", "key", "p12") -> "数据"
            ext in setOf("ttf", "otf", "woff", "woff2", "eot", "fon", "ttc") -> "字体"
            ext in setOf("stl", "obj", "fbx", "glb", "gltf", "blend", "3ds", "dae", "ply", "3mf", "step", "stp") -> "3D模型"
            ext == "torrent" -> "种子"
            else -> "其他"
        }
    }

    private fun update(s: DownloadState) {
        val key = s.fileName
        val prev = trackedBytes[key] ?: 0L
        val delta = s.done - prev
        // 按每次真实写入文件的增量计流量；网络在下载中途从 Wi‑Fi 切到流量时，
        // 后续增量会按当前网络类型记到流量，而不是把整份文件都算成流量。
        // 只有带真实进度的状态才更新基线；纯状态提示（如“检测到移动数据”）不能把基线归零。
        if (s.done > 0 || s.status == "已完成") {
            if (delta > 0 && (s.active || s.status == "已完成") && NetworkTools.isCellular(AppCtx.get())) {
                MobileDataUsage.add(key, delta)
            }
            trackedBytes[key] = s.done
        }
        val mobileNow = MobileDataUsage.bytesFor(key)
        val shown = if (mobileNow > 0 && (s.active || s.status == "已完成")) s.copy(mobileBytes = mobileNow) else s
        state.value = state.value + (key to shown)
    }

    private fun dir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download.bin" }.take(150)

    fun candidates(rawUrl: String, useMirror: Boolean): List<String> {
        val out = ArrayList<String>()
        val isApi = rawUrl.contains("api.github.com") || rawUrl.contains("uploads.github.com") || rawUrl.contains("objects.githubusercontent.com")
        if (useMirror && !isApi) {
            val normalized = when {
                rawUrl.startsWith("https://github.com/") -> rawUrl
                rawUrl.startsWith("https://codeload.github.com/") -> rawUrl
                rawUrl.startsWith("https://raw.githubusercontent.com/") -> rawUrl
                else -> rawUrl
            }
            Cfg.mirrorOf(normalized).forEach { out.add(it) }
        }
        out.add(rawUrl)
        return out
    }

    suspend fun download(
        ctx: Context,
        rawUrl: String,
        fileName: String,
        token: String? = null,
        useMirror: Boolean = true,
        threads: Int = Cfg.downThreads,
        notify: Boolean = true,
        epoch: Long = 0L,
        expectedTotal: Long = -1L
    ): File = withContext(Dispatchers.IO) {
        val safe = sanitize(fileName)
        val cur = state.value[safe]
        if (cur != null && cur.status in listOf("连接中", "下载中", "重试中", "合并中")) {
            throw java.io.IOException("这个文件正在下载中，进度条在上面")
        }
        val outFile = File(dir(ctx), safe)
        trackedBytes[safe] = outFile.takeIf { it.exists() }?.length() ?: 0L
        val cands = candidates(rawUrl, useMirror)
        var lastError: Exception? = null
        var idx = 0
        for (c in cands) {
            if (isCancelled(safe)) throw kotlinx.coroutines.CancellationException("已取消")
            // 每个镜像候选使用新的进度代号：上个候选残留线程的进度会被直接丢弃，绝不可能再闪回旧百分比
            val candEpoch = newEpoch(safe)
            try {
                // 只有第一次显示“连接中”；切镜像时显示“切换线路”，不再重复两遍“连接中”
                val phase = if (idx == 0) "连接中" else "切换下载线路 ${idx + 1}/${cands.size}"
                updateE(DownloadState(safe, status = phase, mirrorUsed = c, url = rawUrl, useMirror = useMirror,
                    done = outFile.takeIf { it.exists() }?.length() ?: 0,
                    total = expectedTotal.takeIf { it > 0 } ?: 0), candEpoch)
                if (notify) Notifier.downloadStatus(ctx, safe, phase, routeOf(safe))
                fetch(c, outFile, token, threads, safe, notify, candEpoch, expectedTotal)
                if (isCancelled(safe)) throw kotlinx.coroutines.CancellationException("已取消")
                val len = outFile.length()
                if (len <= 0) throw IOException("下载到空文件")
                // 关键：先把进度明确推到 100%，再花时间写入系统下载目录，避免条子永远差一点
                updateE(DownloadState(safe, done = len, total = len, status = "已下载 100% · 正在保存…",
                    mirrorUsed = c, threads = threads), candEpoch)
                if (notify) Notifier.downloadProgress(ctx, safe, 100, "已下载 100% · 正在保存…", len, len, routeOf(safe))
                val pub = publish(ctx, outFile, safe)
                val doneState = DownloadState(safe, done = len, total = len,
                    status = "已完成", path = pub.first, publicPath = pub.first, localPath = outFile.absolutePath,
                    mirrorUsed = c, threads = threads, url = rawUrl, useMirror = useMirror,
                    completedAt = System.currentTimeMillis())
                updateE(doneState, candEpoch)
                if (c != rawUrl) Cfg.preferredMirror = c.substringBefore("/https://").substringBefore("/http://")
                if (notify) Notifier.downloadDone(ctx, safe, outFile.absolutePath, pub.first)
                return@withContext outFile
            } catch (e: MobilePauseException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                // 镜像失败不清掉已下好的部分：下一个线路可以按 Range 断点续传
                if (!isCancelled(safe)) updateE(DownloadState(safe, status = "重试中", error = e.message, mirrorUsed = c,
                    done = outFile.takeIf { it.exists() }?.length() ?: 0, total = 0), candEpoch)
            }
            idx++
        }
        // 用户取消时不要再落“失败”或发失败通知；交给外层统一处理“已取消”。
        if (isCancelled(safe)) throw kotlinx.coroutines.CancellationException("已取消")
        val offline = !NetworkTools.info(ctx, true).online
        val rawMsg = lastError?.message ?: "下载失败"
        val msg = if (offline) {
            "网络已断开，下载已暂停；已下载的部分会保留，恢复网络后自动继续。"
        } else rawMsg
        val kept = outFile.takeIf { it.exists() }?.length() ?: 0L
        // 候选线路每次都会刷新 epoch；失败落状态必须用当前 epoch，否则会永远停在「重试中」
        updateE(DownloadState(safe, status = if (offline) "等待网络" else "失败", error = msg, done = kept,
            total = expectedTotal.takeIf { it > 0 } ?: 0, url = rawUrl, useMirror = useMirror),
            epochs[safe] ?: epoch)
        // 断网不是“失败”，由外层进入自动等待/续传，避免先弹一条失败通知再弹等待网络。
        if (notify && !offline) Notifier.downloadFailed(ctx, safe, msg)
        throw IOException(msg)
    }

    /** 真实探测镜像：用 Range 拉 GitHub raw 的 100 字节，200/206 才算可用。 */
    suspend fun probeMirror(mirror: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = mirror.trim().trimEnd('/')
        if (!base.startsWith("http")) return@withContext false to "地址格式不对"
        val target = "$base/https://raw.githubusercontent.com/octocat/Hello-World/master/README"
        val t0 = System.currentTimeMillis()
        try {
            val resp = client.newCall(
                Request.Builder().url(target).header("User-Agent", "GitHubPro/1.0")
                    .header("Range", "bytes=0-99").get().build()
            ).execute()
            resp.use {
                val ms = System.currentTimeMillis() - t0
                if (it.code == 200 || it.code == 206) true to "${it.code} · ${ms}ms"
                else false to "HTTP ${it.code}"
            }
        } catch (e: Exception) {
            false to (e.message ?: "连接失败")
        }
    }

    fun networkOnline(ctx: Context): Boolean {
        return runCatching { NetworkTools.info(ctx, true).online }.getOrDefault(true)
    }

    /** 把下载好的文件放进系统「下载」目录，用户在文件管理器里直接能看到。 */
    fun publish(ctx: Context, src: File, name: String): Pair<String, Uri?> {
        val mime = mimeOf(name)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GitHubPro")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    val doneValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, doneValues, null, null)
                    ("下载/GitHubPro/$name") to uri
                } else legacyCopy(ctx, src, name)
            } else legacyCopy(ctx, src, name)
        } catch (e: Exception) {
            runCatching { src.absolutePath to null }.getOrDefault(src.absolutePath to null)
        }
    }

    private fun legacyCopy(ctx: Context, src: File, name: String): Pair<String, Uri?> {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GitHubPro")
        if (!dir.exists()) dir.mkdirs()
        val dst = File(dir, name)
        src.copyTo(dst, overwrite = true)
        return ("下载/GitHubPro/$name") to Uri.fromFile(dst)
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "xapk", "apks", "aab" -> "application/vnd.android.package-archive"
        "ipa" -> "application/octet-stream"
        "zip" -> "application/zip"
        "7z" -> "application/x-7z-compressed"
        "rar" -> "application/vnd.rar"
        "tar" -> "application/x-tar"
        "gz", "tgz" -> "application/gzip"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "yaml", "yml" -> "application/yaml"
        "txt", "log", "md" -> "text/plain"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "doc", "docx" -> "application/msword"
        "xls", "xlsx" -> "application/vnd.ms-excel"
        "ppt", "pptx" -> "application/vnd.ms-powerpoint"
        "epub" -> "application/epub+zip"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "stl" -> "model/stl"
        "obj" -> "model/obj"
        "glb" -> "model/gltf-binary"
        "gltf" -> "model/gltf+json"
        "iso" -> "application/x-iso9660-image"
        "torrent" -> "application/x-bittorrent"
        else -> "application/octet-stream"
    }

    private fun fetch(url: String, outFile: File, token: String?, threadsWanted: Int, name: String, notify: Boolean, epoch: Long, expectedTotal: Long = -1L) {
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        val sendAuth = token != null && (host.contains("github.com") || host.contains("githubusercontent.com"))
        val head = Request.Builder().url(url)
            .header("User-Agent", "GitHubPro/1.0")
            .apply { if (sendAuth) header("Authorization", "Bearer $token") }
            .head().build()
        // 发行版附件/工作流产物在 API 里已经有真实大小，先拿来当总大小；
        // HEAD 拿不到 Content-Length（例如动态打包的源码 ZIP）时也不会显示成 0。
        var total = expectedTotal.takeIf { it > 0 } ?: -1L
        var acceptRanges = false
        try {
            client.newCall(head).execute().use { r ->
                if (r.isSuccessful) {
                    total = r.header("Content-Length")?.toLongOrNull() ?: -1
                    acceptRanges = r.header("Accept-Ranges")?.contains("bytes", true) == true
                }
            }
        } catch (e: Exception) { }

        // 有些镜像/对象存储 HEAD 不返回 Accept-Ranges，但真发 Range 是 206。
        // 只探 1KB，确认真能分片才开多线程，避免“设置里写着 64 线程、实际一直单线程”。
        if (!acceptRanges) {
            try {
                val probe = Request.Builder().url(url)
                    .header("User-Agent", "GitHubPro/1.0")
                    .header("Range", "bytes=0-1023")
                    .apply { if (sendAuth) header("Authorization", "Bearer $token") }
                    .get().build()
                client.newCall(probe).execute().use { r ->
                    if (r.code == 206) {
                        acceptRanges = true
                        val cr = r.header("Content-Range") ?: ""
                        val realTotal = cr.substringAfterLast('/').toLongOrNull() ?: -1L
                        if (realTotal > 0) total = realTotal
                    }
                }
            } catch (e: Exception) { }
        }

        // 小文件不硬拆线程：2MB 一个分片，12MB 约 6 线程，1GB 才会拉满设置值
        val effective = min(threadsWanted.coerceIn(1, 128), max(1, (total / (2L * 1024 * 1024)).toInt()))
        val existingBytes = if (outFile.exists()) outFile.length() else 0L
        // 已经有断点就优先单线程续传；从 0 开始才用多线程分片
        val useMulti = Cfg.multiThread && effective > 1 && total > 1024 * 1024 && acceptRanges && existingBytes == 0L
        if (useMulti) {
            // 先按设置值下；失败就自动降一半线程再试一次，最后才退单线程。
            // 这个“智能降级”只影响本次下载，不会改用户在设置里填的默认线程数。
            val tries = listOf(effective, maxOf(2, effective / 2)).distinct()
            var lastMulti: Exception? = null
            for ((i, n) in tries.withIndex()) {
                try {
                    if (i > 0) updateE(DownloadState(name, done = 0, total = total,
                        status = "多线程失败，自动降为 $n 线程重试…", threads = n), epoch)
                    outFile.delete()
                    multiThread(url, outFile, token, sendAuth, total, n, name, notify, epoch)
                    return
                } catch (e: Exception) {
                    if (e is MobilePauseException) throw e
                    if (isCancelled(name)) throw e
                    lastMulti = e
                }
            }
            updateE(DownloadState(name, done = 0, total = total,
                status = "多线程均失败（${lastMulti?.message ?: "线路不支持"}），自动改单线程…", threads = 1), epoch)
            outFile.delete()
            single(url, outFile, token, sendAuth, total, name, notify, epoch)
        } else {
            single(url, outFile, token, sendAuth, total, name, notify, epoch)
        }
    }

    private fun single(url: String, outFile: File, token: String?, sendAuth: Boolean, totalHint: Long, name: String, notify: Boolean, epoch: Long) {
        val existing = if (outFile.exists()) outFile.length() else 0L
        val resume = totalHint > 0 && existing in 1 until totalHint
        val req = Request.Builder().url(url)
            .header("User-Agent", "GitHubPro/1.0")
            .apply {
                if (sendAuth) header("Authorization", "Bearer $token")
                if (resume) header("Range", "bytes=$existing-")
            }
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val isPartial = resp.code == 206 && resume
            val total = when {
                isPartial -> resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: totalHint
                else -> resp.body?.contentLength()?.takeIf { it > 0 } ?: totalHint
            }
            if (!isPartial && outFile.exists()) outFile.delete()
            val startAt = if (isPartial) existing else 0L
            val out = java.io.FileOutputStream(outFile, isPartial)
            val buf = ByteArray(64 * 1024)
            var done = startAt
            var last = System.currentTimeMillis()
            var lastBytes = startAt
            resp.body?.byteStream()?.use { input ->
                while (true) {
                    if (isCancelled(name)) throw IOException("已取消")
                    if (isMobilePaused(name)) throw MobilePauseException("检测到切换到移动数据，已暂停")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - last > 400) {
                        val speed = (done - lastBytes) * 1000 / max(1, now - last)
                        updateE(DownloadState(name, done = done, total = total, speed = speed,
                            status = if (total > 0) "下载中 ${done * 100 / total}%" else "下载中",
                            threads = 1), epoch)
                        if (notify) Notifier.downloadProgress(
                            com.luogen.ghpro.AppCtx.get(), name,
                            if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else 0,
                            "单线程下载", done, total, routeOf(name)
                        )
                        last = now
                        lastBytes = done
                    }
                }
            }
            out.flush(); out.close()
            if (total > 0 && done != total) throw IOException("下载不完整：$done/$total")
        }
    }

    private fun multiThread(
        url: String, outFile: File, token: String?, sendAuth: Boolean,
        total: Long, threads: Int, name: String, notify: Boolean, epoch: Long
    ) {
        RandomAccessFile(outFile, "rw").use { raf ->
            raf.setLength(total)
        }
        val done = AtomicLong(0)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val chunk = (total + threads - 1) / threads
        val errors = java.util.Collections.synchronizedList(ArrayList<Exception>())
        val abort = java.util.concurrent.atomic.AtomicBoolean(false)
        val progress = Thread {
            var last = System.currentTimeMillis()
            var lastBytes = 0L
            while (!stop.get() && !abort.get()) {
                if (isCancelled(name)) break
                Thread.sleep(400)
                if (stop.get()) break
                val d = done.get()
                if (d >= total) break
                val now = System.currentTimeMillis()
                val speed = (d - lastBytes) * 1000 / max(1, now - last)
                updateE(DownloadState(name, done = d, total = total, speed = speed,
                    status = "多线程下载 ${d * 100 / total}%", threads = threads), epoch)
                if (notify) Notifier.downloadProgress(com.luogen.ghpro.AppCtx.get(), name, (d * 100 / total).toInt(), "多线程 $threads 线程", d, total, routeOf(name))
                last = now
                lastBytes = d
            }
        }
        progress.isDaemon = true
        progress.start()

        val workers = ArrayList<Thread>()
        for (i in 0 until threads) {
            val start = i * chunk
            val end = min(total - 1, start + chunk - 1)
            if (start > end) continue
            val t = Thread {
                try {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "GitHubPro/1.0")
                        .header("Range", "bytes=$start-$end")
                        .apply { if (sendAuth) header("Authorization", "Bearer $token") }
                        .get().build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.code != 206) throw IOException("这个线路不支持分片（HTTP ${resp.code}），自动改单线程")
                        val cr = resp.header("Content-Range") ?: ""
                        if (!cr.startsWith("bytes $start-")) throw IOException("分片起点不符：$cr")
                        resp.body?.byteStream()?.use { input ->
                            RandomAccessFile(outFile, "rw").use { raf ->
                                raf.seek(start)
                                val buf = ByteArray(32 * 1024)
                                var pos = start
                                while (pos <= end) {
                                    if (isCancelled(name)) throw IOException("已取消")
                                    if (isMobilePaused(name)) throw MobilePauseException("检测到切换到移动数据，已暂停")
                                    val n = input.read(buf, 0, min(buf.size.toLong(), end - pos + 1).toInt())
                                    if (n < 0) break
                                    raf.write(buf, 0, n)
                                    pos += n
                                    done.addAndGet(n.toLong())
                                }
                                // 服务器提前断流也必须报错，否则会留下“大小对但有空洞”的坏文件
                                if (pos <= end) throw IOException("分片不完整：${pos - start}/${end - start + 1}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    errors.add(e)
                    abort.set(true)
                }
            }
            t.isDaemon = true
            t.start()
            workers.add(t)
        }
        try {
            workers.forEach { it.join() }
            errors.filterIsInstance<MobilePauseException>().firstOrNull()?.let { throw it }
            if (errors.isNotEmpty()) throw IOException("分片下载失败：${errors.first().message}")
            if (outFile.length() != total) throw IOException("文件大小不符")
        } finally {
            stop.set(true)
        }
    }

    // 安装 / 分享
    fun installApk(ctx: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        } catch (e: Exception) {
            share(ctx, file)
        }
    }

    /** 其他格式（PDF、压缩包等）用系统打开；图片/视频/文本由 App 内预览，不走这里。 */
    fun openExternal(ctx: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        } catch (e: Exception) {
            share(ctx, file)
        }
    }

    fun share(ctx: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = guessMime(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(i, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
    }

    fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "txt", "md", "kt", "java", "json", "xml" -> "text/plain"
        else -> "*/*"
    }

    fun buildApiUrl(path: String): String = "https://api.github.com$path"
}
