package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogHighlightShadowBinding
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.widget.dialog.M3ColorPickerDialog
import io.legado.app.utils.setAdaptiveHeight
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 编辑阴影参数对话框
 */
class ShadowEditDialog : BaseDialogFragment(R.layout.dialog_highlight_shadow) {

    companion object {
        private const val KEY_RADIUS = "radius"
        private const val KEY_DX = "dx"
        private const val KEY_DY = "dy"
        private const val KEY_COLOR = "color"
        private const val COLOR_REQUEST_KEY = "shadowColor"

        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            shadow: HighlightStyle.Shadow
        ) {
            val dialog = ShadowEditDialog().apply {
                arguments = Bundle().apply {
                    putFloat(KEY_RADIUS, shadow.radius)
                    putFloat(KEY_DX, shadow.dx)
                    putFloat(KEY_DY, shadow.dy)
                    putInt(KEY_COLOR, shadow.color)
                }
            }
            dialog.show(fragmentManager, "ShadowEditDialog")
        }
    }

    interface Callback {
        fun onShadowChanged(shadow: HighlightStyle.Shadow)
    }

    private val binding by viewBinding(DialogHighlightShadowBinding::bind)
    private var currentRadius = 3f
    private var currentDx = 2f
    private var currentDy = 2f
    private var currentColor = 0x80000000.toInt()

    override fun onStart() {
        super.onStart()
        // 保持满宽观感；高度内容自适应、屏高 90% 封顶
        setAdaptiveHeight(1f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            currentRadius = it.getFloat(KEY_RADIUS, 3f)
            currentDx = it.getFloat(KEY_DX, 2f)
            currentDy = it.getFloat(KEY_DY, 2f)
            currentColor = it.getInt(KEY_COLOR, 0x80000000.toInt())
        }

        // 半径 SeekBar (0~20, 步长0.5)
        binding.seekbarRadius.progress = (currentRadius * 2).toInt()
        binding.seekbarRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentRadius = progress / 2f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 横向偏移 SeekBar (-10~10, 步长0.5)
        binding.seekbarDx.progress = ((currentDx + 10f) * 2).toInt()
        binding.seekbarDx.max = 40
        binding.seekbarDx.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentDx = progress / 2f - 10f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 纵向偏移 SeekBar (-10~10, 步长0.5)
        binding.seekbarDy.progress = ((currentDy + 10f) * 2).toInt()
        binding.seekbarDy.max = 40
        binding.seekbarDy.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentDy = progress / 2f - 10f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 颜色选择
        binding.vShadowColor.setBackgroundColor(currentColor)
        binding.vShadowColor.setOnClickListener {
            M3ColorPickerDialog.show(
                childFragmentManager,
                COLOR_REQUEST_KEY,
                currentColor,
                true,
                intArrayOf(0x80000000.toInt(), 0xFFFFFFFF.toInt(), 0x80FFFFFF.toInt())
            )
        }

        childFragmentManager.setFragmentResultListener(COLOR_REQUEST_KEY, this) { _, bundle ->
            val color = bundle.getInt(M3ColorPickerDialog.RESULT_COLOR)
            currentColor = color
            binding.vShadowColor.setBackgroundColor(color)
        }

        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvOk.setOnClickListener {
            val callback = (parentFragment as? Callback) ?: (activity as? Callback)
            callback?.onShadowChanged(HighlightStyle.Shadow(currentRadius, currentDx, currentDy, currentColor))
            dismiss()
        }
    }
}
