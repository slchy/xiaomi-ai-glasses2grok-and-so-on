package io.github.slchy.glasses2grok.core.handshake

import io.github.slchy.glasses2grok.core.Hex
import io.github.slchy.glasses2grok.core.crypto.Crypto
import io.github.slchy.glasses2grok.core.crypto.SessionKeys
import io.github.slchy.glasses2grok.core.pb.Pb
import io.github.slchy.glasses2grok.core.sar.SarConfig
import io.github.slchy.glasses2grok.core.wear.WearCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 三步握手。用「假设备」驱动 —— 设备侧签名由 [SessionKeys.expectedDeviceSign]
 * 按协议定义算出，因此这里验的是**流程与字段号**，不是密码学正确性
 * （后者由 `CryptoTest` 对独立实现的黄金向量负责）。
 */
class HandshakeDriverTest {

    private val token = Hex.decode("00112233445566778899aabbccddeeff")
    private val appRandom = ByteArray(16) { it.toByte() }
    private val deviceRandom = ByteArray(16) { (0x10 + it).toByte() }

    private class Rig(
        val token: ByteArray,
        val appRandom: ByteArray,
        val deviceRandom: ByteArray,
        val deviceSignOverride: ByteArray? = null,
    ) {
        val cmds = mutableListOf<Pair<Int, ByteArray>>()
        val wears = mutableListOf<Triple<Int, Int, ByteArray>>()
        var now = 1000L

        val driver = HandshakeDriver(
            token16 = token,
            model = "test-model",
            locale = "zh-CN",
            sendCmd = { c, b -> cmds += c to b },
            sendWear = { t, i, b, _ -> wears += Triple(t, i, b) },
            now = { now },
            random = { appRandom },
        )

        /** 设备侧按协议算出正确的 deviceSign。 */
        fun correctDeviceSign(): ByteArray {
            val k = SessionKeys.derive(token, appRandom, deviceRandom)
            return SessionKeys.expectedDeviceSign(k.deviceKey, deviceRandom, appRandom)
        }

        fun feedDeviceVerify(sign: ByteArray = correctDeviceSign()) {
            val frame = HandshakeDriver.buildDeviceVerifyForTest(deviceRandom, sign)
            driver.onPacket(assertNotNull(WearCodec.parse(frame)))
        }
    }

    @Test
    fun `完整三步握手成功`() {
        val r = Rig(token, appRandom, deviceRandom)

        r.driver.start()
        assertEquals(HandshakeDriver.State.NEGOTIATING, r.driver.state)
        assertEquals(SarConfig.CMD_L1START_REQ, r.cmds.single().first)
        assertEquals(21, r.cmds.single().second.size, "协商配置 21 字节")

        r.driver.onNegotiated()
        assertEquals(HandshakeDriver.State.VERIFYING, r.driver.state)

        // ② AppVerify：type=1 id=26，payload = Account{f30{f1=appRandom}}
        val verify = r.wears.single()
        assertEquals(HandshakeDriver.ACCOUNT_TYPE, verify.first)
        assertEquals(HandshakeDriver.ID_APP_VERIFY, verify.second)
        val account = Pb.fields(verify.third)
        val inner = assertNotNull(account[30], "字段号必须是 30（0xFA 0x01 两字节 varint）")
        assertTrue(appRandom.contentEquals(Pb.fields(inner)[1]!!))

        // ③ 设备回 DeviceVerify
        r.feedDeviceVerify()
        assertEquals(HandshakeDriver.State.CONFIRMING, r.driver.state)
        assertEquals(2, r.wears.size, "应已发出 AppVerify + AppConfirm")

        // ④ AppConfirm：type=1 id=27，f32{f1=appSign, f2=CCM}
        val confirm = r.wears[1]
        assertEquals(HandshakeDriver.ID_APP_CONFIRM, confirm.second)
        val cf = Pb.fields(assertNotNull(Pb.fields(confirm.third)[32]))
        assertEquals(32, cf[1]!!.size, "appSign 32 字节")
        assertTrue(cf[2]!!.size > 4, "加密后的 CompanionDevice 至少含 4 字节 tag")

        // 设备侧能解出并验签
        val k = assertNotNull(r.driver.keys)
        val sign = cf[1]!!
        assertTrue(k.verifyDevice(deviceRandom, appRandom, r.correctDeviceSign()))
        assertTrue(sign.contentEquals(k.signApp(appRandom, deviceRandom)))

        val companion = Crypto.ccmDecrypt(k.appKey, k.companionNonce(), cf[2]!!)
        val comp = Pb.fields(companion)
        assertEquals(0L, Pb.varintValue(comp[1]!!))
        assertEquals("test-model", String(comp[3]!!))
        assertEquals("zh-CN", String(comp[5]!!))

        // ⑤ DeviceConfirm —— 真机用的是 id=27（与 AppConfirm 同一个 id）
        r.driver.onPacket(assertNotNull(WearCodec.parse(WearCodec.build(1, HandshakeDriver.ID_DEVICE_CONFIRM))))
        assertEquals(HandshakeDriver.State.DONE, r.driver.state)
        assertTrue(r.driver.isDone)
        assertNull(r.driver.failure)
    }

