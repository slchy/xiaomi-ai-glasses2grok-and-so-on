package com.miaiglasses.core.control

import com.miaiglasses.core.Hex
import com.miaiglasses.core.config.GlassesProtocol
import com.miaiglasses.core.wear.WearCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 控制面路由。断言集合与 `原有的 Java 实现`
 * 对齐 —— 两个实现必须给出同一套判定，否则等于有两份「真相」。
 */
class ControlRouterTest {

    private fun outcome(type: Int, id: Int): ControlOutcome =
        ControlRouter.dispatch(type, id, ByteArray(0), null).outcome

    @Test
    fun `Voice 通道 H到P 全部交给宿主`() {
        for (id in listOf(0, 5, 6, 10, 99)) {
            assertEquals(ControlOutcome.HANDLED, outcome(GlassesProtocol.Wear.VOICE, id), "Voice id=$id")
        }
    }

    @Test
    fun `System 只有实测过的 id 被处理`() {
        for (id in listOf(1, 2, 14, 79, 81, 90, 91, 93)) {
            assertEquals(ControlOutcome.HANDLED, outcome(GlassesProtocol.Wear.SYSTEM, id), "System id=$id")
        }
        assertEquals(ControlOutcome.DROPPED_UNVERIFIED, outcome(GlassesProtocol.Wear.SYSTEM, 77))
    }

    @Test
    fun `System id=15 写回执是空 payload 不交给宿主也不报未知`() {
        val got = mutableListOf<String>()
        val sink = object : ControlSink {
            override fun onVoice(id: Int, payload: ByteArray) { got += "voice:$id" }
            override fun onSystem(id: Int, payload: ByteArray) { got += "system:$id" }
            override fun onMedia(id: Int, payload: ByteArray) { got += "media:$id" }
        }
        val d = ControlRouter.dispatch(GlassesProtocol.Wear.SYSTEM, 15, ByteArray(0), sink)
        assertEquals(ControlOutcome.HANDLED, d.outcome, "实测样本已到手，不该再报 DROPPED_UNVERIFIED")
        assertEquals(emptyList(), got, "空回执没有内容可交给宿主")
    }

    @Test
    fun `Media 只有 P1 清单被处理`() {
        for (id in listOf(1, 14, 16, 21)) {
            assertEquals(ControlOutcome.HANDLED, outcome(GlassesProtocol.Wear.MEDIA, id), "Media id=$id")
        }
        assertEquals(ControlOutcome.DROPPED_UNVERIFIED, outcome(GlassesProtocol.Wear.MEDIA, 99))
    }

    @Test
    fun `三个禁止通道硬拒`() {
        assertEquals(ControlOutcome.DROPPED_FORBIDDEN, outcome(GlassesProtocol.Wear.PAYSDK, 9))
        assertEquals(ControlOutcome.DROPPED_FORBIDDEN, outcome(GlassesProtocol.Wear.FACTORY, 6))
        // Interconnection 只有实测见过的直播 id 才放行（见下一条），其余仍然硬拒
        assertEquals(ControlOutcome.DROPPED_FORBIDDEN, outcome(GlassesProtocol.Wear.INTERCONNECTION, 46))
    }

    @Test
    fun `Interconnection 的直播回报必须交付给宿主`() {
        // 2026-11-10 SDK 自连真机实测：直播期间会收到 23/30、23/45、23/47。
        // 若一律 DROPPED_FORBIDDEN，「直播状态」链路在 SDK 层就是断的。
        for (id in listOf(30, 45, 47)) {
            assertEquals(ControlOutcome.HANDLED, outcome(GlassesProtocol.Wear.INTERCONNECTION, id), "id=$id 应交付")
        }
    }

    @Test
    fun `Account 与未知通道都不处理`() {
        assertEquals(ControlOutcome.DROPPED_UNVERIFIED, outcome(GlassesProtocol.Wear.ACCOUNT, 1))
        assertEquals(ControlOutcome.DROPPED_UNKNOWN, outcome(7, 1))
        assertEquals(ControlOutcome.DROPPED_UNKNOWN, outcome(200, 1))
    }

    @Test
    fun `null 报文判为未知且不崩`() {
        val d = ControlRouter.dispatch(null, null)
        assertEquals(ControlOutcome.DROPPED_UNKNOWN, d.outcome)
        assertEquals(-1, d.type)
    }

    @Test
    fun `sink 只在 HANDLED 时被调用`() {
        val got = mutableListOf<String>()
        val sink = object : ControlSink {
            override fun onVoice(id: Int, payload: ByteArray) { got += "voice:$id" }
            override fun onSystem(id: Int, payload: ByteArray) { got += "system:$id" }
            override fun onMedia(id: Int, payload: ByteArray) { got += "media:$id" }
        }

        ControlRouter.dispatch(GlassesProtocol.Wear.MEDIA, 16, byteArrayOf(1), sink)
        ControlRouter.dispatch(GlassesProtocol.Wear.MEDIA, 99, byteArrayOf(1), sink)
        ControlRouter.dispatch(GlassesProtocol.Wear.PAYSDK, 9, byteArrayOf(1), sink)
        ControlRouter.dispatch(GlassesProtocol.Wear.SYSTEM, 77, byteArrayOf(1), sink)

        assertEquals(listOf("media:16"), got, "只有实测 id 该落到 sink")
    }

    @Test
    fun `从 WearPacket 派发`() {
        val body = WearCodec.build(GlassesProtocol.Wear.SYSTEM, 93, byteArrayOf(1, 2, 3))
        val d = ControlRouter.dispatch(WearCodec.parse(body), null)
        assertEquals(GlassesProtocol.Wear.SYSTEM, d.type)
        assertEquals(93, d.id)
        assertEquals(ControlOutcome.HANDLED, d.outcome)
    }

    @Test
    fun `畸形报文不抛异常`() {
        val d = ControlRouter.dispatch(WearCodec.parse(Hex.decode("ff")), null)
        assertEquals(ControlOutcome.DROPPED_UNKNOWN, d.outcome)
    }

    @Test
    fun `真实实测帧按期望路由`() {
        // 摘下时设备主动上报的两帧（控制面）
        val instructionList = Hex.decode("080e10008201080a060a0408036800")
        val voiceError = Hex.decode("080e100682010432020804")

        assertEquals(ControlOutcome.HANDLED, ControlRouter.dispatch(WearCodec.parse(instructionList), null).outcome)
        val err = ControlRouter.dispatch(WearCodec.parse(voiceError), null)
        assertEquals(ControlOutcome.HANDLED, err.outcome)
        assertEquals(6, err.id, "id=6 是 SYNC_VOICE_ERROR")
        assertTrue(err.channelName == "voice")
    }
}
