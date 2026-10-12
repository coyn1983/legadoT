package io.legado.app.ui.widget.dialog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogSleepTimerBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.AppColorScheme
import io.legado.app.service.AudioPlayService
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.gone
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setAdaptiveHeight
import io.legado.app.utils.setRoundBackground
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 听书/音频书「停止设置」共用浮动弹窗:
 * 定时/按集数各一行等宽预设 chip(点选立即生效并关闭),「自定义」原地展开输入并预填上次值,
 * 底部状态条显示当前设置(集数优先于时间)与关闭入口。
 * 宿主实现 [CallBack] 路由到对应播放器;TTS 经 parentFragment、音频书经 activity,
 * 当前状态从同一宿主对应的服务读取。
 */
class SleepTimerDialog : BaseDialogFragment(R.layout.dialog_sleep_timer) {

    interface CallBack {
        /** 定时(分钟, 0=关闭) */
        fun onSleepTimerMinute(minute: Int)

        /** 按集数(章数, 0=关闭) */
        fun onSleepTimerChapter(count: Int)
    }

    private val binding by viewBinding(DialogSleepTimerBinding::bind)
    private val callBack get() = (parentFragment as? CallBack) ?: (activity as? CallBack)

    /** TTS 面板经 childFragmentManager 弹出(parentFragment 即宿主),否则为有声书页 */
    private val isTtsHost get() = parentFragment is CallBack
    private val currentMinute
        get() = if (isTtsHost) BaseReadAloudService.timeMinute else AudioPlayService.timeMinute
    private val currentChapter
        get() = if (isTtsHost) BaseReadAloudService.chapterToStop else AudioPlayService.chapterToStop

    private val timeChips
        get() = binding.run { listOf(tvTimeP1, tvTimeP2, tvTimeP3, tvTimeP4) }
    private val chapterChips
        get() = binding.run { listOf(tvChapterP1, tvChapterP2, tvChapterP3, tvChapterP4) }

    override fun onStart() {
        super.onStart()
        setAdaptiveHeight(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            llStatus.setRoundBackground(
                AppColorScheme.current.surfaceContainer,
                radius = resources.getDimension(R.dimen.radius_m)
            )
            timeChips.forEachIndexed { i, chip ->
                chip.text = getString(R.string.sleep_timer_minute_short, TIME_PRESETS[i])
                chip.setOnClickListener { applyMinute(TIME_PRESETS[i], save = false) }
            }
            chapterChips.forEachIndexed { i, chip ->
                chip.text = getString(R.string.sleep_timer_chapter_short, CHAPTER_PRESETS[i])
                chip.setOnClickListener { applyChapter(CHAPTER_PRESETS[i], save = false) }
            }
            (timeChips + chapterChips + tvTimeCustom + tvChapterCustom + tvTimeOk + tvChapterOk)
                .forEach { it.background = chipBackground(selected = false) }

            tvTimeCustom.setOnClickListener {
                toggleInput(llTimeInput, etTime, PreferKey.lastSleepTimer)
            }
            tvChapterCustom.setOnClickListener {
                toggleInput(llChapterInput, etChapter, PreferKey.lastSleepChapter)
            }
            tvTimeOk.setOnClickListener {
                val m = etTime.text.toString().toIntOrNull()
                if (m == null || m <= 0) {
                    requireContext().toastOnUi(R.string.sleep_timer_minute_hint)
                } else {
                    applyMinute(m, save = true)
                }
            }
            tvChapterOk.setOnClickListener {
                val c = etChapter.text.toString().toIntOrNull()
                if (c == null || c <= 0) {
                    requireContext().toastOnUi(R.string.sleep_timer_chapter_hint)
                } else {
                    applyChapter(c, save = true)
                }
            }
            tvOff.setOnClickListener { applyMinute(0, save = false) }

            // 当前生效项高亮 + 状态条(集数优先,与服务层互斥语义一致)
            val minute = currentMinute
            val chapter = currentChapter
            when {
                chapter > 0 -> {
                    markSelected(
                        chapterChips.getOrNull(CHAPTER_PRESETS.indexOf(chapter)) ?: tvChapterCustom
                    )
                    tvStatus.text = getString(R.string.sleep_timer_status_chapter, chapter)
                }
                minute > 0 -> {
                    markSelected(
                        timeChips.getOrNull(TIME_PRESETS.indexOf(minute)) ?: tvTimeCustom
                    )
                    tvStatus.text = getString(R.string.sleep_timer_status_time, minute)
                }
                else -> tvStatus.text = getString(R.string.sleep_timer_status_none)
            }
        }
    }

    /** 自定义输入行收放;展开时空输入框预填上次自定义值 */
    private fun toggleInput(row: View, editText: EditText, prefKey: String) {
        if (row.isVisible) {
            row.gone()
        } else {
            row.visible()
            if (editText.text.isNullOrEmpty()) {
                requireContext().getPrefInt(prefKey, 0).takeIf { it > 0 }?.let {
                    editText.setText(it.toString())
                    editText.setSelection(editText.text?.length ?: 0)
                }
            }
            editText.requestFocus()
        }
    }

    private fun markSelected(chip: TextView) {
        chip.background = chipBackground(selected = true)
        chip.setTextColor(
            if (AppConfig.isEInkMode) Color.WHITE else AppColorScheme.current.onPrimaryContainer
        )
        chip.typeface = Typeface.DEFAULT_BOLD
    }

    /** chip 背景:默认 outline 描边,选中 primaryContainer 底;eink 选中反色。带按压 ripple */
    private fun chipBackground(selected: Boolean): RippleDrawable {
        val scheme = AppColorScheme.current
        val radius = resources.getDimension(R.dimen.radius_m)
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            when {
                selected && AppConfig.isEInkMode -> setColor(Color.BLACK)
                selected -> {
                    setColor(scheme.primaryContainer)
                    setStroke(1.dpToPx(), scheme.primary)
                }
                else -> {
                    setColor(Color.TRANSPARENT)
                    setStroke(1.dpToPx(), scheme.outline)
                }
            }
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(scheme.outlineVariant), content, mask)
    }

    private fun applyMinute(minute: Int, save: Boolean) {
        if (save && minute > 0) requireContext().putPrefInt(PreferKey.lastSleepTimer, minute)
        if (minute > 0) AppConfig.sleepTimerPreferChapter = false
        callBack?.onSleepTimerMinute(minute)
        dismissAllowingStateLoss()
    }

    private fun applyChapter(count: Int, save: Boolean) {
        if (save && count > 0) requireContext().putPrefInt(PreferKey.lastSleepChapter, count)
        if (count > 0) AppConfig.sleepTimerPreferChapter = true
        callBack?.onSleepTimerChapter(count)
        dismissAllowingStateLoss()
    }

    companion object {
        private val TIME_PRESETS = intArrayOf(15, 30, 45, 60)
        private val CHAPTER_PRESETS = intArrayOf(1, 2, 3, 5)
    }
}
