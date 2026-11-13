package io.github.slchy.glasses2grok.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackoffTest {

    @Test
    fun `无抖动时按倍数增长并封顶`() {
        val b = Backoff(initialMs = 100, maxMs = 1_000, factor = 2.0, jitterRatio = 0.0)
        assertEquals(listOf(100L, 200L, 400L, 800L, 1_000L, 1_000L), List(6) { b.nextDelayMs() })
    }

    @Test
    fun `抖动不会越界`() {
        // random() 分别取 0 和 1 ⇒ 抖动为 -ratio 与 +ratio 两个极值
        val lo = Backoff(initialMs = 1_000, maxMs = 1_000, jitterRatio = 0.2, random = { 0.0 })
        assertEquals(800L, lo.nextDelayMs())
        val hi = Backoff(initialMs = 1_000, maxMs = 1_000, jitterRatio = 0.2, random = { 1.0 })
        assertEquals(1_000L, hi.nextDelayMs()) // 封顶压住上抖
    }

    @Test
    fun `reset 后从初始值重新开始`() {
        val b = Backoff(initialMs = 100, maxMs = 10_000, factor = 2.0, jitterRatio = 0.0)
        b.nextDelayMs()
        b.nextDelayMs()
        assertEquals(400L, b.nextDelayMs())
        b.reset()
        assertEquals(100L, b.nextDelayMs())
    }

    @Test
    fun `抖动为负时不会返回负数`() {
        val b = Backoff(initialMs = 1, maxMs = 10, jitterRatio = 1.0, random = { 0.0 })
        assertTrue(b.nextDelayMs() >= 0)
    }
}
