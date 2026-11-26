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
    val localPath: String? = null
) {
    val percent: Int get() = if (total <= 0) 0 else ((done * 100) / total).toInt()
    val active: Boolean
        get() = status.startsWith("下载中") || status.startsWith("多线程下载") ||
            status in listOf("连接中", "重试中", "合并中", "准备中")
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
    val localPath: String? = null
)

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

    fun repoOf(name: String): String? = repoMap[sanitize(name)]
    private fun newEpoch(name: String): Long {
        val e = System.nanoTime()
        epochs[name] = e
        return e
    }
    private fun sameEpoch(name: String, e: Long) = e == 0L || epochs[name] == e
    private fun updateE(s: DownloadState, e: Long) { if (sameEpoch(s.fileName, e)) update(s) }
    private const val HPREFS = "ghpro_downloads"

    fun stateOf(name: String): DownloadState? = state.value[name]

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
        onDone: ((java.io.File) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): Job {
        val key = sanitize(fileName)
        jobs[key]?.takeIf { it.isActive }?.let { return it }
        val epoch = newEpoch(key)
        if (!repo.isNullOrBlank()) repoMap[key] = repo
        lateinit var job: Job
        job = appScope.launch {
            try {
                val f = download(ctx, rawUrl, fileName, token, useMirror, epoch = epoch)
                val st = stateOf(fileName)
                record(ctx, DlRecord(
                    id = java.util.UUID.randomUUID().toString(),
                    name = fileName, url = rawUrl, size = f.length(), time = System.currentTimeMillis(),
                    source = source, repo = repo, release = release,
                    publicPath = st?.publicPath, localPath = st?.localPath ?: f.absolutePath
                ))
                if (fileName.endsWith(".apk", true)) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { installApk(ctx, f) }
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) { onDone?.invoke(f) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (sameEpoch(key, epoch)) update(DownloadState(key, status = "已取消"))
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onError?.invoke(e.message ?: "下载失败") }
            } finally {
                jobs.remove(key)
            }
        }
        jobs[key] = job
        return job
    }

    fun cancelDownload(name: String): Boolean {
        val key = sanitize(name)
        newEpoch(key)
        val j = jobs.remove(key)
        j?.cancel()
        update(DownloadState(key, status = "已取消"))
        return j != null
    }

    fun retryDownload(ctx: Context, rec: DlRecord, onDone: ((File) -> Unit)? = null): Job =
        startDownload(ctx, rec.url, rec.name, Cfg.token, true, rec.source, rec.repo, rec.release, onDone = onDone)

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
                    o.optString("publicPath").takeIf { it.isNotBlank() }, o.optString("localPath").takeIf { it.isNotBlank() })
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
                put("release", r.release ?: ""); put("publicPath", r.publicPath ?: "")
                put("localPath", r.localPath ?: "")
            })
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
        historyVersion.value++
    }

    fun removeRecord(ctx: Context, id: String, deleteFile: Boolean) {
        val list = history(ctx).toMutableList()
        val target = list.firstOrNull { it.id == id }
        if (deleteFile && target?.localPath != null) runCatching { File(target.localPath!!).delete() }
        list.removeAll { it.id == id }
        save(ctx, list)
    }

    fun clearRecords(ctx: Context, deleteFiles: Boolean) {
        if (deleteFiles) history(ctx).forEach { r -> r.localPath?.let { runCatching { File(it).delete() } } }
        save(ctx, emptyList())
    }

    /** 进程重启后把上次没下完的条目改成「已中断」，不再卡在某个百分比。 */
    fun markInterrupted() {
        val cur = state.value.mapValues { (_, v) ->
            if (v.status in listOf("连接中", "下载中", "重试中", "合并中")) v.copy(status = "已中断（可重新下载）") else v
        }
        if (cur.isNotEmpty()) state.value = cur
    }

    fun categoryOf(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".apk") || n.endsWith(".xapk") || n.endsWith(".apks") -> "应用"
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif") -> "图片"
            n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov") || n.endsWith(".webm") || n.endsWith(".avi") -> "视频"
            n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".m4a") || n.endsWith(".ogg") -> "音频"
            n.endsWith(".zip") || n.endsWith(".7z") || n.endsWith(".rar") || n.endsWith(".tar") || n.endsWith(".gz") -> "压缩包"
            n.endsWith(".iso") || n.endsWith(".esd") || n.endsWith(".img") || n.endsWith(".wim") -> "镜像"
            n.endsWith(".pdf") || n.endsWith(".doc") || n.endsWith(".docx") || n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".txt") || n.endsWith(".md") -> "文档"
            else -> "其他"
        }
    }

    private fun update(s: DownloadState) {
        state.value = state.value + (s.fileName to s)
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
        epoch: Long = 0L
    ): File = withContext(Dispatchers.IO) {
        val safe = sanitize(fileName)
        val cur = state.value[safe]
        if (cur != null && cur.status in listOf("连接中", "下载中", "重试中", "合并中")) {
            throw java.io.IOException("这个文件正在下载中，进度条在上面")
        }
        val outFile = File(dir(ctx), safe)
        val cands = candidates(rawUrl, useMirror)
        var lastError: Exception? = null
        for (c in cands) {
            try {
                updateE(DownloadState(safe, status = "连接中", mirrorUsed = c), epoch)
                fetch(c, outFile, token, threads, safe, notify, epoch)
                if (notify) Notifier.downloadDone(ctx, safe, outFile.absolutePath)
                val pub = publish(ctx, outFile, safe)
                updateE(DownloadState(safe, done = outFile.length(), total = outFile.length(),
                    status = "已完成", path = pub.first, publicPath = pub.first, localPath = outFile.absolutePath,
                    mirrorUsed = c, threads = threads), epoch)
                return@withContext outFile
            } catch (e: Exception) {
                lastError = e
                outFile.delete()
                updateE(DownloadState(safe, status = "重试中", error = e.message, mirrorUsed = c), epoch)
            }
        }
        updateE(DownloadState(safe, status = "失败", error = lastError?.message ?: "下载失败"), epoch)
        throw lastError ?: IOException("下载失败")
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
        "zip" -> "application/zip"
        "json" -> "application/json"
        "txt", "log" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun fetch(url: String, outFile: File, token: String?, threadsWanted: Int, name: String, notify: Boolean, epoch: Long) {
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        val sendAuth = token != null && (host.contains("github.com") || host.contains("githubusercontent.com"))
        val head = Request.Builder().url(url)
            .header("User-Agent", "GitHubPro/1.0")
            .apply { if (sendAuth) header("Authorization", "Bearer $token") }
            .head().build()
        var total = -1L
        var acceptRanges = false
        try {
            client.newCall(head).execute().use { r ->
                if (r.isSuccessful) {
                    total = r.header("Content-Length")?.toLongOrNull() ?: -1
                    acceptRanges = r.header("Accept-Ranges")?.contains("bytes", true) == true
                }
            }
        } catch (e: Exception) { }

        val useMulti = Cfg.multiThread && threadsWanted > 1 && total > 1024 * 1024 && acceptRanges
        if (useMulti) {
            multiThread(url, outFile, token, sendAuth, total, threadsWanted, name, notify, epoch)
        } else {
            single(url, outFile, token, sendAuth, total, name, notify, epoch)
        }
    }

    private fun single(url: String, outFile: File, token: String?, sendAuth: Boolean, totalHint: Long, name: String, notify: Boolean, epoch: Long) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "GitHubPro/1.0")
            .apply { if (sendAuth) header("Authorization", "Bearer $token") }
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: totalHint
            val out = outFile.outputStream()
            val buf = ByteArray(64 * 1024)
            var done = 0L
            var last = System.currentTimeMillis()
            var lastBytes = 0L
            resp.body?.byteStream()?.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - last > 400) {
                        val speed = (done - lastBytes) * 1000 / max(1, now - last)
                        updateE(DownloadState(name, done = done, total = total, speed = speed,
                            status = if (total > 0) "下载中 ${done * 100 / total}%" else "下载中 ${done / 1024}KB",
                            threads = 1), epoch)
                        if (notify && total > 0) Notifier.downloadProgress(
                            com.luogen.ghpro.AppCtx.get(), name, (done * 100 / total).toInt()
                        )
                        last = now
                        lastBytes = done
                    }
                }
            }
            out.flush(); out.close()
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
        val chunk = (total + threads - 1) / threads
        val errors = java.util.Collections.synchronizedList(ArrayList<Exception>())
        val progress = Thread {
            var last = System.currentTimeMillis()
            var lastBytes = 0L
            while (true) {
                Thread.sleep(400)
                val d = done.get()
                if (d >= total) break
                val now = System.currentTimeMillis()
                val speed = (d - lastBytes) * 1000 / max(1, now - last)
                updateE(DownloadState(name, done = d, total = total, speed = speed,
                    status = "多线程下载 ${d * 100 / total}%", threads = threads), epoch)
                if (notify) Notifier.downloadProgress(com.luogen.ghpro.AppCtx.get(), name, (d * 100 / total).toInt())
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
                        if (!resp.isSuccessful && resp.code != 206) throw IOException("HTTP ${resp.code}")
                        resp.body?.byteStream()?.use { input ->
                            RandomAccessFile(outFile, "rw").use { raf ->
                                raf.seek(start)
                                val buf = ByteArray(32 * 1024)
                                var pos = start
                                while (pos <= end) {
                                    val n = input.read(buf, 0, min(buf.size.toLong(), end - pos + 1).toInt())
                                    if (n < 0) break
                                    raf.write(buf, 0, n)
                                    pos += n
                                    done.addAndGet(n.toLong())
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    errors.add(e)
                }
            }
            t.isDaemon = true
            t.start()
            workers.add(t)
        }
        workers.forEach { it.join() }
        if (errors.isNotEmpty()) throw IOException("分片下载失败：${errors.first().message}")
        if (outFile.length() != total) throw IOException("文件大小不符")
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
