package com.miaiglasses.core.session

import com.miaiglasses.core.Hex
import com.miaiglasses.core.config.GlassesOptions
import com.miaiglasses.core.crypto.Crypto
import com.miaiglasses.core.crypto.SessionKeys
import com.miaiglasses.core.l1.L1
import com.miaiglasses.core.l1.L1Codec
import com.miaiglasses.core.l2.L2
import com.miaiglasses.core.l2.L2Codec
import com.miaiglasses.core.mass.MassCodec
import com.miaiglasses.core.mass.MassType
import com.miaiglasses.core.pb.Pb
import com.miaiglasses.core.sar.Clock
import com.miaiglasses.core.sar.SarConfig
import com.miaiglasses.core.sar.SarEngine
import com.miaiglasses.core.wear.WearCodec
import com.miaiglasses.core.media.RecordAction
import com.miaiglasses.core.media.RecordType
import com.miaiglasses.core.wear.WearPacket
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 会话层端到端：用一台**假设备**（同样的协议原语搭出来）跑完整链路
 * `L1 → SAR → L2 → 解密 → Wear → 控制面 / MASS`。
 *
 * 这里验的是**编排正确性**（状态推进、加解密方向、分流、重连语义），
 * 不是协议本身 —— 后者已由各层自己的黄金向量负责。
 */
class SessionEngineTest {

    private val token = Hex.decode("00112233445566778899aabbccddeeff")

    /** 假设备：用自己的 SarEngine 收发，行为按真实设备实现。 */
    private class FakeDevice(val token: ByteArray, val clock: Clock) {
        val toEngine = ByteArrayOutputStream()
        val fromEngine = ByteArrayOutputStream()
        var keys: SessionKeys? = null
        var lastWear: WearPacket? = null
        val receivedWears = mutableListOf<WearPacket>()
        var appRandomSeen: ByteArray = ByteArray(0)
        val deviceRandom = ByteArray(16) { (0x40 + it).toByte() }
        var replyToAppVerify = true
        var replyToAppConfirm = true

        val sar = SarEngine({ toEngine.write(it) }, clock, SarConfig.VALID)

        init {
            sar.onDeliver = { l2 -> handle(l2) }
            // 扮演被叫方：真实设备在回完 L1START_RSP 后自己就算协商完成。
            // 本端 SarEngine 的 isCmdExchanged 只由「收到 RSP」置位（客户端视角），
            // 所以假设备需要显式标记，否则 DATA 会被 runNext 的门槛挡住。
            sar.markNegotiated()
        }

        fun engineWrote(bytes: ByteArray) {
            fromEngine.write(bytes)
            sar.feed(bytes)
        }

        /**
         * 模拟链路重建。
         *
         * 真机上 L1 协商本身就把两端的 DATA 序列窗口归零（实测）。所以假设备也要 reset，
         * 否则它会用旧 seq 发 DeviceVerify，而引擎的接收期望已经从 0 重新开始。
         */
        fun restartForReconnect() {
            keys = null
            appRandomSeen = ByteArray(0)
            receivedWears.clear()
            sar.reset()
            sar.markNegotiated() // 设备侧回复完 RSP 即视为协商完成
        }

        private fun handle(l2Raw: ByteArray) {
            val l2 = L2Codec.parse(l2Raw) ?: return
            // 手机 → 设备方向用 appKey（设备 → 手机才用 deviceKey）
            val plain = if (l2.opCode == L2.OP_WRITE_ENC) Crypto.ctr(keys!!.appKey, l2.body) else l2.body
            val p = WearCodec.parse(plain) ?: return
            receivedWears += p
            lastWear = p

            when {
                p.type == 1 && p.id == 26 && replyToAppVerify -> onAppVerify(p)
                p.type == 1 && p.id == 27 && replyToAppConfirm -> onAppConfirm(p)
            }
        }

