package io.github.slchy.glasses2grok.core.sar

import io.github.slchy.glasses2grok.core.Hex
import io.github.slchy.glasses2grok.core.l1.L1
import io.github.slchy.glasses2grok.core.l1.L1Codec
import io.github.slchy.glasses2grok.core.l1.L1Frame
import io.github.slchy.glasses2grok.core.l1.L1Parser
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 黄金向量覆盖不到的语义。每条都对应一个「不写就会错」的点。
 */
class SarEngineTest {

    // ---------------------------------------------------------------- 夹具

    /** 参考实现协商包（MPS 64512 / TX_WIN 32 / SEND_TIMEOUT 10000），与向量同源。 */
    private val configFrame = Hex.decode(
        "a5a5020016006e580201030001000002020000fc03020020000402001027",
    )

    /** 设备真实协商值：MPS 32768 / TX_WIN **3**。 */
    private val deviceConfigFrame: ByteArray = run {
        val body = byteArrayOf(
            2,                                              // CMD_L1START_RSP
            1, 3, 0, 1, 0, 0,                               // VERSION
            2, 2, 0, 0x00, 0x80.toByte(),                   // MPS 32768
            3, 2, 0, 0x03, 0x00,                            // TX_WIN 3
            4, 2, 0, 0x10, 0x27,                            // SEND_TIMEOUT
        )
        L1Codec.build(L1.TYPE_CMD, seq = 0, payload = body)
    }

    private class Rig(
        val config: SarConfig = SarConfig.VALID,
        startMs: Long = 1000L,
    ) {
        val out = ByteArrayOutputStream()
        val clock = Clock.Fake(startMs)
        val errors = mutableListOf<String>()
        val parser = L1Parser()
        val engine = SarEngine({ out.write(it) }, clock, config).also {
            it.onError = { m -> errors += m }
        }

        /**
         * 取走**自上次调用以来**新写出的字节并解帧。
         *
         * 必须 reset：早期版本把整段 out 反复喂进同一个 parser，
         * 于是第二次调用会把老帧再解一遍 —— 三个测试同时假失败。
         */
        fun drain(): List<L1Frame> {
            val bytes = out.toByteArray()
            out.reset()
            parser.feed(bytes)
            val list = mutableListOf<L1Frame>()
            while (true) list += parser.next() ?: break
            return list
        }

        /** 只取字节不解帧（用于「不该有输出」的断言）。 */
        fun take(): ByteArray = out.toByteArray().also { out.reset() }
    }

    // ---------------------------------------------------------------- 前置检查

    @Test
    fun `协商完成前 DATA 只排队不发出`() {
        val r = Rig()
        r.engine.sendData(1, 2, byteArrayOf(0x01))
        assertTrue(r.out.size() == 0, "协商未完成不得发出 DATA")
        assertEquals(1, r.engine.pending)
    }

    @Test
    fun `协商完成前的 CMD 可以发出`() {
        val r = Rig()
        r.engine.sendCmd(SarConfig.CMD_L1START_REQ)
        val f = r.drain().single()
        assertEquals(L1.TYPE_CMD, f.type)
        assertEquals(0, f.seq)
        assertEquals(SarConfig.CMD_L1START_REQ, f.payload[0].toInt())
    }

    @Test
    fun `空 payload 被丢弃且不产生任何字节`() {
        val r = Rig()
        r.engine.feed(configFrame)
        val item = r.engine.sendData(1, 2, ByteArray(0))
        assertNull(item)
        assertEquals(0, r.out.size(), "空 payload 不得产生帧（参考实现直接丢弃）")
        assertTrue(r.errors.any { it.contains("payload 为空") })
    }

    @Test
    fun `超过 MPS 的回 -3 且不拆分`() {
        val r = Rig()
        r.engine.feed(deviceConfigFrame)          // MPS = 32768
        var code = 0
        val item = r.engine.sendData(3, 2, ByteArray(32_767), onDone = { code = it })
        assertNull(item)
        assertEquals(SarResult.EXCEEDS_MPS, code)
        assertEquals(0, r.out.size(), "MPS 是单帧上限，不是分段大小 —— 绝不拆分")
    }

    @Test
    fun `恰好等于 MPS 的载荷可以发出`() {
        val r = Rig()
        r.engine.feed(deviceConfigFrame)          // MPS = 32768
        val item = r.engine.sendData(3, 2, ByteArray(32_766), onDone = { })  // +2 = 32768
        assertNotNull(item)
        assertEquals(1, r.drain().size)
    }

    // ---------------------------------------------------------------- 窗口策略

