package io.github.slchy.glasses2grok.core.l1

import io.github.slchy.glasses2grok.core.Crc16Arc
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 解析器的**鲁棒性**：真机链路会出各种脏数据（错位、丢字节、重复、噪声），
 * 解析器在这些输入上必须满足三条契约：
 *
 * 1. **不抛异常** —— 一个畸形帧绝不能把会话线程打死；
 * 2. **不吞真帧** —— 噪声之后的真帧必须还能解出来（重同步有效）；
 * 3. **账目平衡** —— `已产出帧字节 + 重同步丢弃字节 + 残留字节 == 喂入字节`，
 *    任何「悄悄吞掉字节」的实现都会让这个等式不成立。
 *
 * 输入全部用确定性的构造方式，没有随机种子漂移。
 */
class L1RobustnessTest {

    private fun frame(type: Int, seq: Int, len: Int, fill: Int): ByteArray {
        val payload = ByteArray(len) { ((fill + it) and 0xFF).toByte() }
        return L1Codec.build(type = type, seq = seq, payload = payload)
    }

    /** 造 [n] 帧，长度在 0..300 之间来回摆，覆盖长度字段高低字节。 */
    private fun blobOf(n: Int): ByteArray {
        var out = ByteArray(0)
        for (i in 0 until n) {
            val len = when (i % 5) {
                0 -> 0
                1 -> 1
                2 -> 255
                3 -> 256
                else -> 300
            }
            out += frame(if (i % 3 == 0) L1.TYPE_ACK else L1.TYPE_DATA, i and 0xFF, len, i)
        }
        return out
    }

    private fun drain(p: L1Parser): List<L1Frame> = generateSequence { p.next() }.toList()

    private fun assertBalanced(fed: Int, frames: List<L1Frame>, p: L1Parser, what: String) {
        val consumed = frames.sumOf { it.totalLength }
        assertEquals(
            fed.toLong(),
            consumed.toLong() + p.resyncCount + p.pendingBytes,
            "$what：账目不平衡（产出 $consumed + 重同步 ${p.resyncCount} + 残留 ${p.pendingBytes} ≠ 喂入 $fed）",
        )
    }

    /** 第 [n] 帧之后的字节偏移（帧边界）。 */
    private fun boundaryAfter(blob: ByteArray, n: Int): Int {
        var off = 0
        var idx = 0
        while (off + L1.HEADER_SIZE <= blob.size && idx < n) {
            val len = (blob[off + 4].toInt() and 0xFF) or ((blob[off + 5].toInt() and 0xFF) shl 8)
            off += L1.HEADER_SIZE + len
            idx++
        }
        return off
    }

    @Test
    fun `单字节翻转 - 只损失该帧，后续帧全部保住`() {
        val clean = blobOf(120)
        val cleanFrames = drain(L1Parser().also { it.feed(clean) })
        assertEquals(120, cleanFrames.size, "基线应全部解出")

        // 选有 payload 的帧（i%5==0 的是空载荷 ACK）
        val victims = (0 until 120).filter { it % 5 != 0 }.filter { it in listOf(3, 17, 40, 88, 118) }
        for (victim in victims) {
            val corrupt = clean.copyOf()
            var off = 0
            var idx = 0
            while (off + L1.HEADER_SIZE <= corrupt.size) {
                val len = (corrupt[off + 4].toInt() and 0xFF) or ((corrupt[off + 5].toInt() and 0xFF) shl 8)
                if (idx == victim) {
                    check(len > 0) { "选中的帧必须有 payload" }
                    corrupt[off + L1.HEADER_SIZE] = (corrupt[off + L1.HEADER_SIZE].toInt() xor 0xFF).toByte()
                    break
                }
                off += L1.HEADER_SIZE + len
                idx++
            }

            val p = L1Parser()
            p.feed(corrupt)
            val got = drain(p)
            assertEquals(119, got.size, "victim=$victim 只应损失被改坏的那一帧")
            assertTrue(p.badCrcCount >= 1, "被改坏的帧必须被 CRC 拦住")
            assertEquals(cleanFrames.last().seq, got.last().seq, "最后一帧仍必须解出（重同步没有卡住）")
            assertBalanced(corrupt.size, got, p, "victim=$victim")
        }
    }

    @Test
    fun `伪帧头 - 协商后上限收紧，超大长度立刻被跳过`() {
        val clean = blobOf(40)
        val at = boundaryAfter(clean, 5)
        // A5 A5 | type=0x0F | seq | len=0xFFFF | crc=0
        val fake = byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0, 0)
        val injected = clean.copyOfRange(0, at) + fake + clean.copyOfRange(at, clean.size)

