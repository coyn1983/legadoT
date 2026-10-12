package io.legado.app.ui.book.searchContent


import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.TextSearchUtils
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class SearchContentViewModel(application: Application) : BaseViewModel(application) {
    var bookUrl: String = ""
    var book: Book? = null
    private var contentProcessor: ContentProcessor? = null
    var lastQuery: String = ""
    var searchResultCounts = 0
    val cacheChapterNames = hashSetOf<String>()
    val searchResultList: MutableList<SearchResult> = mutableListOf()
    var replaceEnabled = false

    fun initBook(bookUrl: String, success: () -> Unit) {
        this.bookUrl = bookUrl
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            book?.let {
                contentProcessor = ContentProcessor.get(it.name, it.origin)
            }
        }.onSuccess {
            success.invoke()
        }
    }

    /**
     * 预热简繁转换词典。
     * 词典是懒加载的，必须在并发搜索开始前串行执行一次，
     * 否则多个 worker 会同时触发首次加载。
     */
    fun warmUpChineseConverter() {
        when (AppConfig.chineseConverterType) {
            1 -> ChineseUtils.t2s("测")
            2 -> ChineseUtils.s2t("测")
        }
    }

    suspend fun searchChapter(
        query: String,
        chapter: BookChapter
    ): List<SearchResult> {
        val searchResultsWithinChapter: MutableList<SearchResult> = mutableListOf()
        val book = book ?: return searchResultsWithinChapter
        val chapterContent = BookHelp.getContent(book, chapter) ?: return searchResultsWithinChapter
        coroutineContext.ensureActive()
        // 不再就地修改 chapter.title：并行搜索时它是共享实体，
        // 且会与「标题 MD5 文件名缓存」耦合，改为局部变量透传给 ContentProcessor
        val searchTitle = when (AppConfig.chineseConverterType) {
            1 -> ChineseUtils.t2s(chapter.title)
            2 -> ChineseUtils.s2t(chapter.title)
            else -> chapter.title
        }
        coroutineContext.ensureActive()
        val mContent = contentProcessor!!.getContent(
            book, chapter, chapterContent,
            useReplace = replaceEnabled,
            titleOverride = searchTitle
        ).toString()
        // 大小写不敏感的字面量匹配；规则统一在 TextSearchUtils
        val positions = TextSearchUtils.findAllIgnoreCase(mContent, query)
        positions.forEachIndexed { index, position ->
            coroutineContext.ensureActive()
            val construct = getResultAndQueryIndex(mContent, position, query)
            val result = SearchResult(
                resultCountWithinChapter = index,
                resultText = construct.second,
                chapterTitle = searchTitle,
                query = query,
                chapterIndex = chapter.index,
                queryIndexInResult = construct.first,
                queryIndexInChapter = position
            )
            searchResultsWithinChapter.add(result)
        }
        // 计数交由调用方单消费者统计，避免并行 += 竞态
        return searchResultsWithinChapter
    }

    private fun getResultAndQueryIndex(
        content: String,
        queryIndexInContent: Int,
        query: String
    ): Pair<Int, String> {
        // 左右移动20个字符，构建关键词周边文字，在搜索结果里显示
        // 判断段落，只在关键词所在段落内分割
        // 利用标点符号分割完整的句
        // length和设置结合，自由调整周边文字长度
        val length = 20
        var po1 = queryIndexInContent - length
        var po2 = queryIndexInContent + query.length + length
        if (po1 < 0) {
            po1 = 0
        }
        if (po2 > content.length) {
            po2 = content.length
        }
        val queryIndexInResult = queryIndexInContent - po1
        val newText = content.substring(po1, po2)
        return queryIndexInResult to newText
    }

}