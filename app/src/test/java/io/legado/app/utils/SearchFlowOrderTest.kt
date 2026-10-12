package io.legado.app.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全文搜索改用「保序受限并发」（[mapAsync]）后的行为测试。
 *
 * 这些性质是生产代码的正确性前提，任何一条被破坏都会导致搜索结果错乱：
 * 1. 结果顺序必须与章节顺序一致；
 * 2. 同时在途的章节数必须有上限（内存有界）；
 * 3. 必须可取消（对应搜索页的「停止」按钮）；
 * 4. 并发与串行的结果必须逐元素相同。
 */
class SearchFlowOrderTest {

    @Test
    fun orderPreservedWithVariableDelays() = runBlocking {
        // 让后面的章节更快完成，制造「乱序完成」的场景
        val order = ArrayList<Int>()
        (0 until 100).toList().asFlow()
            .mapAsync(4) { i ->
                delay(if (i % 2 == 0) 5L else 1L)
                i
            }
            .collect { order.add(it) }
        assertEquals((0 until 100).toList(), order)
    }

    @Test
    fun concurrencyIsBounded() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        (0 until 50).toList().asFlow()
            .mapAsync(3) { i ->
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(5)
                inFlight.decrementAndGet()
                i
            }
            .collect { }
        assertTrue("峰值在途章节数 ${peak.get()} 不应超过并发上限 3", peak.get() <= 3)
    }

    @Test
    fun cancellationStopsProcessing() = runBlocking {
        val processed = AtomicInteger()
        val job = launch {
            (0 until 1000).toList().asFlow()
                .mapAsync(4) { i ->
                    delay(1)
                    processed.incrementAndGet()
                    i
                }
                .collect { }
        }
        delay(50)
        job.cancel()
        job.join()
        assertTrue("取消后不应把剩余章节全部处理完，实际处理 ${processed.get()}", processed.get() < 1000)
    }

    @Test
    fun parallelResultEqualsSequentialResult() {
        val chapters = (0 until 200).toList()
        val sequential = runBlocking { scanChapters(chapters, 1) }
        val parallel = runBlocking { scanChapters(chapters, 6) }

        // 逐元素相同：顺序一致、每章命中数一致
        assertEquals(sequential, parallel)
        // 顺序就是章节顺序
        assertEquals(chapters, parallel.map { it.first })
        // 每章 2 个命中（"关键词A" 与 "关键词a" 都命中 query="关键词a"，忽略大小写）
        parallel.forEach { (chapter, hits) ->
            assertEquals("第 $chapter 章命中数不对", 2, hits.size)
        }
        // 计数一致性：总数 == 各章累加
        assertEquals(parallel.sumOf { it.second.size }, parallel.flatMap { it.second }.size)
    }

    /** 与生产代码同构：mapAsync 保序并发，逐章产出「章节下标 -> 命中位置」 */
    private suspend fun scanChapters(
        chapters: List<Int>,
        concurrency: Int
    ): List<Pair<Int, List<Int>>> {
        val out = ArrayList<Pair<Int, List<Int>>>()
        chapters.asFlow()
            .mapAsync(concurrency) { i ->
                // 模拟长短不一的章节处理耗时
                delay(if (i % 7 == 0) 2L else 1L)
                i to TextSearchUtils.findAllIgnoreCase("第${i}章 关键词A 关键词a", "关键词a")
            }
            .collect { out.add(it) }
        return out
    }
}
