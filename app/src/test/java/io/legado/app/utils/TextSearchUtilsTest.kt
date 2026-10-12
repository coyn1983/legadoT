package io.legado.app.utils

import io.legado.app.help.findTextRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全文搜索「大小写不敏感」的回归测试。
 *
 * 背景：改前搜索链路用 `String.indexOf(query)`（大小写敏感），
 * 导致输入大写搜不到小写、输入小写搜不到大写。
 * 现在三处（命中判定 / 结果高亮 / 阅读器定位）统一走 [TextSearchUtils]。
 */
class TextSearchUtilsTest {

    // ---------------- 大小写：双向命中 ----------------

    @Test
    fun lowerCaseQueryMatchesUpperCaseContent() {
        assertEquals(listOf(0), TextSearchUtils.findAllIgnoreCase("Alice", "alice"))
    }

    @Test
    fun upperCaseQueryMatchesLowerCaseContent() {
        assertEquals(listOf(0), TextSearchUtils.findAllIgnoreCase("alice", "ALICE"))
    }

    @Test
    fun mixedCaseMatches() {
        assertEquals(listOf(0), TextSearchUtils.findAllIgnoreCase("aLiCe", "AlIcE"))
    }

    /** 改前行为对照：大小写敏感时会漏掉，这条断言锁住 bug 不再回归 */
    @Test
    fun caseSensitiveIndexOfWouldMiss() {
        assertEquals(-1, "Alice".indexOf("alice"))
        assertEquals(0, TextSearchUtils.findAllIgnoreCase("Alice", "alice").first())
    }

    @Test
    fun caseInsensitiveInMixedChineseEnglishText() {
        // 中英混排：大小写不同的同一处都应命中
        val text = "第1章 Alice 说：“alice 你好”"
        assertEquals(
            listOf(text.indexOf("Alice"), text.indexOf("alice")),
            TextSearchUtils.findAllIgnoreCase(text, "ALICE")
        )
    }

    // ---------------- 多次命中 / 不重叠推进 ----------------

    @Test
    fun multipleOccurrences() {
        assertEquals(listOf(0, 3, 6), TextSearchUtils.findAllIgnoreCase("JS js Js", "js"))
    }

    /** 不重叠推进：与既有 [findTextRanges] 的约定一致（"aaaa" 查 "aa" → 0..1, 2..3） */
    @Test
    fun occurrencesAreNonOverlapping() {
        assertEquals(listOf(0, 2), TextSearchUtils.findAllIgnoreCase("aaaa", "aa"))
    }

    @Test
    fun chineseQuery() {
        assertEquals(listOf(4), TextSearchUtils.findAllIgnoreCase("他说：“你好世界。”", "你好"))
    }

    // ---------------- 与帮助文档搜索行为保持一致 ----------------

    @Test
    fun consistentWithHelpTextSearch() {
        val text = "JsLib jslib"
        assertEquals(
            findTextRanges(text, "jslib").map { it.first },
            TextSearchUtils.findAllIgnoreCase(text, "jslib")
        )
    }

    // ---------------- 长度不变性（下游偏移计算依赖） ----------------

    /**
     * 匹配长度必须恒等于 query.length：
     * 结果列表高亮、阅读器选区都直接用 query.length 计算，长度不一致就会错位。
     */
    @Test
    fun matchedLengthAlwaysEqualsQueryLength() {
        val text = "ALICE alice Alice 爱丽丝爱丽丝"
        listOf("alice", "ALICE", "爱丽丝").forEach { query ->
            val positions = TextSearchUtils.findAllIgnoreCase(text, query)
            assertTrue("$query 应有命中", positions.isNotEmpty())
            positions.forEach { pos ->
                // substring 不会越界，且取出的片段忽略大小写后等于 query，
                // 即证明「匹配长度 == query.length」
                assertTrue(
                    text.substring(pos, pos + query.length)
                        .equals(query, ignoreCase = true)
                )
            }
        }
    }

    // ---------------- indexOfOccurrence 与 findAllIgnoreCase 必须同序 ----------------

    @Test
    fun indexOfOccurrenceMatchesFindAll() {
        val text = "ab AB Ab aB"
        val query = "ab"
        val all = TextSearchUtils.findAllIgnoreCase(text, query)
        assertEquals(listOf(0, 3, 6, 9), all)
        all.forEachIndexed { n, pos ->
            assertEquals(pos, TextSearchUtils.indexOfOccurrence(text, query, n))
        }
        // 次数不足 → -1（而不是回到开头重新扫描）
        assertEquals(-1, TextSearchUtils.indexOfOccurrence(text, query, all.size))
    }

    @Test
    fun indexOfOccurrenceIsCaseInsensitive() {
        assertEquals(3, TextSearchUtils.indexOfOccurrence("ab AB Ab aB", "AB", 1))
        assertEquals(6, TextSearchUtils.indexOfOccurrence("ab AB Ab aB", "aB", 2))
    }

    // ---------------- 边界 ----------------

    @Test
    fun emptyAndMissingQuery() {
        assertTrue(TextSearchUtils.findAllIgnoreCase("abc", "").isEmpty())
        assertTrue(TextSearchUtils.findAllIgnoreCase("abc", "z").isEmpty())
        assertEquals(-1, TextSearchUtils.indexOfOccurrence("abc", "", 0))
        assertEquals(-1, TextSearchUtils.indexOfOccurrence("abc", "a", -1))
        assertEquals(-1, TextSearchUtils.indexOfOccurrence("abc", "z", 0))
    }

    @Test
    fun queryLongerThanContent() {
        assertTrue(TextSearchUtils.findAllIgnoreCase("ab", "abc").isEmpty())
        assertEquals(-1, TextSearchUtils.indexOfOccurrence("ab", "abc", 0))
    }
}
