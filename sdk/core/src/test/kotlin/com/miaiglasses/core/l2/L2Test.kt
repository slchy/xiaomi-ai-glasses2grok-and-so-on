package com.miaiglasses.core.l2

import com.miaiglasses.core.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class L2Test {

    @Test
    fun `两字节头按大端-无关原样前置`() {
        val bytes = L2Codec.build(L2.CH_MASS_VOICE, L2.OP_WRITE_ENC, byteArrayOf(0x0A, 0x0B))
        assertEquals("03020a0b", Hex.encode(bytes))
        val p = L2Codec.parse(bytes)!!
        assertEquals(L2.CH_MASS_VOICE, p.channel)
        assertEquals(L2.OP_WRITE_ENC, p.opCode)
        assertEquals("0a0b", Hex.encode(p.body))
    }

    @Test
    fun `不足两字节返回 null`() {
        assertNull(L2Codec.parse(null))
        assertNull(L2Codec.parse(ByteArray(0)))
        assertNull(L2Codec.parse(byteArrayOf(3)))
    }

    @Test
    fun `加密只由 opCode 决定与 channel 无关`() {
        assertTrue(L2.isEncrypted(L2.OP_WRITE_ENC))
        assertTrue(!L2.isEncrypted(L2.OP_WRITE))
        assertTrue(!L2.isEncrypted(L2.OP_READ))
    }

    @Test
    fun `NETWORK 通道双向都不 ACK`() {
        assertTrue(!L2.requiresAck(L2.CH_NETWORK))
        for (ch in listOf(L2.CH_PB, L2.CH_MASS, L2.CH_MASS_VOICE, L2.CH_OTA, L2.CH_LYRA)) {
            assertTrue(L2.requiresAck(ch), "channel $ch 应要求 ACK")
        }
    }

    @Test
    fun `PB 与 LYRA 走立即 ACK`() {
        assertTrue(L2.ackImmediately(L2.CH_PB))
        assertTrue(L2.ackImmediately(L2.CH_LYRA))
        assertTrue(!L2.ackImmediately(L2.CH_MASS_VOICE))
    }

    @Test
    fun `未知 channel 可原样上报且不崩`() {
        val p = L2Codec.parse(L2Codec.build(200, 1, byteArrayOf(0x55)))!!
        assertEquals(200, p.channel)
        assertEquals("CH200", p.channelName)
        assertTrue(L2.requiresAck(200))
    }
}
