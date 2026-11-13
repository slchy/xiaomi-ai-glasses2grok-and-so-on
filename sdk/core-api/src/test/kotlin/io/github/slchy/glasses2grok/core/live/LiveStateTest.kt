package io.github.slchy.glasses2grok.core.live

import io.github.slchy.glasses2grok.core.pb.Pb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 直播状态回执（`Interconnection` id=47）。
 *
 * 字段号与 code 语义来源：参考实现
 * - `protobuf 消息类.设置直播状态` → `载荷类型 = 0x23`(35)
 * - `参考实现` 分发 `0x2f`(47) → `直播处理`
 * - `e()` 读 `code` 做 `switch 分支表`，内层 `switch 分支表` 映射到
 *   `设备占用` / `Wi-Fi 失败` / `RTMP 失败` / `高温` 等提示文案
 */
class LiveStateTest {

    @Test
    fun `parseLiveState - code 与 rtspUrl`() {
        val s = LiveStreaming.parseLiveState(payload(1, "rtsp://192.0.2.10:8554/live"))!!
        assertEquals(1, s.code)
        assertEquals("rtsp://192.0.2.10:8554/live", s.rtspUrl)
        assertTrue(s.isOk)
        assertEquals("正常开启直播", s.reason)
    }

    @Test
    fun `parseLiveState - 停止与失败码`() {
        val stopped = LiveStreaming.parseLiveState(payload(2, ""))!!
        assertEquals("正常停止直播", stopped.reason)
        assertTrue(stopped.isOk)

        val rtmp = LiveStreaming.parseLiveState(payload(5, ""))!!
        assertFalse(rtmp.isOk)
        assertEquals("RTMP 连接失败", rtmp.reason)
    }

    @Test
    fun `reason - 3 到 15 逐个对齐参考实现文案`() {
        val expect = mapOf(
            3 to "设备被占用，无法开始直播",
            4 to "Wi-Fi 连接失败",
            5 to "RTMP 连接失败",
            6 to "设备温度过高",
            7 to "电量过低",
            8 to "请先佩戴眼镜",
            9 to "相机指示灯被遮挡",
            10 to "设备忙",
            11 to "设备正在录音",
            12 to "设备正在通话",
            13 to "设备忙",
            14 to "设备正在升级",
            15 to "固件自动重连中",
        )
        expect.forEach { (code, text) ->
            assertEquals(text, LiveStreaming.LiveState(code, "").reason, "code=$code")
        }
        assertEquals("未知固件报错（code=99）", LiveStreaming.LiveState(99, "").reason)
    }

    @Test
    fun `parseLiveState - 不是状态回包时返回 null`() {
        assertNull(LiveStreaming.parseLiveState(ByteArray(0)))
    }

    /** `Interconnection{ f35 = Status{ f1 code, f2 rtspUrl } }` */
    private fun payload(code: Int, url: String): ByteArray {
        val st = java.io.ByteArrayOutputStream()
        Pb.uint(st, 1, code.toLong())
        if (url.isNotEmpty()) Pb.string(st, 2, url)
        val out = java.io.ByteArrayOutputStream()
        Pb.bytes(out, LiveStreaming.F_STATUS, st.toByteArray())
        return out.toByteArray()
    }
}
