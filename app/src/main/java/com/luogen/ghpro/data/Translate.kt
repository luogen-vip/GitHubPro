package com.luogen.ghpro.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 真实可用的免 key 翻译：优先彩云小译公开接口（国内可直连、自动识别、支持长文本），
 * 失败再换有道公开网页接口，最后用 MyMemory。没有本地假翻译/占位结果。
 * 单一接口都有各种网络限制，所以做多接口回退；仍然失败会把真实错误抛给界面。
 */
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

    private const val UA = "Mozilla/5.0 (Linux; Android 13; WDY-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    @Volatile private var msToken: String? = null
    @Volatile private var msTokenAt = 0L

    private val cache = object : LinkedHashMap<String, Result>(80, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 120
    }

    fun cached(text: String, target: String): Result? = synchronized(cache) { cache["$target\u0000$text"] }

    fun sameLanguage(text: String, target: String): Boolean {
        val guess = guessLanguage(text) ?: return false
        val t = lang(target)
        return when (t.code) {
            "zh-CN", "zh-TW" -> guess == "zh"
            else -> guess == t.code
        }
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

    suspend fun translate(text: String, target: String): Result = withContext(Dispatchers.IO) {
        val source = text.trim()
        if (source.isBlank()) return@withContext Result(source, null, "原文")
        val t = lang(target)
        val guessed = guessLanguage(source)
        if (guessed != null && sameLanguage(source, target)) {
            return@withContext Result(source, guessed, "原文已是${t.label}")
        }
        val key = "$target\u0000$source"
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val chunks = chunk(source)
        // 分段并发（每组 4 段），长 README 不会一段一段串行等到天荒地老；
        // 每段内部仍然按 彩云→有道→MyMemory 回退。
        val parts = coroutineScope {
            chunks.chunked(4).map { group ->
                group.map { c -> async(Dispatchers.IO) { translateChunk(c, t, guessed) } }.awaitAll()
            }.flatten()
        }
        val out = StringBuilder()
        var detected: String? = guessed
        var provider = ""
        parts.forEach { r ->
            out.append(r.first)
            detected = r.second ?: detected
            provider = r.third
        }
        val result = Result(out.toString().trim(), detected, provider)
        synchronized(cache) { cache[key] = result }
        result
    }

    private fun chunk(text: String, max: Int = 800): List<String> {
        val out = ArrayList<String>()
        var start = 0
        val s = text.replace("\r\n", "\n").replace('\r', '\n')
        while (start < s.length) {
            var end = (start + max).coerceAtMost(s.length)
            if (end < s.length) {
                val slice = s.substring(start, end)
                val breakAt = slice.lastIndexOfAny(charArrayOf('\n', '。', '！', '？', '.', '!', '?', '；', ';'))
                if (breakAt > max / 3) end = start + breakAt + 1
            }
            if (end <= start) end = (start + max).coerceAtMost(s.length)
            out.add(s.substring(start, end))
            start = end
        }
        return out.ifEmpty { listOf(s) }
    }

    private fun translateChunk(chunk: String, t: Lang, guessed: String?): Triple<String, String?, String> {
        val errors = ArrayList<String>()
        // 1. 彩云小译公开接口：国内可直连，自动识别源语言，单次 1 万字符实测可用。
        try {
            return caiyunTranslate(chunk, t)
        } catch (e: Exception) { errors.add("彩云：${e.message}") }
        // 2. 有道公开网页接口：国内网络通常可达，主要覆盖中英互译。
        try {
            return youdaoTranslate(chunk, t, guessed)
        } catch (e: Exception) { errors.add("有道：${e.message}") }
        // 3. MyMemory：匿名有每日字符限制，只作为兜底。
        try {
            return memoryTranslate(chunk, t, guessed)
        } catch (e: Exception) { errors.add("MyMemory：${e.message}") }
        throw IOException(errors.joinToString("；"))
    }

    private fun caiyunType(t: Lang): String = when (t.code) {
        "zh-CN" -> "auto2zh"
        "zh-TW" -> "auto2zh-Hant"
        else -> "auto2${t.code}"
    }

    /** 彩云小译公开 token：无需注册、无需自己申请 key；失败会明确抛错。 */
    private fun caiyunTranslate(chunk: String, t: Lang): Triple<String, String?, String> {
        val body = JsonObject().apply {
            add("source", JsonArray().apply { add(chunk) })
            addProperty("trans_type", caiyunType(t))
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
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val obj = JsonParser.parseString(text).asJsonObject
            val rc = obj.get("rc")?.asInt ?: -1
            if (rc != 0) {
                val msg = obj.get("message")?.asString ?: obj.get("error")?.asString ?: "错误码 $rc"
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

    private fun ensureMsToken(): String {
        val old = msToken
        if (old != null && System.currentTimeMillis() - msTokenAt < 8 * 60_000L) return old
        val req = Request.Builder().url("https://edge.microsoft.com/translate/auth")
            .header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val token = resp.body?.string()?.trim().orEmpty()
            if (token.isBlank() || token.length < 20) throw IOException("令牌为空")
            msToken = token
            msTokenAt = System.currentTimeMillis()
            return token
        }
    }

    private fun msTranslate(chunk: String, t: Lang): Triple<String, String?, String> {
        var token = ensureMsToken()
        repeat(2) { attempt ->
            val body = JsonArray().apply {
                add(JsonObject().apply { addProperty("Text", chunk) })
            }.toString().toRequestBody("application/json".toMediaType())
            val url = "https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&to=${t.ms}"
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .post(body).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 401 && attempt == 0) {
                    msToken = null
                    token = ensureMsToken()
                    return@use
                }
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val arr = JsonParser.parseString(text).asJsonArray
                val first = arr.firstOrNull()?.asJsonObject ?: throw IOException("返回为空")
                val trans = first.getAsJsonArray("translations")?.firstOrNull()?.asJsonObject
                    ?: throw IOException("没有译文")
                val out = trans.get("text")?.asString ?: throw IOException("没有译文")
                val det = first.getAsJsonObject("detectedLanguage")?.get("language")?.asString
                return Triple(out, det, "微软")
            }
        }
        throw IOException("令牌失效")
    }

    private fun googleTranslate(chunk: String, t: Lang): Triple<String, String?, String> {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${t.google}&dt=t&q=${enc(chunk)}"
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val root = JsonParser.parseString(text).asJsonArray
            val parts = root.firstOrNull()?.asJsonArray ?: throw IOException("返回为空")
            val sb = StringBuilder()
            for (i in 0 until parts.size()) {
                val row = parts.get(i).asJsonArray
                if (row.size() > 0) sb.append(row.get(0).asString)
            }
            if (sb.isEmpty()) throw IOException("没有译文")
            val det = runCatching { root.get(2).asString }.getOrNull()
            return Triple(sb.toString(), det, "谷歌")
        }
    }

    private fun youdaoTranslate(chunk: String, t: Lang, guessed: String?): Triple<String, String?, String> {
        val type = when {
            guessed == "en" && t.code == "zh-CN" -> "EN2ZH_CN"
            guessed == "zh" && t.code == "en" -> "ZH_CN2EN"
            t.code == "zh-CN" -> "AUTO"
            else -> "AUTO"
        }
        val url = "https://fanyi.youdao.com/translate?&doctype=json&type=$type&i=${enc(chunk)}"
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val arr = JsonParser.parseString(text).asJsonObject
            val code = arr.get("errorCode")?.asInt ?: -1
            if (code != 0) throw IOException("错误码 $code")
            val list = arr.getAsJsonArray("translation") ?: throw IOException("没有译文")
            val sb = StringBuilder()
            for (i in 0 until list.size()) sb.append(list.get(i).asString)
            if (sb.isEmpty()) throw IOException("没有译文")
            return Triple(sb.toString(), guessed, "有道")
        }
    }

    private fun memoryTranslate(chunk: String, t: Lang, guessed: String?): Triple<String, String?, String> {
        val src = guessed ?: "en"
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
            return Triple(out, guessed, "MyMemory")
        }
    }

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    } catch (e: Exception) { s }
}
