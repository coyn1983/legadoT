package io.legado.app.help.tts

import io.legado.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 小米 MiMo 在线语音合成。
 *
 * 接口是 OpenAI 兼容的 chat/completions，返回的音频以 base64 放在
 * `choices[0].message.audio.data`，格式为 wav。
 *
 * 鉴权：请求头 `api-key`。取值顺序为
 * 引擎请求头里的 `api-key` → `Authorization: Bearer xxx` → 构建期内置的 [BuildConfig.MiMo_Api_Key]。
 *
 * 协议参考 wangz-code/ddo-tts 的实现（GPL-3.0）。
 */
object MiMoTtsClient {

    const val DEFAULT_VOICE = "Chloe"

    /**
     * MiMo 默认把「25」当作正常语速，这里把阅读 App 的 speakSpeed(10 为原速) 换算过去
     */
    private const val SPEED_NORMAL = 10
    private const val MIMO_NORMAL_RATE = 25

    private const val API_URL = "https://api.xiaomimimo.com/v1/chat/completions"
    private const val MODEL = "mimo-v2.5-tts"

    /**
     * 自定义音色模型：不传具体 voice，改由 user 消息里的自然语言描述来"设计"音色
     */
    private const val MODEL_DESIGN = "mimo-v2.5-tts-voicedesign"

    /**
     * voice 以此前缀开头时走自定义音色，例如 `自定义:温柔的中年女声`
     */
    private const val VOICE_CUSTOM_PREFIX = "自定义"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 从引擎请求头解析 API Key
     */
    fun resolveApiKey(headerMap: Map<String, String>): String {
        headerMap.entries.firstOrNull { it.key.equals("api-key", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }?.let { return it }
        headerMap.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return BuildConfig.MiMo_Api_Key
    }

    /**
     * 合成一段文本，返回 wav 字节
     */
    suspend fun synthesize(
        text: String,
        voice: String? = null,
        speakSpeed: Int = SPEED_NORMAL,
        apiKey: String
    ): ByteArray = withContext(Dispatchers.IO) {
        val body = cleanText(text)
        if (body.isEmpty()) throw IOException("朗读文本为空")
        if (apiKey.isBlank()) {
            throw IOException("小米 MiMo 朗读需要 API Key，请在引擎请求头里填 {\"api-key\":\"sk-...\"}")
        }
        // voice 写成「自定义:温柔的中年女声」时走 voicedesign 模型：
        // 此时不传 voice ID，音色完全由 user 消息里的描述决定
        val rawVoice = voice?.trim().orEmpty()
        val designPrompt = if (rawVoice.startsWith(VOICE_CUSTOM_PREFIX)) {
            rawVoice.removePrefix(VOICE_CUSTOM_PREFIX).trimStart(':', '：').trim()
        } else {
            ""
        }
        val isDesign = designPrompt.isNotEmpty()
        val payload = JSONObject().apply {
            put("model", if (isDesign) MODEL_DESIGN else MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put(
                        "content",
                        if (isDesign) "$designPrompt，${rateDescription(speakSpeed)}"
                        else rateDescription(speakSpeed)
                    )
                })
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", body)
                })
            })
            put("audio", JSONObject().apply {
                put("format", "wav")
                if (!isDesign) {
                    put("voice", rawVoice.takeIf { it.isNotEmpty() } ?: DEFAULT_VOICE)
                }
            })
        }
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val textBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("MiMo 朗读失败（HTTP ${response.code}）：${textBody.take(200)}")
            }
            val base64 = runCatching {
                JSONObject(textBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getJSONObject("audio")
                    .getString("data")
            }.getOrElse {
                throw IOException("MiMo 返回内容无法解析：${textBody.take(200)}")
            }
            val audio = Base64.getDecoder().decode(base64)
            if (audio.isEmpty()) throw IOException("MiMo 未返回音频")
            audio
        }
    }

    /**
     * 语速换算成自然语言描述（MiMo 用它理解快慢）
     */
    private fun rateDescription(speakSpeed: Int): String {
        val rate = (speakSpeed.coerceIn(5, 50) * MIMO_NORMAL_RATE / SPEED_NORMAL)
            .coerceIn(0, 100)
        val diff = rate - MIMO_NORMAL_RATE
        return when {
            diff <= -15 -> "非常慢的语速"
            diff <= -5 -> "偏慢的语速"
            diff >= 15 -> "非常快的语速"
            diff >= 5 -> "偏快的语速"
            else -> "正常语速"
        }
    }

    /**
     * 去掉控制字符与零宽字符
     */
    /**
     * 文本清洗：只保留中文、字母数字、空白与常用中英标点（与豆包侧同一套白名单）
     */
    private fun cleanText(text: String): String = text
        .replace(Regex("[^\\w\\s\u4e00-\u9fff，。！？；：、（）《》【】“”‘’,.!?;:'\"()\\[\\]\\-~]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
