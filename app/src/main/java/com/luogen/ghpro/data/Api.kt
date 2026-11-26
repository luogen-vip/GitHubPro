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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, override val message: String, val body: String? = null) : Exception(message)

data class Page<T>(val items: List<T>, val hasNext: Boolean)

data class DirListing(val isFile: Boolean, val items: List<ContentItem>, val file: ContentItem?)

object Api {

    @Volatile var token: String? = null
    @Volatile var base: String = "https://api.github.com"
    @Volatile var lastRateRemaining: Int = -1
    @Volatile var useCacheOnly: Boolean = false

    val gson: Gson = GsonBuilder().setLenient().create()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 大文件上传单独放宽写超时；手机上传 1-2GB 不能只给 45 秒。 */
    private val uploadHttp: OkHttpClient by lazy {
        http.newBuilder()
            .writeTimeout(30, TimeUnit.MINUTES)
            .readTimeout(30, TimeUnit.MINUTES)
            .build()
    }

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
        extraHeaders: Map<String, String> = emptyMap()
    ): Resp {
        var last: Exception? = null
        repeat(3) { i ->
            try {
                return execOnce(path, method, body, query, accept, absoluteUrl, extraHeaders)
            } catch (e: ApiException) {
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
        extraHeaders: Map<String, String>
    ): Resp = withContext(Dispatchers.IO) {
        val b = Request.Builder()
            .url(absoluteUrl ?: buildUrl(path, query))
            .header("User-Agent", "GitHubPro/1.0 (Android)")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
        token?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        val rb = when (method) {
            "GET" -> null
            "DELETE" -> "".toRequestBody(JSON)
            else -> (body ?: "{}").toRequestBody(JSON)
        }
        b.method(method, rb)
        val resp = http.newCall(b.build()).execute()
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

    suspend fun graphql(query: String, variables: JsonObject = JsonObject()): JsonObject {
        val payload = JsonObject().apply {
            addProperty("query", query)
            add("variables", variables)
        }
        val text = exec("/graphql", "POST", payload.toString(), accept = "application/json").body
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
        // /user/repos 只认 created/updated/pushed/full_name，stars 由客户端排序
        val apiSort = if (sort == "stars") "updated" else sort
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

    suspend fun putFile(o: String, r: String, path: String, message: String, base64: String, sha: String?, branch: String?): JsonObject {
        val body = JsonObject().apply {
            addProperty("message", message)
            addProperty("content", base64)
            if (sha != null) addProperty("sha", sha)
            if (branch != null) addProperty("branch", branch)
        }
        return JsonParser.parseString(exec("/repos/$o/$r/contents/${encPath(path)}", "PUT", body.toString()).body).asJsonObject
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

    suspend fun deletePaths(o: String, r: String, paths: List<String>, branch: String?): Int {
        val files = LinkedHashSet<String>()
        paths.forEach { p -> files.addAll(collectFiles(o, r, p, branch)) }
        if (files.isEmpty()) return 0
        val ref = exec("/repos/$o/$r/git/ref/heads/${branch ?: "main"}").body.let { JsonParser.parseString(it).asJsonObject }
        val commitSha = ref.getAsJsonObject("object").get("sha").asString
        val commit = exec("/repos/$o/$r/git/commits/$commitSha").body.let { JsonParser.parseString(it).asJsonObject }
        val baseTree = commit.getAsJsonObject("tree").get("sha").asString
        val entries = JsonArray()
        files.forEach { f ->
            entries.add(JsonObject().apply {
                addProperty("path", f)
                addProperty("mode", "100644")
                addProperty("type", "blob")
                add("sha", com.google.gson.JsonNull.INSTANCE)
            })
        }
        val treeBody = JsonObject().apply { addProperty("base_tree", baseTree); add("tree", entries) }
        val newTree = JsonParser.parseString(exec("/repos/$o/$r/git/trees", "POST", treeBody.toString()).body)
            .asJsonObject.get("sha").asString
        val commitBody = JsonObject().apply {
            addProperty("message", "delete ${files.size} file(s)")
            addProperty("tree", newTree)
            add("parents", JsonArray().apply { add(commitSha) })
        }
        val newCommit = JsonParser.parseString(exec("/repos/$o/$r/git/commits", "POST", commitBody.toString()).body)
            .asJsonObject.get("sha").asString
        val patch = JsonObject().apply { addProperty("sha", newCommit) }
        exec("/repos/$o/$r/git/refs/heads/${branch ?: "main"}", "PATCH", patch.toString())
        return files.size
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
    suspend fun isWatching(o: String, r: String): Boolean = try { exec("/repos/$o/$r/subscription").code == 200 } catch (e: ApiException) { false }
    suspend fun watch(o: String, r: String, on: Boolean) {
        val body = JsonObject().apply { addProperty("subscribed", on) }.toString()
        exec("/repos/$o/$r/subscription", "PUT", body)
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
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
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
    suspend fun lfsUpload(o: String, r: String, file: java.io.File): String = withContext(Dispatchers.IO) {
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
            val put = pb.put(file.asRequestBody("application/octet-stream".toMediaType())).build()
            uploadHttp.newCall(put).execute().use { up ->
                if (!up.isSuccessful) throw IOException("LFS 文件上传失败（HTTP ${up.code}）")
            }
            oid
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

    suspend fun uploadReleaseAsset(o: String, r: String, releaseId: Long, file: java.io.File, name: String): JsonObject =
        withContext(Dispatchers.IO) {
            val url = "https://uploads.github.com/repos/$o/$r/releases/$releaseId/assets?name=${enc(name)}"
            val body = file.asRequestBody("application/octet-stream".toMediaType())
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token ?: ""}")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GitHubPro/1.0")
                .post(body).build()
            val txt = uploadHttp.newCall(req).execute().use { it.body?.string() ?: "" }
            JsonParser.parseString(txt).asJsonObject
        }

    // ---------------- 通知 ----------------
    suspend fun notifications(all: Boolean = false, page: Int = 1): List<Notification> =
        callList<Notification>("/notifications", mapOf("all" to "$all", "per_page" to "30", "page" to "$page")).items
    suspend fun markThreadRead(threadId: String) { exec("/notifications/threads/$threadId", "PATCH", "{}") }
    suspend fun markAllRead() { exec("/notifications", "PUT", "{}") }

    // ---------------- 搜索 ---------------- ----------------
    suspend fun searchRepos(q: String, page: Int = 1, sort: String? = null, order: String? = null, perPage: Int = 30): SearchRepos {
        val query = mapOf("q" to q, "page" to "$page", "per_page" to "$perPage", "sort" to sort, "order" to order)
        return parse(raw("/search/repositories", query = query), SearchRepos::class.java)
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

    // ---------------- 讨论区 (GraphQL) ----------------
    suspend fun discussions(o: String, r: String, first: Int = 20): List<Discussion> {
        val q = """
            query(${'$'}owner:String!,${'$'}name:String!,${'$'}first:Int!){
              repository(owner:${'$'}owner,name:${'$'}name){
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
        val nodes = data.getAsJsonObject("repository")?.getAsJsonObject("discussions")?.getAsJsonArray("nodes") ?: return emptyList()
        return arr<Discussion>(nodes)
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
