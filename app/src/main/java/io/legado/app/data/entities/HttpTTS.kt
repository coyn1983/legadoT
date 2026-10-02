package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jayway.jsonpath.DocumentContext
import io.legado.app.utils.GSON
import io.legado.app.utils.jsonPath
import io.legado.app.utils.readLong
import io.legado.app.utils.readString

/**
 * 在线朗读引擎
 */
@Entity(tableName = "httpTTS")
data class HttpTTS(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    var name: String = "",
    /**
     * 引擎类型, 见 [ENGINE_HTTP] / [ENGINE_EDGE]
     */
    @ColumnInfo(defaultValue = "http")
    var engineType: String = ENGINE_HTTP,
    /**
     * 引擎自带的音色标识, 目前仅 Edge 引擎使用
     */
    var voice: String? = null,
    var url: String = "",
    var contentType: String? = null,
    @ColumnInfo(defaultValue = "0")
    override var concurrentRate: String? = "0",
    override var loginUrl: String? = null,
    override var loginUi: String? = null,
    override var header: String? = null,
    override var jsLib: String? = null,
    @ColumnInfo(defaultValue = "0")
    override var enabledCookieJar: Boolean? = false,
    var loginCheckJs: String? = null,
    @ColumnInfo(defaultValue = "0")
    var pauseDuration: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var lastUpdateTime: Long = System.currentTimeMillis()
) : BaseSource {

    override fun getTag(): String {
        return name
    }

    override fun getKey(): String {
        return "httpTts:$id"
    }

    /**
     * 是否为内置的 Edge 朗读引擎, 为真时不走 [url] 规则
     */
    val isEdgeEngine: Boolean
        get() = engineType == ENGINE_EDGE

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {

        /**
         * 在线朗读规则(原逻辑): 请求 [url] 取音频流
         */
        const val ENGINE_HTTP = "http"

        /**
         * 微软 Edge「大声朗读」在线神经语音, 免密钥, 需要网络
         */
        const val ENGINE_EDGE = "edge"

        fun fromJsonDoc(doc: DocumentContext): Result<HttpTTS> {
            return kotlin.runCatching {
                val loginUi = doc.read<Any>("$.loginUi")
                val engineType = doc.readString("$.engineType") ?: ENGINE_HTTP
                val url = doc.readString("$.url")
                // Edge 引擎由内置协议合成, 不需要 url
                if (engineType != ENGINE_EDGE && url.isNullOrBlank()) {
                    throw IllegalArgumentException("url 不能为空")
                }
                HttpTTS(
                    id = doc.readLong("$.id") ?: System.currentTimeMillis(),
                    name = doc.readString("$.name")!!,
                    engineType = engineType,
                    voice = doc.readString("$.voice"),
                    url = url.orEmpty(),
                    contentType = doc.readString("$.contentType"),
                    concurrentRate = doc.readString("$.concurrentRate"),
                    loginUrl = doc.readString("$.loginUrl"),
                    loginUi = if (loginUi is List<*>) GSON.toJson(loginUi) else loginUi?.toString(),
                    header = doc.readString("$.header"),
                    jsLib = doc.readString("$.jsLib"),
                    enabledCookieJar = doc.read<Boolean>("$.enabledCookieJar"),
                    loginCheckJs = doc.readString("$.loginCheckJs"),
                    pauseDuration = doc.read<Int>("$.pauseDuration") ?: 0,
                    lastUpdateTime = doc.readLong("$.lastUpdateTime") ?: System.currentTimeMillis()
                )
            }
        }

        fun fromJson(json: String): Result<HttpTTS> {
            return fromJsonDoc(jsonPath.parse(json))
        }

        fun fromJsonArray(jsonArray: String): Result<ArrayList<HttpTTS>> {
            return kotlin.runCatching {
                val sources = arrayListOf<HttpTTS>()
                val doc = jsonPath.parse(jsonArray).read<List<*>>("$")
                doc.forEach {
                    val jsonItem = jsonPath.parse(it)
                    fromJsonDoc(jsonItem).getOrThrow().let { source ->
                        sources.add(source)
                    }
                }
                return@runCatching sources
            }
        }

    }

}