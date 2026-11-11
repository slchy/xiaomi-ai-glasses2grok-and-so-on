package com.miaiglasses.core.sar

import com.miaiglasses.core.l1.L1
import com.miaiglasses.core.l1.L1Codec
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 解析停滞观测 + **大帧实验**（``  的数据来源）。
 *
 * <h3>为什么需要测量而不是加看门狗</h3>
 *
 * `L1Parser` 遇到「声明长度 ≤ maxFrame 的伪帧头」时会一直等 —— 这在字节上与
 * 「一个真的大帧还没收完」**完全不可区分**：两者都是「不产出帧、也不报坏 CRC」。
 * 任何按时间下判断的实现都可能误杀真大帧，所以先测量：
 * 停滞时长、停滞期间吃掉的字节数、残留峰值，三者结合才能区分。
 *
 * 本文件同时是「大帧能否支持」的实验：8 / 32 / 64 KB 的帧在当前
 * `maxFrame = MPS + 8` 下能否正常收发。
 */
class SarStallStatsTest {

    private class Rig {
        val clock = Clock.Fake(1_000)
        val out = ByteArrayOutputStream()
        val errors = mutableListOf<String>()
        val sar = SarEngine({ out.write(it) }, clock, SarConfig.VALID)

        init {
            sar.onError = { errors += it }
            sar.markNegotiated() // 扮演已协商完成的被叫方，DATA 才不会被执行门槛挡住
        }

        fun feed(bytes: ByteArray): Int = sar.feed(bytes)
        fun stats(): SarEngine.StallStats = sar.stallStats
        fun advance(ms: Long) = clock.advance(ms)
    }

    private fun frame(type: Int, seq: Int, len: Int, fill: Int): ByteArray =
        L1Codec.build(type = type, seq = seq, payload = ByteArray(len) { ((fill + it) and 0xFF).toByte() })

    @Test
    fun `正常流不产生停滞`() {
        val r = Rig()
        val buf = ByteArrayOutputStream()
        for (i in 0 until 20) buf.write(frame(L1.TYPE_DATA, i, 100, i))

        assertEquals(20, r.feed(buf.toByteArray()), "20 帧应全部解出")

        val s = r.stats()
        assertEquals(0L, s.currentMs, "正常流不应有进行中的停滞")
        assertEquals(0L, s.occurrences, "每次喂入都产出了帧 ⇒ 不记停滞")
        assertTrue(s.maxPendingBytes <= 108, "残留峰值不应超过一帧（实际 ${s.maxPendingBytes}）")
    }

    @Test
    fun `有产出但仍有残留时不算停滞`() {
        // 正常流里几乎每次喂入都是「解出若干帧 + 残留下一帧的开头」。
        // 若把「残留 > 0」当停滞判据，occurrences 会等于喂入次数 —— 指标就没意义了。
        val r = Rig()
        val all = ByteArrayOutputStream().also { b ->
            for (i in 0 until 10) b.write(frame(L1.TYPE_DATA, i, 1_000, i))
        }.toByteArray()

        // 每次喂 1500 字节：前 1000 那帧解出后还剩 500 → 产出 >0 且残留 >0
        var off = 0
        var feeds = 0
        while (off < all.size) {
            val end = minOf(all.size, off + 1_500)
            r.feed(all.copyOfRange(off, end))
            off = end
            feeds++
            r.advance(100)
        }

        val s = r.stats()
        assertTrue(feeds > 3, "应多次喂入（实际 $feeds）")
        assertEquals(0L, s.occurrences, "每次都有产出 ⇒ 不应记任何停滞（实际 ${s.occurrences}）")
        assertEquals(0L, s.maxMs, "不应记录停滞时长")
        assertTrue(s.maxPendingBytes > 0, "残留峰值应 >0（这正是容易误判的地方）")
    }

    @Test
    fun `伪帧头 - 停滞时长与被吃掉的字节数被记录`() {
        val r = Rig()
        val good = ByteArrayOutputStream()
        for (i in 0 until 10) good.write(frame(L1.TYPE_DATA, i, 50, i))
        r.feed(good.toByteArray())
        assertEquals(0L, r.stats().occurrences, "基线不应有停滞")

        // 声明 8192 的伪头，随后每次喂 1000 字节都填不满 ⇒ 持续无进展
        r.feed(byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0x03, 0x00, 0x00, 0x20, 0xAB.toByte(), 0xCD.toByte()))
        repeat(4) {
            r.advance(250)
            r.feed(ByteArray(1_000) { 0x5A })
        }
        val stalled = r.stats()
        assertTrue(stalled.currentMs >= 1_000, "应记录进行中的停滞（实际 ${stalled.currentMs}ms）")
        assertEquals(0L, stalled.occurrences, "尚未恢复 ⇒ 不算一次完整停滞")
        assertTrue(
            stalled.maxPendingBytes in 4_000..8_208,
            "残留峰值应≈已喂入的字节数且不超过声明长度+8（实际 ${stalled.maxPendingBytes}）",
        )

