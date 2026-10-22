package io.legado.app.help.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sec-MS-GEC v2 的跨语言对拍测试。
 *
 * 期望值由上游 edge-tts 的真实 drm.py（`generate_sec_ms_gec_v2`）在离线环境生成，
 * 因此这里校验的是「与官方实现逐位一致」，而不是自我一致性。
 *
 * v2 令牌格式为 `<CS64 16 位>-<SHA-256 64 位>`，其中 CS64 与改版 SHA-256
 * 都是微软客户端的私有算法，任何一位算错都会被服务端 403。
 */
class SecMsGecTest {

    private val token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private val golden = listOf(
        1761000000000L to
            "9875C7D8BB340E51-4AC084DC23E2DEA93DF952AE15BE8A23A325A2F010C0C277FBC15644D5A006C9",
        1761000299999L to
            "9875C7D8BB340E51-4AC084DC23E2DEA93DF952AE15BE8A23A325A2F010C0C277FBC15644D5A006C9",
        1761000300000L to
            "FADE2B1E31E822BC-A36AF518948C258D241132A4B190DA0D001020BBB50BE798DDAECCFBDA04BB6A",
        1792540800000L to
            "0A40F834AC99F9B0-E80FC69D775664E504AAF54AA415CEB8F9B317537DA08BD1BB8623FEA479AD8A",
        1761002673123L to
            "473D5E53E7F93BA7-4EE5C9B9EB4245B116C32F8F6E03D08C88AD2777F7417571733F23D5D70717BF",
    )

    @Test
    fun matchesUpstreamGoldenVectors() {
        golden.forEach { (timestamp, expected) ->
            assertEquals("时间戳 $timestamp 的令牌与上游不一致", expected, SecMsGec.v2(timestamp, token))
        }
    }

    @Test
    fun tokenLayoutIsCs64DashSha256() {
        val value = SecMsGec.v2(1761000000000L, token)
        val parts = value.split("-")
        assertEquals(2, parts.size)
        assertEquals("CS64 应为 16 位十六进制", 16, parts[0].length)
        assertEquals("SHA-256 应为 64 位十六进制", 64, parts[1].length)
        assertTrue("令牌应全为大写十六进制", value.matches(Regex("[0-9A-F-]+")))
    }

    @Test
    fun sameFiveMinuteWindowSharesToken() {
        // 5 分钟窗口内必须恒定，否则服务端会判定时效异常
        val start = 1761000000000L
        assertEquals(
            SecMsGec.v2(start, token),
            SecMsGec.v2(start + 299_999L, token),
        )
        assertTrue(
            "跨窗口应产生不同令牌",
            SecMsGec.v2(start, token) != SecMsGec.v2(start + 300_000L, token),
        )
    }

    @Test
    fun muidIs32UpperHex() {
        val muid = SecMsGec.generateMuid()
        assertEquals(32, muid.length)
        assertTrue(muid.matches(Regex("[0-9A-F]{32}")))
        assertTrue("两次生成不应相同", muid != SecMsGec.generateMuid())
    }
}
