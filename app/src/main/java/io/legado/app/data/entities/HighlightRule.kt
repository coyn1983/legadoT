package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 关键词/正则自动高亮规则
 * style = HighlightStyle 的 JSON(见 [HighlightStyle]);scope 语义同 ReplaceRule:
 * 空 = 全局,非空 = 按书名/书源(origin)子串限定。
 */
@Parcelize
@Entity(tableName = "highlightRules")
data class HighlightRule(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var name: String = "",
    var pattern: String = "",
    var isRegex: Boolean = false,
    var scope: String? = null,
    var isEnabled: Boolean = true,
    var style: String = "",
    @ColumnInfo(name = "sortOrder")
    var order: Int = Int.MIN_VALUE,
    var timeoutMillisecond: Long = 3000L,
    var group: String? = null,
    @ColumnInfo(defaultValue = "0")
    var applyToTitle: Boolean = false
) : Parcelable {

    override fun equals(other: Any?): Boolean {
        if (other is HighlightRule) return other.id == id
        return super.equals(other)
    }

    override fun hashCode(): Int = id.hashCode()

    @IgnoredOnParcel
    @Ignore
    @Transient
    private var styleCache: HighlightStyle? = null

    /** 解析后的样式(惰性缓存) */
    fun styleObj(): HighlightStyle {
        styleCache?.let { return it }
        return (GSON.fromJsonObject<HighlightStyle>(style).getOrNull() ?: HighlightStyle())
            .also { styleCache = it }
    }

    /** 写入样式并同步 JSON 列 */
    fun applyStyle(s: HighlightStyle) {
        styleCache = s
        style = GSON.toJson(s)
    }

    fun getDisplayName(): String = name.ifBlank { pattern }

    fun isValid(): Boolean {
        if (pattern.isEmpty()) return false
        if (isRegex) {
            try {
                Pattern.compile(pattern)
            } catch (_: PatternSyntaxException) {
                return false
            }
            if (pattern.endsWith('|') && !pattern.endsWith("\\|")) return false
        }
        return true
    }

    fun checkValid() {
        if (!isValid()) {
            throw io.legado.app.exception.NoStackTraceException("规则无效: $pattern")
        }
    }

    companion object {
        /** 内置常用规则(首次启动种入, 默认关闭, 用户可在规则管理页启用) */
        const val GROUP_BUILT_IN = "内置"

        fun builtIns(): List<HighlightRule> = listOf(
            HighlightRule(
                name = "引号对话",
                pattern = "[「『“].+?[」』”]",
                isRegex = true,
                isEnabled = false,
                group = GROUP_BUILT_IN
            ).apply { applyStyle(HighlightStyle(fill = 0x804FC3F7.toInt())) },     // 蓝底
            HighlightRule(
                name = "书名号",
                pattern = "《[^》]+?》",
                isRegex = true,
                isEnabled = false,
                group = GROUP_BUILT_IN
            ).apply {
                applyStyle(
                    HighlightStyle(
                        underline = HighlightStyle.Underline(HighlightStyle.Kind.WAVY, 0xFFE53935.toInt())
                    )
                )                                                                  // 红波浪
            }
        )

        /**
         * 「彩读上色」预设: 规则页一键载入, 用字色区分不同内容, 载入即启用。
         * 与 [builtIns] 同名者(引号对话/书名号)会被覆盖为这里的样式。
         */
        fun colorPresets(): List<HighlightRule> = listOf(
            HighlightRule(
                name = "引号对话",
                pattern = "[「『“][^」』”]{1,300}[」』”]",
                isRegex = true,
                group = GROUP_BUILT_IN
            ).apply { applyStyle(HighlightStyle(textColor = 0xFF1E88E5.toInt())) },   // 蓝字
            HighlightRule(
                name = "书名号",
                pattern = "《[^》]{1,60}》",
                isRegex = true,
                group = GROUP_BUILT_IN
            ).apply {
                applyStyle(
                    HighlightStyle(
                        underline = HighlightStyle.Underline(HighlightStyle.Kind.WAVY, 0xFFE53935.toInt())
                    )
                )                                                                  // 红波浪
            },
            HighlightRule(
                name = "数字",
                pattern = "\\d+(?:\\.\\d+)?",
                isRegex = true,
                group = GROUP_BUILT_IN
            ).apply { applyStyle(HighlightStyle(textColor = 0xFFE08A2E.toInt())) },   // 橙字
            HighlightRule(
                name = "括号夹注",
                pattern = "[（(][^）)]{1,60}[）)]",
                isRegex = true,
                group = GROUP_BUILT_IN
            ).apply { applyStyle(HighlightStyle(textColor = 0xFF2E9E6B.toInt())) }    // 绿字
        )
    }
}