    @Test
    fun `DeviceConfirm 必须是 id=27 - id=31 不算完成`() {
        // 回归锁：曾把 ID_DEVICE_CONFIRM 写成 31，真机于是「确认到了却看不见」，
        // 表现为卡满 15 s 后报「等待 DeviceConfirm 超时」。
        val r = Rig(token, appRandom, deviceRandom)
        r.driver.start()
        r.driver.onNegotiated()
        r.feedDeviceVerify()

        assertEquals(HandshakeDriver.State.CONFIRMING, r.driver.state)
        r.driver.onPacket(assertNotNull(WearCodec.parse(WearCodec.build(1, 31))))
        assertEquals(
            HandshakeDriver.State.CONFIRMING,
            r.driver.state,
            "id=31 不是 DeviceConfirm，不能据此宣布握手完成",
        )

        r.driver.onPacket(assertNotNull(WearCodec.parse(WearCodec.build(1, 27))))
        assertEquals(HandshakeDriver.State.DONE, r.driver.state, "id=27 才是设备确认")
    }

    @Test
    fun `token 不匹配时在 DeviceVerify 处失败`() {
        val r = Rig(token, appRandom, deviceRandom)
        r.driver.start()
        r.driver.onNegotiated()
        r.feedDeviceVerify(sign = ByteArray(32) { 0x42 })      // 伪造签名

        assertEquals(HandshakeDriver.State.FAILED, r.driver.state)
        assertEquals(HandshakeDriver.Failure.BadDeviceSign, r.driver.failure)
        assertNull(r.driver.keys, "失败后不得残留可用会话密钥")
        assertEquals(1, r.wears.size, "失败后不得继续发 AppConfirm")
    }

    @Test
    fun `DeviceVerify 缺失或字段不全时不崩且判失败`() {
        val cases = listOf(
            // 空 payload
            WearCodec.build(1, 26),
            // 有 f31 但缺 f2（deviceSign）
            WearCodec.build(1, 26, run {
                val inner = java.io.ByteArrayOutputStream().also { Pb.bytes(it, 1, deviceRandom) }.toByteArray()
                java.io.ByteArrayOutputStream().also { Pb.bytes(it, 31, inner) }.toByteArray()
            }),
            // f1 长度不对
            HandshakeDriver.buildDeviceVerifyForTest(ByteArray(8), ByteArray(32)),
        )
        for (frame in cases) {
            val r = Rig(token, appRandom, deviceRandom)
            r.driver.start()
            r.driver.onNegotiated()
            r.driver.onPacket(assertNotNull(WearCodec.parse(frame)))
            assertEquals(HandshakeDriver.State.FAILED, r.driver.state)
            assertEquals(HandshakeDriver.Failure.NoDeviceVerify, r.driver.failure)
        }
    }

    @Test
    fun `对抗用例 - 设备在 f31 里插入新字段时仍能正确解析`() {
        // 真正的失效模式不是「签名里出现 FA 01」（那时首字节的 tag 仍是 f31，
        // 旧实现照样能找到），而是**设备在新固件里往 f31 插了一个我们没见过的字段**：
        // 旧实现的固定偏移 2/18/20/52 会整体错位，把别的字节当成 deviceSign。
        // 结构化解析按字段号取，天然免疫。
        val k = SessionKeys.derive(token, appRandom, deviceRandom)
        val goodSign = SessionKeys.expectedDeviceSign(k.deviceKey, deviceRandom, appRandom)
        val signWithMarker = goodSign.copyOf().also {
            it[8] = 0xFA.toByte(); it[9] = 0x01      // 顺便埋一个 FA 01
        }

        // f31 { f1 = deviceRandom, f3 = <未知 8 字节>, f2 = deviceSign }
        val inner = java.io.ByteArrayOutputStream().also {
            Pb.bytes(it, 1, deviceRandom)
            Pb.bytes(it, 3, byteArrayOf(0xFA.toByte(), 0x01, 2, 3, 4, 5, 6, 7))
            Pb.bytes(it, 2, signWithMarker)
        }.toByteArray()
        val account = java.io.ByteArrayOutputStream().also { Pb.bytes(it, 31, inner) }.toByteArray()
        val frame = WearCodec.build(HandshakeDriver.ACCOUNT_TYPE, HandshakeDriver.ID_DEVICE_VERIFY, account)

        val r = Rig(token, appRandom, deviceRandom)
        r.driver.start()
        r.driver.onNegotiated()
        r.driver.onPacket(assertNotNull(WearCodec.parse(frame)))

        assertTrue(
            r.driver.deviceRandom.contentEquals(deviceRandom),
            "deviceRandom 必须按字段号取到",
        )
        assertEquals(
            HandshakeDriver.Failure.BadDeviceSign, r.driver.failure,
            "签名是伪造的 ⇒ 应恰好失败在验签；若失败在「解析不出报文」说明仍依赖字节模式",
        )
    }

