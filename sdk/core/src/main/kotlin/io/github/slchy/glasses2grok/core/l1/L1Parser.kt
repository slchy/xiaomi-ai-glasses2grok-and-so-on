package io.github.slchy.glasses2grok.core.l1

import io.github.slchy.glasses2grok.core.Crc16Arc
import java.util.Arrays

/**
 * A5A5 流式解析器：字节流 → [L1Frame]。
 *
 * <h3>与上游实现的关系</h3>
 *
 * 构造与解析语义对齐 `参考实现` 与 `docs/a5a5.md` 规范，
 * 但**有一处刻意差异**（见 [BAD_CRC_ADVANCE]）：
 * CRC 校验失败时只前进 1 字节，而不是跳过整帧。
 *
 * <h3>性能约定</h3>
 *
 * - 线性缓冲 + 读写游标，只在空间不足时整块压缩一次；**不做每帧内存搬移**
 *   （Python 版 `del rx[:n]` 在长连接上退化为 O(n²)，这是设计记录列出的缺陷 1）。
 * - 魔数从上次停下的位置续扫，不每帧从头 `find`。
 *
 * <h3>线程</h3>
 *
 * **非线程安全**，由引擎线程独占（实测）。
 */
internal class L1Parser(
    capacity: Int = DEFAULT_CAPACITY,
    maxFrameBytes: Int = L1.MAX_FRAME_BYTES,
) {
    companion object {
        private const val DEFAULT_CAPACITY = 1 shl 17          // 128 KB
        private const val MAGIC = L1.MAGIC.toByte()

        /**
         * CRC 失败时前进的字节数。
         *
         * 为什么不是「跳过整帧」：长度字段恰恰是 CRC 失败时最不可信的那个字段。
         * 若这段 `A5 A5` 本就是噪声，跳过一个「看起来合法」的长度会**静默跨过后面的真帧**；
         * 而前进 1 字节的代价只是多扫几十字节，且一定能在下一个真魔数处重新对齐。
         *
         * 这与 `参考实现` 一致（该实现已在上百份抓包上跑出 A5A5 覆盖率 100%、CRC 0 错）；
         * `docs/a5a5.md` 规范走的是「跳过整帧」，两者都丢帧，区别只在重同步粒度。
         */
        const val BAD_CRC_ADVANCE = 1
    }

    private var buf = ByteArray(maxOf(L1.HEADER_SIZE * 2, capacity))
    private var r = 0
    private var w = 0

    private var maxFrame = maxFrameBytes

    private var frames = 0L
    private var badCrc = 0L
    private var resyncBytes = 0L

    /**
     * 收紧单帧合法上限。协商出对端 MPS 后应调用 ——
     * 默认值只能挡住超过 16 位帧长范围的伪魔数，
     * 而噪声里 `A5 A5` 后面两字节完全可能是 30000 这种「合法」长度。
     *
     * @param limit 非正值恢复协议上限
     */
    fun setMaxFrameBytes(limit: Int) {
        maxFrame = if (limit > 0) limit else L1.MAX_FRAME_BYTES
    }

    fun feed(data: ByteArray) = feed(data, 0, data.size)

    fun feed(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        ensure(length)
        data.copyInto(buf, w, offset, offset + length)
        w += length
    }

    private fun ensure(need: Int) {
        if (w + need <= buf.size) return
        val size = w - r
        if (size + need <= buf.size) {
            buf.copyInto(buf, 0, r, w)
            r = 0
            w = size
            return
        }
        var cap = buf.size
        while (cap < size + need) cap = cap shl 1
        val nb = ByteArray(cap)
        buf.copyInto(nb, 0, r, w)
        buf = nb
        r = 0
        w = size
    }

    /** 拉取下一帧；返回 null 表示数据不足一帧，等下次 [feed]。 */
    fun next(): L1Frame? {
        while (true) {
            if (w - r < L1.HEADER_SIZE) return null

            // 1) 定位魔数：游标只前进，不回头
            var i = r
            while (i + 1 < w && !(buf[i] == MAGIC && buf[i + 1] == MAGIC)) i++
            if (i + 1 >= w) {
                // 整段没有魔数：保留末字节（可能是半个魔数）
                val keep = if (buf[w - 1] == MAGIC) 1 else 0
                val consume = (w - r) - keep
                if (consume > 0) {
                    resyncBytes += consume
                    r += consume
                }
                return null
            }
            if (i > r) {
                resyncBytes += (i - r)
                r = i
            }
            if (w - r < L1.HEADER_SIZE) return null

            val b2 = buf[r + 2].toInt() and 0xFF
            val type = b2 and 0x0F
            val frx = (b2 ushr 4) and 1
            val seq = buf[r + 3].toInt() and 0xFF
            val len = (buf[r + 4].toInt() and 0xFF) or ((buf[r + 5].toInt() and 0xFF) shl 8)
            val hdrCrc = (buf[r + 6].toInt() and 0xFF) or ((buf[r + 7].toInt() and 0xFF) shl 8)
            val total = L1.HEADER_SIZE + len

            // 伪魔数：后面两字节被当成帧长。超出合法上限时跳过 1 字节重扫，
            // 而不是停下来等永远凑不齐的字节 —— 否则一个伪造头能把后续好帧全堵死。
            if (total > maxFrame) {
                resyncBytes += 1
                r += 1
                continue
            }

            if (w - r < total) return null

            val calc = Crc16Arc.compute(buf, r + L1.HEADER_SIZE, len)
            if (calc != hdrCrc) {
                badCrc++
                resyncBytes += BAD_CRC_ADVANCE
                r += BAD_CRC_ADVANCE
                continue
            }

            val payload = Arrays.copyOfRange(buf, r + L1.HEADER_SIZE, r + total)
            r += total
            frames++
            return L1Frame(type, frx, seq, payload, hdrCrc)
        }
    }

    fun reset() {
        r = 0
        w = 0
    }

    val frameCount: Long get() = frames
    val badCrcCount: Long get() = badCrc
    val resyncCount: Long get() = resyncBytes
    val pendingBytes: Int get() = w - r

    /** 诊断用：当前缓冲区容量。用于断言「长会话不会无限膨胀」。 */
    internal val bufferCapacityBytes: Int get() = buf.size
}