        private fun onAppVerify(p: WearPacket) {
            val account = Pb.fields(p.payload)
            val inner = Pb.fields(account[30] ?: return)
            appRandomSeen = inner[1] ?: return

            val k = SessionKeys.derive(token, appRandomSeen, deviceRandom)
            keys = k
            val sign = SessionKeys.expectedDeviceSign(k.deviceKey, deviceRandom, appRandomSeen)

            val verifyInner = ByteArrayOutputStream().also {
                Pb.bytes(it, 1, deviceRandom)
                Pb.bytes(it, 2, sign)
            }.toByteArray()
            val accountOut = ByteArrayOutputStream().also { Pb.bytes(it, 31, verifyInner) }.toByteArray()
            sendWear(WearCodec.build(1, 26, accountOut))
        }

        private fun onAppConfirm(p: WearPacket) {
            val account = Pb.fields(p.payload)
            val inner = Pb.fields(account[32] ?: return)
            val sign = inner[1] ?: return
            val k = keys ?: return
            // 设备侧验签：appSign = HMAC(appKey, appRandom‖deviceRandom)
            val expect = k.signApp(appRandomSeen, deviceRandom)
            if (!expect.contentEquals(sign)) return
            // 真机用 id=27 回 DeviceConfirm（与 AppConfirm 同一个 id）
            sendWear(WearCodec.build(1, 27))
        }

        fun sendWear(bytes: ByteArray) {
            // 握手报文全程明文（op=1）—— 加密从 DeviceConfirm 之后才生效
            sar.sendData(L2.CH_PB, L2.OP_WRITE, bytes)
        }

        /** 以设备身份发一条加密的 WearPacket（`deviceKey` 方向，握手完成后用）。 */
        fun sendEncrypted(type: Int, id: Int, body: ByteArray) {
            val wear = WearCodec.build(type, id, body)
            sar.sendData(L2.CH_PB, L2.OP_WRITE_ENC, Crypto.ctr(keys!!.deviceKey, wear))
        }

        /** 以设备身份发一帧加密的 MASS 载荷。 */
        fun sendMass(massBody: ByteArray) {
            sar.sendData(L2.CH_MASS_VOICE, L2.OP_WRITE_ENC, Crypto.ctr(keys!!.deviceKey, massBody))
        }

        fun drainToEngine(): ByteArray = toEngine.toByteArray().also { toEngine.reset() }
    }

    private class Rig(token: ByteArray, engineToken: ByteArray = token) {
        val clock = Clock.Fake(1000)
        val events = mutableListOf<GlassesEvent>()
        val dev = FakeDevice(token, clock)
        var closed = false

        val engine = SessionEngine(
            transport = object : ByteTransport {
                override fun write(bytes: ByteArray) {
                    dev.engineWrote(bytes)
                }

                override fun close() {
                    closed = true
                }
            },
            clock = clock,
            token16 = engineToken,
            model = "test-model",
            locale = "zh-CN",
            random = { ByteArray(16) { (0x20 + it).toByte() } },
            emit = { events += it },
        )

        /** 把假设备产出的字节搬进引擎、并 tick，直到没有新字节。 */
        fun settle(rounds: Int = 20) {
            var i = 0
            while (i++ < rounds) {
                val toEngine = dev.drainToEngine()
                if (toEngine.isEmpty()) return
                engine.feed(toEngine)
                engine.tick()
            }
            error("settle 未收敛：设备仍在产出字节")
        }

        fun state(): SessionState = engine.state
        fun errors(): List<String> = events.filterIsInstance<GlassesEvent.Error>().map { it.message }
    }

    @Test
    fun `完整会话 - 握手到 READY 并能收发加密报文`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()                     // 触发 L1START_REQ
        r.settle()

        assertEquals(SessionState.READY, r.state(), "握手应完成；错误=${r.errors()}")

        // 引擎侧的 appRandom 必须是它自己生成的
        assertTrue(r.dev.appRandomSeen.contentEquals(ByteArray(16) { (0x20 + it).toByte() }))

        // READY 后发一条 GetWearStatus
        val ok = r.engine.requestWearStatus()
        assertTrue(ok)
        r.settle()

