package io.github.slchy.glasses2grok.core.mass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `L2 ch=2` 文件分片组装。
 *
 * 帧布局来自真机（实测）：
 * 片头 `00 83 type 00 seq 00`，首片再跟 `1B idLen + id + 5B 0`，文件末尾另有 4 B 尾标。
 *
 * ⚠️ 末片判据是**整帧**短于片长（真机：前 4 片 4 096、末片 2 618），不是正文长度 ——
 * 首片正文必然比整帧短，这一点曾经写错导致「第一片就当整文件」。
 */
class MassFileAssemblerTest {

    /** 50 B 的假 PNG（8 B 魔数 + 34 B 正文 + 8 B IEND）+ 4 B 尾标 = 54 B 正文。 */
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        ByteArray(34) + byteArrayOf(0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
    private val trailer = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    private val id = "x.jpg"

    @Test
    fun `单文件多片 - 首片带 identifier，末片短于整帧片长才收尾`() {
        val slice = 30
        val asm = MassFileAssembler(sliceSize = slice)
        val all = png + trailer
        var done: MassFileData? = null
        var off = 0
        var seq = 1
        while (off < all.size) {
            val headExtra = if (seq == 1) 1 + id.toByteArray().size + MassFileAssembler.EXTRA_ZEROS else 0
            val capacity = slice - MassFileAssembler.HEADER - headExtra
            val n = minOf(capacity, all.size - off)
            val frame = frame(if (seq == 1) id else "", 5, seq, MassFileAssembler.HEADER + headExtra + n, all, off)
            off += n
            done = asm.feed(frame) ?: done
            seq++
        }
        val f = done!!
        assertEquals(id, f.identifier)
        assertEquals(5, f.mediaType)
        assertEquals(png.size, f.bytes.size, "尾标 4 B 要剥掉")
        assertTrue(f.bytes.contentEquals(png), "拼出来的字节必须与原文件一致")
        assertEquals(trailer.toList(), f.trailer.toList())
        assertEquals(3, f.slices)
        assertTrue(!asm.isInProgress)
    }

    @Test
    fun `分片号不连续 - 整份丢弃`() {
        val asm = MassFileAssembler(sliceSize = 30)
        val all = ByteArray(64)
        assertNull(asm.feed(frame(id, 5, 1, 30, all, 0)), "满帧不算末片")
        assertTrue(asm.isInProgress)
        assertNull(asm.feed(frame(id, 5, 3, 30, all, 0)), "跳号要丢掉，不能拼出坏文件")
        assertTrue(!asm.isInProgress)
    }

    @Test
    fun `没有首片就来后续片 - 丢弃`() {
        val asm = MassFileAssembler()
        assertNull(asm.feed(frame("", 5, 2, 10, ByteArray(10), 0)))
        assertTrue(!asm.isInProgress)
    }

    @Test
    fun `整片收尾 - 等 flush`() {
        val asm = MassFileAssembler(sliceSize = 30)
        assertNull(asm.feed(frame(id, 5, 1, 30, ByteArray(40), 0)))
        assertNull(asm.feed(frame(id, 5, 2, 30, ByteArray(40), 0)))
        val f = asm.flush()!!
        assertEquals(2, f.slices)
        assertTrue(f.bytes.isNotEmpty())
    }

    @Test
    fun `短帧 - 不像分片就忽略`() {
        val asm = MassFileAssembler()
        assertNull(asm.feed(ByteArray(3)))
        assertTrue(!asm.isInProgress)
    }

    /**
     * 造一帧：总长 = [total]；首片先放 `00 83 type 00 seq 00 1B idLen + id + 5B 0`，其余用 [data] 填。
     */
    private fun frame(id: String, type: Int, seq: Int, total: Int, data: ByteArray, offset: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0)
        out.write(0x83)
        out.write(type)
        out.write(0)
        out.write(seq)
        out.write(0)
        if (seq == 1) {
            val idBytes = id.toByteArray()
            out.write(idBytes.size)
            out.write(idBytes)
            repeat(MassFileAssembler.EXTRA_ZEROS) { out.write(0) }
        }
        val head = out.size()
        for (i in 0 until (total - head)) {
            out.write(data[(offset + i) % data.size].toInt())
        }
        return out.toByteArray()
    }
}
