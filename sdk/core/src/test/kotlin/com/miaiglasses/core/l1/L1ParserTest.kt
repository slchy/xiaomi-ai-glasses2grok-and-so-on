package com.miaiglasses.core.l1

import com.miaiglasses.core.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 解析器的行为契约。每条都对应一个真实故障模式，不是凑覆盖率。
 */
class L1ParserTest {

    private fun frame(type: Int, seq: Int, payload: ByteArray = ByteArray(0)) =
        L1Codec.build(type, seq, payload)

    private fun drain(p: L1Parser): List<L1Frame> {
        val out = mutableListOf<L1Frame>()
        while (true) out += p.next() ?: break
        return out
    }

    @Test
    fun `粘包 - 一次 feed 拿到多帧`() {
        val bytes = frame(L1.TYPE_DATA, 0, byteArrayOf(1)) +
            frame(L1.TYPE_DATA, 1, byteArrayOf(2)) +
            frame(L1.TYPE_ACK, 2)
        val p = L1Parser()
        p.feed(bytes)
        val got = drain(p)
        assertEquals(3, got.size)
        assertEquals(listOf(0, 1, 2), got.map { it.seq })
        assertEquals(0, p.pendingBytes)
    }

    @Test
    fun `半包 - 逐字节喂入也必须正确`() {
        val bytes = frame(L1.TYPE_DATA, 5, "hello world".toByteArray())
        val p = L1Parser()
        var produced = 0
        for (b in bytes) {
            p.feed(byteArrayOf(b))
            produced += drain(p).size
        }
        assertEquals(1, produced, "逐字节喂入应恰好产出一帧")
        assertEquals(0, p.pendingBytes)
    }

    @Test
    fun `前缀垃圾 - 重同步后仍能拿到真帧`() {
        val junk = byteArrayOf(0x00, 0x11, 0x22, 0x33)
        val p = L1Parser()
        p.feed(junk + frame(L1.TYPE_DATA, 7, byteArrayOf(9)))
        val got = drain(p)
        assertEquals(1, got.size)
        assertEquals(7, got[0].seq)
        assertTrue(p.resyncCount >= junk.size.toLong(), "应统计被丢弃的垃圾字节")
    }

    @Test
    fun `尾部半个魔数 - 必须保留到下次`() {
        val p = L1Parser()
        p.feed(byteArrayOf(0x00, 0xA5.toByte()))
        assertEquals(0, drain(p).size)
        p.feed(byteArrayOf(0xA5.toByte(), 0x03, 0x01, 0x00, 0x00, 0x00, 0x00))
        val got = drain(p)
        assertEquals(1, got.size, "跨 feed 拼出的 A5A5 头应被识别")
        assertEquals(L1.TYPE_DATA, got[0].type)
        assertEquals(1, got[0].seq)
    }

