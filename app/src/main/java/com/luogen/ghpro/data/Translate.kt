package com.luogen.ghpro.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 真实可用的免 key 翻译：B 站公开 Index-Translate（全语言）→ 彩云小译公开接口
 * → MyMemory 匿名接口。只保留公开、无需申请 key 的入口；没有本地假翻译/占位结果。
 * 单一接口都有网络/额度限制，所以做多接口回退；仍然失败会把真实错误抛给界面。
 */
private class RateLimited(message: String) : IOException(message)

/**
 * 彩云测试 token 对同一 IP 有明显频率限制，短时间连发会被封一段时间。
 * 本地先限制每分钟最多 4 次真正发出的彩云请求，超出的直接走后面的备用接口，
 * 避免测试页连点十几种语言把 IP 打进黑名单。
 */
private object CaiyunGate {
    private const val WINDOW_MS = 60_000L
    private const val MAX = 5
    private val stamps = ArrayList<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        stamps.removeAll { now - it > WINDOW_MS }
        if (stamps.size >= MAX) return false
        stamps.add(now)
        return true
    }
}

object TranslateApi {
    data class Lang(val code: String, val label: String, val ms: String, val google: String, val memory: String)
    data class Result(val text: String, val detected: String?, val provider: String)

    val langs = listOf(
        Lang("zh-CN", "简体中文", "zh-Hans", "zh-CN", "zh-CN"),
        Lang("zh-TW", "繁體中文", "zh-Hant", "zh-TW", "zh-TW"),
        Lang("en", "English", "en", "en", "en"),
        Lang("ja", "日本語", "ja", "ja", "ja"),
        Lang("ko", "한국어", "ko", "ko", "ko"),
        Lang("fr", "Français", "fr", "fr", "fr"),
        Lang("de", "Deutsch", "de", "de", "de"),
        Lang("es", "Español", "es", "es", "es"),
        Lang("ru", "Русский", "ru", "ru", "ru"),
        Lang("pt", "Português", "pt", "pt", "pt"),
        Lang("it", "Italiano", "it", "it", "it"),
        Lang("ar", "العربية", "ar", "ar", "ar"),
        Lang("th", "ไทย", "th", "th", "th"),
        Lang("vi", "Tiếng Việt", "vi", "vi", "vi"),
        Lang("id", "Bahasa Indonesia", "id", "id", "id"),
        Lang("tr", "Türkçe", "tr", "tr", "tr")
    )

    fun lang(code: String): Lang = langs.firstOrNull { it.code == code } ?: langs[0]
    fun label(code: String): String = lang(code).label

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    // B 站公开的 Index-Translate 演示接口：无需注册、无需 key，支持 150 种语言。
    // 长文生成比普通翻译接口慢，所以单独给更长的读超时；失败会继续走后面的公开接口。
    private val biliHttp = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        .build()

