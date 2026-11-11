package com.miaiglasses.core.album

import com.miaiglasses.core.json.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 黄金样本全部是**真机原文**（2026-11-11，`实测``参考协议`）。
 */
class AlbumTest {

    @Test
    fun `MiniJson - 基本类型与嵌套`() {
        val v = MiniJson.parse(
            """{"a":1,"b":2.5,"c":"x\u0041\n","d":[true,false,null],"e":{"f":-3},"g":[]}""",
        ) as Map<*, *>
        assertEquals(1L, v["a"])
        assertEquals(2.5, v["b"])
        assertEquals("xA\n", v["c"])
        assertEquals(listOf<Any?>(true, false, null), v["d"])
        assertEquals(-3L, (v["e"] as Map<*, *>)["f"])
        assertEquals(emptyList<Any?>(), v["g"])
    }

    @Test
    fun `MiniJson - 非法输入必须抛`() {
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("""{"a":1,}""") }
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("""[1,2""") }
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("""{"a":1} tail""") }
        assertFailsWith<IllegalArgumentException> { MiniJson.parseObject("[]") }
    }

    @Test
    fun `AlbumEntry - 真机 filelists 条目字段齐全`() {
        val json = """
            [{"duration":0,"fileAdded":1794402923000,"fileModified":1794402923000,
              "fileName":"IMG_20261111211521821","height":0,"id":12,
              "identifier":"LLHDR_20261111211521821_4032x3024_5.jpg",
              "md5sum":"12345678901234567890123456789012","mimeType":"image/folder","orientation":0,
              "size":36007408,"thumbnailUrl":"thumbnail/LLHDR_20261111211521821_4032x3024_5.jpg",
              "url":"filelists/LLHDR_20261111211521821_4032x3024_5","width":0}]
        """.trimIndent()
        val e = AlbumEntry.parseList(json).single()
        assertEquals("LLHDR_20261111211521821_4032x3024_5.jpg", e.identifier)
        assertEquals("IMG_20261111211521821", e.fileName)
        assertEquals("image/folder", e.mimeType)
        assertTrue(e.isFolder, "照片是 image/folder")
        assertEquals(36_007_408L, e.size, "整组 5 档 EV 之和")
        assertEquals(1794402923000L, e.fileAddedMillis)
        assertEquals("LLHDR_20261111211521821_4032x3024_5", e.stem)
        assertEquals(
            "/v1/filelists/LLHDR_20261111211521821_4032x3024_5",
            AlbumApi.filelistPath(e),
            "子清单路径就是条目 url，前面补 /v1/",
        )
    }

    @Test
    fun `AlbumFile - 真机 filelist 内部条目（identifier 是父名，靠 url 分辨）`() {
        val json = """
            [{"duration":0,"fileAdded":1794402922000,"fileModified":1794402922000,"height":0,"id":0,
              "identifier":"LLHDR_20261111211521821_4032x3024_5.jpg","mimeType":"image/jpeg","orientation":0,
              "size":234,
              "url":"LLHDR_20261111211521821_4032x3024_5-ARCLLHDR_20261111211521821_4032x3024_ev_0_metadata.txt",
              "width":0},
             {"duration":0,"fileAdded":1794402922000,"fileModified":1794402922000,"height":0,"id":6,
              "identifier":"LLHDR_20261111211521821_4032x3024_5.jpg","mimeType":"image/jpeg","orientation":0,
              "size":7618805,
              "url":"LLHDR_20261111211521821_4032x3024_5-ARCLLHDR_20261111211521821_4032x3024_ev0_0.heic",
              "width":0}]
        """.trimIndent()
        val files = AlbumFile.parseList(json)
        assertEquals(2, files.size)
        assertEquals(234L, files[0].size)
        assertTrue(!files[0].isHeic)
        assertTrue(files[1].isHeic, "真正的图是 .heic")
        assertEquals(7_618_805L, files[1].size)
        assertEquals(
            "/v1/files/LLHDR_20261111211521821_4032x3024_5-ARCLLHDR_20261111211521821_4032x3024_ev0_0.heic",
            AlbumApi.filePath(files[1]),
        )
        // 内部条目的 identifier 是父条目名（真机如此），不能当文件名用
        assertEquals("LLHDR_20261111211521821_4032x3024_5.jpg", files[1].identifier)
    }

    @Test
    fun `AlbumEntry - 缺 identifier 的条目被跳过`() {
        val json = """[{"url":"filelists/x"},{"identifier":"a.jpg","url":"filelists/a"}]"""
        val list = AlbumEntry.parseList(json)
        assertEquals(1, list.size)
        assertEquals("a.jpg", list.single().identifier)
    }

    @Test
    fun `AlbumEntry - 非 folder 条目自己就是文件（录音 size 报 0）`() {
        val json = """
            [{"duration":0,"fileAdded":1794398115000,"fileModified":1794398115000,"height":0,"id":9,
              "identifier":"AUD_20261111195508859.jpg","mimeType":"audio/ogg","orientation":0,"size":0,
              "url":"AUD_20261111195508859_fd2c645a6a5387bfac3ee1bda48249e0_5.ogg","width":0}]
        """.trimIndent()
        val e = AlbumEntry.parseList(json).single()
        assertTrue(!e.isFolder, "audio/ogg 不是 folder")
        val f = assertNotNull(e.asFile, "非 folder 必须能直接当文件")
        assertEquals("AUD_20261111195508859_fd2c645a6a5387bfac3ee1bda48249e0_5.ogg", f.url)
        assertEquals(0L, f.size, "列表里 size 报 0 —— 真长度靠 Content-Length")
        assertEquals(
            "/v1/files/AUD_20261111195508859_fd2c645a6a5387bfac3ee1bda48249e0_5.ogg",
            AlbumApi.filePath(f),
        )
        assertNull(AlbumEntry.parseList("""[{"identifier":"a.jpg","mimeType":"image/folder","url":"filelists/a"}]""")
            .single().asFile, "folder 条目没有 asFile")
    }

    @Test
    fun `WifiApCodec - id=56 载荷结构（f32 WiFiConfig）`() {
        val p = com.miaiglasses.core.wifi.WifiApCodec.setConfigPayload(
            "GlassesSDK-AP",
            "glasses1234",
            com.miaiglasses.core.wifi.WifiApCodec.AUTH_WPA_PSK,
            com.miaiglasses.core.wifi.WifiApCodec.F_5G,
        )
        val cfg = com.miaiglasses.core.pb.Pb.fields(p)[32] ?: error("应有 f32 WiFiConfig")
        val c = com.miaiglasses.core.pb.Pb.fields(cfg)
        val basic = com.miaiglasses.core.pb.Pb.fields(c[1] ?: error("应有 basicInfo"))
        assertEquals("GlassesSDK-AP", String(basic[1]!!))
        assertEquals(2L, com.miaiglasses.core.pb.Pb.varintValue(basic[2]!!), "authMode = WPA_PSK")
        assertEquals(2L, com.miaiglasses.core.pb.Pb.varintValue(basic[3]!!), "frequency = F_5G")
        val psk = com.miaiglasses.core.pb.Pb.fields(c[2] ?: error("应有 pskInfo"))
        assertEquals("glasses1234", String(psk[2]!!), "PSKInfo 的密码在 f2（f1 是 identity）")
    }

    @Test
    fun `AlbumApi - 路径拼接与默认 host`() {
        assertEquals("http://192.168.43.1:8080", AlbumApi.baseUrl())
        assertEquals("/v1/files/a.heic", AlbumApi.filePath(AlbumFile(url = "a.heic", size = 1)))
        assertEquals(
            "/v1/files/a.heic",
            AlbumApi.filePath(AlbumFile(url = "/v1/files/a.heic", size = 1)),
            "url 带前缀时不能拼两次",
        )
        assertNull(MiniJson.parseObject("{}")["x"])
    }

    @Test
    fun `AlbumApi - DELETE 路径与 id 转义`() {
        assertEquals("/v1/files?id=x.jpg", AlbumApi.deletePath("x.jpg"))
        assertEquals(
            "/v1/files?id=a%2Bb+1.jpg",
            AlbumApi.deletePath("a+b 1.jpg"),
            "id 必须 URL 转义（文档里 + / 空格都是非法输入）",
        )
    }

    @Test
    fun `AlbumApi - 主文件挑选：mp4 优先，其次最大 heic`() {
        val files = listOf(
            AlbumFile(url = "a.txt", size = 234),
            AlbumFile(url = "small.heic", size = 100),
            AlbumFile(url = "big.heic", size = 900),
            AlbumFile(url = "clip.mp4", size = 55_900_000),
        )
        assertEquals("clip.mp4", AlbumApi.pickPrimary(files)?.url)
        assertEquals("big.heic", AlbumApi.pickPrimary(files.filterNot { it.url.endsWith(".mp4") })?.url)
        assertEquals("a.txt", AlbumApi.pickPrimary(listOf(AlbumFile("a.txt", 234)))?.url)
        assertNull(AlbumApi.pickPrimary(emptyList()))
    }
}