    @Test
    fun `伪魔数 - 帧长超出上限时跳过 1 字节而不是等待`() {
        // A5 A5 后面跟一个 0xFFFF 长度：只可能是噪声
        val fake = byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0x03, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0, 0)
        val p = L1Parser(maxFrameBytes = 2048)
        p.feed(fake + frame(L1.TYPE_DATA, 3, byteArrayOf(1, 2)))
        val got = drain(p)
        assertEquals(1, got.size, "伪魔数不得堵死后续真帧")
        assertEquals(3, got[0].seq)
    }

    @Test
    fun `坏 CRC - 只跳过 1 字节并重新对齐，不静默跨过后续真帧`() {
        val good = frame(L1.TYPE_DATA, 4, byteArrayOf(0x11, 0x22))
        // 复制一份并故意破坏 payload 的最后一字节，保持帧头长度合法
        val bad = good.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte()

        val p = L1Parser()
        p.feed(bad + good)
        val got = drain(p)

        assertEquals(1, p.badCrcCount, "坏帧必须被计数")
        assertEquals(1, got.size, "坏帧之后的好帧必须还能收到")
        assertEquals(4, got[0].seq)
        assertEquals(BAD_CRC_PAYLOAD_FIRST, got[0].payload[0])
    }

    @Test
    fun `坏 CRC 的载荷里含 A5A5 - 最后那个真帧必须还能收到`() {
        // 造一个 payload 内含 A5 A5 的帧，再破坏它的 CRC
        val trickyPayload = byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x00)
        val bad = frame(L1.TYPE_DATA, 1, trickyPayload)
        // 破坏 CRC 字段（第 6、7 字节）
        bad[6] = 0x00
        bad[7] = 0x00

        val p = L1Parser()
        p.feed(bad + frame(L1.TYPE_DATA, 2, byteArrayOf(0x55)))
        val got = drain(p)

        assertTrue(p.badCrcCount >= 1, "至少一次 CRC 失败")

        // ⚠️ 这里**不能**断言「恰好一帧」：被打坏的 payload 本身
        //   `A5 A5 03 00 00 00 00 00` 恰好构成一个合法的空 DATA 帧
        //   （长度 0、CRC 0），跳到那里重扫时它**通过**了 CRC 校验。
        //   这是 CRC-16 + 逐字节重扫的固有局限，不是缺陷：
        //   CRC 只能保证「16 位内大概率无错」，无法区分「真帧」与「伪构成帧」。
        //   真正要保证的契约是：**它绝不能吞掉后面那个真帧**。
        val last = assertNotNull(got.lastOrNull(), "至少要产出一帧")
        assertEquals(2, last.seq, "载荷内的伪魔数不得吞掉后面的真帧")
        assertTrue(last.payload.contentEquals(byteArrayOf(0x55)))
    }

    @Test
    fun `空载荷 DATA 帧合法`() {
        val p = L1Parser()
        p.feed(frame(L1.TYPE_DATA, 0))
        val got = drain(p)
        assertEquals(1, got.size)
        assertEquals(0, got[0].payload.size)
    }

    @Test
    fun `未知 type 与 frx 位必须原样保留`() {
        // 真机抓包里只出现过 type=1/3，所以这条只能靠合成帧覆盖：
        // type 是 4 位、frx 是第 4 位，任何 `and 0x07` 之类的掩码错误都要被抓到。
        val cases = listOf(
            Triple(0x0D, 1, 0x1D), // type 13 + frx=1
            Triple(0x08, 0, 0x08), // 仅高位为 1
            Triple(0x0F, 1, 0x1F), // 全 1
            Triple(L1.TYPE_DATA, 0, 0x03),
        )
        for ((type, frx, b2) in cases) {
            val raw = L1Codec.build(type = type, frx = frx, seq = 0x42, payload = byteArrayOf(1, 2, 3))
            assertEquals(b2, raw[2].toInt() and 0xFF, "第 3 字节应为 (frx<<4)|type")
            val p = L1Parser()
            p.feed(raw)
            val got = drain(p)
            assertEquals(1, got.size, "type=$type frx=$frx 应解出一帧")
            assertEquals(type, got[0].type, "type 不得被掩码截断")
            assertEquals(frx, got[0].frx, "frx 未保留")
            assertEquals(0x42, got[0].seq)
        }
    }

    @Test
    fun `缓冲跨扩容 - 大帧超过初始容量`() {
        val payload = ByteArray(50_000) { (it and 0xFF).toByte() }
        val p = L1Parser(capacity = 64)
        p.feed(frame(L1.TYPE_DATA, 8, payload))
        val got = drain(p)
        assertEquals(1, got.size)
        assertEquals(50_000, got[0].payload.size)
        assertTrue(payload.contentEquals(got[0].payload))
    }

    @Test
    fun `setMaxFrameBytes 收窄后旧上限的帧被判为伪魔数`() {
        val payload = ByteArray(1000) { 0x5A }
        val bytes = frame(L1.TYPE_DATA, 1, payload)
        val p = L1Parser()
        p.setMaxFrameBytes(100)          // 协商值远小于这帧
        p.feed(bytes)
        assertNull(p.next(), "超过协商上限的帧不应被接受")
        assertTrue(p.resyncCount > 0)
    }

    @Test
    fun `reset 清空未消费字节`() {
        val p = L1Parser()
        p.feed(byteArrayOf(0x00, 0x11, 0x22))
        assertTrue(p.pendingBytes > 0)
        p.reset()
        assertEquals(0, p.pendingBytes)
    }

    @Test
    fun `CRC 只覆盖 payload 的负向用例`() {
        // 若实现把长度字段也算进 CRC，这里会失败
        val payload = byteArrayOf(0x0A, 0x0B)
        val bytes = L1Codec.build(L1.TYPE_DATA, seq = 3, payload = payload)
        val declared = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        assertEquals(com.miaiglasses.core.Crc16Arc.compute(payload), declared)

        // 长度字段单独算出来的 CRC 必须不同（否则本用例无法区分两种实现）
        val withLen = payload + byteArrayOf(2, 0)
        assertTrue(
            com.miaiglasses.core.Crc16Arc.compute(withLen) != declared,
            "CRC 覆盖范围若包含长度字段，本断言会失败 —— 说明范围写错了",
        )
    }

    private companion object {
        const val BAD_CRC_PAYLOAD_FIRST: Byte = 0x11
    }
}
