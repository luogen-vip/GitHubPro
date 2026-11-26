package com.luogen.ghpro.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 轻量、可预测的语法高亮：按文件扩展名选择注释/字符串/关键字规则。
 * 只给真实 token 上色，不做“随便一行都变色”的假高亮。
 * 没有引入第三方库，避免 APK 膨胀和 Compose 编辑性能问题。
 */
private val C_KEYWORDS = setOf(
    "abstract","as","as?","break","by","catch","class","companion","const","constructor","continue",
    "data","delegate","do","else","enum","expect","external","false","field","file","final","finally",
    "for","fun","get","if","import","in","init","inline","inner","interface","internal","is","lateinit",
    "noinline","null","object","open","operator","out","override","package","private","protected","public",
    "reified","return","sealed","set","super","suspend","tailrec","this","throw","true","try","typealias",
    "typeof","val","var","vararg","when","where","while","with","yield","new","static","void","int","long",
    "double","float","boolean","char","byte","short","String","List","Map","Set","fun","print","println"
)
private val PY_KEYWORDS = setOf(
    "False","None","True","and","as","assert","async","await","break","class","continue","def","del","elif",
    "else","except","finally","for","from","global","if","import","in","is","lambda","nonlocal","not","or",
    "pass","raise","return","try","while","with","yield","self","print","range","len","str","int","float","list","dict","set","tuple"
)
private val JS_KEYWORDS = setOf(
    "async","await","break","case","catch","class","const","continue","debugger","default","delete","do","else",
    "export","extends","false","finally","for","from","function","if","import","in","instanceof","let","new",
    "null","of","return","static","super","switch","this","throw","true","try","typeof","undefined","var","void",
    "while","with","yield","interface","type","enum","implements","package","private","protected","public","readonly","declare","namespace","as"
)
private val WEB_KEYWORDS = setOf(
    "html","head","body","div","span","script","style","link","meta","title","p","a","img","ul","ol","li",
    "table","tr","td","th","form","input","button","section","header","footer","main","nav","class","id",
    "src","href","type","rel","content","name","value","true","false","null","const","let","var","function",
    "return","if","else","for","while","import","export","from","new","this","async","await","try","catch","finally"
)
private val SQL_KEYWORDS = setOf(
    "SELECT","FROM","WHERE","INSERT","INTO","VALUES","UPDATE","SET","DELETE","CREATE","TABLE","ALTER","DROP",
    "JOIN","LEFT","RIGHT","INNER","OUTER","ON","AS","AND","OR","NOT","NULL","IS","IN","EXISTS","GROUP","BY",
    "ORDER","LIMIT","OFFSET","HAVING","DISTINCT","UNION","ALL","CASE","WHEN","THEN","ELSE","END","PRIMARY",
    "KEY","FOREIGN","REFERENCES","INDEX","VIEW","BEGIN","COMMIT","ROLLBACK","TRANSACTION","INT","INTEGER",
    "TEXT","VARCHAR","CHAR","BOOLEAN","DATE","TIMESTAMP","DECIMAL","FLOAT","DOUBLE"
)
private val GO_KEYWORDS = setOf(
    "break","default","func","interface","select","case","defer","go","map","struct","chan","else","goto",
    "package","switch","const","fallthrough","if","range","type","continue","for","import","return","var",
    "true","false","nil","string","int","int64","float64","bool","byte","rune","error","make","append","len","cap","new"
)
private val RUST_KEYWORDS = setOf(
    "as","async","await","break","const","continue","crate","dyn","else","enum","extern","false","fn","for",
    "if","impl","in","let","loop","match","mod","move","mut","pub","ref","return","self","Self","static",
    "struct","super","trait","true","type","unsafe","use","where","while","String","Vec","Option","Result","Some","None","Ok","Err"
)

private val COLORS = mapOf(
    "keyword" to Color(0xFF7B4DFF),
    "string" to Color(0xFF2E7D32),
    "comment" to Color(0xFF6B7280),
    "number" to Color(0xFFD97706),
    "type" to Color(0xFF0E7490),
    "key" to Color(0xFF2563EB),
    "tag" to Color(0xFF0E7490)
)

private fun langOf(name: String): String {
    val n = name.lowercase()
    val ext = n.substringAfterLast('.', "")
    return when (ext) {
        "kt","kts" -> "kotlin"
        "java" -> "java"
        "py","pyw" -> "python"
        "js","jsx","mjs","cjs" -> "js"
        "ts","tsx" -> "ts"
        "html","htm","vue","svelte" -> "html"
        "css","scss","less" -> "css"
        "xml","svg" -> "xml"
        "json","json5" -> "json"
        "yml","yaml" -> "yaml"
        "sh","bash","zsh","fish" -> "shell"
        "go" -> "go"
        "rs" -> "rust"
        "c","h","cc","cpp","hpp","cxx" -> "c"
        "cs" -> "csharp"
        "php" -> "php"
        "rb" -> "ruby"
        "swift" -> "swift"
        "sql" -> "sql"
        "md","markdown" -> "markdown"
        "toml","ini","conf","properties" -> "ini"
        "gradle" -> "kotlin"
        "diff","patch" -> "diff"
        else -> "text"
    }
}

