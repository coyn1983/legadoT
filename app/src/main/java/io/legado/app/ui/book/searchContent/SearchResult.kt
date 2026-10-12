package io.legado.app.ui.book.searchContent

import android.text.Spanned
import androidx.core.text.HtmlCompat
import io.legado.app.help.config.AppConfig

data class SearchResult(
    val resultCount: Int = 0,
    val resultCountWithinChapter: Int = 0,
    val resultText: String = "",
    val chapterTitle: String = "",
    val query: String = "",
    val pageSize: Int = 0,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val queryIndexInResult: Int = 0,
    val queryIndexInChapter: Int = 0
) {

    fun getHtmlCompat(textColor: String, accentColor: String): Spanned {
        return if (query.isNotBlank()) {
            // 大小写不敏感匹配，与搜索命中判定保持一致
            val queryIndexInSurrounding = resultText.indexOf(query, ignoreCase = true)
            if (queryIndexInSurrounding < 0) {
                // 定位不到关键词（正文变化/净化差异），退化为整段不高亮，
                // 避免 substring(0, -1) 抛 StringIndexOutOfBoundsException
                val plain = if (AppConfig.isEInkMode) resultText
                else resultText.colorTextForHtml(textColor)
                return HtmlCompat.fromHtml(plain, HtmlCompat.FROM_HTML_MODE_LEGACY)
            }
            val leftString = resultText.substring(0, queryIndexInSurrounding)
            val rightString =
                resultText.substring(queryIndexInSurrounding + query.length, resultText.length)
            
            // 检查是否为墨水屏模式
            val html = if (AppConfig.isEInkMode) {
                // 墨水屏模式：使用下划线
                buildString {
                    append("<u>${chapterTitle}</u>")
                    append("<br>")
                    append(leftString)
                    append("<u>${query}</u>")
                    append(rightString)
                }
            } else {
                // 普通模式：使用颜色
                buildString {
                    append(chapterTitle.colorTextForHtml(accentColor))
                    append("<br>")
                    append(leftString.colorTextForHtml(textColor))
                    append(query.colorTextForHtml(accentColor))
                    append(rightString.colorTextForHtml(textColor))
                }
            }
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        } else {
            val html = if (AppConfig.isEInkMode) {
                resultText
            } else {
                resultText.colorTextForHtml(textColor)
            }
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }

    private fun String.colorTextForHtml(textColor: String) =
        "<font color=#${textColor}>$this</font>"

}
