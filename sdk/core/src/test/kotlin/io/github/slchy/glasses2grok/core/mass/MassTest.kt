package io.github.slchy.glasses2grok.core.mass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MASS 头解析 + 两条流的重组。
 *
 * 每条都来自一次真实踩过的坑（短 END、按帧长误判、超限静默）。
 */
class MassTest {

    // ---------------------------------------------------------------- 头解析

    @Test
    fun `12 字节头各字段按大端解析`() {
        // [0]=0 [1]=02 DATA [2:4]=0x0209(521) [4:6]=0x0003 [6:10]=0x0000568F(22159) [10:12]=0x0201(513)
        val payload = ByteArray(513) { 0x5A }
        val frame = MassCodec.buildData(MassType.FIRST_IMAGE, 22_159, payload)
        val m = assertNotNull(MassCodec.parse(frame))

        assertEquals(MassCommand.DATA, m.cmd)
        assertEquals(MassType.FIRST_IMAGE, m.massType)
        assertEquals(22_159, m.total)
        assertEquals(513, m.declaredLen)
        assertEquals(12, m.payloadOff)
        assertEquals(513, m.payloadLen)
        assertEquals(525, frame.size)

        // 自校验关系：[2:4] = 8 + [10:12]
        assertEquals(8 + m.declaredLen, MassCodec.be16(frame, 2))
    }

    @Test
    fun `两字节 END 是合法短包`() {
        val m = assertNotNull(MassCodec.parse(byteArrayOf(0, MassCommand.END.toByte())))
        assertEquals(MassCommand.END, m.cmd)
        assertEquals(MassType.UNKNOWN, m.massType, "短包不携带 MassType")
        assertTrue(m.isEnd)
        assertEquals(0, m.payloadLen)
    }

    @Test
    fun `不足两字节返回 null`() {
        assertNull(MassCodec.parse(null))
        assertNull(MassCodec.parse(ByteArray(0)))
        assertNull(MassCodec.parse(byteArrayOf(0)))
    }

    @Test
    fun `声明长度大于实际可用时按可用长度截断`() {
        val frame = MassCodec.buildData(MassType.FOLLOW_VOICE, 0, ByteArray(100))
        val truncated = frame.copyOf(frame.size - 10)       // 砍掉 10 字节
        val m = assertNotNull(MassCodec.parse(truncated))
        assertEquals(100, m.declaredLen, "头里仍声明 100")
        assertEquals(90, m.payloadLen, "实际可用 90 —— 不能越界读")
    }

    // ---------------------------------------------------------------- 分流

    @Test
    fun `图片与语音按 MassType 分流而不是按帧长`() {
        val a = MassAssembler()
        // 构造成同长度的两帧：语音 570 字节载荷、图片 570 字节载荷
        val voiceFrame = MassCodec.buildData(MassType.FOLLOW_VOICE, 0, ByteArray(570) { 1 })
        val imageFrame = MassCodec.buildData(MassType.FIRST_IMAGE, 570, ByteArray(570) { 2 })

        a.feed(voiceFrame)
        a.feed(imageFrame)

        assertEquals(1, a.voiceFrameCount)
        assertEquals(1, a.imageFrameCount)
        assertEquals(570, a.voiceSize)
        assertEquals(570, a.imageSize)
        assertEquals(setOf(MassType.FOLLOW_VOICE, MassType.FIRST_IMAGE), a.massCounts().keys)
    }

    @Test
    fun `FIRST 与 FOLLOW 必须拼接且按声明总长收尾`() {
        val a = MassAssembler()
        val first = MassCodec.buildData(MassType.FIRST_IMAGE, 30, ByteArray(18) { 0x11 })
        val follow = MassCodec.buildData(MassType.FOLLOW_IMAGE, 0, ByteArray(12) { 0x22 })

        assertTrue(!a.feed(first), "首片不应立即完成")
        assertEquals(30, a.declaredTotal)
        assertTrue(a.feed(follow), "达到声明总长后应报告完成")
        assertEquals(30, a.imageSize)
        assertTrue(a.isImageComplete)
        assertTrue(!a.feed(follow), "完成后补帧不得重复报告完成")
    }

