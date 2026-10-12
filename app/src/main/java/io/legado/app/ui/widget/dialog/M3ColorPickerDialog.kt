package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogM3ColorPickerBinding
import io.legado.app.databinding.ItemColorSwatchBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyAppTint
import io.legado.app.utils.gone
import io.legado.app.utils.isHex
import io.legado.app.utils.setAdaptiveHeight
import io.legado.app.utils.setRoundBackground
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 自建 M3 取色器(N4 Task 6),替代第三方取色器库的 ColorPickerDialog(Task 7 六调用点切换)。
 *
 * 纵排三区(见 `dialog_m3_color_picker.xml`):
 * 1. 色板 grid([ARG_PALETTE] 为 null/空时整区 gone)——点选即把该色(自带 alpha)纳入工作态。
 * 2. [io.legado.app.ui.widget.HsvPanelView] 自绘色相/饱和度-明度面板。
 * 3. hex 输入(6/8 位随 showAlpha)+ alpha Slider(仅 showAlpha 时可见)+ 当前/初始对比条。
 *
 * 区 1~3 同处一个 NestedScrollView(@id/content_scroll)内;确认/取消为常驻底栏,不随内容滚动。
 * 窗口高度由 [setAdaptiveHeight] 控制:内容不超屏高 90% 时贴内容高度,超过则封顶并让本区滚动,
 * 避免小屏(如小米 6)上底部按钮被顶出屏幕而无法点击。
 *
 * 结果交付走 `setFragmentResult`(非 lambda)——[requestKey] 由 [show] 调用方定义,
 * DialogFragment 经系统旋转重建后如果走构造期传入的 lambda 引用会丢失(新实例是反射重建的,
 * 不会重新执行调用方的 `show(...)` 代码),而 `setFragmentResult`/`Bundle` 走 FragmentManager
 * 自身的状态存储与生命周期感知分发,旋转后依然能送达监听方——这是选择该交付方式而非构造时传回调的
 * 唯一原因(其余逻辑用回调反而更直接)。
 *
 * 工作色([workingColor])的旋转存活走 [onSaveInstanceState]:Fragment 的 `arguments` 只承载
 * "初始参数"语义(不应被工作态覆盖,否则失去"初始色"这个对照基准),因此没有直接改写 `arguments`,
 * 而是用 Android Fragment 生命周期本就提供的 `onSaveInstanceState(Bundle)` → 重建后经
 * `onFragmentCreated(view, savedInstanceState)` 的同一个 Bundle 参数原样取回,是这层状态最省事、
 * 最少自定义代码的落点。
 */
class M3ColorPickerDialog : BaseDialogFragment(R.layout.dialog_m3_color_picker) {

