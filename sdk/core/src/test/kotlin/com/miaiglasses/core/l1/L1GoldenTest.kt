package com.miaiglasses.core.l1

import com.miaiglasses.core.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 黄金向量对账：直接读 `tests/golden/vectors.txt`（由 `tests/golden/gen.py` 生成），
 * **不另造一套向量** —— 两份向量必然漂移，一份才不会。
 *
 * 当前覆盖 `kind=P` 里的 `l1_*` 四条：
 * ```
 * l1_data   3|7|68656c6c6f   type=3 seq=7 payload="hello"
 * l1_ack    9|1|0            type=1 seq=9 frx=0
 * l1_nak    9|0|0            type=0 seq=9 frx=0
 * l1_force  9|1|1            type=1 seq=9 frx=1
 * ```
 * 这四条由 Python 参考实现产出，是 Python 与本 SDK 的共同基准。
 */
class L1GoldenTest {

    @Test
    fun `四条 L1 黄金向量逐字节一致`() {
        val rows = GoldenVectors.l1Rows().sortedBy { it.name }

        assertEquals(
            listOf("l1_ack", "l1_data", "l1_force", "l1_nak"),
            rows.map { it.name },
            "黄金向量缺失或被改名 —— 先跑 python tests/golden/gen.py",
        )

        for (row in rows) {
            val parts = row.script.split("|")
            val actual = when (row.name) {
                "l1_data" -> L1Codec.build(
                    type = parts[0].toInt(),
                    seq = parts[1].toInt(),
                    payload = Hex.decode(parts[2]),
                )
                else -> {
                    val seq = parts[0].toInt()
                    val ack = parts[1] == "1"
                    val force = parts[2] == "1"
                    L1Codec.buildAck(seq, ack, force)
                }
            }
            assertEquals(row.expectedHex, Hex.encode(actual), "向量 ${row.name}")
        }
    }

    @Test
    fun `黄金向量解析回环 - 构造出的帧能被解析器读回`() {
        for (row in GoldenVectors.l1Rows()) {
            val bytes = Hex.decode(row.expectedHex)
            val parser = L1Parser()
            parser.feed(bytes)
            val f = assertNotNull(parser.next(), "向量 ${row.name} 未能解析")
            assertEquals(0, parser.pendingBytes, "向量 ${row.name} 有残留字节")
            assertNull(parser.next(), "向量 ${row.name} 多解析出一帧")

            if (row.name == "l1_data") {
                assertEquals(L1.TYPE_DATA, f.type)
                assertEquals(7, f.seq)
                assertEquals("hello", String(f.payload, Charsets.US_ASCII))
                assertTrue(f.requiresAck, "DATA 类型上要求 ACK")
            } else {
                assertEquals(9, f.seq)
                assertEquals(0, f.payload.size)
                assertEquals(0, f.crc, "ACK/NAK 的 CRC 字段必须是 0")
                assertEquals(row.name == "l1_ack" || row.name == "l1_force", f.type == L1.TYPE_ACK)
                assertEquals(row.name == "l1_force", f.frx == 1)
            }
        }
    }
}
