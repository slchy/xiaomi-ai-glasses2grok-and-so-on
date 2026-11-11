package com.miaiglasses.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 向量由 `参考实现` 生成，保证与 Python 参考实现逐位一致。
 */
class Crc16ArcTest {

    @Test
    fun `标准校验向量 123456789 = 0xBB3D`() {
        assertEquals(0xBB3D, Crc16Arc.compute("123456789".toByteArray()))
    }

    @Test
    fun `空输入为 0`() {
        assertEquals(0x0000, Crc16Arc.compute(ByteArray(0)))
    }

    @Test
    fun `L1 头前四字节向量 0x1F32`() {
        assertEquals(0x1F32, Crc16Arc.compute(byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0x03, 0x00)))
    }

    @Test
    fun `L2 明文载荷向量 0x5180`() {
        assertEquals(0x5180, Crc16Arc.compute(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun `区间参数只覆盖指定范围`() {
        val buf = byteArrayOf(0x41, 0x42, 0x43)
        assertEquals(
            Crc16Arc.compute(byteArrayOf(0x42, 0x43)),
            Crc16Arc.compute(buf, offset = 1, length = 2),
        )
    }

    @Test
    fun `越界区间必须抛异常`() {
        val buf = byteArrayOf(0x01, 0x02)
        val failed = runCatching { Crc16Arc.compute(buf, offset = 1, length = 5) }.isFailure
        assertEquals(true, failed)
    }
}