        val status = r.dev.receivedWears.last { it.type == 2 && it.id == 93 }
        assertEquals(0, status.payload.size, "GetWearStatus 请求无参数")
    }

    @Test
    fun `System id=97 - 电池上报解析并抛出 BatteryChanged`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state())

        // 真机原始载荷（2026-11-11）：f63 = Battery{ capacity=58, chargeStatus=0 }
        val raw = com.miaiglasses.core.Hex.decode("fa0304083a1000")
        r.dev.sendEncrypted(2, 97, raw)
        r.settle()

        val ctrl = r.events.filterIsInstance<GlassesEvent.Control>().last()
        assertEquals(97, ctrl.packet.id)
        assertTrue(ctrl.decision.handled, "System id=97 应被处理（之前是 DROPPED_UNVERIFIED）")

        val battery = assertNotNull(
            r.events.filterIsInstance<GlassesEvent.BatteryChanged>().lastOrNull(),
            "应抛出 BatteryChanged",
        )
        assertEquals(58, battery.status.batteryPercent)
        assertEquals(com.miaiglasses.core.query.DeviceStatus.CHARGE_UNKNOWN, battery.status.chargeStatus)
        assertEquals(false, battery.status.isCharging)
    }

    @Test
    fun `System id=88 - 热点回包解析出 SSID 密码网关`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()

        // 真机回包（2026-11-11）：f56 = WiFiAP.Result{ code=0, WiFiAP{ssid, password, gateway} }
        val raw = com.miaiglasses.core.Hex.decode(
            "c2 03 38 08 00 12 34 0a 16 58 69 61 6f 6d 69 20 41 49 20 47 6c 61 73 73 65 73 20 30 30 30 30 " +
                "12 0c 54 65 73 74 50 61 73 73 77 6f 72 64 1a 0c 31 39 32 2e 31 36 38 2e 34 33 2e 31",
        )
        r.dev.sendEncrypted(2, 88, raw)
        r.settle()

        val info = assertNotNull(
            r.events.filterIsInstance<GlassesEvent.WifiApChanged>().lastOrNull(),
            "应抛出 WifiApChanged",
        ).info
        assertTrue(info.isOk, "code 必须是 0")
        assertEquals("Xiaomi AI Glasses 0000", info.ssid)
        assertEquals("TestPassword", info.password)
        assertEquals("192.168.43.1", info.gateway)
    }

    @Test
    fun `热点开关 - id=88 空载荷、id=89 带 ssid-password-gateway`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()

        r.dev.receivedWears.clear()
        assertTrue(r.engine.enableWifiAp())
        val enable = assertNotNull(r.dev.receivedWears.lastOrNull())
        assertEquals(2, enable.type)
        assertEquals(88, enable.id)
        assertEquals(0, enable.payload.size, "EnableWiFiAP 的载荷是空的")

        assertTrue(r.engine.disableWifiAp("GLASSCAP", "glasscap1234", "192.168.43.1"))
        val disable = assertNotNull(r.dev.receivedWears.lastOrNull())
        assertEquals(89, disable.id)
        val ap = Pb.fields(assertNotNull(Pb.fields(disable.payload)[57], "应含 f57 WiFiAP"))
        assertEquals("GLASSCAP", String(assertNotNull(ap[1])))
        assertEquals("glasscap1234", String(assertNotNull(ap[2])))
        assertEquals("192.168.43.1", String(assertNotNull(ap[3])))
    }

    @Test
    fun `设备主动上报控制面 - 路由为 HANDLED`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state())

        // System id=79：佩戴状态上报（System.f49.f2 = 1）
        val f49 = ByteArrayOutputStream().also { Pb.uint(it, 2, 1) }.toByteArray()
        val sysBody = ByteArrayOutputStream().also { Pb.bytes(it, 49, f49) }.toByteArray()
        r.dev.sendEncrypted(2, 79, sysBody)
        r.settle()

        val ctrl = r.events.filterIsInstance<GlassesEvent.Control>().last()
        assertEquals(2, ctrl.packet.type)
        assertEquals(79, ctrl.packet.id)
        assertTrue(ctrl.decision.handled, "System id=79 应被处理")
    }

    @Test
    fun `语音帧按 MassType 流式交付`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()

        val payload = ByteArray(570) { (it and 0xFF).toByte() }
        r.dev.sendMass(MassCodec.buildData(MassType.FOLLOW_VOICE, 0, payload))
        r.settle()

        val audio = r.events.filterIsInstance<GlassesEvent.Audio>()
        assertEquals(1, audio.size)
        assertEquals(570, audio[0].data.size)
        assertTrue(payload.contentEquals(audio[0].data))
    }

    @Test
    fun `语音块带本端序号与到达时刻 - MASS 头里没有这两个字段`() {
        // 宿主做 ASR/AEC 时需要知道「这块是第几块、什么时候到的」。
        // MASS 12 字节头里既没有 seq 也没有时间戳，只能由 SDK 生成 —— 这条钉住它真的生成了。
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()

        repeat(3) { r.dev.sendMass(MassCodec.buildData(MassType.FOLLOW_VOICE, 0, ByteArray(57) { it.toByte() })) }
        r.settle()

        val audio = r.events.filterIsInstance<GlassesEvent.Audio>()
        assertEquals(3, audio.size)
        assertEquals(listOf(0L, 1L, 2L), audio.map { it.seq }, "序号必须从 0 连续自增")
        assertTrue(audio.all { it.timestampMs > 0 }, "时间戳必须是真实时钟值：${audio.map { it.timestampMs }}")
        assertTrue(
            audio.zipWithNext().all { (a, b) -> b.timestampMs >= a.timestampMs },
            "时间戳不得回退：${audio.map { it.timestampMs }}",
        )
        assertTrue(audio.all { it.droppedBefore == 0L }, "引擎侧不该凭空报空洞（那是 transport 补的）")
    }

    @Test
    fun `图片按声明总长拼接后一次性交付`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()

        val jpeg = ByteArray(40) { (it + 1).toByte() }
        r.dev.sendMass(MassCodec.buildData(MassType.FIRST_IMAGE, 40, jpeg.copyOfRange(0, 20)))
        r.settle(4)
        assertTrue(r.events.filterIsInstance<GlassesEvent.Image>().isEmpty(), "未达总长不应交付")

        r.dev.sendMass(MassCodec.buildData(MassType.FOLLOW_IMAGE, 0, jpeg.copyOfRange(20, 40)))
        r.settle(4)

        val images = r.events.filterIsInstance<GlassesEvent.Image>()
        assertEquals(1, images.size)
        assertTrue(jpeg.contentEquals(images[0].jpeg), "拼接结果必须与原始字节一致")
    }

    @Test
    fun `READY 之前发送被拒绝且不产生帧`() {
        val r = Rig(token)
        r.engine.onConnected()
        assertEquals(ErrorCode.NOT_READY, r.engine.sendWearPacket(2, 93, ByteArray(0)))
        assertTrue(r.errors().any { it.contains("未就绪") })
    }

    @Test
    fun `握手失败进入 FAILED 且不残留密钥`() {
        val r = Rig(token)
        r.dev.replyToAppVerify = false
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.HANDSHAKING, r.state())

        r.clock.advance(20_000)
        r.engine.tick()
        assertEquals(SessionState.FAILED, r.state())
        assertTrue(r.errors().any { it.contains("超时") })
        assertEquals(null, r.engine.keys, "失败后不得残留会话密钥")
    }

    @Test
    fun `握手超时用可重试码 208 - 与 token 永久错的 108 分开`() {
        // 两种失败都是终态 FAILED，但语义不同：超时重开一次可能就好，验签失败换多少次都一样。
        // 宿主靠码区分要不要引导用户重新绑定 —— 之前两者共用 TIMEOUT/PROTOCOL 混在一起。
        val r = Rig(token)
        r.dev.replyToAppVerify = false
        r.engine.onConnected()
        r.engine.tick()
        r.clock.advance(20_000)
        r.engine.tick()

        val e = r.events.filterIsInstance<GlassesEvent.Error>().last()
        assertEquals(ErrorCode.HANDSHAKE_TRANSIENT, e.code, "超时必须是 208（可重试），实际 ${e.message}")
        assertEquals(SessionState.FAILED, r.state(), "仍然是终态：不自作主张重连")
    }

    @Test
    fun `断连后必须重做协商 - 状态回 HANDSHAKING`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state())

        r.engine.onDisconnected("socket 关闭")
        assertEquals(SessionState.RECONNECTING, r.state())
        assertEquals(null, r.engine.keys)

        r.dev.restartForReconnect()
        r.engine.onReconnected()
        assertEquals(SessionState.HANDSHAKING, r.state())

        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state(), "重连后应能重新握手：${r.errors()}")
    }

    @Test
    fun `重连路径必须经过 onConnecting 后仍能开始握手`() {
        // 真机断线重连的**真实**调用序列（SessionDriver.supervise）：
        //   onDisconnected -> (门禁通过) onConnecting -> [connectBounded 成功] -> onReconnected
        // 早期只在 onDisconnected 之后直接调 onReconnected，掩盖了「onConnecting 把状态
        // 从 RECONNECTING 推到 CONNECTING 后 onReconnected 变成空操作」这个 bug。
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state())

        r.engine.onDisconnected("链路中断")
        assertEquals(SessionState.RECONNECTING, r.state())

        r.engine.onConnecting()
        assertEquals(SessionState.CONNECTING, r.state())

        r.dev.restartForReconnect()
        r.engine.onReconnected()
        assertEquals(SessionState.HANDSHAKING, r.state(), "重连成功必须立刻开始握手，否则永远停在 CONNECTING")

        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state(), "重连后应能重新握手：${r.errors()}")
    }

    @Test
    fun `READY 上迟到的 onReconnected 不得重开握手`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state())

        r.engine.onReconnected()
        assertEquals(SessionState.READY, r.state(), "已建立的会话不能被迟到的「已连接」打断")
    }

    @Test
    fun `录音开关 - Media id=20 载荷是 RecordRequest`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state())

        r.dev.receivedWears.clear()
        assertTrue(r.engine.requestRecord(RecordType.NORMAL, RecordAction.START), "应发送成功")

        val sent = assertNotNull(r.dev.receivedWears.lastOrNull(), "设备应收到一条 WearPacket")
        assertEquals(18, sent.type, "录音走 Media(18)")
        assertEquals(20, sent.id, "RequestRecord 是 id=20")
        assertEquals("8a010408001000", com.miaiglasses.core.Hex.encode(sent.payload))

        r.dev.receivedWears.clear()
        assertTrue(r.engine.requestRecord(RecordType.NORMAL, RecordAction.STOP))
        val stop = assertNotNull(r.dev.receivedWears.lastOrNull())
        assertEquals("8a010408001001", com.miaiglasses.core.Hex.encode(stop.payload), "停止只差 action")
    }

    @Test
    fun `拍照 - 必须复用 Voice id=10 的两个 ID 发 id=4`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state())
        assertEquals(false, r.engine.canCapturePhoto, "还没收到 OpenDevice 时不该能拍照")

        // 设备下发的 OpenDevice：f11{ f1{ f1=requestId f2=transactionId } f2=kind }
        val reqId = "req-1234567890abcdef1234567890ab".toByteArray()
        val txnId = "txn-1234567890abcdef1234567890ab".toByteArray()
        val inner = ByteArrayOutputStream().also {
            Pb.bytes(it, 1, reqId)
            Pb.bytes(it, 2, txnId)
        }.toByteArray()
        val wake = ByteArrayOutputStream().also {
            Pb.bytes(it, 1, inner)
            Pb.uint(it, 2, 2)
        }.toByteArray()
        val payload = ByteArrayOutputStream().also { Pb.bytes(it, 11, wake) }.toByteArray()
        r.dev.sendEncrypted(14, 10, payload)
        r.settle()
        assertTrue(r.engine.canCapturePhoto, "收到 OpenDevice 后应具备拍照条件")

        r.dev.receivedWears.clear()
        assertTrue(r.engine.capturePhoto(ByteArray(4) { 7 }), "capturePhoto 应发送成功")
        val sent = r.dev.receivedWears.lastOrNull()
        assertNotNull(sent, "设备应收到一条 WearPacket")
        assertEquals(14, sent.type)
        assertEquals(4, sent.id, "拍照必须是 Voice id=4")

        val multiModel = Pb.fields(sent.payload)[4]
        assertNotNull(multiModel, "payload 应含 f4 MultiModel")
        val mm = Pb.fields(multiModel)
        val reqInfo = Pb.fields(mm[1]!!)
        assertEquals(String(reqId), String(reqInfo[1]!!), "f1 必须是设备下发的 requestId")
        assertEquals(String(txnId), String(reqInfo[2]!!), "f2 必须是设备下发的 transactionId")
        assertEquals(1, Pb.varintValue(mm[2]!!).toInt(), "cmd 必须是 1")
    }

    @Test
    fun `拍照 - 没有 id=10 上下文时自造 ID 也能发`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state())
        assertEquals(false, r.engine.canCapturePhoto, "本用例刻意不给 OpenDevice")

        r.dev.receivedWears.clear()
        assertTrue(r.engine.capturePhoto(ByteArray(4) { 7 }), "没有上下文也要能拍照（设备不校验 ID 归属）")
        val sent = assertNotNull(r.dev.receivedWears.lastOrNull(), "设备应收到一条 WearPacket")
        assertEquals(14, sent.type)
        assertEquals(4, sent.id, "拍照必须是 Voice id=4")

        val multiModel = assertNotNull(Pb.fields(sent.payload)[4], "payload 应含 f4 MultiModel")
        val mm = Pb.fields(multiModel)
        val reqInfo = Pb.fields(assertNotNull(mm[1]))
        val reqId = assertNotNull(reqInfo[1], "f1 requestId 必须写（空 payload 也发 tag+00）")
        val txnId = assertNotNull(reqInfo[2], "f2 transactionId 必须写")
        assertEquals(16, reqId.size, "自造 requestId 用 16 字节")
        assertEquals(com.miaiglasses.core.Hex.encode(reqId), String(txnId), "transactionId 是 requestId 的 hex")
        assertEquals(1, Pb.varintValue(assertNotNull(mm[2])).toInt(), "cmd 必须是 1")
    }

    @Test
    fun `断连后拍照上下文失效`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()

        val wake = ByteArrayOutputStream().also {
            val inner = ByteArrayOutputStream().also { i ->
                Pb.bytes(i, 1, "req".toByteArray())
                Pb.bytes(i, 2, "txn".toByteArray())
            }.toByteArray()
            Pb.bytes(it, 1, inner)
            Pb.uint(it, 2, 2)
        }.toByteArray()
        val payload = ByteArrayOutputStream().also { Pb.bytes(it, 11, wake) }.toByteArray()
        r.dev.sendEncrypted(14, 10, payload)
        r.settle()
        assertTrue(r.engine.canCapturePhoto)

        r.engine.onDisconnected("链路中断")
        assertEquals(false, r.engine.canCapturePhoto, "重连后旧 ID 已失效，必须清空")
    }

    @Test
    fun `close 幂等且进入吸收态`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.close()
        r.engine.close()
        assertEquals(SessionState.CLOSED, r.state())
        assertTrue(r.closed, "close 必须关闭传输")
        assertEquals(1, r.events.count { it is GlassesEvent.StateChanged && it.state == SessionState.CLOSED })
    }

    @Test
    fun `握手未完成时收到加密帧不崩并报错`() {
        val r = Rig(token)
        r.engine.onConnected()
        // 直接塞一条加密 MASS 帧（此时无密钥）
        val frame = L1Codec.build(L1.TYPE_DATA, seq = 0, payload = L2Codec.build(L2.CH_MASS_VOICE, L2.OP_WRITE_ENC, ByteArray(20)))
        r.engine.feed(frame)
        assertTrue(r.errors().any { it.contains("握手未完成") })
    }

    @Test
    fun `CRC 坏帧被记为错误但不打断会话`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state())

        val good = L1Codec.build(L1.TYPE_DATA, seq = 0, payload = L2Codec.build(L2.CH_PB, L2.OP_WRITE, WearCodec.build(2, 79, ByteArray(0))))
        val bad = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
        r.engine.feed(bad)

        assertTrue(r.errors().any { it.contains("CRC") })
        assertEquals(SessionState.READY, r.state(), "坏帧不得让会话掉线")
    }

    @Test
    fun `载荷超过远端 MPS 时必须报错而不是假装发出去`() {
        // 回归护栏：sar.sendData 在 payload+2 > MPS 时返回 null，帧根本不会发；
        // 丢掉这个返回值就等于把失败报成成功，上层会看到 Queued。
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state(), "握手应完成；错误=${r.errors()}")

        val before = r.events.size
        val huge = ByteArray(100_000) { 0x41 } // 远超任何 MPS（L1 帧上限才 64 KB）
        assertEquals(ErrorCode.PROTOCOL, r.engine.sendWearPacket(2, 100, huge), "帧没发出去就不能返回成功")
        assertTrue(
            r.events.drop(before).filterIsInstance<GlassesEvent.Error>().any { it.message.contains("MPS") },
            "必须报错说明是载荷过大；实际新增事件=${r.events.drop(before)}",
        )
    }

    @Test
    fun `传输写失败转入 RECONNECTING 而不是静默丢帧`() {
        val clock = Clock.Fake(1000)
        var fail = false
        val events = mutableListOf<GlassesEvent>()
        val engine = SessionEngine(
            transport = object : ByteTransport {
                override fun write(bytes: ByteArray) {
                    if (fail) throw java.io.IOException("broken pipe")
                }
                override fun close() = Unit
            },
            clock = clock,
            token16 = Hex.decode("00112233445566778899aabbccddeeff"),
            model = "test-model",
            locale = "zh-CN",
            random = { ByteArray(16) { 0x20 } },
            emit = { events += it },
        )
        // 上电即断链：首个 L1START_REQ 写不出去。CMD 不重传，所以必须在发送时就暴露失败，
        // 不能靠 tick 兜底。
        fail = true
        engine.onConnected()

        assertEquals(SessionState.RECONNECTING, engine.state)
        assertTrue(events.filterIsInstance<GlassesEvent.Error>().any { it.message.contains("传输失败") })
    }
    // ----------------------------------------------------------------  类型化错误

    @Test
    fun `未 READY 时发命令报 NOT_READY 204`() {
        val r = Rig(token)
        assertEquals(ErrorCode.NOT_READY, r.engine.sendWearPacket(2, 93, ByteArray(0)))
        val e = r.events.filterIsInstance<GlassesEvent.Error>().last()
        assertEquals(ErrorCode.NOT_READY, e.code, "未就绪必须是 204，而不是只有一句字符串")
    }

    @Test
    fun `控制车道塞满时报 QUEUE_FULL 207 而不是静默丢弃`() {
        val r = Rig(token)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()
        assertEquals(SessionState.READY, r.state())

        // 不 settle ⇒ 假设备不回 ACK ⇒ 队列只进不出
        repeat(GlassesOptions.CONTROL_LANE_CAPACITY) {
            assertEquals(ErrorCode.NONE, r.engine.sendWearPacket(2, 93, ByteArray(0)), "第 ${it + 1} 条应受理")
        }
        assertEquals(
            ErrorCode.QUEUE_FULL,
            r.engine.sendWearPacket(2, 93, ByteArray(0)),
            "第 257 条必须被拒，且返回值要说明是车道满（而不是笼统的失败）",
        )

        val e = r.events.filterIsInstance<GlassesEvent.Error>().last()
        assertEquals(ErrorCode.QUEUE_FULL, e.code)
        assertTrue(e.message.contains("control"), "错误信息里要带车道名：${e.message}")
        assertTrue(r.engine.sarSummary.contains("control=${GlassesOptions.CONTROL_LANE_CAPACITY}"), r.engine.sarSummary)
    }

    @Test
    fun `设备签名对不上报 108`() {
        val wrong = ByteArray(16) { 0x5A }
        val r = Rig(token, engineToken = wrong)
        r.engine.onConnected()
        r.engine.tick()
        r.settle()

        assertEquals(SessionState.FAILED, r.state(), "验签失败必须直接终态")
        val e = r.events.filterIsInstance<GlassesEvent.Error>().last()
        assertEquals(ErrorCode.AUTH_FAILED_TOKEN_INVALID, e.code, "验签失败是 token 问题（108）")
    }
}