package io.legado.app.ui.book.searchContent

import io.legado.app.constant.AppConst
import io.legado.app.help.config.AppConfig

/**
 * 全文搜索的并发度。
 *
 * 全文搜索是「小文件 IO + 重 CPU 文本处理」的混合负载，并发度取三者最小值：
 * - [AppConfig.threadCount]：复用既有设置项，用户调低「更新和搜索线程数」即可缓解低端机卡顿；
 * - [AppConst.MAX_THREAD]：全局线程数硬上限；
 * - CPU 核数：CPU 密集型任务超过核数只会空转与发热，没有收益。
 */
object SearchConcurrency {

    fun resolve(): Int {
        val cpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return minOf(AppConfig.threadCount, AppConst.MAX_THREAD, cpu).coerceAtLeast(1)
    }
}
