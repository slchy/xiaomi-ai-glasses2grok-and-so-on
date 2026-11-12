package com.miaiglasses.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RX 丢包 → 语音空洞换算。
 *
 * 这个换算如果错了，宿主拿到的 `GlassesEvent.Audio.droppedBefore` 就是假的：
 * 要么永远 0（丢掉整块音频却报「连续」），要么把上一轮的丢弃算到这一轮头上。
 */
class RxGapTrackerTest {

    @Test
    fun `首次调用只建立基线 - 握手期间的丢弃不算音频空洞`() {
        val t = RxGapTracker()
        assertEquals(0, t.onChunk(7), "第一块不报空洞（可能都是握手期间丢的）")
        assertEquals(0, t.onChunk(7), "没新增丢弃就没有空洞")
        assertEquals(2, t.onChunk(9), "基线之后的增长才算")
    }

    @Test
    fun `累计计数增长时报增量`() {
        val t = RxGapTracker()
        assertEquals(0, t.onChunk(0))
        assertEquals(3, t.onChunk(3), "中间丢了 3 块必须报 3")
        assertEquals(0, t.onChunk(3))
        assertEquals(2, t.onChunk(5))
    }

    @Test
    fun `计数回退不产生负数`() {
        val t = RxGapTracker()
        t.onChunk(5)
        assertEquals(0, t.onChunk(2), "换链路后计数被清零，不能报负空洞")
        assertEquals(1, t.onChunk(3))
    }

    @Test
    fun `reset 之后重新取基线`() {
        val t = RxGapTracker()
        t.onChunk(5)
        t.reset()
        assertEquals(0, t.onChunk(0))
        assertEquals(4, t.onChunk(4))
    }
}