    @Test
    fun `Negotiated 策略把在途帧压到协商窗口 3`() {
        val r = Rig(config = SarConfig(txWindowPolicy = TxWindowPolicy.Negotiated))
        r.engine.feed(deviceConfigFrame)
        repeat(10) { r.engine.sendData(3, 2, byteArrayOf(it.toByte())) }

        assertEquals(3, r.drain().size, "在途不得超窗 ⇒ NAK 路径永不触发")
        assertEquals(10, r.engine.pending)

        // 确认 1 帧 → 只补发 1 帧，在途仍 ≤ 3
        r.engine.feed(L1Codec.buildAck(0, ack = true))
        assertEquals(1, r.drain().size)
    }

    @Test
    fun `Local 策略忽略协商窗口 - 与参考实现一致`() {
        val r = Rig(config = SarConfig(txWindowPolicy = TxWindowPolicy.Local(32)))
        r.engine.feed(deviceConfigFrame)          // 设备说 3
        repeat(10) { r.engine.sendData(3, 2, byteArrayOf(it.toByte())) }
        assertEquals(10, r.drain().size, "Local(32) 不看协商值，10 帧全部发出")
    }

    // ---------------------------------------------------------------- 累积 ACK

    @Test
    fun `累积 ACK 定时器首帧启动后不重置`() {
        val r = Rig()
        r.engine.feed(configFrame)                // TX_WIN 32 ⇒ 阈值 21
        val wire = { seq: Int, ch: Int ->
            L1Codec.build(L1.TYPE_DATA, seq = seq, payload = byteArrayOf(ch.toByte(), 2, 0))
        }
        r.drain()                               // 丢掉 config 之后可能产生的字节（这里没有）

        r.engine.feed(wire(0, 3))                 // 第 1 帧：启动 500 ms 定时器
        r.clock.advance(300)
        r.engine.feed(wire(1, 3))                 // 第 2 帧：**不应**重置截止时间
        r.clock.advance(199)                      // 累计 499 ms：还未到
        r.engine.tick()
        assertEquals(0, r.out.size(), "定时器被重置过就会在这里出错")

        r.clock.advance(2)                        // 累计 501 ms
        r.engine.tick()
        val acks = r.drain()
        assertEquals(1, acks.size, "到期应确认**最新**序号")
        assertEquals(1, acks[0].seq)
    }

    @Test
    fun `累积计数达到窗口三分之二时立即 ACK`() {
        val r = Rig()
        r.engine.feed(deviceConfigFrame)          // TX_WIN 3 ⇒ 阈值 2
        val wire = { seq: Int, ch: Int ->
            L1Codec.build(L1.TYPE_DATA, seq = seq, payload = byteArrayOf(ch.toByte(), 2, 0))
        }
        r.drain()

        r.engine.feed(wire(0, 3))                 // 计数 0 < 2 ⇒ 累积
        assertEquals(0, r.out.size())
        r.engine.feed(wire(1, 3))                 // 计数 1 < 2 ⇒ 累积
        assertEquals(0, r.out.size())
        r.engine.feed(wire(2, 3))                 // 计数 2 ≥ 2 ⇒ **立即** ACK
        val acks = r.drain()
        assertEquals(1, acks.size)
        assertEquals(2, acks[0].seq, "确认的是当前帧的 seq")
    }

    // ---------------------------------------------------------------- 乱序 / NAK

