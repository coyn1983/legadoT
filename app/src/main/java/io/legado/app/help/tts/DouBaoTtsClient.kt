package io.legado.app.help.tts

import io.legado.app.help.http.getProxyClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * 豆包（Doubao）在线语音合成。
 *
 * 协议参考 wangz-code/ddo-tts 的实现（GPL-3.0），走豆包网页端的 WebSocket 接口：
 * 连接后先发 `{"event":"text"}` 再发 `{"event":"finish"}`，服务端返回若干二进制音频帧后关闭连接。
 *
 * 与 Edge 的区别：
 * - 需要用户自己的豆包 Cookie（网页端登录态），否则会被拒绝；
 * - 音频格式为 aac，播放器按内容嗅探，无需改扩展名；
 * - 属于非官方接口，服务端随时可能调整。
 */
object DouBaoTtsClient {

    const val DEFAULT_VOICE = "taozi"

    private const val WS_URL = "wss://ws-samantha.doubao.com/samantha/audio/tts"
    private const val DEFAULT_FORMAT = "aac"
    private const val ORIGIN = "https://www.doubao.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

    /**
     * 朗读速度基准与 Edge 保持一致：speakSpeed 10 为原速
     */
    private const val SPEED_NORMAL = 10

    /**
     * 音色别名 → 豆包音色 ID。用户也可以直接填 ID。
     */
    val VOICES: Map<String, String> = linkedMapOf(
        "taozi" to "zh_female_taozi_conversation_v4_wvae_bigtts",
        "shuangkuai" to "zh_female_shuangkuai_emo_v3_wvae_bigtts",
        "tianmei" to "zh_female_tianmei_conversation_v4_wvae_bigtts",
        "qingche" to "zh_female_qingche_moon_bigtts",
        "yangguang" to "zh_male_yangguang_conversation_v4_wvae_bigtts",
        "chenwen" to "zh_male_chenwen_moon_bigtts",
        "rap" to "zh_male_rap_mars_bigtts",
        "en_female" to "en_female_sarah_conversation_bigtts",
        "en_male" to "en_male_adam_conversation_bigtts",
    )

    /**
     * 合成一段文本，返回音频字节（aac）。
     *
     * @param cookie 豆包网页端 Cookie，为空会直接报错
     * @param speakSpeed 阅读 App 的朗读速度，5-50，10 为原速
     */
    suspend fun synthesize(
        text: String,
        voice: String? = null,
        speakSpeed: Int = SPEED_NORMAL,
        cookie: String,
        timeoutMs: Long = 60 * 1000L
    ): ByteArray {
        val body = cleanText(text)
        if (body.isEmpty()) throw IOException("朗读文本为空")
        if (cookie.isBlank()) {
            throw IOException("豆包朗读需要在请求头里填 {\"Cookie\":\"...\"}（网页端登录后的 Cookie）")
        }
        val speaker = resolveSpeaker(voice)
        val rate = speedToRate(speakSpeed)
        val url = buildWsUrl(speaker, rate)
        try {
            return withTimeout(timeoutMs) { connect(url, cookie, body) }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("豆包朗读超时（网络较慢或服务无响应）")
        }
    }

    private fun resolveSpeaker(voice: String?): String {
        val key = voice?.trim().orEmpty()
        if (key.isEmpty()) return VOICES[DEFAULT_VOICE]!!
        return VOICES[key] ?: key
    }

    /**
     * 语速换算成百分比：speakSpeed 10 → 0%，每档 10%
     */
    private fun speedToRate(speakSpeed: Int): Int =
        ((speakSpeed.coerceIn(5, 50) - SPEED_NORMAL) * 10).coerceIn(-100, 200)

    private fun buildWsUrl(speaker: String, ratePercent: Int): String {
        val deviceId = randomNumericId()
        val webId = randomNumericId()
        val params = buildString {
            append("speaker=").append(speaker)
            append("&format=").append(DEFAULT_FORMAT)
            append("&speech_rate=").append(ratePercent)
            append("&pitch=0")
            append("&version_code=20800")
            append("&language=zh")
            append("&device_platform=web")
            append("&aid=497858")
            append("&real_aid=497858")
            append("&pkg_type=release_version")
            append("&device_id=").append(deviceId)
            append("&pc_version=2.50.6")
            append("&web_id=").append(webId)
            append("&tea_uuid=").append(webId)
            append("&region=CN")
            append("&sys_region=CN")
            append("&samantha_web=1")
            append("&use-olympus-account=1")
            append("&web_tab_id=").append(UUID.randomUUID())
        }
        return "$WS_URL?$params"
    }

    private fun randomNumericId(): String {
        val min = 7400000000000000000L
        val max = 7499999999999999999L
        return (min + Random.nextLong(max - min)).toString()
    }

    /**
     * 清掉控制字符与零宽字符，其余原样保留（豆包对文本较宽松）
     */
    private fun cleanText(text: String): String {
        val builder = StringBuilder(text.length)
        text.forEach { ch ->
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> builder.append(' ')
                ch.code < 0x20 || ch.code == 0x7F -> Unit
                ch.code in 0x200B..0x200F || ch.code == 0xFEFF -> Unit
                else -> builder.append(ch)
            }
        }
        return builder.toString().replace(Regex("\\s+"), " ").trim()
    }

    private suspend fun connect(
        url: String,
        cookie: String,
        text: String
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val audio = ByteArrayOutputStream(64 * 1024)
        val done = AtomicBoolean(false)
        var socket: WebSocket? = null

        fun finishOk() {
            if (!done.compareAndSet(false, true)) return
            val data = audio.toByteArray()
            socket?.cancel()
            if (!cont.isActive) return
            if (data.isEmpty()) {
                cont.resumeWithException(IOException("豆包朗读未返回音频（Cookie 可能已失效）"))
            } else {
                cont.resume(data)
            }
        }

        fun finishError(error: Throwable) {
            if (!done.compareAndSet(false, true)) return
            socket?.cancel()
            if (cont.isActive) cont.resumeWithException(error)
        }

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                runCatching {
                    webSocket.send(JSONObject().apply {
                        put("event", "text")
                        put("text", text)
                    }.toString())
                    webSocket.send(JSONObject().apply {
                        put("event", "finish")
                    }.toString())
                }.onFailure { finishError(it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                val code = event.optInt("code", 0)
                if (code != 0) {
                    finishError(IOException("豆包朗读出错（${code}）：${event.optString("message")}"))
                    return
                }
                if (event.optString("event") == "error") {
                    finishError(IOException("豆包朗读出错：${event.optString("message")}"))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { audio.write(bytes.toByteArray()) }
                    .onFailure { finishError(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                finishOk()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finishOk()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code ?: 0
                if (status == 401 || status == 403) {
                    finishError(IOException("豆包拒绝连接（HTTP $status），Cookie 可能已失效"))
                } else {
                    finishError(t)
                }
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept-Language", "en,zh-CN;q=0.9,zh;q=0.8")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Origin", ORIGIN)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", cookie)
            .build()

        socket = getProxyClient().newWebSocket(request, listener)
        cont.invokeOnCancellation { socket?.cancel() }
    }
}
