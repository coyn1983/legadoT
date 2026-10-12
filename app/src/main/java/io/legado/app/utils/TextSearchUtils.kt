package io.legado.app.utils

/**
 * 文本查找工具。
 *
 * 全文搜索的「命中判定」、搜索结果列表的「关键词高亮」、阅读器点击结果后的「跳转定位」
 * 都依赖同一套匹配规则，统一收敛到这里，避免三处各写一份而行为不一致。
 *
 * 匹配规则：
 * - **大小写不敏感**（与帮助文档搜索 [io.legado.app.help.findTextRanges] 的行为一致）；
 * - 按字面量匹配，不做正则解释；
 * - 采用**不重叠**推进方式，与既有约定一致：`"aaaa"` 里查 `"aa"` 得到 `[0, 2]`；
 * - Kotlin 的 `ignoreCase` 是逐字符大小写比较，**匹配长度恒等于 query.length**，
 *   因此调用方用 `query.length` 计算选区/高亮区间是安全的。
 */
object TextSearchUtils {

    /**
     * 找出 [query] 在 [content] 中所有不重叠出现的位置（0 基下标）。
     * [query] 为空时返回空列表。
     */
    fun findAllIgnoreCase(content: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val positions = ArrayList<Int>()
        var index = content.indexOf(query, ignoreCase = true)
        while (index >= 0) {
            positions.add(index)
            index = content.indexOf(query, index + query.length, ignoreCase = true)
        }
        return positions
    }

    /**
     * 取 [query] 在 [content] 中第 [occurrence] 次（0 基）出现的位置。
     *
     * 这是 [findAllIgnoreCase] 的按序等价实现：`indexOfOccurrence(c, q, n)`
     * 恒等于 `findAllIgnoreCase(c, q).getOrNull(n) ?: -1`，
     * 即「第 n+1 次出现」，保证搜索列表里的序号能对回正文位置。
     * 找不到（次数不足或 [query] 为空、[occurrence] 为负）返回 -1。
     */
    fun indexOfOccurrence(content: String, query: String, occurrence: Int): Int {
        if (query.isEmpty() || occurrence < 0) return -1
        var count = 0
        var index = content.indexOf(query, ignoreCase = true)
        while (index >= 0 && count < occurrence) {
            index = content.indexOf(query, index + query.length, ignoreCase = true)
            count++
        }
        return index
    }
}
