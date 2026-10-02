package io.legado.app.help.tts

import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.help.http.getProxyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 微软 Edge「大声朗读」在线神经语音（Edge-TTS）
 *
 * 协议移植自开源项目 edge-tts (https://github.com/rany2/edge-tts, MIT) 的成熟实现。
 * 免费、无需 API Key、无需注册；合成在云端完成，因此需要联网。
 *
 * 两个已知会导致握手 403 的原因，这里都做了兜底：
 * 1. 客户端版本门限 —— 服务端只放行较新的 Chromium 版本，故内置候选链逐个重试；
 * 2. 本机时钟偏差 —— Sec-MS-GEC 由本机时间派生，403 响应带 Date 时按服务端时间校正重试。
 */
object EdgeTtsClient {

    const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

    /**
     * 24kHz 48kbps 单声道 mp3, ExoPlayer 直接可播
     */
    const val DEFAULT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS_HOST = "speech.platform.bing.com"
    private const val WSS_PATH = "/consumer/speech/synthesize/readaloud/edge/v1"
    private const val VOICES_PATH = "/consumer/speech/synthesize/readaloud/voices/list"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    /**
     * 1601-01-01 到 1970-01-01 的秒数
     */
    private const val WIN_EPOCH = 11644473600.0

    private const val CHROMIUM_DEFAULT = "143.0.3650.75"

    /**
     * 服务端接受的客户端版本, 新 → 旧, 握手 403 时依次回退
     */
    private val CHROMIUM_CANDIDATES = arrayOf(
        "143.0.3650.75", "142.0.3595.94", "141.0.3537.99", "140.0.7339.16",
        "139.0.3405.125", "138.0.3351.121", "137.0.3296.93", "136.0.3240.92",
        "135.0.3179.98", "134.0.3124.95", "133.0.3065.92", "132.0.2957.140"
    )

    /**
     * 朗读速度基准: 阅读 App 的 speakSpeed 为 10 时对应原速, 5-50 有效
     */
    const val SPEED_NORMAL = 10

    /**
     * 语速倍率上下界, 对应 -50% ~ +200%
     */
    private const val MIN_SPEED_FACTOR = 0.5

    private const val MAX_SPEED_FACTOR = 3.0

    private fun httpDateToMillis(serverDate: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(serverDate)?.time
    }.getOrNull()

    private fun isoUtcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date())

    class HandshakeException(
        message: String,
        val statusCode: Int,
        val serverDate: String?
    ) : IOException(message)

    data class EdgeVoice(
        val shortName: String,
        val gender: String,
        val locale: String,
        val friendlyName: String
    ) {
        val isChinese: Boolean get() = locale.startsWith("zh")
    }

    /**
     * 合成一段文本, 返回 mp3 字节
     *
     * @param voice 微软音色 ShortName, 如 zh-CN-XiaoxiaoNeural
     * @param speakSpeed 阅读 App 的朗读速度, 5-50, 10 为原速
     */
    suspend fun synthesize(
        text: String,
        voice: String? = null,
        speakSpeed: Int = SPEED_NORMAL,
        timeoutMs: Long = 30 * 1000L
    ): ByteArray {
        val body = text.trim()
        if (body.isEmpty()) throw IOException("朗读文本为空")
        val useVoice = voice?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_VOICE
        if (!VOICE_NAME_REGEX.matches(useVoice)) {
            throw IOException("语音名称不合法：$useVoice")
        }
        val rate = speedToRate(speakSpeed)
        val versions = (arrayOf(CHROMIUM_DEFAULT) + CHROMIUM_CANDIDATES).distinct().take(4)

        var lastError: Throwable? = null
        var skewMs = 0L
        for (version in versions) {
            try {
                return synthOnce(body, useVoice, rate, version, 0L, timeoutMs)
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                lastError = e
                val handshake = e as? HandshakeException ?: throw e
                if (handshake.statusCode != 403) throw e
                skewFromDate(handshake.serverDate)?.let { skewMs = it }
                AppLog.putDebug("Edge朗读 403, 回退版本 $version")
            }
        }
        if (abs(skewMs) > 60 * 1000L) {
            try {
                return synthOnce(body, useVoice, rate, versions.first(), skewMs, timeoutMs)
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                lastError = e
            }
        }
        val handshake = lastError as? HandshakeException
        if (handshake?.statusCode == 403) {
            throw IOException(
                "朗读服务握手被拒（HTTP 403）：服务端拒绝了当前客户端版本或时间。" +
                    "请确认系统时间准确后重试。"
            )
        }
        throw lastError ?: IOException("朗读失败")
    }

    /**
     * 获取微软在线音色列表, 中文音色排在前面
     */
    suspend fun listVoices(timeoutMs: Long = 20 * 1000L): List<EdgeVoice> =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) {
                    val request = Request.Builder()
                        .url("https://$WSS_HOST$VOICES_PATH?trustedclienttoken=$TRUSTED_CLIENT_TOKEN")
                        .header("User-Agent", uaFor(CHROMIUM_DEFAULT))
                        .header("Accept", "application/json")
                        .build()
                    val body = getProxyClient().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("获取音色列表失败（HTTP ${response.code}）")
                        }
                        response.body.string()
                    }
                    parseVoices(body)
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IOException("获取音色列表超时")
            }
        }

    private fun parseVoices(body: String): List<EdgeVoice> {
        val element = runCatching { JsonParser.parseString(body) }.getOrNull()
        if (element == null || !element.isJsonArray) throw IOException("音色列表格式异常")
        val voices = element.asJsonArray.mapNotNull { child ->
            if (!child.isJsonObject) return@mapNotNull null
            val obj = child.asJsonObject
            val shortName = obj.get("ShortName")?.asString ?: return@mapNotNull null
            EdgeVoice(
                shortName = shortName,
                gender = obj.get("Gender")?.asString.orEmpty(),
                locale = obj.get("Locale")?.asString.orEmpty(),
                friendlyName = obj.get("FriendlyName")?.asString.orEmpty()
                    .substringBefore(" - ").ifEmpty { shortName }
            )
        }
        if (voices.isEmpty()) throw IOException("音色列表为空")
        return voices.sortedWith(compareByDescending<EdgeVoice> { it.isChinese }.thenBy { it.locale })
    }

    private suspend fun synthOnce(
        text: String,
        voice: String,
        rate: String,
        version: String,
        skewMs: Long,
        timeoutMs: Long
    ): ByteArray {
        try {
            return withTimeout(timeoutMs) {
                connect(text, voice, rate, version, skewMs)
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("朗读超时（网络较慢或服务无响应）")
        }
    }

    private suspend fun connect(
        text: String,
        voice: String,
        rate: String,
        version: String,
        skewMs: Long
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val audio = ByteArrayOutputStream(64 * 1024)
        val done = AtomicBoolean(false)
        var socket: WebSocket? = null

        fun finishOk(data: ByteArray) {
            if (done.compareAndSet(false, true)) {
                socket?.close(1000, null)
                if (cont.isActive) cont.resume(data)
            }
        }

        fun finishError(error: Throwable) {
            if (done.compareAndSet(false, true)) {
                socket?.cancel()
                if (cont.isActive) cont.resumeWithException(error)
            }
        }

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                val timestamp = isoUtcNow()
                webSocket.send(
                    "X-Timestamp:$timestamp\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                        "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"}," +
                        "\"outputFormat\":\"$DEFAULT_FORMAT\"}}}}"
                )
                val requestId = UUID.randomUUID().toString().replace("-", "")
                webSocket.send(
                    "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${timestamp}Z\r\n" +
                        "Path:ssml\r\n\r\n" +
                        buildSsml(text, voice, rate)
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    val data = audio.toByteArray()
                    if (data.isEmpty()) {
                        finishError(IOException("朗读服务未返回音频"))
                    } else {
                        finishOk(data)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = bytes.toByteArray()
                if (frame.size < 2) return
                val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
                if (frame.size < 2 + headerLength) return
                val header = String(frame, 2, headerLength, Charsets.UTF_8)
                if (header.contains("Path:audio\r\n")) {
                    audio.write(frame, 2 + headerLength, frame.size - 2 - headerLength)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response != null && response.code != 101) {
                    finishError(
                        HandshakeException(
                            "朗读服务握手被拒（HTTP ${response.code}）",
                            response.code,
                            response.header("Date")
                        )
                    )
                } else {
                    finishError(t)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                finishError(IOException("朗读连接被中断"))
            }
        }

        val request = Request.Builder()
            .url(buildWsUrl(System.currentTimeMillis() + skewMs, version))
            .header("Origin", ORIGIN)
            .header("User-Agent", uaFor(version))
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        socket = getProxyClient().newWebSocket(request, listener)
        cont.invokeOnCancellation { socket?.cancel() }
    }

    /**
     * Sec-MS-GEC: 「当前时间对齐到 5 分钟窗口」的 Windows 文件时间 + 固定令牌做 SHA-256。
     * 服务端据此校验请求时效, 因此本机时钟偏差超过一个窗口就会 403。
     */
    private fun secMsGec(nowMs: Long): String {
        var ticks = nowMs / 1000.0 + WIN_EPOCH
        ticks -= ticks % 300
        ticks *= 1e7
        val raw = String.format(Locale.US, "%.0f", ticks) + TRUSTED_CLIENT_TOKEN
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.US_ASCII))
        val hex = StringBuilder(digest.size * 2)
        digest.forEach { hex.append(String.format(Locale.US, "%02X", it.toInt() and 0xFF)) }
        return hex.toString()
    }

    private fun buildWsUrl(nowMs: Long, version: String): String {
        return "wss://$WSS_HOST$WSS_PATH?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=${secMsGec(nowMs)}&Sec-MS-GEC-Version=1-$version"
    }

    /**
     * 微软官方客户端 UA 只带主版本号
     */
    private fun uaFor(version: String): String {
        val major = version.substringBefore('.')
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0"
    }

    private fun buildSsml(text: String, voice: String, rate: String): String {
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='+0Hz' rate='$rate' volume='+0%'>" +
            escapeXml(text) +
            "</prosody></voice></speak>"
    }

    /**
     * 速度映射: speakSpeed 10 为原速, 有效区间 5-50, 换算成 Edge SSML 的百分比增量。
     * 上下界收敛到 0.5x ~ 3x, 避免把 +400% 这种极端值丢给服务端
     */
    private fun speedToRate(speakSpeed: Int): String {
        val factor = (speakSpeed.coerceIn(5, 50) / SPEED_NORMAL.toDouble())
            .coerceIn(MIN_SPEED_FACTOR, MAX_SPEED_FACTOR)
        val percent = ((factor - 1) * 100).roundToInt()
        return (if (percent >= 0) "+" else "") + percent + "%"
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
    }

    private fun skewFromDate(serverDate: String?): Long? {
        if (serverDate.isNullOrBlank()) return null
        val time = httpDateToMillis(serverDate) ?: return null
        val skew = time - System.currentTimeMillis()
        return if (abs(skew) > 24 * 3600 * 1000L) null else skew
    }

    private val VOICE_NAME_REGEX = Regex("^[A-Za-z0-9.\\-]+$")
}
