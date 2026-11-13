package io.github.slchy.glasses2grok.core.interconn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 按键上报解析。字节全部是 2026-11-11 真机原样帧（实测）。
 *
 * 实验动作：短按一次 → 长按开始录像 → 长按停止录像。
 */
class InterconnectionTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** 四帧只差第一个字段（递增计数 0x21..0x24）。 */
    private fun misFrame(counter: Int) =
        hex("b2 01 2e 12 2c 08 %02x 10 11 18 02 32 19 08 07 42 15 0a 13 0a 09 39 36 39 30 31 33 32 32 32 10 01 18 03 20 02 40 45 72 09 39 36 39 30 31 33 32 32 32".format(counter))

    @Test
    fun `M1 载荷挂在 f22 而不是 f30`() {
        val mis = assertNotNull(MisSpec.parse(misFrame(0x21)))
        assertTrue(mis.raw.startsWith("b2 01"), "tag 必须是 0xB2 0x01 = f22 wire2，实际 ${mis.raw.take(5)}")
        assertEquals(44, mis.packet.size, "Mis.Payload.f2 是 44 字节的 MIoT spec 包")
        assertEquals("", mis.spec, "参考实现实机这条不带 spec 字符串")
    }

    @Test
    fun `M2 packet 里带设备 DID`() {
        val mis = assertNotNull(MisSpec.parse(misFrame(0x21)))
        assertTrue(
            mis.packet.toString(Charsets.ISO_8859_1).contains("969013222"),
            "packet 里应能直接看到 DID 969013222",
        )
    }

    @Test
    fun `M3 四次按键只有递增计数不同 —— 按键类型不在这条报文里`() {
        val frames = (0x21..0x24).map { misFrame(it) }
        val packets = frames.map { assertNotNull(MisSpec.parse(it)).packet }
        // 把第一字段（计数）之后的字节全部比对
        for (i in 1 until packets.size) {
            val rest0 = packets[0].copyOfRange(2, packets[0].size)
            val restI = packets[i].copyOfRange(2, packets[i].size)
            assertTrue(rest0.contentEquals(restI), "第 1 帧与第 ${i + 1} 帧除计数外必须完全一致")
        }
        assertEquals(0x21, packets[0][1].toInt(), "计数 33")
        assertEquals(0x24, packets[3][1].toInt(), "计数 36")
    }

    @Test
    fun `M4 没有 f22 时返回 null 而不是抛异常`() {
        assertNull(MisSpec.parse(hex("08 00")))
    }

    @Test
    fun `A1 camera 状态解析`() {
        val a = assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 01")))
        assertEquals(1, a.cameraStatus)
        assertNull(a.recorderStatus, "这条只有 camera")
    }

    @Test
    fun `A2 recorder 状态解析`() {
        val a = assertNotNull(CaptureAlert.parse(hex("d2 03 04 42 02 08 05")))
        assertEquals(5, a.recorderStatus)
        assertNull(a.cameraStatus)
    }

    @Test
    fun `A3 只有 camera 或 recorder 时不算空`() {
        assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 00")))
        assertNotNull(CaptureAlert.parse(hex("d2 03 04 42 02 08 00")))
    }

    @Test
    fun `A4 两段都没有时返回 null`() {
        // AlertStatus 里只有 battery(f1)
        assertNull(CaptureAlert.parse(hex("d2 03 04 0a 02 08 01")))
    }

    @Test
    fun `A5 真机序列的完整形态`() {
        // 短按 -> camera 1；长按开始 -> recorder 5 + camera 5；停止 -> camera 1, recorder 0, camera 0
        assertEquals(1, assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 01"))).cameraStatus)
        assertEquals(5, assertNotNull(CaptureAlert.parse(hex("d2 03 04 42 02 08 05"))).recorderStatus)
        assertEquals(5, assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 05"))).cameraStatus)
        assertEquals(0, assertNotNull(CaptureAlert.parse(hex("d2 03 04 42 02 08 00"))).recorderStatus)
    }

    @Test
    fun `A6 hasRecorder 只看存在性，不解读值`() {
        assertTrue(!assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 01"))).hasRecorder)
        assertTrue(assertNotNull(CaptureAlert.parse(hex("d2 03 04 42 02 08 05"))).hasRecorder)
        assertTrue(assertNotNull(CaptureAlert.parse(hex("d2 03 04 42 02 08 00"))).hasRecorder)
    }

    // -------------------- 相机状态的语义来自参考实现 --------------------
    // 录制中 = (status == 5)
    // 相机联动     = (status == 2 || status == 3)

    @Test
    fun `A7 isRecording 只在 camera 等于 5 时为真`() {
        assertTrue(assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 05"))).isRecording)
        assertTrue(!assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 01"))).isRecording)
        assertTrue(!assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 00"))).isRecording)
        // 只有 recorder 没有 camera 时，不能推断在录像
        assertTrue(!assertNotNull(CaptureAlert.parse(hex("d2 03 04 42 02 08 05"))).isRecording)
    }

    @Test
    fun `A8 isJoint 对应参考实现的 2 与 3`() {
        assertTrue(assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 02"))).isJoint)
        assertTrue(assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 03"))).isJoint)
        assertTrue(!assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 05"))).isJoint)
        assertTrue(!assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 01"))).isJoint)
    }

    @Test
    fun `A9 isCapturing 与 isIdle 按形态推断的 1 与 0`() {
        val capturing = assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 01")))
        assertTrue(capturing.isCapturing)
        assertTrue(!capturing.isIdle)
        assertTrue(!capturing.isRecording, "1 不是录像")

        val idle = assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 00")))
        assertTrue(idle.isIdle)
        assertTrue(!idle.isCapturing)

        // 录像中既不是 idle 也不是 capturing
        val rec = assertNotNull(CaptureAlert.parse(hex("d2 03 04 32 02 08 05")))
        assertTrue(!rec.isCapturing)
        assertTrue(!rec.isIdle)
    }

    @Test
    fun `A10 真机拍照全过程 1 开头 0 收尾`() {
        // 两次短按的真机序列：camera 1 -> 0，中间没有 5，也没有 recorder
        val seq = listOf(
            "d2 03 04 32 02 08 01",
            "d2 03 04 32 02 08 00",
        ).map { assertNotNull(CaptureAlert.parse(hex(it))) }
        assertTrue(seq[0].isCapturing && !seq[0].hasRecorder)
        assertTrue(seq[1].isIdle && !seq[1].hasRecorder)
    }
}