        // 喂到超过 8192 → CRC 失败 → 1 字节步进重新对齐 → 停滞结束
        r.advance(250)
        r.feed(ByteArray(9_000) { 0x33 })
        val recovered = r.stats()
        assertEquals(0L, recovered.currentMs, "恢复后不应再有进行中的停滞")
        assertTrue(recovered.occurrences >= 1, "应记一次停滞")
        assertTrue(recovered.maxMs >= 1_250, "最长停滞应 ≥ 1.25s（实际 ${recovered.maxMs}ms）")
        assertTrue(
            recovered.maxStalledFeedBytes >= 4_000,
            "应记录停滞期间吃掉的字节（实际 ${recovered.maxStalledFeedBytes}）",
        )
    }

    @Test
    fun `真大帧 - 组装期间也算停滞，残留峰值等于帧长`() {
        val r = Rig()
        val payload = ByteArray(32 * 1024) { (it * 7 and 0xFF).toByte() }
        val big = L1Codec.build(type = L1.TYPE_CMD, seq = 0, payload = payload)

        var off = 0
        while (off < big.size) {
            val end = minOf(big.size, off + 4_096)
            r.feed(big.copyOfRange(off, end))
            off = end
            if (off < big.size) r.advance(100)
        }

        val s = r.stats()
        assertTrue(s.occurrences >= 1, "大帧组装期间应有停滞记录")
        assertTrue(s.maxPendingBytes >= 32 * 1024, "残留峰值应 ≥ 帧长（实际 ${s.maxPendingBytes}）")
        assertEquals(0L, s.currentMs, "帧收齐后停滞应结束")
        assertTrue(
            s.maxStalledFeedBytes >= 32 * 1024,
            "应吃掉 ≈32KB（实际 ${s.maxStalledFeedBytes}）",
        )
    }

    @Test
    fun `大帧实验 - 8K 32K 与 16 位上限 65535 都能完整解析`() {
        for (len in listOf(8 * 1024, 32 * 1024, L1.MAX_PAYLOAD)) {
            val payload = ByteArray(len) { ((it * 13) and 0xFF).toByte() }
            val f = L1Codec.build(type = L1.TYPE_DATA, seq = 7, payload = payload)
            val r = Rig()
            assertEquals(1, r.feed(f), "${len}B 帧应被解析出来")
            assertEquals(0L, r.stats().currentMs, "${len}B 帧收齐后不应残留停滞")
        }
    }

    /**
     * **大帧的失明代价**（RTMP 级别帧的关键数字）。
     *
     * 一个声明 64 KB 的伪头会让解析器等 64 KB；若这是假帧头，这段时间产出零帧。
     * 按真机实测语音速率（~60 kbps ≈ 7.5 KB/s）折算，失明约 8.5 秒 ——
     * 这就是「不砍 maxFrame」换来的最坏代价，也是要决策的数字。
     */
    @Test
    fun `失明代价 - 64K 伪头 = 等满 64K 字节才恢复`() {
        val r = Rig()
        val declared = 64 * 1024
        // 手工构造伪头：len = 0xFC00 (64512)，CRC 字段填 0（必然对不上）
        r.feed(byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0x03, 0x00, 0x00, 0xFC.toByte(), 0x00, 0x00))

        var fed = 0
        while (fed < declared) {
            r.feed(ByteArray(4_096))
            fed += 4_096
            r.advance(546) // ≈ 7.5 KB/s
        }
        // 再多喂一点，越过声明的长度并让解析器完成 CRC 失败 + 重对齐
        r.feed(ByteArray(4_096))

        val s = r.stats()
        assertTrue(s.maxMs >= 8_000, "64KB 伪头的失明应 ≈8s（实际 ${s.maxMs}ms）")
        // 统计的是「停滞期间又喂入的字节」，不含开始停滞那一批与收尾那一批 ⇒ 相差约 1 个 chunk
        assertTrue(
            s.maxStalledFeedBytes >= declared - 4_096 * 2,
            "吃掉的字节应≈声明长度（实际 ${s.maxStalledFeedBytes}，声明 $declared）",
        )
        assertEquals(0L, s.currentMs, "喂够之后必须自愈")
    }
}
