package com.luogen.ghpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Para(val text: String) : MdBlock()
    data class Bullet(val text: String, val marker: String) : MdBlock()
    data class Code(val lang: String, val text: String) : MdBlock()
    data object Rule : MdBlock()
}

private fun parseMd(md: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = md.replace("\r\n", "\n").split("\n")
    val para = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    var codeLang = ""

    fun flushPara() {
        if (para.isNotBlank()) out.add(MdBlock.Para(para.toString().trim()))
        para.setLength(0)
    }

    for (raw in lines) {
        val line = raw.trimEnd()
        if (line.startsWith("```")) {
            if (inCode) {
                out.add(MdBlock.Code(codeLang, code.toString().trimEnd()))
                code.setLength(0); inCode = false; codeLang = ""
            } else {
                flushPara(); inCode = true; codeLang = line.removePrefix("```").trim()
            }
            continue
        }
        if (inCode) { code.append(line).append('\n'); continue }
        when {
            line.isBlank() -> flushPara()
            line.matches(Regex("^#{1,6}\\s+.*")) -> {
                flushPara()
                val level = line.takeWhile { it == '#' }.length
                out.add(MdBlock.Heading(level, line.dropWhile { it == '#' }.trim()))
            }
            line.matches(Regex("^([-*_])\\1{2,}\\s*$")) -> { flushPara(); out.add(MdBlock.Rule) }
            line.matches(Regex("^\\s*[-*+]\\s+.*")) -> {
                flushPara()
                out.add(MdBlock.Bullet(line.trimStart().removePrefix("-").removePrefix("*").removePrefix("+").trim(), "•"))
            }
            line.matches(Regex("^\\s*\\d+[.)]\\s+.*")) -> {
                flushPara()
                val num = line.trimStart().takeWhile { it.isDigit() }
                out.add(MdBlock.Bullet(line.trimStart().removePrefix(num).trimStart().removePrefix(".").removePrefix(")").trim(), "$num."))
            }
            line.startsWith(">") -> {
                flushPara()
                out.add(MdBlock.Para(line.dropWhile { it == '>' }.trim()))
            }
            else -> para.append(line).append('\n')
        }
    }
    if (inCode && code.isNotEmpty()) out.add(MdBlock.Code(codeLang, code.toString().trimEnd()))
    flushPara()
    return out
}

private val INLINE = Regex(
    "!\\[([^\\]]*)]\\(([^)]+)\\)" +            // image
        "|\\[([^\\]]*)]\\(([^)]+)\\)" +        // link
        "|\\*\\*([^*]+)\\*\\*" +               // bold
        "|`([^`]+)`" +                         // inline code
        "|~~([^~]+)~~" +                       // strike
        "|_([^_]+)_" +                         // italic
        "|\\*([^*]+)\\*"                       // italic star
)

/** 极简 Markdown 渲染：标题、段落、列表、代码块、加粗、行内代码、链接可点。 */
@Composable
fun MdText(md: String, modifier: Modifier = Modifier, baseSize: Int = 13) {
    val ctx = LocalContext.current

    // 内联文本 + 链接点击
    @Composable
    fun InlineText(text: String, size: Int, bold: Boolean = false) {
        val codeBg = MaterialTheme.colorScheme.surfaceVariant
        val annotated = remember(text, bold, codeBg) { buildInline(text, bold, codeBg) }
        ClickableText(
            text = annotated,
            style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = size.sp, lineHeight = (size + 5).sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
            onClick = { offset ->
                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { a ->
                    openUrl(ctx, a.item)
                }
            }
        )
    }

    val blocks = remember(md) { parseMd(md) }
    Column(modifier) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Code -> {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(b.text, fontFamily = FontFamily.Monospace, fontSize = (baseSize - 1).sp,
                            color = MaterialTheme.colorScheme.onSurface, lineHeight = (baseSize + 3).sp)
                    }
                }
                is MdBlock.Rule -> {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
                    Spacer(Modifier.height(8.dp))
                }
                is MdBlock.Heading -> {
                    Spacer(Modifier.height(if (b.level <= 2) 8.dp else 4.dp))
                    val size = when (b.level) { 1 -> baseSize + 7; 2 -> baseSize + 4; 3 -> baseSize + 2; else -> baseSize + 1 }
                    InlineText(b.text, size = size, bold = true)
                }
                is MdBlock.Bullet -> Row(Modifier.padding(vertical = 1.dp)) {
                    Text(b.marker, fontSize = baseSize.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
                    InlineText(b.text, size = baseSize)
                }
                is MdBlock.Para -> {
                    InlineText(b.text, size = baseSize)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

private fun buildInline(src: String, bold: Boolean, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    fun plain(s: String) {
        if (s.isNotEmpty()) withStyle(SpanStyle(fontWeight = if (bold) FontWeight.SemiBold else null)) { append(s) }
    }
    for (m in INLINE.findAll(src)) {
        plain(src.substring(last, m.range.first))
        val imgAlt = m.groups[1]?.value
        val imgUrl = m.groups[2]?.value
        val linkText = m.groups[3]?.value
        val linkUrl = m.groups[4]?.value
        val b = m.groups[5]?.value
        val code = m.groups[6]?.value
        val strike = m.groups[7]?.value
        val italic1 = m.groups[8]?.value
        val italic2 = m.groups[9]?.value
        when {
            imgUrl != null -> {
                pushStringAnnotation("URL", imgUrl)
                withStyle(SpanStyle(color = GH.blue, textDecoration = TextDecoration.Underline)) {
                    append("[图片] ${imgAlt.orEmpty()}".trim())
                }
                pop()
            }
            linkUrl != null -> {
                pushStringAnnotation("URL", linkUrl)
                withStyle(SpanStyle(color = GH.blue, textDecoration = TextDecoration.Underline)) {
                    append(linkText?.takeIf { it.isNotBlank() } ?: linkUrl)
                }
                pop()
            }
            b != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(b) }
            code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = GH.blue)) { append(" $code ") }
            strike != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(strike) }
            italic1 != null -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(italic1) }
            italic2 != null -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(italic2) }
        }
        last = m.range.last + 1
    }
    plain(src.substring(last))
}