    @Test
    fun `两字节 END 不再结束图片流 - 对齐参考实现口径`() {
        // 参考实现语音处理的 switch：case 4(END)/case 5(QUIT) 直接 return true，
        // 图片完成只看声明总长（实测）。早先我们把 END 当结束信号并对
        // 半张图交付，是没有依据的发明。
        val a = MassAssembler()
        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 999, ByteArray(10)))
        assertTrue(!a.isImageComplete)
        assertTrue(!a.feed(MassCodec.buildEnd()), "短 END 不得交付图片")
        assertTrue(!a.isImageComplete)
        assertEquals(10, a.imageSize, "数据仍在，只是不算完成")
        assertEquals(1, a.shortEndCount, "END 仍要被计数（诊断用）")
    }

    @Test
    fun `凑满声明总长才算完成 - END 之后再补帧也行`() {
        val a = MassAssembler()
        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 20, ByteArray(10) { 0x11 }))
        a.feed(MassCodec.buildEnd()) // 顺序颠倒：先 END 再补最后一段
        assertTrue(!a.isImageComplete)
        assertTrue(a.feed(MassCodec.buildData(MassType.FOLLOW_IMAGE, 0, ByteArray(10) { 0x22 })))
        assertEquals(20, a.imageSize)
        assertTrue(a.isImageComplete)
    }

    @Test
    fun `不完整图片在下一条流开始时被丢弃并计数`() {
        // 参考实现用 15 s 定时器丢弃凑不满的图；我们用「下一条流声明了不同总长」这个边界。
        // 两种做法都不交付残缺图，但我们的丢弃必须可数，否则就是静默丢图。
        val a = MassAssembler()
        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 999, ByteArray(10)))
        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 8, ByteArray(8)))

        assertEquals(1, a.droppedIncompleteCount)
        assertEquals(10, a.lastDroppedImageSize)
        assertEquals(999, a.lastDroppedDeclaredTotal)
        assertTrue(a.isImageComplete, "新流自己收满了")
    }

    @Test
    fun `没有图片在收时 END 不报告完成`() {
        val a = MassAssembler()
        assertTrue(!a.feed(MassCodec.buildEnd()))
        assertTrue(!a.isImageComplete)
    }

    @Test
    fun `声明总长变化视为新图片流`() {
        val a = MassAssembler()
        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 20, ByteArray(10) { 0x11 }))
        // 上一轮没有 END 就直接开始新流
        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 8, ByteArray(8) { 0x22 }))
        assertEquals(8, a.imageSize, "新流应清掉上一轮残留")
        assertTrue(a.isImageComplete)
    }

    @Test
    fun `图片超过上限时停止积累但继续计帧`() {
        val a = MassAssembler(maxImageBytes = 100)
        repeat(5) {
            a.feed(MassCodec.buildData(MassType.FOLLOW_IMAGE, 0, ByteArray(40)))
        }
        assertTrue(a.isImageTruncated)
        assertEquals(80, a.imageSize, "前两帧（40+40）能放下，第三帧起超出上限不再写入")
        assertEquals(5, a.imageFrameCount, "计帧不能停 —— 调用方需要知道设备还在推")
    }

    @Test
    fun `语音超过上限时同理`() {
        val a = MassAssembler(maxVoiceBytes = 100)
        repeat(3) { a.feed(MassCodec.buildData(MassType.FOLLOW_VOICE, 0, ByteArray(57))) }
        assertTrue(a.isVoiceTruncated)
        assertEquals(114 - 57, a.voiceSize, "第 3 帧起不再写入")
        assertEquals(3, a.voiceFrameCount)
    }

    @Test
    fun `语音时长按 mSBC 帧长换算`() {
        val a = MassAssembler()
        a.feed(MassCodec.buildData(MassType.FOLLOW_VOICE, 0, ByteArray(570)))   // 10 帧
        assertEquals(0.075, a.voiceSeconds, 1e-9)
    }

    @Test
    fun `reset 清空两条流与统计`() {
        val a = MassAssembler()
        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 100, ByteArray(10)))
        a.feed(MassCodec.buildData(MassType.FOLLOW_VOICE, 0, ByteArray(57)))

        a.reset()
        assertEquals(0, a.imageSize)
        assertEquals(0, a.voiceSize)
        assertEquals(0, a.imageFrameCount)
        assertEquals(0, a.voiceFrameCount)
        assertEquals(0, a.declaredTotal)
        assertTrue(!a.isImageComplete)
        assertTrue(a.massCounts().isEmpty())
        assertNull(a.frame)
    }

    @Test
    fun `短 END 会记下当时的图片进度`() {
        // 诊断用：v0 已对齐参考实现（END 不交付），但万一真机上声明总长永远凑不满，
        // 这组数字能立刻看出「END 时差了多少」，而不是干等一张不来的图。
        val a = MassAssembler()
        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 999, ByteArray(10)))
        assertTrue(!a.feed(MassCodec.buildEnd()), "END 不交付")

        assertEquals(1, a.shortEndCount)
        assertEquals(10, a.endAtImageSize, "END 到达时只收到 10 字节")
        assertEquals(999, a.endAtDeclaredTotal, "声明 999 字节")
    }

    @Test
    fun `reset 只清数据，不动语音回调`() {
        // 回归护栏：reset() 曾经顺手把 onVoiceChunk 置 null，
        // 一旦会话层为「新一轮唤醒」调它，语音流会静默断掉（而且毫无报错）。
        val a = MassAssembler()
        val chunks = mutableListOf<ByteArray>()
        a.onVoiceChunk = { chunks += it }

        a.feed(MassCodec.buildData(MassType.FOLLOW_VOICE, 0, ByteArray(57) { 1 }))
        a.reset()
        a.feed(MassCodec.buildData(MassType.FOLLOW_VOICE, 0, ByteArray(57) { 2 }))

        assertEquals(2, chunks.size, "reset 之后语音回调必须还在")
        assertEquals(2, chunks[1][0].toInt() and 0xFF, "第二块应是 reset 之后那帧")
    }

    @Test
    fun `构造的 END 帧就是设备实际的两字节形态`() {
        assertEquals("0003", io.github.slchy.glasses2grok.core.Hex.encode(MassCodec.buildEnd()))
    }

    // ---------------------------------------------------------------- 流边界与超时

    @Test
    fun `同总长的下一条流必须重新开始 - 不得拼接`() {
        // 回归护栏：边界曾经只看「声明总长变了」。上一条被截断（只收到 10/20 字节），
        // 下一条声明了**相同**总长 ⇒ 不 reset ⇒ 两段字节首尾相接 ⇒ size>=20 提前判完成
        // ⇒ 交付一张损坏的 JPEG（首尾拼在一起）。
        val a = MassAssembler()
        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 20, ByteArray(10) { 0x11 }))
        assertEquals(10, a.imageSize)

        // 第二条流：同样的总长 20，自己的内容全 0x22
        val delivered = a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 20, ByteArray(10) { 0x22 }))
        assertTrue(!delivered, "刚收到 10 字节，不该判完成")
        assertEquals(10, a.imageSize, "必须只剩新流的 10 字节，不能是 20")
        assertEquals(0x22.toByte(), a.imageBytes()[0], "首字节应属于新流")

        assertTrue(a.feed(MassCodec.buildData(MassType.FOLLOW_IMAGE, 0, ByteArray(10) { 0x33 })))
        val bytes = a.imageBytes()
        assertEquals(20, bytes.size)
        assertEquals(0x22.toByte(), bytes[0], "前半属于新流")
        assertEquals(0x33.toByte(), bytes[10], "后半属于新流")
        assertEquals(1, a.droppedIncompleteCount, "被截断的上一条要被计数")
        assertEquals(10, a.lastDroppedImageSize)
    }

    @Test
    fun `上一张已完成时同总长的下一张也必须交付`() {
        // 同一个根因的第二种症状：完成之后 imageComplete 一直为真，
        // 同总长的下一张既不 reset 也不会再交付 —— 第二张图凭空消失。
        val a = MassAssembler()
        assertTrue(a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 10, ByteArray(10))))
        assertTrue(a.isImageComplete, "第一张应完成")

        val second = a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 10, ByteArray(10) { 0x55 }))
        assertTrue(second, "第二张同样要交付，实际被吞了")
        assertEquals(0x55.toByte(), a.imageBytes()[0], "交付的是第二张的内容")
    }

    @Test
    fun `图片流空闲超时丢弃半成品并计数`() {
        val a = MassAssembler(imageIdleTimeoutMs = 1_000)
        a.tick(0)
        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 999, ByteArray(10)))

        assertTrue(!a.tick(500), "还没到 15 s（这里 1 s），不能丢")
        assertEquals(10, a.imageSize)

        assertTrue(a.tick(1_500), "超时后应丢弃")
        assertEquals(0, a.imageSize)
        assertEquals(1, a.droppedByTimeoutCount)
        assertEquals(10, a.lastDroppedImageSize)
        assertEquals(999, a.lastDroppedDeclaredTotal)
        assertTrue(!a.isImageComplete)
    }

    @Test
    fun `超时不碰已完成或空的图片`() {
        val a = MassAssembler(imageIdleTimeoutMs = 1_000)
        assertTrue(!a.tick(5_000), "没有半成品时什么都不做")

        a.feed(MassCodec.buildData(MassType.FIRST_IMAGE, 10, ByteArray(10)))
        assertTrue(a.isImageComplete)
        assertTrue(!a.tick(99_000), "已完成的图不该被超时清掉")
        assertEquals(10, a.imageSize)
    }
}
