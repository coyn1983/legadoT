package io.legado.app.utils

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnPreDraw
import androidx.core.view.forEach
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.AppColorScheme
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.filletBackground
import splitties.systemservices.windowManager

fun AlertDialog.applyTint(): AlertDialog {
    window?.setBackgroundDrawable(context.filletBackground)
    val colorStateList = Selector.colorBuild()
        .setDefaultColor(context.accentColor)
        .setPressedColor(ColorUtils.darkenColor(context.accentColor))
        .create()
    if (getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
        getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(colorStateList)
    }
    if (getButton(AlertDialog.BUTTON_POSITIVE) != null) {
        getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(colorStateList)
    }
    if (getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
        getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(colorStateList)
    }
    window?.decorView?.post {
        listView?.forEach {
            it.applyTint(context.accentColor)
        }
    }
    return this
}

fun AlertDialog.requestInputMethod() {
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
}

/**
 * 统一 BottomSheet 容器外观:顶部 radius_xl 圆角 + surfaceContainerLow(M3 sheet 容器色,用户背景锚定,
 * attr 路径拿不到锚定色所以代码设置);eink 时为顶部边框方角。
 * 在 onStart/show 之后调用(design_bottom_sheet 已就位)。
 */
fun Dialog.applyAppSheetBackground() {
    val sheet =
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
    if (AppConfig.isEInkMode) {
        sheet.setBackgroundResource(R.drawable.bg_eink_border_top)
        return
    }
    val radius = context.resources.getDimension(R.dimen.radius_xl)
    sheet.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        setColor(AppColorScheme.current.surfaceContainerLow)
    }
    sheet.clipToOutline = true
}

fun DialogFragment.setLayout(widthMix: Float, heightMix: Float) {
    dialog?.setLayout(widthMix, heightMix)
}

fun Dialog.setLayout(widthMix: Float, heightMix: Float) {
    val dm = context.windowManager.windowSize
    window?.setLayout(
        (dm.widthPixels * widthMix).toInt(),
        (dm.heightPixels * heightMix).toInt()
    )
}

fun DialogFragment.setLayout(width: Int, heightMix: Float) {
    dialog?.setLayout(width, heightMix)
}

fun Dialog.setLayout(width: Int, heightMix: Float) {
    val dm = context.windowManager.windowSize
    window?.setLayout(
        width,
        (dm.heightPixels * heightMix).toInt()
    )
}

fun DialogFragment.setLayout(widthMix: Float, height: Int) {
    dialog?.setLayout(widthMix, height)
}

fun Dialog.setLayout(widthMix: Float, height: Int) {
    val dm = context.windowManager.windowSize
    window?.setLayout(
        (dm.widthPixels * widthMix).toInt(),
        height
    )
}

fun DialogFragment.setLayout(width: Int, height: Int) {
    dialog?.setLayout(width, height)
}

fun Dialog.setLayout(width: Int, height: Int) {
    window?.setLayout(width, height)
}

/**
 * 内容自适应 + 屏高封顶（小屏弹窗底部按钮被顶出屏幕的统一解法）。
 *
 * - 内容自然高 ≤ [maxHeightMix] × 可用屏高：窗口保持 `WRAP_CONTENT`，弹窗贴内容高度，
 *   大屏观感与改造前一致；
 * - 超过：窗口封顶到 [maxHeightMix] × 可用屏高，此时内容区的 `layout_weight="1"` 才生效 →
 *   `@id/content_scroll`（NestedScrollView）滚动，底栏常驻可见可点。
 *
 * 前置约定：内容区必须是 `NestedScrollView` 且 id 为 `@+id/content_scroll`，
 * 底栏与其同级、位于其后（照抄 `dialog_bookmark.xml` 的结构）。
 *
 * 调用方式（替代原来的 `setLayout(...)`，必须在 `super.onStart()` 之后）：
 * ```
 * override fun onStart() {
 *     super.onStart()
 *     setAdaptiveHeight()          // 默认 0.9f 宽、0.9f 屏高封顶
 * }
 * ```
 */
fun DialogFragment.setAdaptiveHeight(widthMix: Float = 0.9f, maxHeightMix: Float = 0.9f) {
    val dlg = dialog ?: return
    val root = view ?: return
    applyAdaptiveHeight(dlg, root, widthMix, maxHeightMix)
}

/**
 * 裸 `android.app.Dialog` 版（如 PageKeyDialog，不走 BaseDialogFragment）。
 * [content] 传 `setContentView` 用的根视图（通常是 `binding.root`）。
 */
fun Dialog.setAdaptiveHeight(widthMix: Float, maxHeightMix: Float, content: View) {
    applyAdaptiveHeight(this, content, widthMix, maxHeightMix)
}

private fun applyAdaptiveHeight(
    dialog: Dialog,
    root: View,
    widthMix: Float,
    maxHeightMix: Float
) {
    // windowSize 已扣除系统栏与挖孔（见 ActivityExtensions.windowSize），故 0.9 是「可用区」的 90%
    val windowSize = dialog.context.windowManager.windowSize
    val widthPx = (windowSize.widthPixels * widthMix).toInt()
    // 兼容 DialogForm.FULL_SCREEN 在 decorView 上加的留边，避免实际高度超出封顶值
    val decorPadding =
        (dialog.window?.decorView?.paddingTop ?: 0) + (dialog.window?.decorView?.paddingBottom ?: 0)
    val maxHeightPx = (windowSize.heightPixels * maxHeightMix).toInt() - decorPadding

    // 第一遍：宽度固定、高度交给内容（此时弹窗贴内容高度，短弹窗不会被拉满）
    dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)

    // doOnPreDraw 内部是一次性监听器：回调一次即自移除，不会因二次 setLayout 形成回调环
    root.doOnPreDraw {
        val scroll = root.findViewById<NestedScrollView>(R.id.content_scroll)
        // ScrollView 无论自身被量成多少，都以 UNSPECIFIED 量其唯一子 View，
        // 故这里恒为真实内容高——用它而不是用 scroll.height，才能免疫
        // 「WRAP_CONTENT 窗口下 weight=1 的 NSV 被分到整屏高」的歧义，避免短弹窗被误判超限
        val contentHeight = scroll?.getChildAt(0)?.measuredHeight ?: 0
        // 顶栏 + 底栏 + 根 padding：与 NSV 自身高度无关，任何测量结果下都成立
        val chromeHeight = root.height - (scroll?.height ?: 0)
        if (contentHeight + chromeHeight > maxHeightPx) {
            // 超限：封顶 → NSV 收缩为剩余空间而滚动，底栏常驻
            dialog.window?.setLayout(widthPx, maxHeightPx)
        }
        true
    }
}
