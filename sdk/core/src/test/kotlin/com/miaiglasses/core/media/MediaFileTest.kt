package com.miaiglasses.core.media

import com.miaiglasses.core.pb.Pb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Media` 通道「按 identifier 取文件」的编解码。
 *
 * 字段号来源（参考实现，见 `实测`）：
 * - `RequestMediaFile.getPackedId()` = `0x11`(17)，载荷 `Media.f15 = Identifier{ f1 id, f2 thumbnail }`
 * - `RequestMediaFileList.getPackedId()` = `0x12`(18)，载荷 `Media.f16 = Identifier.List{ f1 repeated }`
 * - `ConfirmMediaFile.getPackedId()` = `0x13`(19)
 * - `SyncMediaFileList.getPackedId()` = `0x0f`(15)，空载荷，**回包 id=16 / `f14 mediaFileList`**
 */
class MediaFileTest {

    @Test
    fun `MediaFileIds - 命令号与协议规范一致`() {
        assertEquals(15, MediaFileIds.ID_SYNC_LIST)
        assertEquals(16, MediaFileIds.ID_LIST_RESPONSE)
        assertEquals(17, MediaFileIds.ID_REQUEST_FILE)
        assertEquals(18, MediaFileIds.ID_REQUEST_FILE_LIST)
        assertEquals(19, MediaFileIds.ID_CONFIRM_FILE)
    }

    @Test
    fun `requestFilePayload - f15 里是 id 与 thumbnail`() {
        val media = Pb.fields(MediaFileCodec.requestFilePayload("IMG_20261111_120000.jpg", true))
        assertEquals(setOf(15), media.keys)

        val identifier = Pb.fields(media[15]!!)
        assertEquals("IMG_20261111_120000.jpg", String(identifier[1]!!))
        assertEquals(1L, Pb.varintValue(identifier[2]!!), "参考实现 17 恒定 thumbnail=true")
    }

    @Test
    fun `requestFilePayload - thumbnail=false 时不写该字段`() {
        val identifier = Pb.fields(Pb.fields(MediaFileCodec.requestFilePayload("a.jpg", false))[15]!!)
        assertEquals(setOf(1), identifier.keys)
    }

    @Test
    fun `requestFileListPayload - f16 是 Identifier List`() {
        val media = Pb.fields(MediaFileCodec.requestFileListPayload(listOf("a.jpg", "b.mp4"), true))
        assertEquals(setOf(16), media.keys)

        val list = Pb.fields(media[16]!!)
        assertEquals(setOf(1), list.keys, "List 只用 f1 装 repeated Identifier")
        val raw = media[16]!!
        // 两条 Identifier 都是 f1（wire 2），这里用 Pb.fields 只能拿到最后一条，所以手工数 tag
        assertEquals(2, countField(raw, 1))
    }

    @Test
    fun `MediaFileInfo - 解析列表里的一条`() {
        val payload = mediaPayload {
            val file = java.io.ByteArrayOutputStream()
            val identifier = java.io.ByteArrayOutputStream()
            Pb.string(identifier, 1, "AUD_20261105.jpg")
            Pb.uint(identifier, 2, 0)
            Pb.bytes(file, 1, identifier.toByteArray())
            Pb.uint(file, 2, MediaFormat.OPUS.toLong())
            Pb.uint(file, 3, 2_152_000)
            Pb.uint(file, 4, 1_794_385_622_173)
            Pb.uint(file, 5, 12_370)
            file.toByteArray()
        }
        val list = com.miaiglasses.core.query.MediaList.parse(payload)!!
        assertEquals(1, list.files.size)
        val f = list.files.single()
        assertEquals("AUD_20261105.jpg", f.identifier)
        assertFalse(f.thumbnail)
        assertEquals(MediaFormat.OPUS, f.type)
        assertEquals(2_152_000L, f.size)
        assertEquals(1_794_385_622_173L, f.timestamp)
        assertEquals(12_370L, f.duration)
        assertEquals(com.miaiglasses.core.query.MediaKind.AUDIO, f.kind)
    }

    @Test
    fun `MediaList - 空列表与坏条目`() {
        val empty = java.io.ByteArrayOutputStream()
        Pb.bytes(empty, 14, ByteArray(0))
        val list = com.miaiglasses.core.query.MediaList.parse(empty.toByteArray())!!
        assertTrue(list.entries.isEmpty())
        assertTrue(list.files.isEmpty())

        // 条目里没有 identifier ⇒ 只保留 hex，不塞进 files
        val broken = mediaPayload { java.io.ByteArrayOutputStream().also { Pb.uint(it, 2, 0) }.toByteArray() }
        val b = com.miaiglasses.core.query.MediaList.parse(broken)!!
        assertEquals(1, b.entries.size)
        assertTrue(b.files.isEmpty(), "缺 Identifier 的条目不能瞎猜")
        assertNull(com.miaiglasses.core.query.MediaList.parse(ByteArray(0)))
    }

    /** 包一层 `Media{ f14 = MediaFile.List{ f1 = <entry> } }`。 */
    private fun mediaPayload(entry: () -> ByteArray): ByteArray {
        val list = java.io.ByteArrayOutputStream()
        Pb.bytes(list, 1, entry())
        val media = java.io.ByteArrayOutputStream()
        Pb.bytes(media, 14, list.toByteArray())
        return media.toByteArray()
    }

    private fun countField(data: ByteArray, field: Int): Int {
        var i = 0
        var n = 0
        while (i < data.size) {
            var tag = 0L
            var j = i
            var shift = 0
            while (j < data.size) {
                val b = data[j].toInt() and 0xFF
                tag = tag or ((b and 0x7F).toLong() shl shift)
                j++
                if (b and 0x80 == 0) break
                shift += 7
            }
            val f = (tag ushr 3).toInt()
            when ((tag and 7L).toInt()) {
                0 -> {
                    while (j < data.size && (data[j].toInt() and 0x80) != 0) j++
                    j++
                }
                2 -> {
                    var len = 0L
                    shift = 0
                    while (j < data.size) {
                        val b = data[j].toInt() and 0xFF
                        len = len or ((b and 0x7F).toLong() shl shift)
                        j++
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (f == field) n++
                    j += len.toInt()
                }
                else -> return n
            }
            i = j
        }
        return n
    }
}