    companion object {
        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_INITIAL = "initial"
        private const val ARG_SHOW_ALPHA = "showAlpha"
        private const val ARG_PALETTE = "palette"
        private const val STATE_WORKING_COLOR = "workingColor"

        /** setFragmentResult 携带的 Bundle key(拾取到的最终颜色,Int) */
        const val RESULT_COLOR = "color"

        private const val DIALOG_TAG = "m3ColorPicker"

        /**
         * @param fm 承载该弹窗的 FragmentManager(调用方 activity 的 supportFragmentManager 或
         *   host fragment 的 childFragmentManager 均可)——结果经由同一个 FragmentManager 的
         *   `setFragmentResult`/`setFragmentResultListener` 配对送达,调用方须在此 fm 上注册监听。
         * @param requestKey 本次拾色会话的唯一 key,调用方各处需各自不同(通常按语义命名,如
         *   "highlightBg"/"reviewIconColor"),避免多个取色器实例互相串号。
         * @param initial 弹窗打开时的初始色(同时作为"初始色对比条"的基准,弹窗内不会改变)。
         * @param showAlpha 是否显示 alpha 通道(8 位 hex + alpha Slider);false 时强制拾取不透明色。
         * @param palette 色板预设(null/空数组则色板区整体隐藏)。
         */
        fun show(
            fm: FragmentManager,
            requestKey: String,
            @ColorInt initial: Int,
            showAlpha: Boolean,
            palette: IntArray?,
        ) {
            val dialog = M3ColorPickerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putInt(ARG_INITIAL, initial)
                    putBoolean(ARG_SHOW_ALPHA, showAlpha)
                    putIntArray(ARG_PALETTE, palette)
                }
            }
            dialog.show(fm, DIALOG_TAG)
        }
    }

    private val binding by viewBinding(DialogM3ColorPickerBinding::bind)
    private val paletteAdapter by lazy { PaletteAdapter(requireContext()) }
    private val radiusS by lazy { resources.getDimension(R.dimen.radius_s) }

    private val requestKey: String get() = arguments?.getString(ARG_REQUEST_KEY) ?: ""

    private var showAlpha = false
    private var initialColor = Color.BLACK

    /** 唯一权威工作态(ARGB 全量);三区任一控件变化都先改写它,再统一驱动其余控件回显 */
    private var workingColor = Color.BLACK

    /**
     * 双向同步回声守卫:true 期间 hex/slider 的 change 监听器直接 return,防止
     * "程序回写控件→触发该控件监听→再次回写"的无限自触发。三个源头(HSV 触摸/hex 输入/
     * palette 点选/alpha 滑杆)每次变化都统一走 [applyWorkingColor],内部用该标志包裹对
     * 其余控件的回写,而不区分"跳过发起者"——回写发起者自身是幂等的(如 hex 输入合法值后
     * 把同一文本原样 setText 回去),多余一次赋值但不产生错误状态。
     */
    private var isUpdating = false

    override fun onStart() {
        super.onStart()
        setAdaptiveHeight(0.9f, 0.9f)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_WORKING_COLOR, workingColor)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        showAlpha = arguments?.getBoolean(ARG_SHOW_ALPHA) ?: false
        val initialArg = arguments?.getInt(ARG_INITIAL) ?: Color.BLACK
        // 与旧 ColorPreference 同规则:showAlpha=false 时初始色也强制去 alpha,避免残留历史透明度
        initialColor = if (showAlpha) initialArg else ColorUtils.withAlpha(initialArg, 1f)
        binding.viewInitialPreview.setRoundBackground(initialColor, radius = radiusS)

        binding.layoutAlpha.visible(showAlpha)
        binding.sliderAlpha.applyAppTint(requireContext().accentColor)
        binding.hsvPanel.onColorChanged = { rgb -> onControlChanged(composeColor(currentAlpha(), rgb)) }
        binding.etHex.doAfterTextChanged { text ->
            if (isUpdating) return@doAfterTextChanged
            parseHex(text?.toString().orEmpty(), showAlpha)?.let { onControlChanged(it) }
        }
        binding.sliderAlpha.addOnChangeListener { _, value, _ ->
            if (isUpdating) return@addOnChangeListener
            onControlChanged(composeColor(value.toInt().coerceIn(0, 255), binding.hsvPanel.color))
        }

        val palette = arguments?.getIntArray(ARG_PALETTE)
        if (palette == null || palette.isEmpty()) {
            binding.layoutPalette.gone()
        } else {
            // 小色板(如 HighlightColors 5 色)整行铺满;大色板保持 6 列
            binding.recyclerPalette.layoutManager =
                GridLayoutManager(requireContext(), minOf(6, palette.size))
            binding.recyclerPalette.adapter = paletteAdapter
            paletteAdapter.setItems(palette.toList())
        }

        // 工作色旋转存活:优先用 savedInstanceState 里的值,首次创建才落回 initialColor
        val restored = savedInstanceState?.getInt(STATE_WORKING_COLOR) ?: initialColor
        applyWorkingColor(restored)

        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvOk.setOnClickListener {
            parentFragmentManager.setFragmentResult(requestKey, bundleOf(RESULT_COLOR to workingColor))
            dismiss()
        }
    }

    private fun currentAlpha(): Int = (workingColor ushr 24) and 0xFF

    /** 三个源头控件(HSV 面板/hex 输入/alpha 滑杆)共用的变化入口;palette 点选直接调 [applyWorkingColor] */
    private fun onControlChanged(newColor: Int) {
        applyWorkingColor(if (showAlpha) newColor else ColorUtils.withAlpha(newColor, 1f))
    }

    /** 唯一写入口:更新 [workingColor] 并把三个受控控件(HSV/hex/alpha)+两条预览+色板选中态全部刷新 */
    @SuppressLint("NotifyDataSetChanged")
    private fun applyWorkingColor(@ColorInt color: Int) {
        workingColor = color
        isUpdating = true
        binding.hsvPanel.color = color
        binding.etHex.setText(formatHex(color, showAlpha))
        if (showAlpha) binding.sliderAlpha.value = ((color ushr 24) and 0xFF).toFloat()
        isUpdating = false
        binding.viewCurrentPreview.setRoundBackground(color, radius = radiusS)
        paletteAdapter.notifyDataSetChanged()
    }

    private fun composeColor(alpha: Int, @ColorInt rgb: Int): Int =
        (alpha shl 24) or (rgb and 0x00FFFFFF)

    private fun formatHex(@ColorInt color: Int, withAlpha: Boolean): String = if (withAlpha) {
        String.format("%08X", color)
    } else {
        String.format("%06X", color and 0xFFFFFF)
    }

    /** 严格按 [withAlpha] 要求的位数校验(6 或 8),非法输入原样返回 null(调用方静默忽略,不崩、不清空) */
    private fun parseHex(raw: String, withAlpha: Boolean): Int? {
        val clean = raw.trim().removePrefix("#")
        val expectedLen = if (withAlpha) 8 else 6
        if (clean.length != expectedLen || !clean.isHex()) return null
        return if (withAlpha) {
            clean.toLongOrNull(16)?.toInt()
        } else {
            clean.toIntOrNull(16)?.let { (0xFF shl 24) or it }
        }
    }

    private inner class PaletteAdapter(context: Context) :
        RecyclerAdapter<Int, ItemColorSwatchBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemColorSwatchBinding =
            ItemColorSwatchBinding.inflate(inflater, parent, false)

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemColorSwatchBinding,
            item: Int,
            payloads: MutableList<Any>
        ) {
            binding.ivSwatch.setImageDrawable(ColorDrawable(item))
            // 与点选路径同规归一再比:showAlpha=false 时 workingColor 已被去 alpha,
            // 非全透明预设(如 HighlightColors.bg 的 0x80 alpha)裸比永不相等→勾选永不出现
            val normalizedItem = if (showAlpha) item else ColorUtils.withAlpha(item, 1f)
            binding.ivSwatchCheck.visible(normalizedItem == workingColor)
            binding.ivSwatchCheck.setColorFilter(
                if (ColorUtils.isColorLight(item)) Color.BLACK else Color.WHITE
            )
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemColorSwatchBinding) {
            binding.root.setOnClickListener {
                getItem(holder.layoutPosition)?.let { onControlChanged(it) }
            }
        }
    }
}
