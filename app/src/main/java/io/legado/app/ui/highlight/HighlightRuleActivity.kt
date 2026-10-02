package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.databinding.ActivityHighlightRuleBinding
import io.legado.app.databinding.ItemManageBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadBook
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.setupManagePage
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 高亮规则管理(关键词/正则)
 */
class HighlightRuleActivity :
    VMBaseActivity<ActivityHighlightRuleBinding, HighlightRuleViewModel>(),
    HighlightRuleAdapter.CallBack {

    override val binding by viewBinding(ActivityHighlightRuleBinding::inflate)
    override val viewModel by viewModels<HighlightRuleViewModel>()
    private val adapter by lazy { HighlightRuleAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        observeData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.highlight_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_highlight_rule ->
                showDialogFragment(HighlightRuleEditDialog.create(pattern = ""))
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        // A15+ 强制 e2e:末项抬离手势条(xml 已配 clipToPadding=false)
        binding.recyclerView.applyNavigationBarPadding()
        binding.recyclerView.setupManagePage(
            adapter,
            ItemTouchCallback(adapter).apply { isCanDrag = true },
        )
        adapter.addFooterView { parent ->
            ItemManageBinding.inflate(layoutInflater, parent, false).apply {
                cbName.gone()
                tvName.visible()
                tvName.text = getString(R.string.highlight_load_preset)
                tvSubtitle.visible()
                tvSubtitle.text = getString(R.string.highlight_load_preset_desc)
                swtEnabled.gone()
                ivEdit.gone()
                ivMenuMore.gone()
                contentLayout.setOnClickListener { confirmLoadPreset() }
            }
        }
    }

    /** 一键载入「彩读上色」预设 */
    private fun confirmLoadPreset() {
        alert(R.string.highlight_load_preset) {
            setMessage(getString(R.string.highlight_load_preset_msg))
            noButton()
            yesButton {
                viewModel.loadColorPresets()
                toastOnUi(R.string.highlight_load_preset_done)
            }
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            appDb.highlightRuleDao.flowAll()
                .catch { AppLog.put("高亮规则界面更新数据出错", it) }
                .flowOn(IO).conflate().collect {
                    adapter.setItems(it, adapter.diffItemCallBack)
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ReadBook.upHighlightRules() // 刷新已打开的阅读页
    }

    override fun update(vararg rule: HighlightRule) = viewModel.update(*rule)

    override fun delete(rule: HighlightRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.getDisplayName())
            noButton()
            yesButton { viewModel.delete(rule) }
        }
    }

    override fun edit(rule: HighlightRule) {
        showDialogFragment(HighlightRuleEditDialog.edit(rule.id))
    }

    override fun toTop(rule: HighlightRule) = viewModel.toTop(rule)
    override fun toBottom(rule: HighlightRule) = viewModel.toBottom(rule)
    override fun upOrder() = viewModel.upOrder()
}
