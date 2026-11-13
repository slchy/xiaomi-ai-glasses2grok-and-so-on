package io.github.slchy.glasses2grok.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Voice` 上行事件解析 —— 用例字节全部是 2026-11-10 真机原样回包（实测）。
 */
class VoiceCodecTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ascii32(s: String) = s.toByteArray(Charsets.US_ASCII).toHexSpaced()

    private fun ByteArray.toHexSpaced() = joinToString(" ") { "%02x".format(it) }

    @Test
    fun `长按触控板 - 真机 74 字节带两个 32 字符 UUID`() {
        val uuid1 = "732df1373422fd45b77486bb2328db5a"
        val uuid2 = "297a5fb17e78c9192eb3c0ab7c4c219e"
        val payload = hex(
            "5a 48 0a 44 0a 20 " + ascii32(uuid1) + " 12 20 " + ascii32(uuid2) + " 10 02",
        )
        val e = VoiceCodec.parse(VoiceIds.ID_OPEN_DEVICE, payload)
        assertTrue(e is VoiceEvent.OpenDevice, "应解析为 OpenDevice，实际 $e")
        assertEquals(uuid1, e.requestId)
        assertEquals(uuid2, e.transactionId)
        assertEquals(2, e.kind)
    }

    @Test
    fun `错误同步 - 摘下 errorCode 4`() {
        val e = VoiceCodec.parse(VoiceIds.ID_ERROR, hex("32 02 08 04"))
        assertTrue(e is VoiceEvent.Error, "实际 $e")
        assertEquals(4, e.errorCode)
        assertTrue(e.isTakenOff, "4 必须被识别为摘下")
    }

    @Test
    fun `错误同步 - 另一种码 5 不算摘下`() {
        val e = VoiceCodec.parse(VoiceIds.ID_ERROR, hex("32 02 08 05"))
        assertTrue(e is VoiceEvent.Error)
        assertEquals(5, e.errorCode)
        assertTrue(!e.isTakenOff)
    }

    @Test
    fun `会话状态 - 真机 8 字节 state 3`() {
        val e = VoiceCodec.parse(VoiceIds.ID_STATUS, hex("0a 06 0a 04 08 03 68 00"))
        assertTrue(e is VoiceEvent.Status, "实际 $e")
        assertEquals(3, e.state)
        assertEquals(0, e.extra)
    }

    @Test
    fun `未知 id 原样交 hex 不抛异常`() {
        val e = VoiceCodec.parse(99, hex("08 01"))
        assertTrue(e is VoiceEvent.Unknown)
        assertEquals(99, e.id)
        assertEquals("08 01", e.hex)
    }

    @Test
    fun `结构不符时退化为 Unknown 而不是抛异常`() {
        val e = VoiceCodec.parse(VoiceIds.ID_ERROR, hex("00 00"))
        assertTrue(e is VoiceEvent.Unknown, "实际 $e")
    }

    // ------------------------------------------------------------------ 下行 multiModel

    // 以下 9 项对齐 `docs/a5a5.md` 规范的 B1–B9，其中 B4/B5 就是「f1/f2 写反 -> 设备不响应」的回归。

    private fun mm(salt: ByteArray = byteArrayOf(0, 1, 2, 3)) =
        io.github.slchy.glasses2grok.core.pb.Pb.fields(
            VoiceCodec.buildMultiModel("txn-9", "req-1".toByteArray(), salt),
        )

    @Test
    fun `B1 Voice 只含 f4 MultiModel`() {
        val f = mm()
        assertEquals(setOf(4), f.keys)
    }

    @Test
    fun `B2 MultiModel 含 f1 与 f2`() {
        val m = io.github.slchy.glasses2grok.core.pb.Pb.fields(mm()[4]!!)
        assertTrue(m.containsKey(1) && m.containsKey(2), "实际 ${m.keys}")
    }

    @Test
    fun `B3 cmd 等于 1`() {
        val m = io.github.slchy.glasses2grok.core.pb.Pb.fields(mm()[4]!!)
        assertEquals(1, io.github.slchy.glasses2grok.core.pb.Pb.varintValue(m[2]!!).toInt())
    }

    @Test
    fun `B4 RequestInfo f1 是 requestId`() {
        val ri = io.github.slchy.glasses2grok.core.pb.Pb.fields(
            io.github.slchy.glasses2grok.core.pb.Pb.fields(mm()[4]!!)[1]!!,
        )
        assertEquals("req-1", String(ri[1]!!, Charsets.UTF_8))
    }

    @Test
    fun `B5 RequestInfo f2 是 transactionId`() {
        val ri = io.github.slchy.glasses2grok.core.pb.Pb.fields(
            io.github.slchy.glasses2grok.core.pb.Pb.fields(mm()[4]!!)[1]!!,
        )
        assertEquals("txn-9", String(ri[2]!!, Charsets.UTF_8))
    }

    @Test
    fun `B6 payload 含 resourceId 前缀`() {
        val ri = io.github.slchy.glasses2grok.core.pb.Pb.fields(
            io.github.slchy.glasses2grok.core.pb.Pb.fields(mm()[4]!!)[1]!!,
        )
        val json = String(ri[3]!!, Charsets.UTF_8)
        assertTrue(json.contains("\"resourceId\":\"multimodal_txn-9"), "实际 $json")
    }

    @Test
    fun `B7 payload 是花括号包裹的 JSON`() {
        val ri = io.github.slchy.glasses2grok.core.pb.Pb.fields(
            io.github.slchy.glasses2grok.core.pb.Pb.fields(mm()[4]!!)[1]!!,
        )
        val json = String(ri[3]!!, Charsets.UTF_8)
        assertTrue(json.startsWith("{") && json.endsWith("}"), "实际 $json")
    }

    @Test
    fun `B8 salt 影响报文内容`() {
        val a = VoiceCodec.buildMultiModel("t", null, byteArrayOf(0, 0, 0, 0))
        val b = VoiceCodec.buildMultiModel("t", null, byteArrayOf(1, 2, 3, 4))
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun `B9 空 requestId 仍写出 f1`() {
        val voice = VoiceCodec.buildMultiModel("t", null, byteArrayOf(0))
        val m = io.github.slchy.glasses2grok.core.pb.Pb.fields(io.github.slchy.glasses2grok.core.pb.Pb.fields(voice)[4]!!)
        val ri = io.github.slchy.glasses2grok.core.pb.Pb.fields(m[1]!!)
        assertTrue(ri.containsKey(1), "空 requestId 也必须显式写 f1，实际 ${ri.keys}")
        assertEquals(0, ri[1]!!.size)
    }

    // -------------------------------------------------- id=5 会话状态（双向）
    // 2026-11-11 真机对照实验（实测）：
    //   只写 f1 的 `2a 02 08 00` → 发出去后眼镜继续推流 23 s，无效
    //   6 字段全量         → 12 s 只多 1 个包，立刻停采集
    // 所以断言锁的是「6 个字段全在」，这正是当初踩的坑。

    @Test
    fun `E1 结束语音会话 = Voice{f5} 六字段全量`() {
        assertEquals(
            "2a 0c 08 00 10 00 18 00 20 00 28 00 30 00",
            VoiceCodec.endVoicePayload().toHexSpaced(),
        )
    }

    @Test
    fun `E2 容器字段号是 5 不是别的`() {
        val voice = VoiceCodec.endVoicePayload()
        val fields = io.github.slchy.glasses2grok.core.pb.Pb.fields(voice)
        assertEquals(setOf(5), fields.keys, "id=5 的载荷字段是 Voice.f5，见命令图谱")
        assertEquals(0x2A.toByte(), voice[0], "f5 的 wire-2 tag 是 0x2A")
    }

    @Test
    fun `E3 六个字段必须全部显式写出缺席就是 bug`() {
        // 这条是回归护栏：只写 f1 的版本真机上根本不生效
        val status = io.github.slchy.glasses2grok.core.pb.Pb.fields(VoiceCodec.endVoicePayload())[5]!!
        val inner = io.github.slchy.glasses2grok.core.pb.Pb.fields(status)
        assertEquals(setOf(1, 2, 3, 4, 5, 6), inner.keys, "f1..f6 一个都不能少")
        for (n in 1..6) {
            assertEquals(0L, io.github.slchy.glasses2grok.core.pb.Pb.varintValue(inner[n]!!), "f$n")
        }
    }

    @Test
    fun `E4 buildVoiceStatus 能带任意 VoiceStatus 字节`() {
        val voice = VoiceCodec.buildVoiceStatus(hex("08 01"))
        assertEquals("2a 02 08 01", voice.toHexSpaced())
    }

    @Test
    fun `E5 设备上报的 id=5 能解析成六字段`() {
        // 2026-11-11 真机原样：设备发给我们的 id=5
        val ev = VoiceCodec.parse(5, hex("2a 0c 08 00 10 00 18 00 20 00 28 00 30 00"))
        val s = ev as VoiceEvent.SyncStatus
        assertEquals(listOf(0, 0, 0, 0, 0, 0), listOf(s.voice, s.tts, s.mediaPlay, s.image, s.operating, s.device))
    }

    @Test
    fun `E6 id=5 缺 f5 时退回 Unknown 而不是抛异常`() {
        assertTrue(VoiceCodec.parse(5, hex("08 00")) is VoiceEvent.Unknown)
    }
}
