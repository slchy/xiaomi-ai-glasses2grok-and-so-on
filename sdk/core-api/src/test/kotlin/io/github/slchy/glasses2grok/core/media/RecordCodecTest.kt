package io.github.slchy.glasses2grok.core.media

import io.github.slchy.glasses2grok.core.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `Media id=20 RequestRecord` 的编码与回执解析。
 *
 * 字段号来自参考实现（nano protobuf，名字没被混淆）：
 * `Media.f17 = RecordRequest{f1=type int32, f2=action uint32}`、
 * `Media.f18 = RecordResponse{f1=code, f2=mediaFileId{f1=id}, f3=duration}`。
 */
class RecordCodecTest {

    @Test
    fun `开始录音的载荷就是 Media_f17_RecordRequest`() {
        // f17(0x8a 0x01) len=4 │ f1=0 │ f2=0
        assertEquals(
            "8a010408001000",
            Hex.encode(RecordCodec.requestPayload(RecordType.NORMAL, RecordAction.START)),
        )
    }

    @Test
    fun `停止录音只差 action 一个字节`() {
        assertEquals(
            "8a010408001001",
            Hex.encode(RecordCodec.requestPayload(RecordType.NORMAL, RecordAction.STOP)),
        )
    }

    @Test
    fun `type 走 varint - 翻译录音是 3`() {
        assertEquals(
            "8a010408031000",
            Hex.encode(RecordCodec.requestPayload(RecordType.TRANSLATION, RecordAction.START)),
        )
    }

    @Test
    fun `参考实现五个 type 常量与数值一一对应`() {
        // protobuf 定义: RECORD_NORMAL=0 RECORD_REALTIME=1 RECORD_MUSIC=2
        //              RECORD_TRANSLATION=3 RECORD_TRANSLATION_FACE=4
        assertEquals(0, RecordType.NORMAL)
        assertEquals(1, RecordType.REALTIME)
        assertEquals(2, RecordType.MUSIC)
        assertEquals(3, RecordType.TRANSLATION)
        assertEquals(4, RecordType.TRANSLATION_FACE)
    }

    @Test
    fun `解析 RecordResponse`() {
        // f18(0x92 0x01) len=12 │ f1=code 0 │ f2{f1="abc"} │ f3=duration 1234
        val resp = assertNotNull(
            RecordCodec.parseResponse(
                Hex.decode("92 01 0c 08 00 12 05 0a 03 61 62 63 18 d2 09"),
            ),
        )
        assertTrue(resp.isOk, "code=0 即成功")
        assertEquals(0, resp.code)
        assertEquals("abc", resp.mediaFileId)
        assertEquals(1234, resp.duration)
    }

    @Test
    fun `code 非 0 就是失败，并把参考实现文案标识带出来`() {
        // 真机样本：眼镜拒绝「开始录音」时回的 code=5
        val refused = assertNotNull(
            RecordCodec.parseResponse(Hex.decode("92 01 0a 08 05 12 04 0a 00 10 00 18 00")),
        )
        assertEquals(5, refused.code)
        assertTrue(!refused.isOk)
        assertEquals("UNKNOWN(5)", refused.codeName, "参考实现把 3/5 映射到了 Alipay 文案，语义不明")

        // 「停止」在没录音时回 4 = NOT_IN_RECORDING（参考实现）
        val notRecording = assertNotNull(
            RecordCodec.parseResponse(Hex.decode("92 01 0a 08 04 12 04 0a 00 10 00 18 00")),
        )
        assertEquals(4, notRecording.code)
        assertEquals("NOT_IN_RECORDING", notRecording.codeName)
        assertEquals(RecordCode.NOT_IN_RECORDING, notRecording.code)
    }

    @Test
    fun `没有 f18 时返回 null 而不是瞎猜`() {
        assertEquals(null, RecordCodec.parseResponse(Hex.decode("0a 02 08 01")))
        assertEquals(null, RecordCodec.parseResponse(ByteArray(0)))
    }
}