    private const val UA = "Mozilla/5.0 (Linux; Android 13; WDY-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val cache = object : LinkedHashMap<String, Result>(80, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 120
    }

    fun cached(text: String, target: String): Result? = synchronized(cache) { cache["$target\u0000$text"] }

    // 只放“简体里绝不会出现”的繁体字。不要把「著 / 於 / 硬 / 後 / 面」这类
    // 简体也会合法使用的字算进来，否则正常简体文本会被误判成繁体。
    private val traditionalOnly = "們這來個時為與學說對會還麼樣點發現實應該無數據網頁載體驗軟檔讀寫選擇轉換語言臺灣華龍鳳龜髮鬥鬱鹹並從將產專業區問題幾張開關門間頭長萬爲"

    // 只放“繁体里基本不会出现”的简体字，用于识别简繁混排。
    private val simplifiedOnly = "们这个时与学说对会还么样点发现实应该无数据网页载体验软档读写选择转换语言华龙凤龟发并从将产专业区问题几张开关门间头长为湾证认识让给"

    private data class Scripts(
        val cjk: Int, val latin: Int, val kana: Int, val hangul: Int,
        val cyr: Int, val arab: Int, val thai: Int
    )

    private fun scripts(text: String): Scripts {
        var cjk = 0; var latin = 0; var kana = 0; var hangul = 0; var cyr = 0; var arab = 0; var thai = 0
        for (c in text) {
            val cp = c.code
            when {
                cp in 0x3040..0x30FF -> kana++
                cp in 0xAC00..0xD7AF -> hangul++
                cp in 0x4E00..0x9FFF -> cjk++
                cp in 0x0400..0x04FF -> cyr++
                cp in 0x0600..0x06FF -> arab++
                cp in 0x0E00..0x0E7F -> thai++
                cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code -> latin++
            }
        }
        return Scripts(cjk, latin, kana, hangul, cyr, arab, thai)
    }

    fun isTraditionalChinese(text: String): Boolean =
        text.any { traditionalOnly.contains(it) }

    fun hasSimplifiedChinese(text: String): Boolean =
        text.any { simplifiedOnly.contains(it) }

    private fun hasOtherScriptForZh(s: Scripts): Boolean =
        s.kana > 3 || s.hangul > 3 || s.cyr > 3 || s.arab > 3 || s.thai > 3 ||
            // 中文里夹大段英文/法文等拉丁文字，也要交给翻译，不能直接判定“已是中文”
            s.latin > s.cjk * 0.2 + 6

    fun sameLanguage(text: String, target: String): Boolean {
        val t = lang(target)
        val s = scripts(text)
        return when (t.code) {
            "zh-CN" -> s.cjk > 0 && !isTraditionalChinese(text) && !hasOtherScriptForZh(s)
            "zh-TW" -> s.cjk > 0 && !hasSimplifiedChinese(text) && !hasOtherScriptForZh(s)
            "en" -> s.latin > 0 && s.cjk == 0 && s.kana == 0 && s.hangul == 0 &&
                s.cyr == 0 && s.arab == 0 && s.thai == 0
            "ja" -> s.kana > 0 && s.hangul < 4 && s.cyr < 4 &&
                s.arab < 4 && s.thai < 4 && s.latin <= (s.cjk + s.kana) * 0.25 + 8
            "ko" -> s.hangul > 0 && s.cjk < 4 && s.cyr < 4 && s.arab < 4 && s.thai < 4 &&
                s.latin <= s.hangul * 0.25 + 8
            "ru" -> s.cyr > 0 && s.cjk < 4 && s.kana < 4 && s.hangul < 4 && s.arab < 4 && s.thai < 4
            "ar" -> s.arab > 0 && s.cjk < 4 && s.kana < 4 && s.hangul < 4 && s.cyr < 4 && s.thai < 4
            "th" -> s.thai > 0 && s.cjk < 4 && s.kana < 4 && s.hangul < 4 && s.cyr < 4 && s.arab < 4
            else -> guessLanguage(text) == t.code
        }
    }

    /** 给界面显示“原文：简体中文 / 繁體中文 / 多语言”，而不是笼统的“中文”。 */
    /**
     * 代码、纯符号、纯表情、纯链接没有自然语言，不应该送翻译接口；
     * 否则会一直“翻译中”，甚至把变量名/代码结构翻成乱码。
     */
    fun isTranslatable(text: String): Boolean {
        val s = text.trim()
        if (s.isBlank()) return false
        val fenced = Regex("""```[\s\S]*?```""")
        val hasFence = fenced.containsMatchIn(s)
        val inline = Regex("""`[^`\n]+`""")
        val url = Regex("""https?://[^\s)\]]+""")
        val stripped = s.replace(fenced, " ").replace(inline, " ").replace(url, " ").trim()
        if (stripped.isBlank()) return false
        val sc = scripts(stripped)
        val letters = sc.cjk + sc.latin + sc.kana + sc.hangul + sc.thai + sc.arab + sc.cyr
        // 表情、标点、数字、纯符号：没有足够自然语言字符。
        if (letters < 2) return false
        val lines = s.replace("\r\n", "\n").split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (!hasFence && lines.size >= 3) {
            val codeLines = lines.count { line ->
                line.startsWith("```") || line.startsWith("~~~") ||
                    line.startsWith("{") || line.startsWith("}") ||
                    line.startsWith("//") || line.startsWith("#include") ||
                    line.startsWith("import ") || line.startsWith("const ") ||
                    line.startsWith("func ") || line.startsWith("def ") ||
                    line.startsWith("class ") || line.startsWith("public ") ||
                    line.startsWith("private ") || line.startsWith("val ") ||
                    line.startsWith("var ") || line.contains("=>") ||
                    line.matches(Regex("""^[\s{}\[\]();,./\\|&=+\-*<>!:#@]+$"""))
            }
            // 六成以上都是代码行，整段当代码，不翻译。
            if (codeLines * 10 >= lines.size * 6) return false
        }
        return true
    }

    fun detectedLabel(text: String): String? {
        val s = scripts(text)
        if (s.cjk == 0 && s.latin == 0 && s.kana == 0 && s.hangul == 0 &&
            s.cyr == 0 && s.arab == 0 && s.thai == 0) return null
        val kinds = ArrayList<String>()
        // 有假名时优先按日语显示；纯汉字仍按中文显示。
        if (s.cjk > 0 && s.kana < 4) {
            val trad = isTraditionalChinese(text)
            val simp = hasSimplifiedChinese(text)
            kinds += when {
                trad && simp -> "简繁混排中文"
                trad -> "繁體中文"
                simp -> "简体中文"
                else -> "中文"
            }
        }
        if (s.kana > 3) kinds += "日本語"
        if (s.hangul > 3) kinds += "한국어"
        if (s.cyr > 3) kinds += "Русский"
        if (s.arab > 3) kinds += "العربية"
        if (s.thai > 3) kinds += "ไทย"
        if (s.latin > 4 && (s.cjk > 0 || s.kana > 0 || s.hangul > 0 || s.cyr > 0 || s.arab > 0 || s.thai > 0)) {
            kinds += "拉丁文字"
        }
        if (kinds.isEmpty()) return null
        return if (kinds.size > 1) "多语言（${kinds.joinToString("+")}）" else kinds[0]
    }

    /** 只做非常明确的文字系统判断，用于“原文已经是目标语言就不强行翻译”和给 MyMemory 源语言。 */
    fun guessLanguage(text: String): String? {
        if (text.isBlank()) return null
        var cjk = 0; var kana = 0; var hangul = 0; var latin = 0; var cyr = 0; var arab = 0; var thai = 0
        for (c in text) {
            val cp = c.code
            when {
                cp in 0x3040..0x30FF -> kana++
                cp in 0xAC00..0xD7AF -> hangul++
                cp in 0x4E00..0x9FFF -> cjk++
                cp in 0x0400..0x04FF -> cyr++
                cp in 0x0600..0x06FF -> arab++
                cp in 0x0E00..0x0E7F -> thai++
                cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code -> latin++
            }
        }
        return when {
            kana > 3 -> "ja"
            hangul > 3 -> "ko"
            cjk > 0 && cjk >= (latin + cjk) / 3 -> "zh"
            thai > 3 -> "th"
            arab > 3 -> "ar"
            cyr > 3 -> "ru"
            latin > 3 -> "en"
            else -> null
        }
    }

    private data class Seg(val text: String, val code: Boolean)

    /**
     * Markdown 里的代码块原样保留，只翻译周围的自然语言；
     * 否则接口会把变量名、缩进、花括号当文字翻成乱码。
     */
    private fun splitFenced(source: String): List<Seg> {
        val re = Regex("""```[\s\S]*?```""")
        val out = ArrayList<Seg>()
        var last = 0
        for (m in re.findAll(source)) {
            if (m.range.first > last) out.add(Seg(source.substring(last, m.range.first), false))
            out.add(Seg(m.value, true))
            last = m.range.last + 1
        }
        if (last < source.length) out.add(Seg(source.substring(last), false))
        return out.ifEmpty { listOf(Seg(source, false)) }
    }

    suspend fun translate(text: String, target: String, onPartial: ((String) -> Unit)? = null): Result = withContext(Dispatchers.IO) {
        val source = text.trim().let { if (it.length > 100_000) it.take(100_000) else it }
        if (source.isBlank()) return@withContext Result(source, null, "原文")
        if (!isTranslatable(source)) return@withContext Result(source, null, "代码/符号，无需翻译")
        val key = "$target\u0000$source"
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val parts = splitFenced(source)
        if (parts.size <= 1) return@withContext translatePlain(source, target, onPartial)
        val out = StringBuilder()
        var detected: String? = null
        val providers = ArrayList<String>()
        for (part in parts) {
            if (part.code) {
                out.append(part.text)
                continue
            }
            if (!isTranslatable(part.text)) {
                out.append(part.text)
                continue
            }
            val r = translatePlain(part.text, target) { partial ->
                onPartial?.invoke((out.toString() + partial).trim())
            }
            out.append(r.text)
            detected = detected ?: r.detected
            if (r.provider !in providers) providers.add(r.provider)
            onPartial?.invoke(out.toString().trim())
        }
        val result = Result(out.toString().trim(), detected, providers.joinToString(" + ").ifBlank { "原文" })
        synchronized(cache) { cache[key] = result }
        result
    }

    private suspend fun translatePlain(text: String, target: String, onPartial: ((String) -> Unit)? = null): Result = withContext(Dispatchers.IO) {
        val source = text.trim().let { if (it.length > 100_000) it.take(100_000) else it }
        if (source.isBlank()) return@withContext Result(source, null, "原文")
        if (!isTranslatable(source)) {
            return@withContext Result(source, null, "代码/符号，无需翻译")
        }
        val t = lang(target)
        val guessed = guessLanguage(source)
        if (guessed != null && sameLanguage(source, target)) {
            return@withContext Result(source, guessed, "原文已是${t.label}")
        }
        val key = "$target\u0000$source"
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val chunks = chunk(source)
        // 第一屏只翻 200 字：实测 200 字约 1~2 秒就能先显示；900 字约 5~7 秒，
        // 1400 字经常 20 秒甚至 503，所以剩余统一按 900 字并发补齐。
        val firstChunk = chunks.first()
        val firstText = firstChunk.take(200)
        val out = StringBuilder()
        var detected: String? = guessed
        var provider = ""
        val first = translateChunk(firstText, t, guessed)
        out.append(first.first)
        detected = first.second ?: detected
        provider = first.third
        onPartial?.invoke(out.toString().trim())
        val restParts = (
            if (firstChunk.length > 200) chunk(firstChunk.substring(200), 900) else emptyList()
        ) + chunks.drop(1)
        if (restParts.isNotEmpty()) {
            coroutineScope {
                val jobs = restParts.map { c -> async(Dispatchers.IO) { translateChunk(c, t, guessed) } }
                // 按原文顺序逐块落地：每完成一块就更新一次界面，不能等所有块全部翻完才显示。
                for (job in jobs) {
                    val r = job.await()
                    out.append(r.first)
                    detected = r.second ?: detected
                    provider = r.third
                    onPartial?.invoke(out.toString().trim())
                }
            }
        }
        val translated = out.toString().trim()
        if (translated.isBlank()) throw IOException("所有翻译接口都返回空译文")
        // 有些接口在目标语言不支持时会原样返回；不能把它当成功，否则界面显示“翻译中”结束后
        // 正文完全没变，用户会以为卡住。短词/专有名词允许相同，长正文必须真的变化。
        if (translated == source.trim() && source.length >= 12 && !sameLanguage(source, target)) {
            throw IOException("接口没有真正翻译，请重试或切换目标语言")
        }
        val result = Result(translated, detected, provider)
        synchronized(cache) { cache[key] = result }
        result
    }

    private fun chunk(text: String, max: Int = 10_000): List<String> {
        // 固定长度切分，不按标点回退。40,000 字上限正好 4 段，
        // 不会因为一个换行符被切成 8 段而触发“每分钟 5 次”的限流。
        val s = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<String>()
        var start = 0
        while (start < s.length) {
            val end = (start + max).coerceAtMost(s.length)
            out.add(s.substring(start, end))
            start = end
        }
        return out.ifEmpty { listOf(s) }
    }

    private suspend fun translateChunk(chunk: String, t: Lang, guessed: String?): Triple<String, String?, String> {
        val errors = ArrayList<String>()
        var rateLimited: RateLimited? = null
        // 1. B 站公开 Index-Translate：无需注册和 key，支持 150 种语言。
        //    这是目前实测最稳定的全语言入口；长文也交给它（每次最多 1 万字）。
        //    503/超时再给一次很短的重试，仍失败才走后面两个额度有限的兜底。
        try {
            var out = ""
            for (attempt in 0..1) {
                try {
                    out = bilibiliTranslate(chunk, t)
                    break
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (attempt == 1) throw e
                    delay(500L)
                }
            }
            if (isSameOrUntranslated(out, chunk)) throw IOException("B站接口原样返回")
            return Triple(out, null, "B站翻译")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { errors.add("B站：${e.message}") }
        // 2. 彩云小译公开接口：国内可直连，自动识别源语言。公开 token 每分钟约 5 次，
        //    所以放在第二；短文本/单段通常直接成功，长文则由 B 站先分担。
        try {
            val out = caiyunTranslate(chunk, t)
            if (isSameOrUntranslated(out.first, chunk)) throw IOException("彩云接口原样返回")
            return out
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            errors.add("彩云：${e.message}")
            if (e is RateLimited) rateLimited = e
        }
        // 3. MyMemory：公开匿名接口，有每日额度；只作为最后兜底。
        try {
            val out = memoryTranslate(chunk, t, guessed)
            if (isSameOrUntranslated(out.first, chunk)) throw IOException("MyMemory 原样返回")
            return out
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { errors.add("MyMemory：${e.message}") }
        if (rateLimited != null) throw rateLimited
        throw IOException(errors.joinToString("；"))
    }

    private fun isSameOrUntranslated(out: String, src: String): Boolean {
        // 短词/专有名词可能本来就相同；长正文如果完全一样，说明接口没真正翻译。
        return out.trim() == src.trim() && src.trim().length >= 12
    }

    /** B 站公开演示接口：OpenAI 兼容，任意占位 key 可用；只接受纯译文。 */
    private suspend fun bilibiliTranslate(chunk: String, t: Lang): String {
        // 实测 1400 字请求会明显变慢并间歇 503；900 字小块稳定得多，再由全局并发拉总吞吐。
        if (chunk.length > 900) {
            val out = StringBuilder()
            for (part in chunk.chunked(900)) out.append(bilibiliOne(part, t))
            return out.toString().trim()
        }
        return bilibiliOne(chunk, t)
    }

    /** 900 字小块下实测 20 并发仍稳定；并发太低才是长文慢的主因。 */
    private val biliGate = Semaphore(20)

    private suspend fun bilibiliOne(chunk: String, t: Lang): String = withContext(Dispatchers.IO) {
        biliGate.withPermit {
        val prompt = "请将下面的文本翻译成${t.label}，只输出译文，不要解释、不要加引号、不要输出原文：\n\n$chunk"
        val body = JsonObject().apply {
            addProperty("model", "Index-Translate-35B-A3B")
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            })
            addProperty("temperature", 0)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://index-translate.bilibili.com/v1/chat/completions")
            .header("Authorization", "Bearer GitHubPro")
            .header("Content-Type", "application/json")
            .header("User-Agent", UA)
            .post(body).build()
        biliHttp.newCall(req).await().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val obj = JsonParser.parseString(text).asJsonObject
            val choices = obj.getAsJsonArray("choices") ?: throw IOException("返回为空")
            val first = choices.firstOrNull()?.asJsonObject ?: throw IOException("返回为空")
            val content = first.getAsJsonObject("message")?.get("content")?.asString
                ?: throw IOException("没有译文")
            val out = content.trim()
            if (out.isBlank()) throw IOException("译文为空")
            out
        }
    }
    }

    private fun hasLargeLatinPart(text: String): Boolean {
        val s = scripts(text)
        if (s.latin < 6) return false
        return s.cjk > 0 && s.latin > s.cjk * 0.2 + 6
    }

    private fun caiyunType(t: Lang, chunk: String): String = when {
        // 中文里混入大段英文/法文时，auto 可能只识别其中一种语言、把另一种原样留下；
        // 固定用 en 作源语言再翻成中文，彩云会连同混排的其他外语一起处理。
        t.code == "zh-CN" && hasLargeLatinPart(chunk) -> "en2zh"
        t.code == "zh-TW" && hasLargeLatinPart(chunk) -> "en2zh-Hant"
        t.code == "zh-CN" -> "auto2zh"
        t.code == "zh-TW" -> "auto2zh-Hant"
        else -> "auto2${t.code}"
    }

    /** 彩云小译公开 token：无需注册、无需自己申请 key；失败会明确抛错。 */
    private fun caiyunTranslate(chunk: String, t: Lang): Triple<String, String?, String> {
        if (!CaiyunGate.tryAcquire()) throw RateLimited("本机请求过快，已保护性跳过")
        val body = JsonObject().apply {
            add("source", JsonArray().apply { add(chunk) })
            addProperty("trans_type", caiyunType(t, chunk))
            addProperty("request_id", "ghpro-" + System.currentTimeMillis())
            addProperty("detect", true)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.interpreter.caiyunai.com/v1/translator")
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .header("x-authorization", "token:3975l6lr5pcbvidl6jl2")
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 403 || resp.code == 429) throw RateLimited("HTTP ${resp.code}")
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val obj = JsonParser.parseString(text).asJsonObject
            val rc = obj.get("rc")?.asInt ?: -1
            if (rc != 0) {
                val msg = obj.get("message")?.asString ?: obj.get("error")?.asString ?: "错误码 $rc"
                if (msg.contains("rate limit", ignoreCase = true)) throw RateLimited(msg)
                throw IOException(msg)
            }
            val arr = obj.getAsJsonArray("target") ?: throw IOException("没有译文")
            val out = StringBuilder()
            for (i in 0 until arr.size()) out.append(arr.get(i).asString)
            if (out.isEmpty()) throw IOException("译文为空")
            val tt = obj.get("trans_type")?.asString.orEmpty()
            val src = tt.substringBefore("2").takeIf { it.isNotBlank() }
            return Triple(out.toString(), src, "彩云")
        }
    }

    private fun memoryTranslate(chunk: String, t: Lang, guessed: String?): Triple<String, String?, String> {
        val src = when {
            guessed == "zh" -> if (isTraditionalChinese(chunk)) "zh-TW" else "zh-CN"
            else -> guessed ?: "en"
        }
        val url = "https://api.mymemory.translated.net/get?q=${enc(chunk)}&langpair=${src}|${t.memory}"
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val obj = JsonParser.parseString(text).asJsonObject
            val out = obj.getAsJsonObject("responseData")?.get("translatedText")?.asString.orEmpty()
            if (out.isBlank() || out.contains("MYMEMORY WARNING") || out.contains("QUERY LENGTH LIMIT")) {
                throw IOException(if (out.contains("MYMEMORY WARNING")) "今日免费额度已用完" else "没有译文")
            }
            // 有些时段接口会原样返回英文（例如目标俄语却还是 Hello...），不能当成功。
            val targetGuess = if (t.code == "zh-CN" || t.code == "zh-TW") "zh" else t.code
            if (guessed != targetGuess && out.trim().equals(chunk.trim(), ignoreCase = true)) {
                throw IOException("接口没有真正翻译")
            }
            return Triple(out, guessed, "MyMemory")
        }
    }

    /** OkHttp 异步等待：协程取消时真正 cancel 请求，切换语言不会让旧 README 继续占着 4 个并发槽。 */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    } catch (e: Exception) { s }
}