        // 真机会在协商出 MPS 后调用 setMaxFrameBytes(MPS+8)（见 SarEnginehandleConfig）
        val p = L1Parser(maxFrameBytes = 2048)
        p.feed(injected)
        val got = drain(p)
        assertEquals(40, got.size, "伪帧头之后所有真帧都必须保住")
        assertEquals(0L, p.badCrcCount, "伪帧头应被长度上限挡住，而不是走 CRC 失败")
        assertEquals(0, p.pendingBytes)
        assertTrue(p.resyncCount >= 8, "至少要把那 8 字节伪头算作丢弃")
        assertBalanced(injected.size, got, p, "超大长度伪帧头")
    }

    /**
     * **已知边界**（不是 bug，也修不掉）：伪头的长度字段只要 ≤ `maxFrame`，就与
     * 「一个还没收完的大帧」完全不可区分 —— 解析器只能等。
     * 契约降级为：**等够声明长度后必然恢复**，损失有上界（≤ `maxFrame`）。
     */
    @Test
    fun `合法长度伪头 - 会阻塞到攒够声明长度，之后必然恢复`() {
        val clean = blobOf(40)
        val at = boundaryAfter(clean, 5)
        // len = 0x2000 (8192)，仍在默认 maxFrame 之内
        val fake = byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0x03, 0x00, 0x00, 0x20, 0xAB.toByte(), 0xCD.toByte())
        val injected = clean.copyOfRange(0, at) + fake + clean.copyOfRange(at, clean.size)

        val p = L1Parser()
        p.feed(injected)
        val head = drain(p)
        assertEquals(5, head.size, "伪头之前只有 5 帧")
        assertTrue(p.pendingBytes > 0, "此时应卡在等 8192 字节")

        // 继续喂垃圾把「声明长度」填满：CRC 失败 → 1 字节步进 → 重新对齐到后续真帧
        p.feed(ByteArray(9000))
        val rest = drain(p)
        val tail = rest.count { it.seq in 5..39 }
        assertTrue(tail >= 30, "填满后必须恢复出绝大部分真帧（实际 $tail/35）")
        assertTrue(p.badCrcCount >= 1, "伪头应被 CRC 拦下")
        assertBalanced(injected.size + 9000, head + rest, p, "合法长度伪头")
    }

    @Test
    fun `伪帧头 - 帧中插入（模拟错位），在收紧上限下立刻重对齐`() {
        val clean = blobOf(40)
        val fake = byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0, 0)
        // 插在第 2 帧的 payload 中间：该帧必坏
        val injected = clean.copyOfRange(0, 100) + fake + clean.copyOfRange(100, clean.size)

        val p = L1Parser(maxFrameBytes = 2048)
        p.feed(injected)
        val got = drain(p)

        assertTrue(got.any { it.seq == 39 }, "最后一帧必须重新对齐后解出（实际 seq=${got.map { it.seq }}）")
        assertTrue(p.badCrcCount >= 1, "被污染的区段应被 CRC 拦下")
        assertTrue(got.size >= 30, "损失应有界（实际 ${got.size}/40）")
        assertBalanced(injected.size, got, p, "帧中插入")
    }

    @Test
    fun `伪帧头 - 合法长度但坏 CRC，也必须重同步`() {
        val clean = blobOf(40)
        val at = boundaryAfter(clean, 5)
        // A5 A5 | DATA | seq | len=32 | crc=0 + 32 字节垃圾 → CRC 必错
        val fake = byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0x03, 0x00, 0x20, 0x00, 0x00, 0x00) + ByteArray(32) { 0x5A }
        val injected = clean.copyOfRange(0, at) + fake + clean.copyOfRange(at, clean.size)

        val p = L1Parser()
        p.feed(injected)
        val got = drain(p)
        assertEquals(40, got.size, "坏 CRC 的伪帧不得吞掉真帧")
        assertTrue(p.badCrcCount >= 1, "伪帧应被 CRC 拦下")
        assertBalanced(injected.size, got, p, "坏 CRC 伪帧")
    }

    @Test
    fun `任意切断点 - 不抛异常且续喂后全部恢复`() {
        val clean = blobOf(60)
        val total = drain(L1Parser().also { it.feed(clean) }).size

        var cut = 0
        while (cut < clean.size) {
            val p = L1Parser()
            p.feed(clean, 0, cut)
            val head = drain(p)
            assertTrue(p.pendingBytes <= L1.HEADER_SIZE + 2 + 300, "切断点 $cut 的残留过大：${p.pendingBytes}")
            // 再喂剩余部分：合并结果必须与一次性喂入一致
            p.feed(clean, cut, clean.size - cut)
            val all = head + drain(p)
            assertEquals(total, all.size, "切断点 $cut 续喂后帧数不对")
            assertEquals(0L, p.badCrcCount, "切断点 $cut 不应产生坏 CRC")
            assertBalanced(clean.size, all, p, "截断@$cut")
            cut += 137
        }
    }

    @Test
    fun `全零填充的伪帧头会通过 CRC - 记录这条已知边界`() {
        // CRC-16/ARC 在 init=0 时对全零输入恒为 0，所以「crc 字段 = 0 + 全零 payload」
        // 的伪帧头能通过校验，被当成一个真帧（由 tests/golden/bigframe_probe.py 发现）。
        // 这不是能修的东西（CRC 本来就无法区分），要保证的是：
        // 它吃掉的长度恰好等于声明长度，之后的真帧仍然对得上。
        val zeroLen = 1_000
        val fake = byteArrayOf(
            0xA5.toByte(), 0xA5.toByte(), 0x03, 0x00,
            (zeroLen and 0xFF).toByte(), ((zeroLen ushr 8) and 0xFF).toByte(), 0, 0,
        ) + ByteArray(zeroLen)

        val prefix = ByteArrayOutputStream().also { b ->
            for (i in 0 until 3) b.write(L1Codec.build(type = L1.TYPE_DATA, seq = i, payload = byteArrayOf(9)))
        }.toByteArray()
        val tail = ByteArrayOutputStream().also { b ->
            for (i in 3 until 6) b.write(L1Codec.build(type = L1.TYPE_DATA, seq = i, payload = byteArrayOf(7)))
        }.toByteArray()

        val p = L1Parser()
        p.feed(prefix + fake + tail)
        val seqs = drain(p).map { it.seq }

        assertEquals(listOf(0, 1, 2), seqs.take(3), "前缀真帧应全部解出")
        assertTrue(3 in seqs, "伪帧吃掉的长度必须恰好等于声明长度，之后要对齐回来（实际 $seqs）")
        assertEquals(0L, p.badCrcCount, "全零伪帧不会报坏 CRC —— 这正是它的危险之处")
    }

    @Test
    fun `纯垃圾 1 MB - 不膨胀、不产出帧`() {
        val p = L1Parser()
        val junk = ByteArray(4096)
        var fed = 0
        repeat(256) {
            p.feed(junk)
            fed += junk.size
            assertEquals(0, drain(p).size, "全零流不应产出帧")
        }
        assertEquals(0, p.pendingBytes, "无魔数时只应保留最多 1 字节")
        assertTrue(p.resyncCount >= fed - 1L, "除保留字节外都只能算重同步")
        assertTrue(p.bufferCapacityBytes <= 1 shl 18, "垃圾流不得撑大缓冲：${p.bufferCapacityBytes}")
    }

    @Test
    fun `随机噪声 - 不抛异常，且产出的每一帧 CRC 都真的对`() {
        val p = L1Parser()
        var seed = 0x12345678
        val fed = 262_144
        val chunk = ByteArray(1024)
        var fedTotal = 0
        val produced = mutableListOf<L1Frame>()
        while (fedTotal < fed) {
            for (i in chunk.indices) {
                seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
                chunk[i] = ((seed ushr 16) and 0xFF).toByte()
            }
            p.feed(chunk)
            fedTotal += chunk.size
            produced += drain(p)
        }
        // 噪声里偶尔会撞出「合法帧」，但只要产出，CRC 必须真对 —— 否则就是校验被跳过
        for (f in produced) {
            assertEquals(Crc16Arc.compute(f.payload), f.crc, "产出的帧必须通过 CRC（seq=${f.seq}）")
        }
        // 噪声里撞出的帧不能是任意多：至少不能把「随机字节」当成大部分输入
        assertTrue(produced.size < fedTotal / 8, "噪声误判率过高：${produced.size} 帧 / ${fedTotal} B")
        assertBalanced(fedTotal, produced, p, "随机噪声")
    }

    @Test
    fun `长会话 - 反复复用同一缓冲不增长`() {
        val clean = blobOf(200)
        val p = L1Parser()
        val cap = p.bufferCapacityBytes
        var frames = 0
        repeat(300) {
            p.feed(clean)
            frames += drain(p).size
        }
        assertEquals(300 * 200, frames, "长会话不得丢帧")
        assertEquals(0L, p.badCrcCount)
        assertEquals(0, p.pendingBytes)
        assertEquals(cap, p.bufferCapacityBytes, "缓冲区不应因反复使用而扩容")
    }
}
