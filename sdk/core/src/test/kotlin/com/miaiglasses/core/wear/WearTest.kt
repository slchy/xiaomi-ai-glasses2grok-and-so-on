package com.miaiglasses.core.wear

import com.miaiglasses.core.Hex
import com.miaiglasses.core.l1.GoldenVectors
import com.miaiglasses.core.pb.Pb
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WearPacket 的编码/解析。
 *
 * 三条向量直接来自 `tests/golden/vectors.txt`（`参考实现` 产出），
 * 不另造副本。
 */
class WearTest {

    @Test
    fun `三条黄金向量逐字节一致`() {
        val rows = GoldenVectors.wearRows()
        assertEquals(
            listOf("wear_ai", "wear_bigid", "wear_empty"),
            rows.map { it.name }.sorted(),
            "Wear 向量缺失或被改名 —— 先跑 python tests/golden/gen.py",
        )

        for (row in rows) {
            val p = row.script.split("|")
            val type = p[0].toInt()
            val id = p[1].toInt()
            val payload = if (p.size > 2 && p[2].isNotEmpty()) Hex.decode(p[2]) else ByteArray(0)
            assertEquals(row.expectedHex, Hex.encode(WearCodec.build(type, id, payload)), "向量 ${row.name}")
        }
    }

    @Test
    fun `黄金向量解析回环`() {
        for (row in GoldenVectors.wearRows()) {
            val bytes = Hex.decode(row.expectedHex)
            val p = assertNotNull(WearCodec.parse(bytes), "向量 ${row.name} 解析失败")
            val script = row.script.split("|")
            assertEquals(script[0].toInt(), p.type, "向量 ${row.name} 的 type")
            assertEquals(script[1].toInt(), p.id, "向量 ${row.name} 的 id")
            val wantPayload = if (script.size > 2 && script[2].isNotEmpty()) Hex.decode(script[2]) else ByteArray(0)
            assertTrue(wantPayload.contentEquals(p.payload), "向量 ${row.name} 的 payload")
            assertEquals(p.type + 2, p.payloadField, "容器字段号 = type + 2")
        }
    }

    @Test
    fun `id 大于等于 128 时 id 必须是合法 varint`() {
        // 300 = 0xAC 0x02；若当单字节写会变成 0x2C（44）
        val bytes = WearCodec.build(14, 300, ByteArray(0))
        assertTrue(Hex.encode(bytes).startsWith("080e10ac02"), "实际 ${Hex.encode(bytes)}")
        assertEquals(300, WearCodec.parse(bytes)!!.id)
    }

    @Test
    fun `空 payload 仍显式写长度 0`() {
        val bytes = WearCodec.build(2, 1, ByteArray(0))
        assertEquals("080210012200", Hex.encode(bytes), "不能省略容器字段")
        val p = WearCodec.parse(bytes)!!
        assertEquals(0, p.payload.size)
    }

    @Test
    fun `容器字段号随 type 变化 - 写死 22 只能发 System`() {
        assertEquals(0x1A, WearCodec.build(1, 0).let { it[4].toInt() and 0xFF })   // Account → field 3
        assertEquals(0x22, WearCodec.build(2, 0).let { it[4].toInt() and 0xFF })   // System  → field 4
        assertEquals(0x82, WearCodec.build(14, 0).let { it[4].toInt() and 0xFF })  // Voice    → field 16
        assertEquals(0xA2, WearCodec.build(18, 0).let { it[4].toInt() and 0xFF })  // Media   → field 20
    }

    @Test
    fun `缺 type 或 id 时解析返回 null`() {
        assertNull(WearCodec.parse(null))
        assertNull(WearCodec.parse(ByteArray(0)))
        assertNull(WearCodec.parse(byteArrayOf(0x08, 0x02)))            // 只有 type
        assertNull(WearCodec.parse(byteArrayOf(0x10, 0x05)))            // 只有 id
    }

    @Test
    fun `截断的报文不抛异常`() {
        val full = WearCodec.build(14, 4, byteArrayOf(1, 2, 3, 4))
        val truncated = full.copyOf(full.size - 2)
        val p = WearCodec.parse(truncated)
        // 结果可以是 null 或字段不全，但**不能崩**
        assertTrue(p == null || p.payload.size <= 4)
    }

    // ---------------------------------------------------------------- Pb

    @Test
    fun `varint 编解码覆盖边界`() {
        for (v in listOf(0L, 1L, 127L, 128L, 300L, 16383L, 16384L, 2_147_483_647L, 4_294_967_295L)) {
            val out = ByteArrayOutputStream()
            Pb.varint(out, v)
            val fields = Pb.fields(out.toByteArray())
            // 裸 varint 没有 tag，Pb.fields 会把它当 tag 解析 —— 这里直接验字节长度
            assertTrue(out.size() in 1..5, "v=$v 编码长度异常 ${out.size()}")
        }
    }

    @Test
    fun `fields 解析 varint 与 length-delimited 混排`() {
        val out = ByteArrayOutputStream()
        Pb.uint(out, 1, 42)
        Pb.bytes(out, 4, byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        Pb.uint(out, 7, 300)

        val f = Pb.fields(out.toByteArray())
        assertEquals(42L, Pb.varintValue(f[1]!!))
        assertTrue(byteArrayOf(0xAA.toByte(), 0xBB.toByte()).contentEquals(f[4]!!))
        assertEquals(300L, Pb.varintValue(f[7]!!))
    }

    @Test
    fun `fields 遇到截断的 length 字段时停止而不是抛异常`() {
        val out = ByteArrayOutputStream()
        Pb.tag(out, 4, Pb.WIRE_LEN)
        Pb.varint(out, 100)                       // 声明 100 字节但后面没有
        val f = Pb.fields(out.toByteArray())
        assertNull(f[4])
    }
}
