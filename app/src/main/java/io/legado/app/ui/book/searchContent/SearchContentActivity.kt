package io.legado.app.ui.book.searchContent

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.view.allViews
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivitySearchContentBinding
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.toolbarTextColor
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.DebugLog
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyTint
import io.legado.app.utils.invisible
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.mapAsync
import io.legado.app.utils.showSoftInput
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SearchContentActivity :
    VMBaseActivity<ActivitySearchContentBinding, SearchContentViewModel>(),
    SearchContentAdapter.Callback {

    override val binding by viewBinding(ActivitySearchContentBinding::inflate)
    override val viewModel by viewModels<SearchContentViewModel>()
    private val adapter by lazy { SearchContentAdapter(this, this) }
    private val mLayoutManager by lazy { UpLinearLayoutManager(this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var durChapterIndex = 0
    private var searchJob: Job? = null
    private var initJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val bbg = bottomBackground
        val btc = getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        binding.llSearchBaseInfo.setBackgroundColor(bbg)
        binding.llSearchBaseInfo.applyNavigationBarMargin()
        binding.tvCurrentSearchInfo.setTextColor(btc)
        binding.ivSearchContentTop.setColorFilter(btc)
        binding.ivSearchContentBottom.setColorFilter(btc)
        val searchResultList = IntentData.get<List<SearchResult>>("searchResultList")
        val position = intent.getIntExtra("searchResultIndex", 0)
        val noSearchResult = searchResultList == null
        initSearchView(noSearchResult)
        initRecyclerView()
        initView()
        val bookUrl = intent.getStringExtra("bookUrl") ?: return
        viewModel.initBook(bookUrl) {
            initSearchResultList(searchResultList, position)
            initBook(noSearchResult)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.content_search, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_enable_replace)?.isChecked = viewModel.replaceEnabled
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_replace -> {
                viewModel.replaceEnabled = !viewModel.replaceEnabled
                item.isChecked = viewModel.replaceEnabled
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initSearchResultList(list: List<SearchResult>?, position: Int) {
        list ?: return
        viewModel.searchResultList.addAll(list)
        viewModel.searchResultCounts = list.size
        adapter.setItems(list)
        binding.recyclerView.scrollToPosition(position)
    }

    private fun initSearchView(requestFocus: Boolean) {
        searchView.applyTint(toolbarTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search)
        if (requestFocus) searchView.isIconified = false
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                startContentSearch(query.trim())
                searchView.clearFocus()
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                return false
            }
        })
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() {
        binding.ivSearchContentTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
        }
        binding.ivSearchContentBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        binding.tvCurrentSearchInfo.setOnClickListener {
            searchView.allViews.forEach { view ->
                if (view is EditText) {
                    view.showSoftInput()
                    return@setOnClickListener
                }
            }
        }
        binding.fbStop.setOnClickListener {
            searchJob?.cancel()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(submit: Boolean = true) {
        binding.tvCurrentSearchInfo.text =
            this.getString(R.string.search_content_size) + ": ${viewModel.searchResultCounts}"
        viewModel.book?.let {
            initCacheFileNames(it)
            durChapterIndex = it.durChapterIndex
            intent.getStringExtra("searchWord")?.let { searchWord ->
                searchView.setQuery(searchWord, submit)
            }
        }
    }

    private fun initCacheFileNames(book: Book) {
        initJob = lifecycleScope.launch {
            withContext(IO) {
                viewModel.cacheChapterNames.addAll(BookHelp.getChapterFiles(book))
            }
            adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.book?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    viewModel.cacheChapterNames.add(chapter.getFileName())
                    adapter.notifyItemChanged(chapter.index, true)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun startContentSearch(query: String) {
        // 按章节搜索内容
        if (query.isBlank()) return
        searchJob?.cancel()
        adapter.clearItems()
        viewModel.searchResultList.clear()
        viewModel.searchResultCounts = 0
        viewModel.lastQuery = query
        binding.refreshProgressBar.isAutoLoading = true
        binding.fbStop.visible()
        searchJob = lifecycleScope.launch(IO) {
            initJob?.join()
            // 只搜索本地书或已缓存章节（未缓存章节没有正文可搜）
            val chapters = appDb.bookChapterDao.getChapterList(viewModel.bookUrl).filter {
                isLocalBook || viewModel.cacheChapterNames.contains(it.getFileName())
            }
            // 简繁词典是懒加载的，必须在并发开始前串行预热一次，
            // 否则多个 worker 会同时触发首次加载
            viewModel.warmUpChineseConverter()
            val concurrency = SearchConcurrency.resolve()
            val startTime = System.currentTimeMillis()
            kotlin.runCatching {
                // 保序受限并发：结果顺序与章节顺序一致，取消随 searchJob 级联，
                // 在途章节数受 concurrency 限制（内存有界）
                chapters.asFlow()
                    .mapAsync(concurrency) { chapter ->
                        val chapterStart = System.currentTimeMillis()
                        val result = viewModel.searchChapter(query, chapter)
                        DebugLog.d(
                            "SEARCH_TIMING",
                            "chapter=${chapter.index} cost=${System.currentTimeMillis() - chapterStart}ms hits=${result.size}"
                        )
                        result
                    }
                    .flowOn(Dispatchers.Default.limitedParallelism(concurrency))
                    .collect { searchResults ->
                        ensureActive()
                        if (searchResults.isNotEmpty()) {
                            viewModel.searchResultList.addAll(searchResults)
                            // 计数由单一消费者统计，避免并行 += 竞态
                            val total = viewModel.searchResultList.size
                            viewModel.searchResultCounts = total
                            binding.tvCurrentSearchInfo.post {
                                binding.tvCurrentSearchInfo.text =
                                    this@SearchContentActivity.getString(R.string.search_content_size) + ": $total"
                                adapter.addItems(searchResults)
                            }
                        }
                    }
                if (viewModel.searchResultCounts == 0) {
                    val noSearchResult =
                        SearchResult(resultText = getString(R.string.search_content_empty))
                    binding.tvCurrentSearchInfo.post {
                        adapter.addItem(noSearchResult)
                    }
                }
            }.onFailure {
                // 用户主动停止（fbStop）会以 CancellationException 结束，不算错误
                if (it !is CancellationException) {
                    AppLog.put("全文搜索出错\n${it.localizedMessage}", it)
                }
            }
            DebugLog.d(
                "SEARCH_TIMING",
                "SEARCH_TOTAL cost=${System.currentTimeMillis() - startTime}ms " +
                    "count=${viewModel.searchResultCounts} chapters=${chapters.size} concurrency=$concurrency"
            )
            binding.tvCurrentSearchInfo.post {
                binding.fbStop.invisible()
                binding.refreshProgressBar.isAutoLoading = false
            }
        }
    }

    private val isLocalBook: Boolean
        get() = viewModel.book?.isLocal == true

    override fun openSearchResult(searchResult: SearchResult, index: Int) {
        searchJob?.cancel()
        postEvent(EventBus.SEARCH_RESULT, viewModel.searchResultList as List<SearchResult>)
        val searchData = Intent()
        val key = System.currentTimeMillis()
        IntentData.put("searchResult$key", searchResult)
        IntentData.put("searchResultList$key", viewModel.searchResultList)
        searchData.putExtra("key", key)
        searchData.putExtra("index", index)
        setResult(RESULT_OK, searchData)
        finish()
    }

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

}