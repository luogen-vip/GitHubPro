package com.luogen.ghpro.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, override val message: String, val body: String? = null) : Exception(message)

data class Page<T>(val items: List<T>, val hasNext: Boolean)

data class DirListing(val isFile: Boolean, val items: List<ContentItem>, val file: ContentItem?)

/** 真实上传进度：在写入网络流时按实际写出的字节回调，不是假动画。 */
private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Long, Long) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        var lastAt = 0L
        var lastPct = -1
        onProgress(0L, total)
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                val pct = if (total > 0) ((sent * 100) / total).toInt() else 0
                val now = System.currentTimeMillis()
                if (pct != lastPct || sent == total || now - lastAt >= 80) {
                    lastPct = pct; lastAt = now
                    onProgress(sent, total)
                }
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

object Api {

    @Volatile var token: String? = null
    @Volatile var base: String = "https://api.github.com"
    @Volatile var lastRateRemaining: Int = -1
    @Volatile var useCacheOnly: Boolean = false

    val gson: Gson = GsonBuilder().setLenient().create()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 大文件上传单独放宽写超时；手机上传 1-2GB 不能只给 45 秒。 */
    private val uploadHttp: OkHttpClient by lazy {
        http.newBuilder()
            .writeTimeout(30, TimeUnit.MINUTES)
            .readTimeout(30, TimeUnit.MINUTES)
            .callTimeout(30, TimeUnit.MINUTES)
            .build()
    }

    /** 搜索接口单独超时：GitHub 搜索偶尔要十几秒，给 25 秒；失败仍会明确报错，不会无限转圈。 */
    private val searchHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** 精选页只取展示需要的字段：GraphQL 返回约 15KB，REST 完整仓库对象要 200~400KB。 */
    private val leanSearchHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(13, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 轻量搜索失败后短暂停用，避免每次都先等 15 秒再退回 REST。 */
    @Volatile private var leanSearchDisabledUntil = 0L

    fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    } catch (e: Exception) { s }

    /** 仅编码每一段路径，保留 /；文件名含空格、#、中文时必须这样编码。 */
    fun encPath(path: String): String =
        path.trim('/').split('/').joinToString("/") { enc(it) }

    fun buildUrl(path: String, query: Map<String, String?> = emptyMap()): String {
        val p = if (path.startsWith("http")) path else base + path
        if (query.isEmpty()) return p
        val parts = ArrayList<String>()
        for ((k, v) in query) {
            if (v == null) continue
            parts.add(enc(k) + "=" + enc(v))
        }
        if (parts.isEmpty()) return p
        return p + (if (p.contains("?")) "&" else "?") + parts.joinToString("&")
    }

    @PublishedApi
    internal data class Resp(val body: String, val headers: Headers, val code: Int)

    @PublishedApi
    internal suspend fun exec(
        path: String,
        method: String = "GET",
        body: String? = null,
        query: Map<String, String?> = emptyMap(),
        accept: String = "application/vnd.github+json",
        absoluteUrl: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
        client: OkHttpClient = http
    ): Resp {
        var last: Exception? = null
        repeat(2) { i ->
            try {
                return execOnce(path, method, body, query, accept, absoluteUrl, extraHeaders, onProgress, client)
            } catch (e: ApiException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                kotlinx.coroutines.delay(250L * (i + 1))
            }
        }
        throw last ?: java.io.IOException("网络连接失败")
    }

    private suspend fun execOnce(
        path: String,
        method: String,
        body: String?,
        query: Map<String, String?>,
        accept: String,
        absoluteUrl: String?,
        extraHeaders: Map<String, String>,
        onProgress: ((Long, Long) -> Unit)?,
        client: OkHttpClient
    ): Resp = withContext(Dispatchers.IO) {
        val b = Request.Builder()
            .url(absoluteUrl ?: buildUrl(path, query))
            .header("User-Agent", "GitHubPro/1.0 (Android)")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
        token?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        val rawBody = when {
            method == "GET" -> null
            // DELETE 也要带上 body：删除 GitHub 文件必须提交 sha/message/branch，
            // 之前这里无条件发空 body，GitHub 会返回 422 "Invalid request."，
            // 界面就表现为单文件、多选全部删除失败。
            body != null -> body.toRequestBody(JSON)
            method == "DELETE" -> "".toRequestBody(JSON)
            else -> "{}".toRequestBody(JSON)
        }
        val rb = if (rawBody != null && onProgress != null) ProgressRequestBody(rawBody, onProgress) else rawBody
        b.method(method, rb)
        val resp = client.newCall(b.build()).execute()
        resp.use {
            val text = it.body?.string() ?: ""
            it.header("X-RateLimit-Remaining")?.toIntOrNull()?.let { r -> lastRateRemaining = r }
            if (it.code !in 200..299) {
                val msg = try {
                    val o = JsonParser.parseString(text).asJsonObject
                    o.get("message")?.asString ?: "HTTP ${it.code}"
                } catch (e: Exception) { "HTTP ${it.code}" }
                val hint = when (it.code) {
                    401 -> "令牌无效或已过期"
                    403 -> if (msg.contains("rate limit", true)) "API 次数用完了，稍后再试（每小时 5000 次）" else msg
                    404 -> "内容不存在或无权限"
                    422 -> msg
                    else -> msg
                }
                throw ApiException(it.code, hint, text)
            }
            Resp(text, it.headers, it.code)
        }
    }

    @PublishedApi
    internal fun hasNext(h: Headers): Boolean {
        val link = h["Link"] ?: return false
        return link.contains("rel=\"next\"")
    }

    suspend fun raw(
        path: String,
        method: String = "GET",
        body: String? = null,
        query: Map<String, String?> = emptyMap(),
        accept: String = "application/vnd.github+json"
    ): String = exec(path, method, body, query, accept).body

    suspend fun rawAuth(
        path: String,
        method: String = "GET",
        body: String? = null,
        query: Map<String, String?> = emptyMap(),
        accept: String = "application/vnd.github+json"
    ): String = exec(path, method, body, query, accept).body

    fun <T> parse(text: String, clazz: Class<T>): T = gson.fromJson(text, clazz)

    fun <T> parseList(text: String, type: TypeToken<*>): List<T> {
        if (text.isBlank()) return emptyList()
        val arr = JsonParser.parseString(text)
        if (!arr.isJsonArray) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return gson.fromJson(arr, type.type) as List<T>
    }

    suspend fun <T> callObj(path: String, clazz: Class<T>, query: Map<String, String?> = emptyMap(), accept: String = "application/vnd.github+json"): T =
        parse(exec(path, "GET", null, query, accept).body, clazz)

    suspend inline fun <reified T> callList(path: String, query: Map<String, String?> = emptyMap(), accept: String = "application/vnd.github+json"): Page<T> {
        val r = exec(path, "GET", null, query, accept)
        val list: List<T> = if (r.body.isBlank()) emptyList() else {
            val el = JsonParser.parseString(r.body)
            if (!el.isJsonArray) emptyList()
            else el.asJsonArray.map { e -> gson.fromJson(e, T::class.java) }
        }
        return Page(list, hasNext(r.headers))
    }


