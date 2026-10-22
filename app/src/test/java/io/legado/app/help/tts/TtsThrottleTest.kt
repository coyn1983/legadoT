package io.legado.app.help.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置朗读引擎(豆包 / MiMo)的连接节流哨兵。
 *
 * 背景:这两个接口都按账号限制连接频率。原来的实现走 legado 通用下载循环,
 * 逐段(几十字)建一条 WebSocket,且每章开头无条件预取下一章 10 段,
 * 失败还要重试 3 次 —— 一章开播几秒内就能打出十几条连接,服务端直接回
 * `710022002 block`,表现为「只朗读 3 秒就连续错误停止」。
 *
 * 修复照 ddo-tts 的做法:段落累积成大块再请求、块间按朗读时长 delay、
 * 不做下一章预取、失败不重试。本测试锁住这几条,避免以后被无意改回去。
 *
 * 无 Robolectric,沿用仓库既有的「读源码文本做断言」风格。
 */
class TtsThrottleTest {

    private val servicePath = "src/main/java/io/legado/app/service/HttpReadAloudService.kt"
    private val douBaoPath = "src/main/java/io/legado/app/help/tts/DouBaoTtsClient.kt"
    private val miMoPath = "src/main/java/io/legado/app/help/tts/MiMoTtsClient.kt"

    @Test
    fun builtInEnginesMergeParagraphsBeforeRequest() {
        val src = read(servicePath)
        assertTrue("合并起步字数应为 301", src.contains("private val builtInReadTextStart = 301"))
        assertTrue("每块递增应为 200", src.contains("private val builtInReadTextStep = 200"))
        assertTrue("合并上限应为 700", src.contains("private val builtInReadTextMax = 700"))
        assertTrue(
            "应存在内置引擎专用的下载循环",
            src.contains("private fun downloadAndPlayBuiltInAudios()"),
        )
        assertTrue(
            "downloadAndPlayAudios 必须能分流到合并实现,否则节流形同虚设",
            src.contains("if (isBuiltInEngine()) {"),
        )
    }

    @Test
    fun builtInEnginesSkipPreDownload() {
        val src = read(servicePath)
        assertTrue(
            "内置引擎必须跳过下一章预取,否则开章瞬间打出 10 条连接",
            src.contains("if (httpTts.isBuiltInEngine) return"),
        )
    }

    @Test
    fun builtInEnginesThrottleBetweenBlocks() {
        val src = read(servicePath)
        assertTrue(
            "节流等待应按块字数与语速计算",
            src.contains("blockText.length * 150L * 10L"),
        )
        assertTrue(
            "应存在最小等待,避免语速极快时几乎不等待",
            src.contains("delay(pauseMs.coerceAtLeast(300L))"),
        )
    }

    @Test
    fun douBaoAndMiMoDoNotRetryButEdgeKeepsRetry() {
        val src = read(servicePath)
        listOf("getDouBaoSpeakStream", "getMiMoSpeakStream").forEach { name ->
            val body = methodBody(src, name)
            assertTrue("$name 应存在", body.isNotEmpty())
            assertFalse(
                "$name 不允许重试: 失败后 500ms 重连会延长账号封锁",
                body.contains("while (true)"),
            )
        }
        val edge = methodBody(src, "getEdgeSpeakStream")
        assertTrue("Edge 必须保持原有行为(带重试)", edge.contains("while (true)"))
    }

    @Test
    fun ttsClientsUseWhitelistCleaning() {
        listOf(douBaoPath, miMoPath).forEach { path ->
            val src = read(path)
            assertTrue("$path 应使用白名单正则清洗文本", src.contains("[^\\\\w\\\\s"))
            assertFalse(
                "$path 不应再用旧的黑名单式清洗",
                src.contains("ch.code in 0x200B..0x200F"),
            )
        }
    }

    @Test
    fun miMoSupportsCustomVoiceDesign() {
        val src = read(miMoPath)
        assertTrue(
            "应支持 voicedesign 模型",
            src.contains("mimo-v2.5-tts-voicedesign"),
        )
        assertTrue(
            "应有「自定义」音色前缀约定",
            src.contains("VOICE_CUSTOM_PREFIX"),
        )
    }

    /**
     * 截取方法体:从 `fun name(` 到下一个同级成员声明之前。
     */
    private fun methodBody(src: String, name: String): String {
        val start = src.indexOf("fun $name(")
        if (start < 0) return ""
        val end = listOf(
            src.indexOf("\n    private ", start + 1),
            src.indexOf("\n    /**", start + 1),
        ).filter { it > start }.minOrNull() ?: src.length
        return src.substring(start, end)
    }

    private fun read(pathInApp: String): String {
        val candidates = listOf(File(pathInApp), File("app/$pathInApp"))
        return candidates.first { it.isFile }.readText()
    }
}
