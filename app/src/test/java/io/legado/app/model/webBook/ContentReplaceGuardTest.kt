package io.legado.app.model.webBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 正文全文替换兜底(contentAfterReplace)的回归测试。
 *
 * 背景:ContentRule.replaceRegex 的语义是「替换规则」而不是裸正则。填成裸正则时,
 * AnalyzeRule.getString 会把它当 DOM 选择器执行,选不到元素就返回 null 并被兜成空串,
 * 于是整章正文被静默抹掉,最终只报「内容为空」——与网络故障现象完全一样。
 *
 * 这里锁两件事:
 * 1. 替换结果为空、而替换前有内容时,必须保留替换前内容;
 * 2. BookContent 的调用点确实接了这个兜底,并会打日志。
 */
class ContentReplaceGuardTest {

    @Test
    fun normalReplaceKeepsResult() {
        assertEquals("替换后", contentAfterReplace("替换前", "替换后"))
    }

    @Test
    fun blankResultFallsBackToOriginal() {
        assertEquals("正文第一段", contentAfterReplace("正文第一段", ""))
    }

    @Test
    fun whitespaceOnlyResultAlsoFallsBack() {
        assertEquals("正文第一段", contentAfterReplace("正文第一段", "  \n\t\u3000 "))
    }

    @Test
    fun bothBlankStaysBlank() {
        // 卷占位章等处正文本来就允许为空,不能被兜底伪造出内容
        assertEquals("", contentAfterReplace("", ""))
    }

    @Test
    fun blankOriginalKeepsNonBlankResult() {
        // 原文为空时不做回退,替换结果照常采用
        assertEquals("替换结果", contentAfterReplace("", "替换结果"))
    }

    @Test
    fun sameResultReturnedAsIs() {
        val text = "正文第一段"
        assertSame(text, contentAfterReplace(text, text))
    }

    @Test
    fun bookContentCallsGuardAndLogs() {
        val src = readProjectFile("src/main/java/io/legado/app/model/webBook/BookContent.kt")
        assertTrue(
            "BookContent 未使用 contentAfterReplace 兜底",
            src.contains("contentAfterReplace(beforeReplace, replaced)"),
        )
        assertTrue(
            "缺少替换规则返回空时的日志提示",
            src.contains("正文替换规则返回空"),
        )
        assertTrue(
            "应在取值后保留 beforeReplace 快照",
            src.contains("val beforeReplace = contentStr"),
        )
        assertTrue(
            "原先直接覆盖 contentStr 的写法应已被替换",
            !src.contains("contentStr = analyzeRule.getString(replaceRegex, contentStr)"),
        )
    }

    private fun readProjectFile(pathInApp: String): String {
        val candidates = listOf(File(pathInApp), File("app/$pathInApp"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件: $pathInApp (cwd=${File("").absolutePath})")
        return file.readText()
    }
}
