package com.miaiglasses.core.interconn

import com.miaiglasses.core.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `System id=79` 佩戴状态上报。
 *
 * 取值语义是**文档里已有的确证事实**（`命令图谱`「`System id=79` 是佩戴状态主动上报」、
 * `参考协议`）：**1 = 佩戴中、2 = 未佩戴**，与 `GetWearStatus`（`f60`）同一
 * value space。
 */
class WearStatusTest {

    @Test
    fun `真机字节 - 佩戴中`() {
        // 2026-11-11 19:52:43 样本：f49(0x8a 0x03) len=2 │ f2=1
        val w = assertNotNull(WearStatus.fromReport(Hex.decode("8a 03 02 10 01")))
        assertEquals(1, w.state)
        assertTrue(w.isWorn, "1 = 佩戴中")
        assertTrue(!w.isOff)
    }

    @Test
    fun `真机字节 - 未佩戴`() {
        // 2026-11-11 19:52:45 样本：f2=2
        val w = assertNotNull(WearStatus.fromReport(Hex.decode("8a 03 02 10 02")))
        assertEquals(2, w.state)
        assertTrue(w.isOff, "2 = 未佩戴")
        assertTrue(!w.isWorn)
    }

    @Test
    fun `id=93 回包 - f60 是权威当前态`() {
        // 命令图谱的真机闭环：请求空载荷，响应 f60 = 1（当时正佩戴）
        val w = assertNotNull(WearStatus.fromQuery(Hex.decode("e0 03 01")))
        assertEquals(1, w.state)
        assertTrue(w.isWorn)

        val off = assertNotNull(WearStatus.fromQuery(Hex.decode("e0 03 02")))
        assertTrue(off.isOff, "2 = 未佩戴")
    }

    @Test
    fun `两种来源不能混用`() {
        // f49.f2（id=79 上报）用 fromQuery 解不出来，反之亦然 —— 结构不同，别猜
        assertNull(WearStatus.fromQuery(Hex.decode("8a 03 02 10 01")), "上报载荷里没有 f60")
        assertNull(WearStatus.fromReport(Hex.decode("e0 03 01")), "回包载荷里没有 f49")
    }

    @Test
    fun `没有 f49 时返回 null 而不是瞎猜`() {
        assertNull(WearStatus.fromReport(ByteArray(0)))
        assertNull(WearStatus.fromReport(Hex.decode("0a 02 08 01")))
    }
}