    @Test
    fun `对抗用例 - 未知字段不影响成功路径`() {
        val k = SessionKeys.derive(token, appRandom, deviceRandom)
        val goodSign = SessionKeys.expectedDeviceSign(k.deviceKey, deviceRandom, appRandom)

        val inner = java.io.ByteArrayOutputStream().also {
            Pb.bytes(it, 1, deviceRandom)
            Pb.bytes(it, 3, byteArrayOf(9, 9, 9))        // 未知字段插在中间
            Pb.bytes(it, 2, goodSign)
        }.toByteArray()
        val account = java.io.ByteArrayOutputStream().also { Pb.bytes(it, 31, inner) }.toByteArray()
        val frame = WearCodec.build(HandshakeDriver.ACCOUNT_TYPE, HandshakeDriver.ID_DEVICE_VERIFY, account)

        val r = Rig(token, appRandom, deviceRandom)
        r.driver.start()
        r.driver.onNegotiated()
        r.driver.onPacket(assertNotNull(WearCodec.parse(frame)))

        assertEquals(HandshakeDriver.State.CONFIRMING, r.driver.state, "插入未知字段不应影响握手")
        assertNull(r.driver.failure)
    }

    @Test
    fun `DeviceConfirm 之前到达的其它报文被忽略`() {
        val r = Rig(token, appRandom, deviceRandom)
        r.driver.start()
        r.driver.onNegotiated()
        r.driver.onPacket(assertNotNull(WearCodec.parse(WearCodec.build(2, 79, byteArrayOf(1)))))
        assertEquals(HandshakeDriver.State.VERIFYING, r.driver.state, "控制面报文不得推进握手状态")
    }

    @Test
    fun `协商超时判失败`() {
        val r = Rig(token, appRandom, deviceRandom)
        r.driver.start()
        r.driver.tick(1000 + 9_999)
        assertEquals(HandshakeDriver.State.NEGOTIATING, r.driver.state)
        r.driver.tick(1000 + 10_001)
        assertEquals(HandshakeDriver.State.FAILED, r.driver.state)
        assertEquals(HandshakeDriver.Failure.NegotiateTimeout, r.driver.failure)
    }

    @Test
    fun `AppVerify 无响应超时判失败`() {
        val r = Rig(token, appRandom, deviceRandom)
        r.driver.start()
        r.driver.onNegotiated()
        r.driver.tick(1000 + 14_999)
        assertEquals(HandshakeDriver.State.VERIFYING, r.driver.state)
        r.driver.tick(1000 + 15_001)
        assertEquals(HandshakeDriver.Failure.VerifyTimeout, r.driver.failure)
    }

    @Test
    fun `DeviceConfirm 无响应超时判失败`() {
        val r = Rig(token, appRandom, deviceRandom)
        r.driver.start()
        r.driver.onNegotiated()
        r.feedDeviceVerify()
        r.driver.tick(1000 + 15_001)
        assertEquals(HandshakeDriver.Failure.ConfirmTimeout, r.driver.failure)
    }

    @Test
    fun `重复 start 与重复 onNegotiated 不产生额外报文`() {
        val r = Rig(token, appRandom, deviceRandom)
        r.driver.start()
        r.driver.start()
        assertEquals(1, r.cmds.size, "重复 start 不得重发协商")

        r.driver.onNegotiated()
        r.driver.onNegotiated()
        assertEquals(1, r.wears.size, "重复 onNegotiated 不得重发 AppVerify")
    }

    @Test
    fun `token 长度错误在派生阶段被判失败而不是崩`() {
        val r = Rig(ByteArray(8), appRandom, deviceRandom)
        r.driver.start()
        r.driver.onNegotiated()
        // 这里不能调 correctDeviceSign()（它自己就会因 token 长度抛异常）
        r.feedDeviceVerify(sign = ByteArray(32) { 0x33 })
        assertEquals(HandshakeDriver.State.FAILED, r.driver.state)
        assertTrue(r.driver.failure is HandshakeDriver.Failure.Error)
    }
}