fun searchMatches(text: String, query: String, ignoreCase: Boolean = true): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var i = 0
    while (i <= text.length - query.length) {
        val hit = text.regionMatches(i, query, 0, query.length, ignoreCase)
        if (hit) { out.add(i); i += query.length.coerceAtLeast(1) } else i++
        if (out.size >= 5000) break
    }
    return out
}

/** 把真实 token 上色，并把搜索命中项加浅黄背景。 */
fun highlightCode(code: String, fileName: String, query: String = ""): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString("")
    val lang = langOf(fileName)
    val spans = ArrayList<Triple<Int, Int, SpanStyle>>()
    fun add(start: Int, end: Int, color: Color, bold: Boolean = false) {
        if (end > start) spans.add(Triple(start, end, SpanStyle(color = color, fontWeight = if (bold) FontWeight.SemiBold else null)))
    }
    // 普通文本不做语法猜测：只有用户主动搜索时才加命中背景。
    if (lang == "text") {
        return buildAnnotatedString {
            append(code)
            searchMatches(code, query).forEach { i ->
                addStyle(SpanStyle(background = Color(0xFFFFE082)), i, i + query.length)
            }
        }
    }

    // 先标注释和字符串，后面关键字的匹配会跳过这些区间，避免字符串里的 if/for 被错误上色。
    val protectedRanges = ArrayList<IntRange>()

    fun protectedFind(regex: Regex) {
        regex.findAll(code).forEach { m ->
            add(m.range.first, m.range.last + 1, COLORS["comment"]!!)
            protectedRanges.add(m.range)
        }
    }
    fun strings(regex: Regex) {
        regex.findAll(code).forEach { m ->
            add(m.range.first, m.range.last + 1, COLORS["string"]!!)
            protectedRanges.add(m.range)
        }
    }

    if (lang in setOf("kotlin","java","js","ts","go","rust","c","csharp","php","swift","gradle")) {
        protectedFind(Regex("(?s)/\\*.*?\\*/"))
        protectedFind(Regex("//[^\\n]*"))
        strings(Regex("\"\"\"(?s:.*?)\"\"\""))
        strings(Regex("\"(?:\\\\.|[^\"\\\\\\n])*\""))
        strings(Regex("'(?:\\\\.|[^'\\\\\\n])*'"))
        strings(Regex("`(?:\\\\.|[^`\\\\])*`"))
    } else if (lang in setOf("python","shell","yaml","ruby","ini","toml")) {
        protectedFind(Regex("(?m)#[^\\n]*"))
        strings(Regex("\"\"\"(?s:.*?)\"\"\""))
        strings(Regex("'''[^']*'''"))
        strings(Regex("\"(?:\\\\.|[^\"\\\\\\n])*\""))
        strings(Regex("'(?:\\\\.|[^'\\\\\\n])*'"))
    } else if (lang == "sql") {
        protectedFind(Regex("--[^\\n]*"))
        protectedFind(Regex("(?s)/\\*.*?\\*/"))
        strings(Regex("'(?:''|[^'])*'"))
        strings(Regex("\"(?:\\\"\"|[^\"])*\""))
    } else if (lang == "html" || lang == "xml" || lang == "markdown") {
        protectedFind(Regex("(?s)<!--.*?-->"))
        if (lang != "markdown") {
            strings(Regex("\"(?:\\\\.|[^\"\\\\])*\""))
            strings(Regex("'(?:\\\\.|[^'\\\\])*'"))
        }
    }

    fun isProtected(i: Int): Boolean = protectedRanges.any { i in it }
    fun keywords(words: Set<String>) {
        if (words.isEmpty()) return
        val re = Regex("\\b(" + words.joinToString("|") { Regex.escape(it) } + ")\\b")
        re.findAll(code).forEach { m ->
            if (!isProtected(m.range.first)) add(m.range.first, m.range.last + 1, COLORS["keyword"]!!, true)
        }
    }
    when (lang) {
        "kotlin","java","gradle","csharp" -> keywords(C_KEYWORDS)
        "python" -> keywords(PY_KEYWORDS)
        "js","ts" -> keywords(JS_KEYWORDS)
        "html","xml" -> {
            Regex("<[/!]?([A-Za-z][A-Za-z0-9:_-]*)").findAll(code).forEach { m ->
                if (!isProtected(m.range.first)) {
                    val g = m.groups[1]
                    if (g != null) add(g.range.first, g.range.last + 1, COLORS["tag"]!!, true)
                }
            }
            Regex("\\b([A-Za-z_:][A-Za-z0-9_:.-]*)\\s*=").findAll(code).forEach { m ->
                if (!isProtected(m.range.first)) {
                    val g = m.groups[1]
                    if (g != null) add(g.range.first, g.range.last + 1, COLORS["key"]!!)
                }
            }
        }
        "css" -> {
            strings(Regex("\"(?:\\\\\\.|[^\"\\\\])*\""))
            strings(Regex("'(?:\\\\\\.|[^'\\\\])*'"))
            Regex("\\b([#.]?[A-Za-z_-][A-Za-z0-9_-]*)\\s*\\{").findAll(code).forEach { m ->
                val g = m.groups[1]; if (g != null) add(g.range.first, g.range.last + 1, COLORS["tag"]!!, true)
            }
            Regex("\\b([A-Za-z-]+)\\s*:").findAll(code).forEach { m ->
                val g = m.groups[1]; if (g != null && !isProtected(g.range.first)) add(g.range.first, g.range.last + 1, COLORS["key"]!!)
            }
        }
        "json" -> {
            strings(Regex("\"(?:\\\\\\.|[^\"\\\\])*\""))
            Regex("\"([^\"\\\\]|\\\\.)*\"\\s*:").findAll(code).forEach { m ->
                val idx = code.indexOf('"', m.range.first)
                if (idx >= 0) add(idx, m.range.last + 1, COLORS["key"]!!)
            }
        }
        "yaml","ini","toml" -> {
            Regex("(?m)^\\s*([A-Za-z0-9_.-]+)\\s*[:=]").findAll(code).forEach { m ->
                val g = m.groups[1]; if (g != null && !isProtected(g.range.first)) add(g.range.first, g.range.last + 1, COLORS["key"]!!)
            }
        }
        "shell" -> {
            Regex("(?m)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=").findAll(code).forEach { m ->
                val g = m.groups[1]; if (g != null && !isProtected(g.range.first)) add(g.range.first, g.range.last + 1, COLORS["key"]!!)
            }
        }
        "go" -> keywords(GO_KEYWORDS)
        "rust" -> keywords(RUST_KEYWORDS)
        "sql" -> keywords(SQL_KEYWORDS)
        "php","ruby","swift" -> keywords(C_KEYWORDS)
        "markdown" -> {
            Regex("(?m)^(#{1,6})\\s+.*$").findAll(code).forEach { m -> add(m.range.first, m.range.last + 1, COLORS["tag"]!!, true) }
            Regex("\\*\\*[^*\\n]+\\*\\*").findAll(code).forEach { m -> add(m.range.first, m.range.last + 1, COLORS["keyword"]!!, true) }
            Regex("`[^`\\n]+`").findAll(code).forEach { m -> add(m.range.first, m.range.last + 1, COLORS["string"]!!) }
            Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").findAll(code).forEach { m -> add(m.range.first, m.range.last + 1, COLORS["key"]!!) }
        }
        "diff" -> {
            Regex("(?m)^\\+.*$").findAll(code).forEach { m -> add(m.range.first, m.range.last + 1, COLORS["string"]!!) }
            Regex("(?m)^-.*$").findAll(code).forEach { m -> add(m.range.first, m.range.last + 1, Color(0xFFDC2626)) }
            Regex("(?m)^@@.*$").findAll(code).forEach { m -> add(m.range.first, m.range.last + 1, COLORS["type"]!!) }
        }
    }
    // 数字最后标，跳过注释/字符串，避免日期、端口、字符串里的数字乱亮。
    Regex("\\b\\d+(?:\\.\\d+)?\\b").findAll(code).forEach { m ->
        if (!isProtected(m.range.first)) add(m.range.first, m.range.last + 1, COLORS["number"]!!)
    }
    // 类型/常见类名（首字母大写）只给“像类型”的单词上色，不全行乱亮。
    Regex("\\b([A-Z][A-Za-z0-9_]{1,30})\\b").findAll(code).forEach { m ->
        if (!isProtected(m.range.first)) add(m.range.first, m.range.last + 1, COLORS["type"]!!)
    }
    // 搜索命中背景放最后，保证在语法颜色之上仍然看得见。
    if (query.isNotEmpty()) {
        searchMatches(code, query).forEach { i ->
            spans.add(Triple(i, i + query.length, SpanStyle(background = Color(0xFFFFE082))))
        }
    }
    return buildAnnotatedString {
        append(code)
        spans.forEach { (s, e, style) ->
            if (s >= 0 && e <= code.length && s < e) addStyle(style, s, e)
        }
    }
}

/** 编辑框的 VisualTransformation：只改样式、不改字符，光标偏移保持 Identity。 */
class SyntaxTransformation(
    private val fileName: String,
    private val query: String = ""
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightCode(text.text, fileName, query), OffsetMapping.Identity)
}
