package io.legado.app.ui.dialog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 小屏(如小米 6,1080×1920)弹窗底部按钮可达性哨兵。
 *
 * 背景:设置类弹窗原先窗口高度用 `setLayout(0.9f, WRAP_CONTENT)` 无上限,内容一撑高就把最下面的
 * 「确定」顶出屏幕且无法滚动到达。修复方案是统一改成
 * 「内容区 NestedScrollView(@id/content_scroll, 0dp + weight=1) + 同级常驻底栏 + 宿主 setAdaptiveHeight 限高」。
 *
 * 本测试锁三件事(无 Robolectric,故为源码级断言,与仓库既有哨兵测试同风格):
 * 1. 共享 helper 存在且签名正确;
 * 2. 每个布局都有滚动容器,且底栏位于滚动容器**之外**(这条才真正保证底栏不会再被裁掉);
 * 3. 每个宿主都接了新 helper,且不再有旧的无上限 `setLayout(` 调用。
 */
class AdaptiveDialogHeightTest {

    private data class Target(val layout: String, val footerId: String, val host: String)

    private val targets = listOf(
        Target(
            "src/main/res/layout/dialog_check_source_config.xml",
            "@+id/tv_ok",
            "src/main/java/io/legado/app/ui/config/CheckSourceConfig.kt",
        ),
        Target(
            "src/main/res/layout/dialog_explore_container_edit.xml",
            "@+id/tv_ok",
            "src/main/java/io/legado/app/ui/main/explore/manage/ExploreContainerEditDialog.kt",
        ),
        Target(
            "src/main/res/layout/dialog_m3_color_picker.xml",
            "@+id/tv_ok",
            "src/main/java/io/legado/app/ui/widget/dialog/M3ColorPickerDialog.kt",
        ),
        Target(
            "src/main/res/layout/dialog_page_key.xml",
            "@+id/tv_ok",
            "src/main/java/io/legado/app/ui/book/read/config/PageKeyDialog.kt",
        ),
        Target(
            "src/main/res/layout/dialog_highlight_shadow.xml",
            "@+id/tv_ok",
            "src/main/java/io/legado/app/ui/book/read/ShadowEditDialog.kt",
        ),
        Target(
            "src/main/res/layout/dialog_sleep_timer.xml",
            "@+id/ll_status",
            "src/main/java/io/legado/app/ui/widget/dialog/SleepTimerDialog.kt",
        ),
        Target(
            "src/main/res/layout/dialog_book_group_edit.xml",
            "@+id/btn_ok",
            "src/main/java/io/legado/app/ui/book/group/GroupEditDialog.kt",
        ),
    )

    @Test
    fun `adaptive height helper exists for dialog fragment and raw dialog`() {
        val src = readProjectFile("src/main/java/io/legado/app/utils/DialogExtensions.kt")
        assertTrue(
            "应提供 DialogFragment 版 setAdaptiveHeight",
            src.contains("fun DialogFragment.setAdaptiveHeight("),
        )
        assertTrue(
            "应提供裸 Dialog 版 setAdaptiveHeight(PageKeyDialog 用)",
            src.contains("fun Dialog.setAdaptiveHeight("),
        )
        assertTrue("应在预绘制前完成测高", src.contains("doOnPreDraw"))
        assertTrue(
            "第一遍必须按内容量(WRAP_CONTENT),否则短弹窗会被拉满",
            src.contains("setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)"),
        )
        assertTrue(
            "内容区约定 id 必须与布局一致",
            src.contains("R.id.content_scroll"),
        )
    }

    @Test
    fun `each target layout has scrollable content and footer outside it`() {
        targets.forEach { t ->
            val xml = readProjectFile(t.layout)
            assertTrue(
                "${t.layout} 缺滚动容器 NestedScrollView",
                xml.contains("androidx.core.widget.NestedScrollView"),
            )
            assertTrue(
                "${t.layout} 缺约定 id @+id/content_scroll",
                xml.contains("@+id/content_scroll"),
            )
            assertTrue(
                "${t.layout} 滚动容器应占满剩余高度(0dp + weight=1)",
                xml.contains("android:layout_weight=\"1\""),
            )
            val footer = xml.indexOf(t.footerId)
            val scrollEnd = xml.indexOf("</androidx.core.widget.NestedScrollView>")
            assertTrue("${t.layout} 里找不到底栏 ${t.footerId}", footer >= 0)
            assertTrue(
                "${t.layout} 底栏 ${t.footerId} 必须在滚动容器之外,否则小屏上仍会被顶出屏幕",
                footer > scrollEnd,
            )
        }
    }

    @Test
    fun `hosts use adaptive height and dropped unbounded setLayout`() {
        targets.forEach { t ->
            val kt = readProjectFile(t.host)
            assertTrue(
                "${t.host} 未接入 setAdaptiveHeight",
                kt.contains("setAdaptiveHeight"),
            )
            assertTrue(
                "${t.host} 仍有旧的无上限 setLayout( 调用,会导致高度不受控",
                !kt.contains("setLayout("),
            )
        }
    }

    private fun readProjectFile(pathInApp: String): String {
        val candidates = listOf(File(pathInApp), File("app/$pathInApp"))
        return candidates.first { it.isFile }.readText()
    }
}