    @Test
    fun `乱序帧回 NAK 且 seq 填期望值而不是收到的值`() {
        val r = Rig()
        r.engine.feed(configFrame)
        r.drain()

        r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = 0, payload = byteArrayOf(1, 2, 0)))
        r.drain()                               // PB 通道 → 立即 ACK
        r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = 5, payload = byteArrayOf(1, 2, 0)))

        val f = r.drain().single()
        assertEquals(L1.TYPE_NAK, f.type)
        assertEquals(1, f.seq, "NAK 的 seq 是「接收方期望值」= 1，不是收到的 5")
    }

    @Test
    fun `差值大于等于 128 的旧帧被静默忽略`() {
        val r = Rig()
        r.engine.feed(configFrame)
        r.drain()

        // 先把 rxExpect 推到 10；PB 通道每帧都回 ACK，这里把它们排空
        for (i in 0 until 10) {
            r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = i, payload = byteArrayOf(1, 2, 0)))
        }
        val acksBefore = r.drain().size
        assertEquals(10, acksBefore, "PB 通道每帧立即 ACK")

        // seq=200 → (200-10)&0xFF = 190 ≥ 128 ⇒ 旧帧，静默忽略
        r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = 200, payload = byteArrayOf(1, 2, 0)))
        assertEquals(0, r.drain().size, "旧帧不得触发任何回包")
    }

    @Test
    fun `rxFrameCount 只统计交付出去的 DATA 帧`() {
        val r = Rig()
        r.engine.feed(configFrame)
        r.drain()

        for (i in 0 until 3) {
            r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = i, payload = byteArrayOf(1, 2, 0)))
        }
        assertEquals(3L, r.engine.rxFrameCount, "三帧按序交付，应计 3")

        // 乱序帧只回 NAK、不交付 ⇒ 不计数（否则仪表盘会把丢包读成「在收数据」）
        r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = 9, payload = byteArrayOf(1, 2, 0)))
        assertEquals(3L, r.engine.rxFrameCount, "被 NAK 掉的帧不算交付")
        assertEquals(1L, r.engine.nakSentCount)
    }

    @Test
    fun `frx 为 1 时强制采纳对端序号`() {
        val r = Rig()
        r.engine.feed(configFrame)
        val delivered = mutableListOf<ByteArray>()
        r.engine.onDeliver = { delivered += it }

        r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = 0, payload = byteArrayOf(1, 2, 0)))
        // 跳一大步并置 frx=1：直接重同步到 100
        r.engine.feed(L1Codec.build(L1.TYPE_DATA, frx = 1, seq = 100, payload = byteArrayOf(1, 2, 3)))
        // 之后 seq=101 必须被接受（说明 rxExpect 已被强制成 101）
        r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = 101, payload = byteArrayOf(1, 2, 4)))

        assertEquals(3, delivered.size, "三帧都应交付，且不产生 NAK")
        assertEquals(3, delivered[1].size)
    }

    // ---------------------------------------------------------------- 超时 / 重传

    @Test
    fun `十秒超时触发 Go-Back-N 全量重传`() {
        val r = Rig()
        r.engine.feed(configFrame)
        r.engine.sendData(1, 2, byteArrayOf(0x0A))
        r.engine.sendData(1, 2, byteArrayOf(0x0B))
        val first = r.drain()
        assertEquals(2, first.size)

        r.clock.advance(9_999)
        r.engine.tick()
        assertEquals(0, r.out.size(), "未到 10 s 不得重传")

        r.clock.advance(2)
        r.engine.tick()
        val again = r.drain()
        assertEquals(2, again.size, "Go-Back-N：从超时帧起全部未确认帧都重传")
        assertEquals(first.map { it.seq }, again.map { it.seq })
        assertEquals(1, r.engine.timeoutCount)
        assertEquals(2, r.engine.retransCount)
    }

    @Test
    fun `重传不消耗新序号`() {
        val r = Rig()
        r.engine.feed(configFrame)
        r.engine.sendData(1, 2, byteArrayOf(1))
        r.clock.advance(10_001)
        r.engine.tick()
        r.engine.sendData(1, 2, byteArrayOf(2))

        val frames = r.drain()
        assertEquals(listOf(0, 0, 1), frames.map { it.seq }, "重传沿用原 seq，新帧才取新号")
    }

    @Test
    fun `ACK 按累积确认推进 txBase`() {
        val r = Rig()
        r.engine.feed(configFrame)
        repeat(3) { r.engine.sendData(1, 2, byteArrayOf(it.toByte())) }
        r.drain()

        r.engine.feed(L1Codec.buildAck(2, ack = true))    // 确认到 seq=2
        assertEquals(3, r.engine.ackedCount)
        assertEquals(0, r.engine.pending)
    }

    // ---------------------------------------------------------------- 复位 / 协商

    @Test
    fun `reset 把 isCmdExchanged 归零 - 重连必须重做协商`() {
        val r = Rig()
        r.engine.feed(configFrame)
        assertTrue(r.engine.isCmdExchanged)

        r.engine.reset()
        assertTrue(!r.engine.isCmdExchanged, "重连后必须重做三步协商")

        r.engine.sendData(1, 2, byteArrayOf(1))
        assertEquals(0, r.out.size(), "复位后 DATA 必须重新被门槛挡住")
    }

    @Test
    fun `协商配置载荷与黄金向量逐字节一致`() {
        // HandshakeDriver 与 SarEngine 现在共用 buildNegotiationConfig()，
        // 这条把共享实现的字节钉在参考实现协商包上（向量见 configFrame）。
        assertEquals(
            "01030001000002020000fc03020020000402001027",
            Hex.encode(buildNegotiationConfig()),
            "TLV 漂移会同时打坏真机协商",
        )
        // 黄金包 = 8 字节 L1 头 + 1 字节 CMD + 21 字节配置；尾部必须逐字节相同
        assertEquals(
            Hex.encode(buildNegotiationConfig()),
            Hex.encode(configFrame.copyOfRange(9, configFrame.size)),
            "黄金包的配置段与共享实现不一致",
        )
    }

    @Test
    fun `收到 L1START_REQ 会回 RSP 并携带协商配置`() {
        val r = Rig()
        val req = L1Codec.build(
            L1.TYPE_CMD, seq = 0,
            payload = byteArrayOf(SarConfig.CMD_L1START_REQ.toByte()) + r.engine.buildConfig(),
        )
        r.engine.feed(req)
        val f = r.drain().single()
        assertEquals(L1.TYPE_CMD, f.type)
        assertEquals(SarConfig.CMD_L1START_RSP, f.payload[0].toInt())
        assertEquals(21, f.payload.size - 1, "配置段 21 字节：4 个 TLV")
    }

    @Test
    fun `协商配置解析出设备真实值 MPS 32768 与 TX_WIN 3`() {
        val r = Rig()
        r.engine.feed(deviceConfigFrame)
        assertEquals(32_768, r.engine.remoteMps)
        assertEquals(3, r.engine.remoteTxWindow)
        assertEquals(2, txWinAckThr(r.engine.remoteTxWindow))
    }

    @Test
    fun `配置里出现未知 TLV 时不崩且已知项仍生效`() {
        val r = Rig()
        val body = byteArrayOf(
            2,
            2, 2, 0, 0x00, 0x80.toByte(),        // MPS 32768
            9, 1, 0, 0x7F,                        // 未知类型 9
            3, 2, 0, 0x03, 0x00,                  // TX_WIN 3
        )
        r.engine.feed(L1Codec.build(L1.TYPE_CMD, seq = 0, payload = body))
        assertEquals(32_768, r.engine.remoteMps)
        assertEquals(3, r.engine.remoteTxWindow)
    }

    @Test
    fun `NETWORK 通道收到 DATA 不回 ACK`() {
        val r = Rig()
        r.engine.feed(configFrame)
        r.drain()
        r.engine.feed(
            L1Codec.build(
                L1.TYPE_DATA, seq = 0,
                payload = byteArrayOf(io.github.slchy.glasses2grok.core.l2.L2.CH_NETWORK.toByte(), 2, 0),
            ),
        )
        assertEquals(0, r.out.size(), "channel 7 双向都不 ACK")
    }

    @Test
    fun `不 ACK 的 DATA 帧会被计数，尤其是正好压在 rxExpect 上的`() {
        // 观测项（不改行为）：参考实现同样不 ACK、不推进 rxExpect。但如果设备用这种帧占了
        // 序号，下一帧正常数据就会被判乱序。没有数据就先不动协议，只留计数。
        val r = Rig()
        r.engine.feed(configFrame)
        r.drain()
        assertEquals(0L, r.engine.noAckDataFrames, "初始为 0")

        val ch7 = byteArrayOf(io.github.slchy.glasses2grok.core.l2.L2.CH_NETWORK.toByte(), 2, 0)
        r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = 0, payload = ch7))
        assertEquals(1L, r.engine.noAckDataFrames)
        assertEquals(1L, r.engine.noAckDataOnExpectedSeq, "seq 0 正是当时的 rxExpect")

        // 同一帧重放：rxExpect 没被推进，所以它仍然压在期望序号上
        r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = 0, payload = ch7))
        assertEquals(2L, r.engine.noAckDataFrames)
        assertEquals(2L, r.engine.noAckDataOnExpectedSeq)

        // 换一个不在期望上的序号：只增加总数，不算「压在 rxExpect 上」
        r.engine.feed(L1Codec.build(L1.TYPE_DATA, seq = 5, payload = ch7))
        assertEquals(3L, r.engine.noAckDataFrames)
        assertEquals(2L, r.engine.noAckDataOnExpectedSeq)
    }

    @Test
    fun `带 frx 的 DATA 帧不算不 ACK 帧`() {
        val r = Rig()
        r.engine.feed(configFrame)
        r.drain()
        r.engine.feed(
            L1Codec.build(
                L1.TYPE_DATA, seq = 0,
                payload = byteArrayOf(io.github.slchy.glasses2grok.core.l2.L2.CH_NETWORK.toByte(), 2, 0),
                frx = 1,
            ),
        )
        assertEquals(0L, r.engine.noAckDataFrames, "frx=1 走重同步分支，由它推进 rxExpect")
    }
}
