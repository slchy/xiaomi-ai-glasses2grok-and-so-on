package com.miaiglasses.core.live

import com.miaiglasses.core.pb.Pb
import com.miaiglasses.core.wear.WearCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 直播命令的字节级测试。
 *
 * 最硬的一条是 [`query` 与真机抓包逐字节一致]：那串 hex 来自参考实现在真机上
 * 实际发出的帧（实测），不是从文档抄的。
 * 只要有人改动信封字段号或 payload 布局，这条会立刻红。
 */
class LiveStreamingTest {

    /** 真机抓到的 `id=45` 原始帧：type=23 id=45 f25{ f33{ f1=1, f2=0 } }。 */
    private val realQueryFrame =
        "08 17 10 2D CA 01 07 8A 02 04 08 01 10 00"

    /**
     * 真机抓到的 `id=45` 回复（`System id=86`）的 WearPacket payload，
     * 即 `f4 → f33{ f1{ f1:"GLASSCAP", f2, f3 }, f2{ f1:"glasscap1234" } }` 的外层 `f4` 内容。
     */
    private val realStatusPayload = hex(
        "8a 02 24 0a 20 0a 0e 0a 08 47 4c 41 53 53 43 41 50 10 02 18 02" +
            " 12 0e 0a 0c 67 6c 61 73 73 63 61 70 31 32 33 34 10 01",
    )

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun hexOf(b: ByteArray): String =
        b.joinToString(" ") { "%02X".format(it) }

    // ---------------------------------------------------------------- 查询

    @Test
    fun `query 与真机抓包逐字节一致`() {
        assertEquals(realQueryFrame, hexOf(LiveStreaming.query()))
    }

    @Test
    fun `query 的信封是 type=23 id=45 容器字段 25`() {
        val p = assertNotNull(WearCodec.parse(LiveStreaming.query()))
        assertEquals(23, p.type)
        assertEquals(45, p.id)
        assertEquals(25, p.payloadField, "Interconnection 的容器字段 = type+2 = 25")
    }

    // ---------------------------------------------------------------- 起播

    @Test
    fun `start 的信封是 type=23 id=46 且带完整参数`() {
        val raw = LiveStreaming.start(
            url = "rtmp://192.0.2.20:1935/live/test",
            resolution = LiveStreaming.Resolution.P1080,
            fps = LiveStreaming.FrameRate.FPS25,
            streamMode = LiveStreaming.StreamMode.QUALITY_FIRST,
            mode = LiveStreaming.Mode.BLUETOOTH,
        )
        val p = assertNotNull(WearCodec.parse(raw))
        assertEquals(23, p.type)
        assertEquals(46, p.id)

        val req = assertNotNull(Pb.fields(p.payload)?.get(34), "payload 应装在 f34 里")
        val body = assertNotNull(Pb.fields(req))
        assertEquals(1, Pb.varintValue(assertNotNull(body[1])).toInt(), "f1 = 1 表示开始")

        val param = assertNotNull(Pb.fields(assertNotNull(body[2])))
        assertEquals(3, Pb.varintValue(assertNotNull(param[1])).toInt(), "分辨率 1080p")
        assertEquals(25, Pb.varintValue(assertNotNull(param[2])).toInt(), "帧率 25fps")
        assertEquals(3, Pb.varintValue(assertNotNull(param[3])).toInt(), "码率优先")
        assertEquals(0, Pb.varintValue(assertNotNull(param[4])).toInt(), "livePlatform = RTMP")
        assertEquals(4, Pb.varintValue(assertNotNull(param[5])).toInt(), "liveMode = 蓝牙")
        assertEquals("rtmp://192.0.2.20:1935/live/test", assertNotNull(param[6]).decodeToString())
    }

    @Test
    fun `start 默认 720p30 热点且不默认走未验证的蓝牙通道`() {
        val p = assertNotNull(WearCodec.parse(LiveStreaming.start("rtmp://x/y")))
        val body = assertNotNull(Pb.fields(assertNotNull(Pb.fields(p.payload)?.get(34))))
        val param = assertNotNull(Pb.fields(assertNotNull(body[2])))
        assertEquals(2, Pb.varintValue(assertNotNull(param[1])).toInt(), "720p")
        assertEquals(30, Pb.varintValue(assertNotNull(param[2])).toInt(), "30fps")
        // liveMode=1 是唯一实测通的通道；4（蓝牙）零实测，绝不能是默认值
        assertEquals(1, Pb.varintValue(assertNotNull(param[5])).toInt(), "默认必须是 liveMode=1")
    }

    @Test
    fun `start 显式写出 livePlatform=0 不靠省略`() {
        val p = assertNotNull(WearCodec.parse(LiveStreaming.start("rtmp://x/y")))
        val body = assertNotNull(Pb.fields(assertNotNull(Pb.fields(p.payload)?.get(34))))
        val param = assertNotNull(Pb.fields(assertNotNull(body[2])))
        assertTrue(param.containsKey(4), "livePlatform 必须显式出现在编码里")
    }

    // ---------------------------------------------------------------- 停播

    @Test
    fun `stop 只有 f1=2 不带参数块`() {
        val p = assertNotNull(WearCodec.parse(LiveStreaming.stop()))
        assertEquals(46, p.id)
        val body = assertNotNull(Pb.fields(assertNotNull(Pb.fields(p.payload)?.get(34))))
        assertEquals(2, Pb.varintValue(assertNotNull(body[1])).toInt(), "f1 = 2 表示停止")
        assertNull(body[2], "停播不该带 param")
    }

    // ---------------------------------------------------------------- 回复解析

    @Test
    fun `能从真机回复里解出热点 SSID 与密码`() {
        val st = assertNotNull(LiveStreaming.parseStatus(realStatusPayload))
        assertEquals("GLASSCAP", st.ssid)
        assertEquals("glasscap1234", st.password)
    }

    @Test
    fun `解析器容错垃圾输入返回 null 而不是抛异常`() {
        assertNull(LiveStreaming.parseStatus(byteArrayOf(0x08)))
        assertNull(LiveStreaming.parseStatus(ByteArray(0)))
    }

    // ---------------------------------------------------------------- 枚举

    @Test
    fun `枚举 wire 值与协议表一致`() {
        assertContentEquals(
            intArrayOf(1, 2, 3).toTypedArray(),
            LiveStreaming.Resolution.entries.map { it.wire }.toTypedArray(),
        )
        assertContentEquals(
            intArrayOf(15, 24, 25, 30).toTypedArray(),
            LiveStreaming.FrameRate.entries.map { it.wire }.toTypedArray(),
        )
        assertContentEquals(
            intArrayOf(1, 2, 4).toTypedArray(),
            LiveStreaming.Mode.entries.map { it.wire }.toTypedArray(),
        )
        assertEquals(LiveStreaming.Mode.BLUETOOTH, LiveStreaming.Mode.of(4))
        assertNull(LiveStreaming.Mode.of(3), "3 不是合法的 liveMode")
    }
}
