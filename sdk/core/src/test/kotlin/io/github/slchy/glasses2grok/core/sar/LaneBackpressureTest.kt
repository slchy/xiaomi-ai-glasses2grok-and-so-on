package io.github.slchy.glasses2grok.core.sar

import io.github.slchy.glasses2grok.core.Hex
import io.github.slchy.glasses2grok.core.l1.L1Codec
import io.github.slchy.glasses2grok.core.l2.L2
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 *  分车道背压。
 *
 * SAR 只有一条有序 seq 流，所以「分车道」＝**同一队列上的三类配额**：
 * 某条车道挤满只拒它自己，不会把控制命令饿死。
 */
class LaneBackpressureTest {

    /** 参考实现协商包（MPS 64512 / TX_WIN 32 / SEND_TIMEOUT 10000）。 */
    private val configFrame = Hex.decode(
        "a5a5020016006e580201030001000002020000fc03020020000402001027",
    )

    private class Rig {
        val out = ByteArrayOutputStream()
        val clock = Clock.Fake(1000L)
        val full = mutableListOf<Lane>()
        val engine = SarEngine({ out.write(it) }, clock, SarConfig.VALID).also {
            it.onQueueFull = { lane -> full += lane }
        }
    }

    @Test
    fun `音频车道挤满后拒绝第 65 帧，控制车道照常`() {
        val r = Rig()
        repeat(Lane.AUDIO.capacity) {
            assertNotNull(r.engine.sendData(L2.CH_MASS_VOICE, 2, byteArrayOf(1)), "第 ${it + 1} 帧应能入队")
        }
        assertEquals(Lane.AUDIO.capacity, r.engine.laneDepth(Lane.AUDIO))

        assertNull(r.engine.sendData(L2.CH_MASS_VOICE, 2, byteArrayOf(1)), "第 65 帧应被拒")
        assertEquals(1L, r.engine.laneRejects(Lane.AUDIO))
        assertEquals(SarResult.QUEUE_FULL, r.engine.lastRejectCode)
        assertEquals(listOf(Lane.AUDIO), r.full, "onQueueFull 要带着车道名回调")

        // 控制车道是独立的配额：音频塞满不该影响命令
        assertNotNull(r.engine.sendCmd(SarConfig.CMD_L1START_REQ), "控制车道不应被音频拖死")
        assertEquals(1, r.engine.laneDepth(Lane.CONTROL))
    }

    @Test
    fun `图片车道配额只有 4`() {
        val r = Rig()
        repeat(4) { assertNotNull(r.engine.sendData(L2.CH_MASS, 2, byteArrayOf(1))) }
        assertNull(r.engine.sendData(L2.CH_MASS, 2, byteArrayOf(1)))
        assertEquals(4, r.engine.laneDepth(Lane.IMAGE))
        assertEquals(1L, r.engine.laneRejects(Lane.IMAGE))
    }

    @Test
    fun `未知通道按控制车道计（宁占配额也不静默丢）`() {
        assertEquals(Lane.CONTROL, Lane.ofChannel(99))
        assertEquals(Lane.AUDIO, Lane.ofChannel(L2.CH_MASS_VOICE))
        assertEquals(Lane.IMAGE, Lane.ofChannel(L2.CH_MASS))
    }

    @Test
    fun `ACK 推进后车道占用会释放`() {
        val r = Rig()
        r.engine.feed(configFrame) // 协商完成，窗口打开
        repeat(5) { assertNotNull(r.engine.sendData(L2.CH_MASS_VOICE, 2, byteArrayOf(1))) }
        assertEquals(5, r.engine.laneDepth(Lane.AUDIO))

        for (seq in 0 until 5) r.engine.feed(L1Codec.buildAck(seq, ack = true))

        assertEquals(0, r.engine.laneDepth(Lane.AUDIO), "ACK 之后配额必须还回来")
        assertEquals(0, r.engine.pending)
    }

    @Test
    fun `reset 把车道占用清零（拒绝计数保留）`() {
        val r = Rig()
        repeat(Lane.IMAGE.capacity + 1) { r.engine.sendData(L2.CH_MASS, 2, byteArrayOf(1)) }
        assertEquals(1L, r.engine.laneRejects(Lane.IMAGE))

        r.engine.reset()

        assertEquals(0, r.engine.laneDepth(Lane.IMAGE))
        assertEquals(1L, r.engine.laneRejects(Lane.IMAGE), "历史拒绝次数不该被 reset 抹掉")
        assertNotNull(r.engine.sendData(L2.CH_MASS, 2, byteArrayOf(1)))
    }

    @Test
    fun `laneSummary 形如 control=0-256 audio=1-64 image=0-4`() {
        val r = Rig()
        r.engine.sendData(L2.CH_MASS_VOICE, 2, byteArrayOf(1))
        assertEquals("control=0/256 audio=1/64 image=0/4", r.engine.laneSummary)
    }
}
