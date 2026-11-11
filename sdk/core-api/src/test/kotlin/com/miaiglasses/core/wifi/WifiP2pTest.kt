package com.miaiglasses.core.wifi

import com.miaiglasses.core.pb.Pb
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * `System id=99 EnableWiFiP2PGC` 的编解码。
 *
 * 字段号来源（参考实现，见 `实测`）：
 * - `EnableWiFiP2PGC.getPackedId()` = `0x63`(99)
 * - `protobuf 消息类.protobuf 编码`：1 ssid / 2 password / 3 frequency / 4 taskId
 * - `protobuf 消息类`：1 code / 2 ipAddress / 3 taskId
 * - `设置 P2P` → `载荷类型 = 0x41`(65)；`P2P 回执` → 66
 */
class WifiP2pTest {

    @Test
    fun `WifiP2pIds - 常量与协议规范一致`() {
        assertEquals(99, WifiP2pIds.ID_ENABLE_P2P_GC)
        assertEquals(65, WifiP2pIds.FIELD_WIFI_P2P)
        assertEquals(66, WifiP2pIds.FIELD_RESULT)
        assertEquals(5745, WifiP2pIds.DEFAULT_FREQUENCY_MHZ, "参考实现 startGo 的 0x1671 = 5745MHz（ch149）")
    }

    @Test
    fun `enablePayload - f65 里是 ssid password frequency taskId`() {
        val payload = WifiP2pCodec.enablePayload("DIRECT-ab12cd34", "Secret12", 5745, 7)
        val system = Pb.fields(payload)
        assertEquals(setOf(WifiP2pIds.FIELD_WIFI_P2P), system.keys)

        val p2p = Pb.fields(system[WifiP2pIds.FIELD_WIFI_P2P]!!)
        assertEquals("DIRECT-ab12cd34", String(p2p[1]!!))
        assertEquals("Secret12", String(p2p[2]!!), "密码在 f2")
        assertEquals(5745L, Pb.varintValue(p2p[3]!!))
        assertEquals(7L, Pb.varintValue(p2p[4]!!))
    }

    @Test
    fun `enablePayload - taskId 不传就不写（nano 默认值不上线）`() {
        val p2p = Pb.fields(
            Pb.fields(WifiP2pCodec.enablePayload("DIRECT-1", "12345678"))[WifiP2pIds.FIELD_WIFI_P2P]!!,
        )
        assertEquals(setOf(1, 2, 3), p2p.keys)
        assertEquals(WifiP2pIds.DEFAULT_FREQUENCY_MHZ.toLong(), Pb.varintValue(p2p[3]!!), "默认 5745")
    }

    @Test
    fun `parseResult - code 与 ipAddress`() {
        val inner = java.io.ByteArrayOutputStream()
        Pb.uint(inner, 1, 0)
        Pb.string(inner, 2, "192.168.49.220")
        Pb.uint(inner, 3, 7)
        val payload = java.io.ByteArrayOutputStream()
        Pb.bytes(payload, WifiP2pIds.FIELD_RESULT, inner.toByteArray())

        val r = WifiP2pCodec.parseResult(payload.toByteArray())!!
        assertEquals(0, r.code)
        assertEquals("192.168.49.220", r.ip)
        assertEquals(7, r.taskId)
        assertTrue(r.isOk)
    }

    @Test
    fun `parseResult - 失败码或空 IP 都不算成功`() {
        val payload = java.io.ByteArrayOutputStream()
        val failedInner = java.io.ByteArrayOutputStream()
        Pb.uint(failedInner, 1, 3)
        Pb.bytes(payload, WifiP2pIds.FIELD_RESULT, failedInner.toByteArray())
        val failed = WifiP2pCodec.parseResult(payload.toByteArray())!!
        assertEquals(3, failed.code)
        assertEquals("", failed.ip)
        assertFalse(failed.isOk)

        val okNoIpPayload = java.io.ByteArrayOutputStream()
        val okInner = java.io.ByteArrayOutputStream()
        Pb.uint(okInner, 1, 0)
        Pb.bytes(okNoIpPayload, WifiP2pIds.FIELD_RESULT, okInner.toByteArray())
        val okNoIp = WifiP2pCodec.parseResult(okNoIpPayload.toByteArray())!!
        assertEquals(0, okNoIp.code)
        assertFalse(okNoIp.isOk, "没有 IP 就用不了 HTTP，不能算成功")
    }

    @Test
    fun `parseResult - 不是 P2P 回包时返回 null`() {
        assertNull(WifiP2pCodec.parseResult(ByteArray(0)))
        assertNull(WifiP2pCodec.parseResult(WifiP2pCodec.enablePayload("DIRECT-1", "12345678")))
    }
}
