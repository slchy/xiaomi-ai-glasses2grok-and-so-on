package com.miaiglasses.core.crypto

import com.miaiglasses.core.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 会话密钥派生与两条签名。
 *
 * **两个 HMAC 的 message 顺序相反**是这里唯一容易写错的地方，两条都单独覆盖：
 * ```
 * deviceSign = HMAC(deviceKey, deviceRandom‖appRandom)
 * appSign    = HMAC(appKey,    appRandom‖deviceRandom)   ← 顺序相反
 * ```
 */
class SessionKeysTest {

    private val token = Hex.decode("00112233445566778899aabbccddeeff")
    private val appRandom = ByteArray(16) { it.toByte() }
    private val deviceRandom = ByteArray(16) { (0x10 + it).toByte() }

    @Test
    fun `64 字节派生按 16-16-4-4 切开`() {
        val k = SessionKeys.derive(token, appRandom, deviceRandom)
        assertEquals(16, k.deviceKey.size)
        assertEquals(16, k.appKey.size)
        assertEquals(4, k.deviceIv.size)
        assertEquals(4, k.appIv.size)

        // 与直接调 HKDF 的结果一致
        val raw = Crypto.hkdfSha256(
            token, Crypto.concat(appRandom, deviceRandom),
            "miwear-auth".toByteArray(), 64,
        )
        assertTrue(raw.copyOfRange(0, 16).contentEquals(k.deviceKey))
        assertTrue(raw.copyOfRange(16, 32).contentEquals(k.appKey))
        assertTrue(raw.copyOfRange(32, 36).contentEquals(k.deviceIv))
        assertTrue(raw.copyOfRange(36, 40).contentEquals(k.appIv))
        assertFalse(k.appKey.contentEquals(k.deviceKey), "两把密钥不能相同")
    }

    @Test
    fun `deviceSign 与 appSign 的消息顺序相反`() {
        val k = SessionKeys.derive(token, appRandom, deviceRandom)

        val deviceSign = SessionKeys.expectedDeviceSign(k.deviceKey, deviceRandom, appRandom)
        val appSign = k.signApp(appRandom, deviceRandom)

        // 用「错误的顺序 + 错误的密钥」交叉验证：四者互不相同
        assertFalse(
            deviceSign.contentEquals(SessionKeys.expectedDeviceSign(k.deviceKey, appRandom, deviceRandom)),
            "deviceSign 必须用 deviceRandom‖appRandom",
        )
        assertFalse(
            appSign.contentEquals(Crypto.hmacSha256(k.appKey, Crypto.concat(deviceRandom, appRandom))),
            "appSign 必须用 appRandom‖deviceRandom",
        )
        assertTrue(k.verifyDevice(deviceRandom, appRandom, deviceSign))
    }

    @Test
    fun `验签对错误签名返回 false 而不是抛异常`() {
        val k = SessionKeys.derive(token, appRandom, deviceRandom)
        assertFalse(k.verifyDevice(deviceRandom, appRandom, ByteArray(32)))
        assertFalse(k.verifyDevice(deviceRandom, appRandom, ByteArray(31)))
    }

    @Test
    fun `CTR 加解密对称且 key 相同则密文相同`() {
        val k = SessionKeys.derive(token, appRandom, deviceRandom)
        val plain = "hello miwear".toByteArray()
        val enc = k.encryptApp(plain)
        assertTrue(plain.contentEquals(k.encryptApp(enc)), "同一把 key 下 CTR 自反")
        assertTrue(
            enc.contentEquals(SessionKeys.derive(token, appRandom, deviceRandom).encryptApp(plain)),
            "同输入必得同密文（counter 每帧从 0 重置）",
        )
    }

    @Test
    fun `收发用不同的密钥`() {
        val k = SessionKeys.derive(token, appRandom, deviceRandom)
        val plain = ByteArray(32) { 0x11 }
        assertFalse(k.encryptApp(plain).contentEquals(k.decryptDevice(plain)))
    }

    @Test
    fun `CompanionDevice nonce 是 appIV 加 8 个零`() {
        val k = SessionKeys.derive(token, appRandom, deviceRandom)
        val nonce = k.companionNonce()
        assertEquals(12, nonce.size)
        assertTrue(nonce.copyOfRange(0, 4).contentEquals(k.appIv))
        assertTrue(nonce.copyOfRange(4, 12).all { it == 0.toByte() })
    }

    @Test
    fun `输入长度不对时明确报错`() {
        assertFailsWith<IllegalArgumentException> { SessionKeys.derive(ByteArray(32), appRandom, deviceRandom) }
        assertFailsWith<IllegalArgumentException> { SessionKeys.derive(token, ByteArray(8), deviceRandom) }
        assertFailsWith<IllegalArgumentException> { SessionKeys.derive(token, appRandom, ByteArray(0)) }
    }

    @Test
    fun `destroy 抹掉密钥内容且之后拒绝再用`() {
        // 回归护栏：早先只有 keys = null —— 那只丢引用，密钥数组还在堆里等 GC。
        val k = SessionKeys.derive(token, appRandom, deviceRandom)
        k.destroy()

        assertTrue(k.isDestroyed)
        assertTrue(k.deviceKey.all { it == 0.toByte() }, "deviceKey 必须被清零")
        assertTrue(k.appKey.all { it == 0.toByte() }, "appKey 必须被清零")
        assertTrue(k.deviceIv.all { it == 0.toByte() }, "deviceIv 必须被清零")
        assertTrue(k.appIv.all { it == 0.toByte() }, "appIv 必须被清零")

        // 用被抹掉的密钥「加密」只会产生垃圾 —— 与其静默出错，不如直接拒绝
        assertFailsWith<IllegalStateException> { k.encryptApp(byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { k.decryptDevice(byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { k.signApp(appRandom, deviceRandom) }
        assertFailsWith<IllegalStateException> { k.verifyDevice(deviceRandom, appRandom, ByteArray(32)) }

        k.destroy() // 幂等
    }

    @Test
    fun `每次派生结果只取决于输入 - 无隐藏状态`() {
        val a = SessionKeys.derive(token, appRandom, deviceRandom)
        val b = SessionKeys.derive(token, appRandom, deviceRandom)
        assertTrue(a.appKey.contentEquals(b.appKey))
        assertTrue(a.deviceKey.contentEquals(b.deviceKey))

        val other = SessionKeys.derive(token, deviceRandom, appRandom)   // salt 顺序换了
        assertFalse(a.appKey.contentEquals(other.appKey), "salt 顺序参与派生")
    }
}
