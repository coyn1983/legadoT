package io.legado.app.data.entities

import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge 朗读引擎的导入解析。
 *
 * 默认数据是「全部成功才采用」的语义(HttpTTS.fromJsonArray 任一条失败即整体空),
 * 所以预置的 edge 引擎必须能在没有 url 的情况下解析成功,否则会连累百度/阿里云一起丢失;
 * 反过来,普通 http 引擎缺 url 仍要判为非法。
 */
class EdgeTtsImportTest {

    @Test
    fun `edge engine imports without url and keeps voice`() {
        val httpTTS = HttpTTS.fromJson(
            """{"id":-200,"name":"3.微软Edge语音","engineType":"edge",
                "voice":"zh-CN-XiaoxiaoNeural","contentType":"audio/mpeg"}"""
        ).getOrThrow()
        assertTrue(httpTTS.isEdgeEngine)
        assertEquals(HttpTTS.ENGINE_EDGE, httpTTS.engineType)
        assertEquals("zh-CN-XiaoxiaoNeural", httpTTS.voice)
        assertEquals("", httpTTS.url)
    }

    @Test
    fun `edge engine tolerates empty url`() {
        val httpTTS = HttpTTS.fromJson(
            """{"name":"edge","engineType":"edge","url":""}"""
        ).getOrThrow()
        assertTrue(httpTTS.isEdgeEngine)
    }

    @Test
    fun `http engine without url is rejected`() {
        assertTrue(
            HttpTTS.fromJson("""{"name":"某引擎","engineType":"http"}""").isFailure
        )
    }

    @Test
    fun `engineType defaults to http`() {
        val httpTTS = HttpTTS.fromJson(
            """{"name":"某引擎","url":"http://a.com/tts"}"""
        ).getOrThrow()
        assertEquals(HttpTTS.ENGINE_HTTP, httpTTS.engineType)
        assertFalse(httpTTS.isEdgeEngine)
        assertNull(httpTTS.voice)
    }

    @Test
    fun `export then import preserves engineType and voice`() {
        val httpTTS = HttpTTS(
            id = -200,
            name = "3.微软Edge语音",
            engineType = HttpTTS.ENGINE_EDGE,
            voice = "zh-CN-YunxiNeural",
            url = ""
        )
        val back = HttpTTS.fromJson(GSON.toJson(httpTTS)).getOrThrow()
        assertEquals(httpTTS.engineType, back.engineType)
        assertEquals(httpTTS.voice, back.voice)
        assertTrue(back.isEdgeEngine)
    }

    @Test
    fun `mixed array of http and edge engines parses as a whole`() {
        val presets = """
            [
              {"id":-100,"name":"1.百度","url":"http://tts.baidu.com/text2audio",
               "contentType":"audio/wav"},
              {"id":-200,"name":"3.微软Edge语音","engineType":"edge",
               "voice":"zh-CN-XiaoxiaoNeural","url":"","contentType":"audio/mpeg"}
            ]
        """.trimIndent()
        val list = HttpTTS.fromJsonArray(presets).getOrThrow()
        assertEquals(2, list.size)
        assertFalse(list[0].isEdgeEngine)
        assertTrue(list[1].isEdgeEngine)
    }
}