    inline fun <reified T> arr(el: JsonElement?): List<T> {
        if (el == null || !el.isJsonArray) return emptyList()
        return try {
            el.asJsonArray.map { e -> gson.fromJson(e, T::class.java) }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun graphql(query: String, variables: JsonObject = JsonObject(), client: OkHttpClient = http, singleAttempt: Boolean = false): JsonObject {
        val payload = JsonObject().apply {
            addProperty("query", query)
            add("variables", variables)
        }
        val text = if (singleAttempt) {
            execOnce("/graphql", "POST", payload.toString(), emptyMap(), "application/json", null, emptyMap(), null, client).body
        } else {
            exec("/graphql", "POST", payload.toString(), accept = "application/json", client = client).body
        }
        val o = JsonParser.parseString(text).asJsonObject
        if (o.has("errors") && !o.has("data")) {
            throw ApiException(400, o.getAsJsonArray("errors").firstOrNull()?.asJsonObject?.get("message")?.asString ?: "GraphQL 错误")
        }
        return o.getAsJsonObject("data") ?: JsonObject()
    }

    // ---------------- 用户 ----------------
    suspend fun me(): User = callObj("/user", User::class.java)
    suspend fun user(login: String): User = callObj("/users/$login", User::class.java)
    suspend fun myRepos(page: Int, perPage: Int = 60, sort: String = "updated", type: String? = null): Page<Repo> {
        // “最近更新”用 pushed（最后一次代码推送）；GitHub 的 updated 会把改描述、改设置也算进去。
        // stars 由客户端排序，这里先按 pushed 拉取，避免顺序混乱。
        val apiSort = when (sort) {
            "stars" -> "pushed"
            "updated" -> "pushed"
            else -> sort
        }
        val dir = if (apiSort == "full_name") "asc" else "desc"
        return callList("/user/repos", mapOf("page" to "$page", "per_page" to "$perPage",
            "sort" to apiSort, "direction" to dir,
            "affiliation" to "owner,collaborator,organization_member", "type" to type))
    }
    suspend fun userRepos(login: String, page: Int, perPage: Int = 60, sort: String = "updated"): Page<Repo> =
        callList("/users/$login/repos", mapOf("page" to "$page", "per_page" to "$perPage", "sort" to sort))
    suspend fun starred(page: Int): Page<Repo> {
        val r = exec("/user/starred", "GET", null, mapOf("page" to "$page", "per_page" to "60"),
            accept = "application/vnd.github.star+json")
        val list: List<Repo> = try {
            val el = JsonParser.parseString(r.body)
            if (!el.isJsonArray) emptyList() else el.asJsonArray.map { e ->
                if (e.isJsonObject && e.asJsonObject.has("repo")) gson.fromJson(e.asJsonObject.get("repo"), Repo::class.java)
                else gson.fromJson(e, Repo::class.java)
            }
        } catch (e: Exception) { emptyList() }
        return Page(list, hasNext(r.headers))
    }
    suspend fun followers(login: String, page: Int): Page<User> = callList("/users/$login/followers", mapOf("page" to "$page", "per_page" to "60"))
    suspend fun following(login: String, page: Int): Page<User> = callList("/users/$login/following", mapOf("page" to "$page", "per_page" to "60"))
    suspend fun isFollowing(login: String): Boolean = try {
        exec("/user/following/$login").code == 204
    } catch (e: ApiException) { e.code == 204 }
    suspend fun follow(login: String, on: Boolean) {
        if (on) exec("/user/following/$login", "PUT") else exec("/user/following/$login", "DELETE")
    }
    suspend fun userEvents(login: String): List<GhEvent> = callList<GhEvent>("/users/$login/events/public", mapOf("per_page" to "30")).items

    // ---------------- 仓库 ----------------
    suspend fun repo(o: String, r: String): Repo = callObj("/repos/$o/$r", Repo::class.java)
    suspend fun languages(o: String, r: String): Map<String, Long> {
        val text = raw("/repos/$o/$r/languages")
        val out = LinkedHashMap<String, Long>()
        runCatching {
            JsonParser.parseString(text).asJsonObject.entrySet().forEach { (k, v) -> out[k] = v.asLong }
        }
        return out.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }
    suspend fun contributors(o: String, r: String): List<User> =
        callList<User>("/repos/$o/$r/contributors", mapOf("per_page" to "100")).items
    suspend fun branches(o: String, r: String): List<Branch> = callList<Branch>("/repos/$o/$r/branches", mapOf("per_page" to "100")).items
    suspend fun commits(o: String, r: String, sha: String? = null, page: Int = 1): Page<GitCommit> =
        callList("/repos/$o/$r/commits", mapOf("per_page" to "30", "page" to "$page", "sha" to sha))
    suspend fun commitDetail(o: String, r: String, sha: String): GitCommit = callObj("/repos/$o/$r/commits/$sha", GitCommit::class.java)
    suspend fun repoEvents(o: String, r: String): List<GhEvent> = callList<GhEvent>("/repos/$o/$r/events", mapOf("per_page" to "30")).items

    suspend fun contents(o: String, r: String, path: String, ref: String?): DirListing {
        val text = raw("/repos/$o/$r/contents/${encPath(path)}", query = mapOf("ref" to ref))
        if (text.isBlank()) return DirListing(false, emptyList(), null)
        val el = JsonParser.parseString(text)
        return if (el.isJsonArray) {
            DirListing(false, arr<ContentItem>(el), null)
        } else {
            val c = gson.fromJson(el, ContentItem::class.java)
            DirListing(true, listOf(c), c)
        }
    }

    suspend fun fileContent(o: String, r: String, path: String, ref: String?): String {
        val c = callObj("/repos/$o/$r/contents/${encPath(path)}", ContentItem::class.java, mapOf("ref" to ref))
        if (c.encoding == "base64" && c.content != null) {
            return String(android.util.Base64.decode(c.content.replace("\n", ""), android.util.Base64.DEFAULT))
        }
        return c.content ?: ""
    }

    /** 超过 1MB 时用 raw 媒体类型直接取文件字节（GitHub 仍限制单文件 100MB）。 */
    suspend fun fileRaw(o: String, r: String, path: String, ref: String?): ByteArray =
        withContext(Dispatchers.IO) {
            val url = buildUrl("/repos/$o/$r/contents/${encPath(path)}", mapOf("ref" to ref))
            val req = Request.Builder().url(url)
                .header("Accept", "application/vnd.github.raw")
                .header("User-Agent", "GitHubPro/1.0")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.bytes() ?: throw IOException("文件为空")
            }
        }

    /** 读取文件元信息（含 sha，编辑保存时要用）。 */
    suspend fun fileItem(o: String, r: String, path: String, ref: String?): ContentItem =
        callObj("/repos/$o/$r/contents/${encPath(path)}", ContentItem::class.java, mapOf("ref" to ref))

    suspend fun putFile(o: String, r: String, path: String, message: String, base64: String, sha: String?, branch: String?, onProgress: ((Long, Long) -> Unit)? = null): JsonObject {
        val body = JsonObject().apply {
            addProperty("message", message)
            addProperty("content", base64)
            if (sha != null) addProperty("sha", sha)
            if (branch != null) addProperty("branch", branch)
        }
        return JsonParser.parseString(exec("/repos/$o/$r/contents/${encPath(path)}", "PUT", body.toString(), onProgress = onProgress).body).asJsonObject
    }

    private suspend fun collectFiles(o: String, r: String, path: String, branch: String?): List<String> {
        val head = contents(o, r, path, branch)
        if (head.isFile) return listOf(head.file?.path ?: path)
        val out = ArrayList<String>()
        head.items.forEach { child ->
            if (child.type == "dir") out.addAll(collectFiles(o, r, child.path, branch))
            else out.add(child.path)
        }
        return out
    }

    /** 真实删除文件或整个文件夹：一次 Git Tree 提交，目录下所有文件一起删掉。 */
    suspend fun deletePath(o: String, r: String, path: String, branch: String?): Int =
        deletePaths(o, r, listOf(path), branch)

    /**
     * 多选删除单独走 Contents API：单文件删除已经被真实仓库验证可用，
     * 多选时不再先试整树提交。整树提交在部分仓库/分支会被 404/422 拒绝，
     * 之前失败后虽然会回退，但一次失败会把整批文件还原，用户看到就是“多选删除失败”。
     * 这里逐个选中项解析成文件再删：文件一项一个提交，文件夹递归收集后逐个删；
     * 能删多少算多少，只有真的一个都没删才抛错。
     */
    private suspend fun deleteByContents(o: String, r: String, wanted: List<String>, branchName: String): Int {
        var deleted = 0
        var firstError: Exception? = null
        var sawAny = false
        wanted.forEach { p ->
            try {
                val head = contents(o, r, p, branchName)
                if (head.isFile) {
                    val sha = head.file?.sha
                    if (sha.isNullOrBlank()) throw IOException("拿不到文件标识：$p")
                    deleteFile(o, r, p, "delete $p", sha, branchName)
                    deleted++
                    sawAny = true
                } else {
                    val files = collectFiles(o, r, p, branchName)
                    files.forEach { f ->
                        val item = fileItem(o, r, f, branchName)
                        val sha = item.sha
                        if (sha.isNullOrBlank()) return@forEach
                        deleteFile(o, r, f, "delete $f", sha, branchName)
                        deleted++
                        sawAny = true
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 404 视为这一项已经不存在；其它错误先记下，继续删下一项，不让一个坏项拖垮整批。
                if ((e as? ApiException)?.code != 404) firstError = firstError ?: e
            }
        }
        if (!sawAny && firstError != null) throw firstError!!
        return deleted
    }

    suspend fun deletePaths(o: String, r: String, paths: List<String>, branch: String?): Int {
        val wanted0 = paths.map { it.trim('/') }.filter { it.isNotBlank() }.distinct()
        val branch0 = branch?.takeIf { it.isNotBlank() } ?: "main"
        // 多选：按 Contents API 逐个删除。单文件已经验证可用，这条路不会再因整树提交失败而整批还原。
        if (wanted0.size > 1) return deleteByContents(o, r, wanted0, branch0)
        val wanted = wanted0
        if (wanted.isEmpty()) return 0
        val branchName = branch0
        val ref = exec("/repos/$o/$r/git/ref/heads/${enc(branchName)}").body.let { JsonParser.parseString(it).asJsonObject }
        val commitSha = ref.getAsJsonObject("object").get("sha").asString
        val commit = exec("/repos/$o/$r/git/commits/$commitSha").body.let { JsonParser.parseString(it).asJsonObject }
        val baseTree = commit.getAsJsonObject("tree").get("sha").asString

        // 一次递归读整棵树，按路径前缀精确匹配；不再对每个选中项反复打 contents。
        // 这样多选文件/文件夹时不会因为某一次目录请求失败就全删失败。
        val treeObj = runCatching {
            JsonParser.parseString(exec("/repos/$o/$r/git/trees/$baseTree", query = mapOf("recursive" to "1")).body).asJsonObject
        }.getOrNull()
        val matched = ArrayList<JsonObject>()
        treeObj?.getAsJsonArray("tree")?.forEach { el ->
            val e = el.asJsonObject
            if (e.get("type")?.asString != "blob") return@forEach
            val path = e.get("path")?.asString ?: return@forEach
            if (wanted.any { w -> path == w || path.startsWith("$w/") }) matched.add(e)
        }
        // 大树被 GitHub 截断 / 递归树读取失败时，退回逐目录 collectFiles；
        // 绝不把“树没读到”当成“没有文件”静默返回 0。
        if (matched.isEmpty() || treeObj?.get("truncated")?.asBoolean == true) {
            matched.clear()
            wanted.forEach { p ->
                runCatching { collectFiles(o, r, p, branchName) }.getOrDefault(emptyList()).forEach { f ->
                    matched.add(JsonObject().apply {
                        addProperty("path", f)
                        addProperty("mode", "100644")
                        addProperty("type", "blob")
                    })
                }
            }
        }
        val files = matched.mapNotNull { it.get("path")?.asString }.distinct()

        suspend fun makeTree(): String {
            val entries = JsonArray()
            matched.forEach { e ->
                entries.add(JsonObject().apply {
                    addProperty("path", e.get("path").asString)
                    addProperty("mode", e.get("mode")?.asString ?: "100644")
                    addProperty("type", "blob")
                    add("sha", com.google.gson.JsonNull.INSTANCE)
                })
            }
            val treeBody = JsonObject().apply { addProperty("base_tree", baseTree); add("tree", entries) }
            return JsonParser.parseString(exec("/repos/$o/$r/git/trees", "POST", treeBody.toString()).body)
                .asJsonObject.get("sha").asString
        }

        if (files.isEmpty()) return 0
        try {
            val newTree = makeTree()
            val commitBody = JsonObject().apply {
                addProperty("message", "delete ${files.size} file(s)")
                addProperty("tree", newTree)
                add("parents", JsonArray().apply { add(commitSha) })
            }
            val newCommit = JsonParser.parseString(exec("/repos/$o/$r/git/commits", "POST", commitBody.toString()).body)
                .asJsonObject.get("sha").asString
            val patch = JsonObject().apply { addProperty("sha", newCommit) }
            exec("/repos/$o/$r/git/refs/heads/${enc(branchName)}", "PATCH", patch.toString())
            return files.size
        } catch (treeError: Exception) {
            // 极端仓库（子模块/特殊 mode/受保护规则）一次树提交可能被拒：
            // 逐个文件走 Contents API 兜底，能删一个算一个，不再“一个都没删”。
            var deleted = 0
            files.forEach { f ->
                val item = runCatching { fileItem(o, r, f, branchName) }.getOrNull() ?: return@forEach
                runCatching {
                    deleteFile(o, r, f, "delete $f", item.sha, branchName)
                    deleted++
                }
            }
            if (deleted == 0) throw treeError
            return deleted
        }
    }

    suspend fun deleteFile(o: String, r: String, path: String, message: String, sha: String, branch: String?): JsonObject {
        val body = JsonObject().apply {
            addProperty("message", message)
            addProperty("sha", sha)
            if (branch != null) addProperty("branch", branch)
        }
        return JsonParser.parseString(exec("/repos/$o/$r/contents/${encPath(path)}", "DELETE", body.toString()).body).asJsonObject
    }

    suspend fun createRepo(
        name: String, description: String, isPrivate: Boolean, autoInit: Boolean,
        homepage: String?, gitignore: String?, license: String?, hasIssues: Boolean = true,
        hasWiki: Boolean = false, hasProjects: Boolean = true, hasDiscussions: Boolean = false
    ): Repo {
        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            addProperty("private", isPrivate)
            addProperty("auto_init", autoInit)
            addProperty("has_issues", hasIssues)
            addProperty("has_wiki", hasWiki)
            addProperty("has_projects", hasProjects)
            addProperty("has_discussions", hasDiscussions)
            if (!homepage.isNullOrBlank()) addProperty("homepage", homepage)
            if (!gitignore.isNullOrBlank()) addProperty("gitignore_template", gitignore)
            if (!license.isNullOrBlank()) addProperty("license_template", license)
        }
        return parse(exec("/user/repos", "POST", body.toString()).body, Repo::class.java)
    }

    suspend fun patchRepo(o: String, r: String, fields: Map<String, Any?>): Repo {
        val body = JsonObject()
        fields.forEach { (k, v) ->
            when (v) {
                null -> {}
                is Boolean -> body.addProperty(k, v)
                is Number -> body.addProperty(k, v)
                is String -> body.addProperty(k, v)
                else -> body.addProperty(k, v.toString())
            }
        }
        if (body.size() == 0) return repo(o, r)
        return parse(exec("/repos/$o/$r", "PATCH", body.toString()).body, Repo::class.java)
    }

    suspend fun deleteRepo(o: String, r: String) { exec("/repos/$o/$r", "DELETE") }

    suspend fun renameRepo(o: String, r: String, newName: String): Repo =
        patchRepo(o, r, mapOf("name" to newName))

    suspend fun setTopics(o: String, r: String, topics: List<String>): JsonObject {
        val body = JsonObject().apply { add("names", JsonArray().apply { topics.forEach { add(it) } }) }
        return JsonParser.parseString(exec("/repos/$o/$r/topics", "PUT", body.toString(), accept = "application/vnd.github+json").body).asJsonObject
    }

    suspend fun isStarred(o: String, r: String): Boolean = try { exec("/user/starred/$o/$r").code == 204 } catch (e: ApiException) { e.code == 204 }
    suspend fun star(o: String, r: String, on: Boolean) {
        val body = JsonObject().apply { addProperty("starred", on) }.toString()
        if (on) exec("/user/starred/$o/$r", "PUT") else exec("/user/starred/$o/$r", "DELETE")
    }
    suspend fun stargazers(o: String, r: String, page: Int = 1, perPage: Int = 100): Page<User> =
        callList("/repos/$o/$r/stargazers", mapOf("page" to "$page", "per_page" to "$perPage"))
    /** REST 的 Star 名单对部分仓库返回 404 时，用 GraphQL 兜底取前 100 个真实用户。 */
    suspend fun stargazersGraphql(o: String, r: String): Page<User> {
        val q = "query(\$owner:String!,\$name:String!){repository(owner:\$owner,name:\$name){stargazers(first:100){totalCount pageInfo{hasNextPage} edges{node{login avatarUrl name}}}}}"

        val vars = JsonObject().apply { addProperty("owner", o); addProperty("name", r) }
        val data = graphql(q, vars).getAsJsonObject("data")?.getAsJsonObject("repository")
            ?.getAsJsonObject("stargazers") ?: return Page(emptyList(), false)
        val users = ArrayList<User>()
        data.getAsJsonArray("edges")?.forEach { e ->
            val n = e.asJsonObject.getAsJsonObject("node")
            users.add(User(
                login = n.get("login")?.asString ?: "",
                avatar_url = n.get("avatarUrl")?.asString,
                name = n.get("name")?.takeIf { !it.isJsonNull }?.asString
            ))
        }
        // GraphQL 兜底只取前 100 个，避免翻页重复；名单被 GitHub 隐藏时本来就没有更多
        return Page(users, false)
    }

    suspend fun forks(o: String, r: String, page: Int = 1, perPage: Int = 100): Page<Repo> =
        callList("/repos/$o/$r/forks", mapOf("page" to "$page", "per_page" to "$perPage"))
    suspend fun watchers(o: String, r: String, page: Int = 1, perPage: Int = 100): Page<User> =
        callList("/repos/$o/$r/subscribers", mapOf("page" to "$page", "per_page" to "$perPage"))

    /** 当前登录用户真实订阅（watch）的全部仓库；和本地监听列表不同，这是 GitHub 服务端真值。 */
    suspend fun watchedRepos(page: Int = 1, perPage: Int = 100): Page<Repo> =
        callList("/user/subscriptions", mapOf("page" to "$page", "per_page" to "$perPage"))
    suspend fun isWatching(o: String, r: String): Boolean = try {
        // GitHub 的 subscription 接口对“未关注”也会返回 200，只是 subscribed=false；
        // 之前只看 200 会把所有仓库都当成已关注，取消后进页面又“变回关注”。
        val obj = JsonParser.parseString(exec("/repos/$o/$r/subscription").body).asJsonObject
        obj.get("subscribed")?.asBoolean == true
    } catch (e: ApiException) {
        // 404 才是真的没有订阅；网络/解析错误交给调用方保留旧状态，不能假装成“未关注”。
        if (e.code == 404) false else throw e
    }
    suspend fun watch(o: String, r: String, on: Boolean) {
        // 取消订阅必须走 DELETE；GitHub 文档里 PUT {"subscribed":false} 并不会真正取消，
        // 这也是之前“列表里消失了，但服务端还继续发订阅通知”的根因。
        if (on) {
            val body = JsonObject().apply { addProperty("subscribed", true) }.toString()
            exec("/repos/$o/$r/subscription", "PUT", body)
        } else {
            // 已经取消过（服务端 404）也算成功，不能因此把本地列表又“回滚”成已订阅。
            try {
                exec("/repos/$o/$r/subscription", "DELETE")
            } catch (e: ApiException) {
                if (e.code != 404) throw e
            }
        }
    }
    suspend fun fork(o: String, r: String): Repo {
        val body = JsonObject().apply { addProperty("default_branch_only", true) }.toString()
        return parse(exec("/repos/$o/$r/forks", "POST", body).body, Repo::class.java)
    }

    // ---------------- 议题 ----------------
    suspend fun issues(o: String, r: String, state: String, page: Int, labels: String? = null): Page<Issue> =
        callList("/repos/$o/$r/issues", mapOf("state" to state, "per_page" to "30", "page" to "$page", "labels" to labels))
    suspend fun issue(o: String, r: String, n: Int): Issue = callObj("/repos/$o/$r/issues/$n", Issue::class.java)
    suspend fun createIssue(o: String, r: String, title: String, body: String?, labels: List<String>?, assignees: List<String>?): Issue {
        val jo = JsonObject().apply {
            addProperty("title", title)
            if (!body.isNullOrBlank()) addProperty("body", body)
            if (!labels.isNullOrEmpty()) add("labels", JsonArray().apply { labels.forEach { add(it) } })
            if (!assignees.isNullOrEmpty()) add("assignees", JsonArray().apply { assignees.forEach { add(it) } })
        }
        return parse(exec("/repos/$o/$r/issues", "POST", jo.toString()).body, Issue::class.java)
    }
    suspend fun patchIssue(o: String, r: String, n: Int, fields: Map<String, Any?>): Issue {
        val jo = JsonObject()
        fields.forEach { (k, v) ->
            when (v) {
                is String -> jo.addProperty(k, v)
                is Boolean -> jo.addProperty(k, v)
                is Number -> jo.addProperty(k, v)
                null -> {}
                else -> jo.addProperty(k, v.toString())
            }
        }
        return parse(exec("/repos/$o/$r/issues/$n", "PATCH", jo.toString()).body, Issue::class.java)
    }
    suspend fun issueComments(o: String, r: String, n: Int, page: Int = 1): List<Comment> =
        callList<Comment>("/repos/$o/$r/issues/$n/comments", mapOf("per_page" to "50", "page" to "$page")).items
    suspend fun addComment(o: String, r: String, n: Int, body: String): Comment {
        val jo = JsonObject().apply { addProperty("body", body) }
        return parse(exec("/repos/$o/$r/issues/$n/comments", "POST", jo.toString()).body, Comment::class.java)
    }
    suspend fun issueReactions(o: String, r: String, n: Int): List<JsonObject> {
        val arr = runCatching {
            JsonParser.parseString(exec("/repos/$o/$r/issues/$n/reactions", query = mapOf("per_page" to "100"),
                accept = "application/vnd.github.squirrel-girl+json").body).asJsonArray
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
    }

    /** 点一下表态：已有的删除，没有的添加。返回操作后的反应列表。 */
    suspend fun toggleIssueReaction(o: String, r: String, n: Int, content: String): List<JsonObject> {
        val existing = issueReactions(o, r, n).firstOrNull {
            it.get("content")?.asString == content && it.getAsJsonObject("user")?.get("login")?.asString == Cfg.login
        }
        val id = existing?.get("id")?.asLong
        if (id != null) {
            exec("/repos/$o/$r/issues/$n/reactions/$id", "DELETE", null, emptyMap(),
                "application/vnd.github.squirrel-girl+json")
        } else {
            val jo = JsonObject().apply { addProperty("content", content) }
            exec("/repos/$o/$r/issues/$n/reactions", "POST", jo.toString(), emptyMap(),
                "application/vnd.github.squirrel-girl+json")
        }
        return issueReactions(o, r, n)
    }

    suspend fun lockIssue(o: String, r: String, n: Int, lock: Boolean, reason: String = "resolved") {
        val jo = JsonObject().apply {
            addProperty("lock_reason", reason)
        }
        if (lock) exec("/repos/$o/$r/issues/$n/lock", "PUT", jo.toString()) else exec("/repos/$o/$r/issues/$n/lock", "DELETE")
    }

    // ---------------- 拉取请求 ----------------
    suspend fun pulls(o: String, r: String, state: String, page: Int): Page<PullRequest> =
        callList("/repos/$o/$r/pulls", mapOf("state" to state, "per_page" to "30", "page" to "$page"))
    suspend fun pull(o: String, r: String, n: Int): PullRequest = callObj("/repos/$o/$r/pulls/$n", PullRequest::class.java)
    suspend fun prFiles(o: String, r: String, n: Int): List<PRFile> =
        callList<PRFile>("/repos/$o/$r/pulls/$n/files", mapOf("per_page" to "100")).items
    suspend fun prComments(o: String, r: String, n: Int): List<Comment> =
        callList<Comment>("/repos/$o/$r/pulls/$n/comments", mapOf("per_page" to "50")).items
    /** 真实创建 Pull Request。head 支持 "分支名"（同仓库）或 "用户名:分支名"（跨复刻仓库）。 */
    suspend fun createPull(o: String, r: String, title: String, head: String, base: String, body: String? = null, draft: Boolean = false): PullRequest {
        val jo = JsonObject().apply {
            addProperty("title", title)
            addProperty("head", head)
            addProperty("base", base)
            addProperty("body", body ?: "")
            addProperty("draft", draft)
        }
        return parse(exec("/repos/$o/$r/pulls", "POST", jo.toString()).body, PullRequest::class.java)
    }

    suspend fun mergePr(o: String, r: String, n: Int, message: String?, method: String = "merge"): JsonObject {
        val jo = JsonObject().apply {
            if (message != null) addProperty("commit_message", message)
            addProperty("merge_method", method)
        }
        return JsonParser.parseString(exec("/repos/$o/$r/pulls/$n/merge", "PUT", jo.toString()).body).asJsonObject
    }
    suspend fun closePr(o: String, r: String, n: Int) {
        val jo = JsonObject().apply { addProperty("state", "closed") }
        exec("/repos/$o/$r/pulls/$n", "PATCH", jo.toString())
    }
    suspend fun reopenPr(o: String, r: String, n: Int) {
        val jo = JsonObject().apply { addProperty("state", "open") }
        exec("/repos/$o/$r/pulls/$n", "PATCH", jo.toString())
    }
    suspend fun requestReviewers(o: String, r: String, n: Int, reviewers: List<String>) {
        val jo = JsonObject().apply {
            add("reviewers", JsonArray().apply { reviewers.filter { it.isNotBlank() }.distinct().forEach { add(it.trim().removePrefix("@")) } })
        }
        exec("/repos/$o/$r/pulls/$n/requested_reviewers", "POST", jo.toString())
    }

    suspend fun prReviews(o: String, r: String, n: Int): List<JsonObject> {
        val arr = runCatching { JsonParser.parseString(raw("/repos/$o/$r/pulls/$n/reviews", query = mapOf("per_page" to "50"))).asJsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
    }

    suspend fun prReview(o: String, r: String, n: Int, event: String, body: String?) {
        val jo = JsonObject().apply {
            addProperty("event", event)
            if (!body.isNullOrBlank()) addProperty("body", body)
        }
        exec("/repos/$o/$r/pulls/$n/reviews", "POST", jo.toString())
    }

    // ---------------- Actions 工作流 ----------------
    suspend fun workflows(o: String, r: String): List<Workflow> =
        callList<Workflow>("/repos/$o/$r/actions/workflows", mapOf("per_page" to "50")).items
    suspend fun runs(o: String, r: String, page: Int = 1, branch: String? = null, status: String? = null): Pair<List<Run>, Boolean> {
        val body = raw("/repos/$o/$r/actions/runs",
            query = mapOf("per_page" to "20", "page" to "$page", "branch" to branch, "status" to status))
        val arrEl = runCatching { JsonParser.parseString(body).asJsonObject.getAsJsonArray("workflow_runs") }.getOrNull()
        val items: List<Run> = arr<Run>(arrEl)
        return Pair(items, items.size >= 20)
    }
    suspend fun run(o: String, r: String, runId: Long): Run = callObj("/repos/$o/$r/actions/runs/$runId", Run::class.java)
    suspend fun runJobs(o: String, r: String, runId: Long): List<Job> =
        runCatching {
            val text = raw("/repos/$o/$r/actions/runs/$runId/jobs", query = mapOf("per_page" to "50"))
            JsonParser.parseString(text).asJsonObject.getAsJsonArray("jobs")?.let {
                arr<Job>(it)
            } ?: emptyList()
        }.getOrDefault(emptyList())
    suspend fun runArtifacts(o: String, r: String, runId: Long): List<Artifact> =
        runCatching {
            val text = raw("/repos/$o/$r/actions/runs/$runId/artifacts")
            JsonParser.parseString(text).asJsonObject.getAsJsonArray("artifacts")?.let {
                arr<Artifact>(it)
            } ?: emptyList()
        }.getOrDefault(emptyList())
    suspend fun repoArtifacts(o: String, r: String): List<Artifact> =
        runCatching {
            val text = raw("/repos/$o/$r/actions/artifacts", query = mapOf("per_page" to "50"))
            JsonParser.parseString(text).asJsonObject.getAsJsonArray("artifacts")?.let {
                arr<Artifact>(it)
            } ?: emptyList()
        }.getOrDefault(emptyList())
    suspend fun rerun(o: String, r: String, runId: Long) { exec("/repos/$o/$r/actions/runs/$runId/rerun", "POST", "{}") }
    suspend fun cancelRun(o: String, r: String, runId: Long) { exec("/repos/$o/$r/actions/runs/$runId/cancel", "POST", "{}") }
    suspend fun deleteRun(o: String, r: String, runId: Long) { exec("/repos/$o/$r/actions/runs/$runId", "DELETE") }
    suspend fun deleteArtifact(o: String, r: String, artifactId: Long) { exec("/repos/$o/$r/actions/artifacts/$artifactId", "DELETE") }
    suspend fun dispatch(o: String, r: String, workflowId: Long, ref: String, inputs: Map<String, String> = emptyMap()) {
        val jo = JsonObject().apply {
            addProperty("ref", ref)
            if (inputs.isEmpty()) add("inputs", JsonObject()) else add("inputs", JsonObject().apply { inputs.forEach { (k, v) -> addProperty(k, v) } })
        }
        exec("/repos/$o/$r/actions/workflows/$workflowId/dispatches", "POST", jo.toString())
    }
    suspend fun jobLogs(o: String, r: String, jobId: Long): String = raw("/repos/$o/$r/actions/jobs/$jobId/logs")

    /** 取某个任务日志 zip 中对应步骤的纯文本；匹配不到时回退整份日志，绝不直接失败。 */
    private fun sliceLogByTime(text: String, startedAt: String?, completedAt: String?): String {
        if (startedAt.isNullOrBlank()) return ""
        val from = runCatching { java.time.Instant.parse(startedAt) }.getOrNull() ?: return ""
        val to = runCatching { completedAt?.let { java.time.Instant.parse(it) } }.getOrNull()
        val fromMs = from.toEpochMilli() - 200
        val toMs = to?.toEpochMilli()?.plus(300) ?: Long.MAX_VALUE
        val out = StringBuilder()
        var inside = false
        text.lineSequence().forEach { line ->
            val ts = line.trimStart('\uFEFF').substringBefore(' ', "")
            val at = runCatching { java.time.Instant.parse(ts) }.getOrNull()
            if (at != null) inside = at.toEpochMilli() in fromMs..toMs
            if (inside) out.append(line).append('\n')
        }
        return out.toString().trim()
    }

    private fun sliceLogByName(text: String, stepName: String): String {
        val lines = text.lines()
        val exact = lines.indexOfFirst { it.contains("##[group]$stepName") || it.contains("Starting: $stepName") }
        if (exact < 0) return ""
        val end = (exact + 1 until lines.size).firstOrNull {
            lines[it].contains("##[group]Run ") || lines[it].contains("##[debug]Finishing: $stepName")
        } ?: lines.size
        return lines.subList(exact, end).joinToString("\n").take(30000)
    }

    suspend fun stepLogText(o: String, r: String, jobId: Long, stepNumber: Int, stepName: String,
                            startedAt: String? = null, completedAt: String? = null): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$base/repos/$o/$r/actions/jobs/$jobId/logs")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GitHubPro/1.0")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Actions 日志默认只保留 90 天；过期后 GitHub 会回 410，不是 App 网络错误
                    when (resp.code) {
                        410 -> throw IOException("这次运行的日志已被 GitHub 自动清理（Actions 日志默认只保留 90 天），不是 App 出错")
                        404 -> throw IOException("日志还没生成，或已经被清理掉了")
                        403 -> throw IOException("当前登录账号没有读取这个仓库日志的权限")
                        else -> throw IOException("HTTP ${resp.code}")
                    }
                }
                val bytes = resp.body?.bytes() ?: throw IOException("日志为空")
                val all = StringBuilder()
                var matched: String? = null
                var sawZip = false
                try {
                    java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            sawZip = true
                            val n = e.name
                            val txt = zip.readBytes().toString(Charsets.UTF_8)
                            val simple = n.substringAfterLast('/')
                            val stepKey = stepName.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5]+"), "").take(12)
                            val simpleKey = simple.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5]+"), "")
                            val hit = simple.startsWith("${stepNumber}_") ||
                                simple.startsWith("$stepNumber.") ||
                                simpleKey.startsWith("${stepNumber}_") ||
                                (stepKey.length >= 3 && simpleKey.contains(stepKey, ignoreCase = true))
                            if (hit && matched == null) { matched = txt.take(30000); break }
                            if (all.length < 30000) {
                                all.append("\n===== ").append(simple).append(" =====\n")
                                all.append(txt.take(6000))
                            }
                        }
                    }
                } catch (_: Exception) { }
                matched?.let { return@withContext it }
                if (sawZip) {
                    if (all.isNotBlank()) return@withContext "（没有精确匹配到第 $stepNumber 步，下面是整个任务的日志）\n" + all.take(30000)
                    throw IOException("日志为空")
                }
                val plain = bytes.toString(Charsets.UTF_8)
                if (plain.isNotBlank() && !plain.startsWith("PK")) {
                    val byTime = sliceLogByTime(plain, startedAt, completedAt)
                    if (byTime.isNotBlank()) return@withContext byTime.take(30000)
                    val byName = sliceLogByName(plain, stepName)
                    if (byName.isNotBlank()) return@withContext byName.take(30000)
                    return@withContext "（没有精确定位到第 $stepNumber 步，下面是整个任务日志）\n" + plain.take(30000)
                }
                throw IOException("日志格式无法识别")
            }
        }

    // ---------------- Git LFS（大文件） ----------------
    private fun sha256File(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun basicAuth(): String {
        val user = Cfg.login ?: "x-access-token"
        val raw = "$user:$token"
        return "Basic " + android.util.Base64.encodeToString(raw.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** 把文件上传到 GitHub LFS（单文件最大 2GB，受账号 LFS 配额限制），返回 oid。 */
    suspend fun lfsUpload(o: String, r: String, file: java.io.File, onProgress: ((Long, Long) -> Unit)? = null): String = withContext(Dispatchers.IO) {
        val oid = sha256File(file)
        val body = JsonObject().apply {
            addProperty("operation", "upload")
            add("transfers", JsonArray().apply { add("basic") })
            add("objects", JsonArray().apply {
                add(JsonObject().apply { addProperty("oid", oid); addProperty("size", file.length()) })
            })
            addProperty("hash_algo", "sha256")
        }
        val req = Request.Builder()
            .url("https://github.com/$o/$r.git/info/lfs/objects/batch")
            .header("Accept", "application/vnd.git-lfs+json")
            .header("Content-Type", "application/vnd.git-lfs+json")
            .header("Authorization", basicAuth())
            .header("User-Agent", "GitHubPro/1.0")
            .post(body.toString().toRequestBody("application/vnd.git-lfs+json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("LFS 拒绝（HTTP ${resp.code}）：${txt.take(300)}")
            val root = JsonParser.parseString(txt).asJsonObject
            val obj = root.getAsJsonArray("objects")?.firstOrNull()?.asJsonObject
                ?: throw IOException("LFS 返回为空")
            obj.getAsJsonObject("error")?.let { throw IOException(it.get("message")?.asString ?: "LFS 上传失败") }
            val actions = obj.getAsJsonObject("actions")
            val upload = actions?.getAsJsonObject("upload")
            if (upload == null) return@withContext oid // 服务器已有这个对象
            val href = upload.get("href")?.asString ?: throw IOException("LFS 上传地址为空")
            val headers = HashMap<String, String>()
            upload.getAsJsonObject("header")?.entrySet()?.forEach { (k, v) -> headers[k] = v.asString }
            val pb = Request.Builder().url(href)
            headers.forEach { (k, v) -> pb.header(k, v) }
            val raw = file.asRequestBody("application/octet-stream".toMediaType())
            val put = pb.put(if (onProgress != null) ProgressRequestBody(raw, onProgress) else raw).build()
            uploadHttp.newCall(put).execute().use { up ->
                if (!up.isSuccessful) throw IOException("LFS 文件上传失败（HTTP ${up.code}）")
            }
            oid
        }
    }

    /** 取 LFS 大文件下载地址（S3 预签名，不需要再带 token）。 */
    suspend fun lfsDownloadHref(o: String, r: String, oid: String, size: Long): String = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("operation", "download")
            add("transfers", JsonArray().apply { add("basic") })
            add("objects", JsonArray().apply {
                add(JsonObject().apply { addProperty("oid", oid); addProperty("size", size) })
            })
            addProperty("hash_algo", "sha256")
        }
        val req = Request.Builder()
            .url("https://github.com/$o/$r.git/info/lfs/objects/batch")
            .header("Accept", "application/vnd.git-lfs+json")
            .header("Content-Type", "application/vnd.git-lfs+json")
            .header("Authorization", basicAuth())
            .header("User-Agent", "GitHubPro/1.0")
            .post(body.toString().toRequestBody("application/vnd.git-lfs+json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("LFS 下载地址失败（HTTP ${resp.code}）：${txt.take(300)}")
            val obj = JsonParser.parseString(txt).asJsonObject.getAsJsonArray("objects")?.firstOrNull()?.asJsonObject
                ?: throw IOException("LFS 下载返回为空")
            obj.getAsJsonObject("error")?.let { throw IOException(it.get("message")?.asString ?: "LFS 下载失败") }
            obj.getAsJsonObject("actions")?.getAsJsonObject("download")?.get("href")?.asString
                ?: throw IOException("LFS 没有返回下载地址")
        }
    }

    /** 提交 LFS 指针文件（.gitattributes + pointer），一次真实 commit。 */
    suspend fun lfsCommit(o: String, r: String, path: String, branch: String, oid: String, size: Long, message: String) {
        val ref = exec("/repos/$o/$r/git/ref/heads/$branch").body.let { JsonParser.parseString(it).asJsonObject }
        val commitSha = ref.getAsJsonObject("object").get("sha").asString
        val commit = exec("/repos/$o/$r/git/commits/$commitSha").body.let { JsonParser.parseString(it).asJsonObject }
        val baseTree = commit.getAsJsonObject("tree").get("sha").asString
        val ext = path.substringAfterLast('.', "")
        val attrLine = if (ext.isNotBlank() && path.contains('.')) "*.$ext filter=lfs diff=lfs merge=lfs -text" else "/$path filter=lfs diff=lfs merge=lfs -text"
        val attrsExists = runCatching { fileItem(o, r, ".gitattributes", branch) }.isSuccess
        val attrsOld = if (attrsExists) runCatching { fileContent(o, r, ".gitattributes", branch) }.getOrNull() else ""
        if (attrsOld != null && !attrsOld.lineSequence().any { it.trim() == attrLine }) {
            val attrsNew = attrsOld.trimEnd() + (if (attrsOld.isBlank()) "" else "\n") + attrLine + "\n"
            val attrSha = JsonParser.parseString(exec("/repos/$o/$r/git/blobs", "POST", JsonObject().apply {
                addProperty("content", android.util.Base64.encodeToString(attrsNew.toByteArray(), android.util.Base64.NO_WRAP))
                addProperty("encoding", "base64")
            }.toString()).body).asJsonObject.get("sha").asString
            // .gitattributes 与指针一起放进 tree
            val tree = JsonObject().apply {
                addProperty("base_tree", baseTree)
                add("tree", JsonArray().apply {
                    add(JsonObject().apply { addProperty("path", ".gitattributes"); addProperty("mode", "100644"); addProperty("type", "blob"); addProperty("sha", attrSha) })
                })
            }
            val treeSha = JsonParser.parseString(exec("/repos/$o/$r/git/trees", "POST", tree.toString()).body).asJsonObject.get("sha").asString
            commitPointer(o, r, path, branch, oid, size, message, commitSha, treeSha)
        } else {
            commitPointer(o, r, path, branch, oid, size, message, commitSha, baseTree)
        }
    }

    private suspend fun commitPointer(o: String, r: String, path: String, branch: String, oid: String, size: Long,
                              message: String, parent: String, baseTree: String) {
        val pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:$oid\nsize $size\n"
        val blob = JsonParser.parseString(exec("/repos/$o/$r/git/blobs", "POST", JsonObject().apply {
            addProperty("content", android.util.Base64.encodeToString(pointer.toByteArray(), android.util.Base64.NO_WRAP))
            addProperty("encoding", "base64")
        }.toString()).body).asJsonObject.get("sha").asString
        val treeBody = JsonObject().apply {
            addProperty("base_tree", baseTree)
            add("tree", JsonArray().apply {
                add(JsonObject().apply { addProperty("path", path); addProperty("mode", "100644"); addProperty("type", "blob"); addProperty("sha", blob) })
            })
        }
        val tree = JsonParser.parseString(exec("/repos/$o/$r/git/trees", "POST", treeBody.toString()).body).asJsonObject.get("sha").asString
        val cb = JsonObject().apply {
            addProperty("message", message)
            addProperty("tree", tree)
            add("parents", JsonArray().apply { add(parent) })
        }
        val newCommit = JsonParser.parseString(exec("/repos/$o/$r/git/commits", "POST", cb.toString()).body).asJsonObject.get("sha").asString
        exec("/repos/$o/$r/git/refs/heads/$branch", "PATCH", JsonObject().apply { addProperty("sha", newCommit) }.toString())
    }

    suspend fun createLabel(o: String, r: String, name: String, color: String, description: String?): JsonObject {
        val jo = JsonObject().apply {
            addProperty("name", name)
            addProperty("color", color.removePrefix("#").take(6).ifBlank { "cccccc" })
            if (!description.isNullOrBlank()) addProperty("description", description)
        }
        return JsonParser.parseString(exec("/repos/$o/$r/labels", "POST", jo.toString()).body).asJsonObject
    }

    suspend fun createMilestone(o: String, r: String, title: String, description: String?): JsonObject {
        val jo = JsonObject().apply {
            addProperty("title", title)
            if (!description.isNullOrBlank()) addProperty("description", description)
        }
        return JsonParser.parseString(exec("/repos/$o/$r/milestones", "POST", jo.toString()).body).asJsonObject
    }

    // ---------------- 发行版 ----------------
    suspend fun releases(o: String, r: String, page: Int = 1): Page<Release> =
        callList("/repos/$o/$r/releases", mapOf("per_page" to "20", "page" to "$page"))
    suspend fun release(o: String, r: String, id: Long): Release = callObj("/repos/$o/$r/releases/$id", Release::class.java)
    suspend fun createRelease(o: String, r: String, tag: String, name: String?, body: String?, draft: Boolean, prerelease: Boolean, target: String?): Release {
        val jo = JsonObject().apply {
            addProperty("tag_name", tag)
            if (!name.isNullOrBlank()) addProperty("name", name)
            if (!body.isNullOrBlank()) addProperty("body", body)
            addProperty("draft", draft)
            addProperty("prerelease", prerelease)
            if (!target.isNullOrBlank()) addProperty("target_commitish", target)
        }
        return parse(exec("/repos/$o/$r/releases", "POST", jo.toString()).body, Release::class.java)
    }
    suspend fun patchRelease(o: String, r: String, id: Long, fields: Map<String, Any?>): Release {
        val jo = JsonObject()
        fields.forEach { (k, v) -> when (v) { is String -> jo.addProperty(k, v); is Boolean -> jo.addProperty(k, v); null -> {}; else -> jo.addProperty(k, v.toString()) } }
        return parse(exec("/repos/$o/$r/releases/$id", "PATCH", jo.toString()).body, Release::class.java)
    }
    suspend fun deleteRelease(o: String, r: String, id: Long) { exec("/repos/$o/$r/releases/$id", "DELETE") }
    suspend fun deleteAsset(o: String, r: String, assetId: Long) { exec("/repos/$o/$r/releases/assets/$assetId", "DELETE") }

    suspend fun uploadReleaseAsset(o: String, r: String, releaseId: Long, file: java.io.File, name: String, onProgress: ((Long, Long) -> Unit)? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val url = "https://uploads.github.com/repos/$o/$r/releases/$releaseId/assets?name=${enc(name)}"
            val body0 = file.asRequestBody("application/octet-stream".toMediaType())
            val body = if (onProgress != null) ProgressRequestBody(body0, onProgress) else body0
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token ?: ""}")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GitHubPro/1.0")
                .post(body).build()
            val txt = uploadHttp.newCall(req).execute().use { resp ->
                val t = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw ApiException(resp.code, if (resp.code == 422) "同名附件已存在或文件名不合法" else "上传失败 HTTP ${resp.code}", t)
                t
            }
            JsonParser.parseString(txt).asJsonObject
        }

    // ---------------- 通知 ----------------
    suspend fun notifications(all: Boolean = false, page: Int = 1): List<Notification> =
        callList<Notification>("/notifications", mapOf("all" to "$all", "per_page" to "30", "page" to "$page")).items
    suspend fun markThreadRead(threadId: String) { exec("/notifications/threads/$threadId", "PATCH", "{}") }
    suspend fun markAllRead() { exec("/notifications", "PUT", "{}") }

    // ---------------- 用户状态（GraphQL） ----------------
    /** GitHub 状态：真实写回服务端，别人在个人主页/头像悬浮卡能看到。 */
    private val statusFields = "emoji message indicatesLimitedAvailability expiresAt organization{id login name}"

    suspend fun myStatus(): UserStatus? {
        val q = "query{viewer{status{$statusFields}}}"
        val body = JsonObject().apply { addProperty("query", q) }
        val root = JsonParser.parseString(raw("/graphql", "POST", body.toString())).asJsonObject
        root.getAsJsonObject("data")?.getAsJsonObject("viewer")?.get("status")?.takeIf { it.isJsonObject }?.asJsonObject?.let { return parseStatus(it) }
        val err = root.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString
        if (!err.isNullOrBlank()) throw IOException(err)
        return null
    }

    /** 查看别人的公开状态；如果对方设为仅组织可见且你不是该组织成员，GitHub 会返回 null。 */
    suspend fun userStatus(login: String): UserStatus? {
        if (login.isBlank()) return null
        val q = "query(\$login:String!){user(login:\$login){status{$statusFields}}}"
        val body = JsonObject().apply {
            addProperty("query", q)
            add("variables", JsonObject().apply { addProperty("login", login) })
        }
        val root = JsonParser.parseString(raw("/graphql", "POST", body.toString())).asJsonObject
        val err = root.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString
        if (!err.isNullOrBlank()) throw IOException(err)
        return root.getAsJsonObject("data")?.getAsJsonObject("user")
            ?.get("status")?.takeIf { it.isJsonObject }?.asJsonObject?.let { parseStatus(it) }
    }

    /** 当前用户加入的组织；用于 GitHub 支持的“仅组织成员可见”状态。 */
    suspend fun myOrganizations(): List<OrgSummary> {
        val q = "query{viewer{organizations(first:100){nodes{id login name avatarUrl}}}}"
        val body = JsonObject().apply { addProperty("query", q) }
        val root = JsonParser.parseString(raw("/graphql", "POST", body.toString())).asJsonObject
        val err = root.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString
        if (!err.isNullOrBlank()) throw IOException(err)
        val arr = root.getAsJsonObject("data")?.getAsJsonObject("viewer")
            ?.getAsJsonObject("organizations")?.getAsJsonArray("nodes") ?: return emptyList()
        return arr.mapNotNull { it.takeIf { x -> x.isJsonObject }?.asJsonObject }.mapNotNull { o ->
            val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            val login = o.get("login")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            OrgSummary(id, login, o.get("name")?.takeIf { !it.isJsonNull }?.asString,
                o.get("avatarUrl")?.takeIf { !it.isJsonNull }?.asString)
        }
    }

    suspend fun changeMyStatus(emoji: String, message: String, limited: Boolean, expiresAt: String?, organizationId: String? = null): UserStatus? {
        val q = "mutation(\$input:ChangeUserStatusInput!){changeUserStatus(input:\$input){status{$statusFields}}}"
        val input = JsonObject().apply {
            addProperty("emoji", emoji)
            addProperty("message", message.take(80))
            addProperty("limitedAvailability", limited)
            if (expiresAt.isNullOrBlank()) add("expiresAt", com.google.gson.JsonNull.INSTANCE) else addProperty("expiresAt", expiresAt)
            // 公开状态不要传 organizationId:null；GitHub 文档要求“省略”才是公开，显式 null 部分账号会写入失败。
            if (!organizationId.isNullOrBlank()) addProperty("organizationId", organizationId)
        }
        val body = JsonObject().apply {
            addProperty("query", q)
            add("variables", JsonObject().apply { add("input", input) })
        }
        val root = JsonParser.parseString(raw("/graphql", "POST", body.toString())).asJsonObject
        root.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString?.let { throw IOException(it) }
        val st = root.getAsJsonObject("data")?.getAsJsonObject("changeUserStatus")?.get("status")
        return if (st != null && st.isJsonObject) parseStatus(st.asJsonObject) else null
    }

    private fun parseStatus(o: JsonObject): UserStatus {
        val org = o.get("organization")?.takeIf { it.isJsonObject }?.asJsonObject
        return UserStatus(
            emoji = o.get("emoji")?.takeIf { !it.isJsonNull }?.asString,
            message = o.get("message")?.takeIf { !it.isJsonNull }?.asString,
            limitedAvailability = o.get("indicatesLimitedAvailability")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            expiresAt = o.get("expiresAt")?.takeIf { !it.isJsonNull }?.asString,
            organizationId = org?.get("id")?.takeIf { !it.isJsonNull }?.asString,
            organizationLogin = org?.get("login")?.takeIf { !it.isJsonNull }?.asString,
            organizationName = org?.get("name")?.takeIf { !it.isJsonNull }?.asString
        )
    }

    // ---------------- 搜索 ---------------- ----------------
    suspend fun searchRepos(q: String, page: Int = 1, sort: String? = null, order: String? = null, perPage: Int = 30): SearchRepos {
        val query = mapOf("q" to q, "page" to "$page", "per_page" to "$perPage", "sort" to sort, "order" to order)
        // 搜索只发一次：失败就立刻报错，避免 15 秒超时后再重试 2 次变成 45 秒转圈。
        val r = execOnce("/search/repositories", "GET", null, query, "application/vnd.github+json",
            null, emptyMap(), null, searchHttp)
        return parse(r.body, SearchRepos::class.java)
    }

    /**
     * 精选页专用搜索：先走轻量 GraphQL（约 15KB），失败再退回 REST。
     * 不改动全局 searchRepos，避免搜索结果页依赖的 topics/open_issues 等字段被裁掉。
     */
    suspend fun searchReposFeatured(q: String, sort: String? = null, perPage: Int = 30): SearchRepos {
        if (!token.isNullOrBlank() && System.currentTimeMillis() >= leanSearchDisabledUntil) {
            val lean = runCatching { searchReposLean(q, sort, perPage) }.getOrNull()
            if (lean != null && lean.items.isNotEmpty()) return lean
            if (lean == null) leanSearchDisabledUntil = System.currentTimeMillis() + 120_000L
        }
        val query = mapOf("q" to q, "page" to "1", "per_page" to "$perPage", "sort" to sort, "order" to "desc")
        val r = execOnce("/search/repositories", "GET", null, query, "application/vnd.github+json",
            null, emptyMap(), null, searchHttp)
        return parse(r.body, SearchRepos::class.java)
    }

    /** GraphQL 精选搜索：字段按 RepoRow 实际展示所需裁剪，避免大响应拖慢首屏。 */
    private suspend fun searchReposLean(q: String, sort: String?, perPage: Int): SearchRepos {
        val gql = """query(${'$'}q:String!,${'$'}n:Int!){ search(query:${'$'}q, type:REPOSITORY, first:${'$'}n){ repositoryCount nodes{ ... on Repository { databaseId id name nameWithOwner url owner{login avatarUrl} description stargazerCount forkCount updatedAt pushedAt primaryLanguage{name} isPrivate } } } }"""
        // GraphQL 没有 sort 参数，但搜索语法支持 sort:stars / sort:updated / sort:forks。
        val sortQuery = if (sort.isNullOrBlank()) q else "$q sort:$sort"
        val vars = JsonObject().apply {
            addProperty("q", sortQuery)
            addProperty("n", perPage.coerceIn(1, 100))
        }
        val data = graphql(gql, vars, leanSearchHttp, singleAttempt = true)
        val search = data.getAsJsonObject("search") ?: return SearchRepos()
        val count = search.get("repositoryCount")?.asLong ?: 0L
        val nodes = search.getAsJsonArray("nodes") ?: return SearchRepos(total_count = count)
        val items = ArrayList<Repo>()
        for (el in nodes) {
            val n = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val full = n.get("nameWithOwner")?.asString.orEmpty()
            if (full.isBlank()) continue
            val ownerObj = n.getAsJsonObject("owner")
            val private = n.get("isPrivate")?.asBoolean ?: false
            items.add(
                Repo(
                    id = n.get("databaseId")?.asLong ?: 0L,
                    node_id = n.get("id")?.asString,
                    name = n.get("name")?.asString ?: full.substringAfter('/'),
                    full_name = full,
                    owner = RepoOwner(
                        login = ownerObj?.get("login")?.asString.orEmpty(),
                        avatar_url = ownerObj?.get("avatarUrl")?.asString
                    ),
                    description = n.get("description")?.takeIf { !it.isJsonNull }?.asString,
                    isPrivate = private,
                    html_url = n.get("url")?.asString ?: "https://github.com/$full",
                    language = n.getAsJsonObject("primaryLanguage")?.get("name")?.asString,
                    forks_count = n.get("forkCount")?.asInt ?: 0,
                    stargazers_count = n.get("stargazerCount")?.asInt ?: 0,
                    updated_at = n.get("updatedAt")?.asString,
                    pushed_at = n.get("pushedAt")?.asString,
                    visibility = if (private) "private" else "public"
                )
            )
        }
        return SearchRepos(total_count = count, incomplete_results = false, items = items)
    }
    suspend fun searchUsers(q: String, page: Int = 1, perPage: Int = 30): SearchUsers =
        parse(raw("/search/users", query = mapOf("q" to q, "page" to "$page", "per_page" to "$perPage")), SearchUsers::class.java)
    suspend fun searchCode(q: String, page: Int = 1, perPage: Int = 30): SearchCode =
        parse(raw("/search/code", query = mapOf("q" to q, "page" to "$page", "per_page" to "$perPage"), accept = "application/vnd.github.text-match+json"), SearchCode::class.java)
    suspend fun searchIssues(q: String, page: Int = 1, perPage: Int = 30): SearchIssues =
        parse(raw("/search/issues", query = mapOf("q" to q, "page" to "$page", "per_page" to "$perPage")), SearchIssues::class.java)

    suspend fun rateLimit(): RateLimitResp = callObj("/rate_limit", RateLimitResp::class.java)

    /** GitHub 官方许可证模板列表（真实接口） */
    suspend fun licenses(): List<LicenseTpl> = callList<LicenseTpl>("/licenses", mapOf("per_page" to "100")).items

    /** GitHub 官方 .gitignore 模板名列表（真实接口） */
    suspend fun gitignoreTemplates(): List<String> =
        runCatching { parseList<String>(raw("/gitignore/templates"), TypeToken.getParameterized(List::class.java, String::class.java)) }
            .getOrDefault(emptyList())

    /**
     * 严格“只统计 Issue”的开放数。GraphQL 的 issues 连接不包含 Pull Request，
     * 和 REST 的 open_issues_count（Issue + PR 混在一起）不一样，所以卡片上不会再虚高。
     * 一次最多查 25 个仓库，批量别名查询，省额度也快。
     */
    suspend fun issueCounts(repos: List<Pair<String, String>>): Map<String, Int> {
        if (repos.isEmpty()) return emptyMap()
        val sb = StringBuilder("query{")
        repos.forEachIndexed { i, (o, r) ->
            val oo = o.replace("\\", "\\\\").replace("\"", "\\\"")
            val rr = r.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append("r$i:repository(owner:\"$oo\",name:\"$rr\"){issues(states:OPEN){totalCount}}")
        }
        sb.append("}")
        val data = runCatching { graphql(sb.toString()) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Int>()
        repos.forEachIndexed { i, (o, r) ->
            val n = data.getAsJsonObject("r$i")?.getAsJsonObject("issues")?.get("totalCount")?.asInt
            if (n != null) out["$o/$r"] = n
        }
        return out
    }

    // ---------------- 讨论区 (GraphQL) ----------------
    suspend fun discussions(o: String, r: String, first: Int = 20): DiscussionsPage {
        val q = """
            query(${'$'}owner:String!,${'$'}name:String!,${'$'}first:Int!){
              repository(owner:${'$'}owner,name:${'$'}name){
                hasDiscussionsEnabled
                discussions(first:${'$'}first,orderBy:{field:UPDATED_AT,direction:DESC}){
                  nodes{
                    number title body createdAt updatedAt url answerChosenAt
                    author{login avatarUrl url}
                    category{name emoji}
                    comments(first:3){totalCount nodes{id body createdAt isAnswer author{login avatarUrl}}}
                  }
                }
              }
            }
        """.trimIndent()
        val vars = JsonObject().apply {
            addProperty("owner", o); addProperty("name", r); addProperty("first", first)
        }
        val data = graphql(q, vars)
        val repoObj = data.getAsJsonObject("repository") ?: return DiscussionsPage(false, emptyList())
        // 以 GitHub 服务端返回的开关为唯一权威：没开启就是 false，绝不靠“列表为空”猜成已开启
        val enabled = repoObj.get("hasDiscussionsEnabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        if (!enabled) return DiscussionsPage(false, emptyList())
        val nodes = repoObj.getAsJsonObject("discussions")?.getAsJsonArray("nodes") ?: return DiscussionsPage(true, emptyList())
        return DiscussionsPage(true, arr<Discussion>(nodes))
    }

    suspend fun discussionDetail(o: String, r: String, number: Int): Discussion? {
        val q = """
            query(${'$'}owner:String!,${'$'}name:String!,${'$'}number:Int!){
              repository(owner:${'$'}owner,name:${'$'}name){
                discussion(number:${'$'}number){
                  number title body createdAt updatedAt url answerChosenAt
                  author{login avatarUrl url}
                  category{name emoji}
                  comments(first:40){totalCount nodes{id body createdAt isAnswer author{login avatarUrl}}}
                }
              }
            }
        """.trimIndent()
        val vars = JsonObject().apply {
            addProperty("owner", o); addProperty("name", r); addProperty("number", number)
        }
        val data = graphql(q, vars)
        val node = data.getAsJsonObject("repository")?.getAsJsonObject("discussion") ?: return null
        return gson.fromJson(node, Discussion::class.java)
    }

    suspend fun addDiscussionComment(o: String, r: String, number: Int, body: String) {
        val q = """
            mutation(${'$'}owner:String!,${'$'}repo:String!,${'$'}number:Int!,${'$'}body:String!){
              addDiscussionComment(input:{repositoryName:${'$'}repo,discussionNumber:${'$'}number,body:${'$'}body}){
                comment{id}
              }
            }
        """.trimIndent()
        val vars = JsonObject().apply {
            addProperty("owner", o); addProperty("repo", r); addProperty("number", number); addProperty("body", body)
        }
        graphql(q, vars)
    }

    suspend fun createDiscussion(o: String, r: String, categoryId: String, title: String, body: String): Int {
        val q = """
            mutation(${'$'}repoId:ID!,${'$'}catId:ID!,${'$'}title:String!,${'$'}body:String!){
              createDiscussion(input:{repositoryId:${'$'}repoId,categoryId:${'$'}catId,title:${'$'}title,body:${'$'}body}){
                discussion{number}
              }
            }
        """.trimIndent()
        val vars = JsonObject().apply {
            addProperty("repoId", repoId(o, r)); addProperty("catId", categoryId)
            addProperty("title", title); addProperty("body", body)
        }
        val data = graphql(q, vars)
        return data.getAsJsonObject("createDiscussion")?.getAsJsonObject("discussion")?.get("number")?.asInt ?: 0
    }

    suspend fun repoId(o: String, r: String): String {
        val q = """query(${'$'}owner:String!,${'$'}name:String!){repository(owner:${'$'}owner,name:${'$'}name){id discussionCategories(first:20){nodes{id name emoji}}}}"""
        val vars = JsonObject().apply { addProperty("owner", o); addProperty("name", r) }
        val data = graphql(q, vars)
        return data.getAsJsonObject("repository")?.get("id")?.asString ?: ""
    }

    suspend fun discussionCategories(o: String, r: String): List<Pair<String, String>> {
        val q = """query(${'$'}owner:String!,${'$'}name:String!){repository(owner:${'$'}owner,name:${'$'}name){discussionCategories(first:20){nodes{id name emoji}}}}"""
        val vars = JsonObject().apply { addProperty("owner", o); addProperty("name", r) }
        val data = graphql(q, vars)
        val nodes = data.getAsJsonObject("repository")?.getAsJsonObject("discussionCategories")?.getAsJsonArray("nodes") ?: return emptyList()
        return nodes.mapNotNull { el ->
            val o2 = el.asJsonObject
            val id = o2.get("id")?.asString ?: return@mapNotNull null
            id to (o2.get("name")?.asString ?: "")
        }
    }

    // ---------------- OAuth 设备流 ----------------
    suspend fun startDeviceFlow(clientId: String): JsonObject {
        val form = okhttp3.FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", "repo workflow notifications user delete_repo write:discussion")
            .build()
        val req = Request.Builder().url("https://github.com/login/device/code")
            .header("Accept", "application/json").post(form).build()
        val txt = withContext(Dispatchers.IO) { http.newCall(req).execute().use { it.body?.string() ?: "" } }
        return JsonParser.parseString(txt).asJsonObject
    }

    suspend fun pollDeviceFlow(clientId: String, deviceCode: String): JsonObject {
        val body = com.google.gson.JsonObject().apply {
            addProperty("client_id", clientId); addProperty("device_code", deviceCode)
            addProperty("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        }
        val form = okhttp3.FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val req = Request.Builder().url("https://github.com/login/oauth/access_token")
            .header("Accept", "application/json").post(form).build()
        val txt = withContext(Dispatchers.IO) { http.newCall(req).execute().use { it.body?.string() ?: "" } }
        return JsonParser.parseString(txt).asJsonObject
    }

    suspend fun checkToken(t: String): User {
        val req = Request.Builder().url(base + "/user")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "GitHubPro/1.0")
            .header("Authorization", "Bearer $t").build()
        val txt = withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { r ->
                val b = r.body?.string() ?: ""
                if (r.code !in 200..299) throw ApiException(r.code, "令牌无效或无权限（HTTP ${r.code}）", b)
                b
            }
        }
        return parse(txt, User::class.java)
    }
}
