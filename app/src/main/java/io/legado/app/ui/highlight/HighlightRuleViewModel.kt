package io.legado.app.ui.highlight

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule

class HighlightRuleViewModel(application: Application) : BaseViewModel(application) {

    fun update(vararg rule: HighlightRule) {
        execute { appDb.highlightRuleDao.update(*rule) }
    }

    fun delete(rule: HighlightRule) {
        execute { appDb.highlightRuleDao.delete(rule) }
    }

    fun toTop(rule: HighlightRule) {
        execute {
            rule.order = appDb.highlightRuleDao.minOrder - 1
            appDb.highlightRuleDao.update(rule)
        }
    }

    fun toBottom(rule: HighlightRule) {
        execute {
            rule.order = appDb.highlightRuleDao.maxOrder + 1
            appDb.highlightRuleDao.update(rule)
        }
    }

    fun upOrder() {
        execute {
            val rules = appDb.highlightRuleDao.all
            for ((index, rule) in rules.withIndex()) {
                rule.order = index + 1
            }
            appDb.highlightRuleDao.update(*rules.toTypedArray())
        }
    }

    /** 载入「彩读上色」预设: 同名规则重置为预设样式, 其余追加到末尾 */
    fun loadColorPresets() {
        execute {
            val dao = appDb.highlightRuleDao
            val existByName = dao.all.associateBy { it.name }
            var order = dao.maxOrder
            val rules = HighlightRule.colorPresets().onEach { rule ->
                rule.order = ++order
                existByName[rule.name]?.let { rule.id = it.id }
            }
            dao.insert(*rules.toTypedArray())
        }
    }
}
