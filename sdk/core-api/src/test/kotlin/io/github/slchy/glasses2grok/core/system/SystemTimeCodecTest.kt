package io.github.slchy.glasses2grok.core.system

import io.github.slchy.glasses2grok.core.pb.Pb
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `System id=3 SetSystemTime` 的载荷编码。
 *
 * 字段号/类型来源：参考实现 `命令实现类` +
 * `protobuf 消息类` / `protobuf 消息类·Time·Timezone` 的 `writeTo`
 * （实测）。没有真机回包可对账（`needResponse=false`），
 * 所以这里锁的是「结构与参考实现逐字段一致」，真机采纳与否靠 `Media 21` 的时间戳验。
 */
class SystemTimeCodecTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHexSpaced() = joinToString(" ") { "%02x".format(it) }

    /** 2026-11-11 17:39:15.250 +08:00 的毫秒时间戳。 */
    private val epoch = 1_794_389_955_250L
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    private fun decode(payload: ByteArray): Map<Int, ByteArray> {
        val systemTime = assertNotNull(Pb.fields(payload)[4], "外层必须是 System.f4")
        return Pb.fields(systemTime)
    }

    @Test
    fun `T1 Date 用 1-12 的月份不是 0-based`() {
        val st = decode(SystemTimeCodec.encode(epoch, shanghai))
        val date = Pb.fields(assertNotNull(st[1]))
        assertEquals(2026L, Pb.varintValue(assertNotNull(date[1])), "year")
        assertEquals(11L, Pb.varintValue(assertNotNull(date[2])), "month 必须是 11，0-based 会得到 10")
        assertEquals(11L, Pb.varintValue(assertNotNull(date[3])), "day")
    }

    @Test
    fun `T2 Time 四个字段含毫秒`() {
        val st = decode(SystemTimeCodec.encode(epoch, shanghai))
        val time = Pb.fields(assertNotNull(st[2]))
        assertEquals(17L, Pb.varintValue(assertNotNull(time[1])), "hour")
        assertEquals(39L, Pb.varintValue(assertNotNull(time[2])), "minuter")
        assertEquals(15L, Pb.varintValue(assertNotNull(time[3])), "second")
        assertEquals(250L, Pb.varintValue(assertNotNull(time[4])), "millisecond")
    }

    @Test
    fun `T3 Timezone offset 单位是 15 分钟 UTC+8 等于 32`() {
        val st = decode(SystemTimeCodec.encode(epoch, shanghai))
        val tz = Pb.fields(assertNotNull(st[3]))
        // sint32 zigzag：32 -> 64
        assertEquals(64L, Pb.varintValue(assertNotNull(tz[1])), "480 分钟 / 15 = 32，zigzag 后 64")
        assertEquals(0L, Pb.varintValue(assertNotNull(tz[2])), "dstSaving 恒 0")
        assertEquals("Asia/Shanghai", assertNotNull(tz[3]).toString(Charsets.UTF_8))
    }

    @Test
    fun `T4 负时区走 zigzag UTC-5 等于 -20`() {
        val st = decode(SystemTimeCodec.encode(epoch, TimeZone.getTimeZone("America/New_York")))
        val tz = Pb.fields(assertNotNull(st[3]))
        val offset = Pb.varintValue(assertNotNull(tz[1]))
        // -20 的 zigzag = 39
        assertEquals(39L, offset, "用 int32 编码负数会得到一大串，zigzag 才是 39")
        // 反解回有符号值：zigzag 解码
        val signed = (offset ushr 1).toInt().let { if (offset and 1L == 1L) -it - 1 else it }
        assertEquals(-20, signed, "-5 小时 = -300 分钟 = -20 格")
    }

    @Test
    fun `T5 is12Hours 显式写出 0 与 1`() {
        assertEquals(0L, Pb.varintValue(assertNotNull(decode(SystemTimeCodec.encode(epoch, shanghai))[4])))
        assertEquals(1L, Pb.varintValue(assertNotNull(decode(SystemTimeCodec.encode(epoch, shanghai, false))[4])))
    }

    @Test
    fun `T6 四个字段一个都不能少`() {
        val st = decode(SystemTimeCodec.encode(epoch, shanghai))
        assertEquals(setOf(1, 2, 3, 4), st.keys, "date/time/timezone/is12Hours 全写")
        assertEquals(setOf(1, 2, 3), Pb.fields(assertNotNull(st[1])).keys)
        assertEquals(setOf(1, 2, 3, 4), Pb.fields(assertNotNull(st[2])).keys)
    }

    @Test
    fun `T7 跨时区同一瞬间解出的墙上时间不同`() {
        val a = Pb.fields(assertNotNull(decode(SystemTimeCodec.encode(epoch, shanghai))[2]))
        val b = Pb.fields(
            assertNotNull(decode(SystemTimeCodec.encode(epoch, TimeZone.getTimeZone("UTC")))[2]),
        )
        assertEquals(17L, Pb.varintValue(assertNotNull(a[1])))
        assertEquals(9L, Pb.varintValue(assertNotNull(b[1])), "UTC 比 +08 早 8 小时")
    }

    @Test
    fun `T8 负毫秒取模不出负数`() {
        // floorMod 保证毫秒字段落在 0..999
        val st = decode(SystemTimeCodec.encode(-1_000L, TimeZone.getTimeZone("UTC")))
        val ms = Pb.varintValue(assertNotNull(Pb.fields(assertNotNull(st[2]))[4]))
        assertTrue(ms in 0..999, "实际 $ms")
    }

    @Test
    fun `T9 编码与手算字节一致`() {
        // 2026-11-11 17:39:15.250 +08:00（Asia/Shanghai）
        val expected = hex(
            "22 2b " + // System.f4, len 43
                "0a 07 08 ea 0f 10 0b 18 0b " + // Date{2026,11,11}
                "12 09 08 11 10 27 18 0f 20 fa 01 " + // Time{17,39,15,250}
                "1a 13 08 40 10 00 1a 0d " + "41 73 69 61 2f 53 68 61 6e 67 68 61 69 " + // Timezone
                "20 00", // is12Hours = 0（24 小时制）
        )
        assertEquals(expected.toHexSpaced(), SystemTimeCodec.encode(epoch, shanghai).toHexSpaced())
    }
}
