package io.github.slchy.glasses2grok.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import io.github.slchy.glasses2grok.core.album.AlbumApi
import io.github.slchy.glasses2grok.core.album.AlbumEntry
import io.github.slchy.glasses2grok.core.query.QueryResult
import io.github.slchy.glasses2grok.sdk.AlbumClient
import io.github.slchy.glasses2grok.sdk.GlassesSession
import io.github.slchy.glasses2grok.sdk.P2pGroupOwner
import java.io.ByteArrayOutputStream
import java.io.File

/** 高清照失败的原因。`wire` 是发给云端的字符串，与 `cloud/src/photo-inbox.ts` 的 `PhotoFailReason` 对齐。 */
internal enum class PhotoFail(val wire: String) {
    P2p("p2p"),
    Join("join"),
    Connect("connect"),

    /** 建组/入组/连相册整段超时：知道慢了，但说不清卡在哪一步 */
    Arm("arm"),

    Download("download"),
    Decode("decode"),
    Send("send"),

    /** 就绪之后用户一直没按快门 —— 最常见，而且**不是故障** */
    NoShutter("no_shutter"),
}

/**
 * 高清照的**能力层**：一次 P2P、一次相册会话、一次解码缩放。
 *
 * 链路（每一步都有真机依据，见 `参考协议``实测`）：
 *
 * ```text
 * connect()  P2pGroupOwner.create()        手机当 GO 自建组（静默、无弹窗）
 *              → System id=99 让眼镜入组拿回包里眼镜的 IP
 *              → AlbumClient.connectP2pDefaultRoute(ip)
 * list()     filelists                     相册条目（只挑 isFolder）
 * download() downloadPrimary               原图 HEIC，5–8 MB，~30 MB/s
 * encode()   ImageDecoder → 长边 3072 → JPEG q85   约 1.1 MB
 * close()    解散组、关临时文件一次一开，绝不常驻
 * ```
 *
 * 为什么必须在手机上缩：视觉模型实际只吃 1–1.5 MP，传 8 MB HEIC 到云端纯属浪费
 * 上行带宽；而且 Node 侧没有 HEIC 解码器，手机上 `ImageDecoder` 现成。
 *
 * **什么时候建、等多久、失败怎么报** 在 [PhotoJob] / [PhotoFsm] 里 —— 这一层只干活，
 * 不做任何「该不该等」的判断，这样相位逻辑可以脱离 Android 单测。
 *
 * ⚠️ **绝不 `bindProcessToNetwork`**：那会把进程默认路由切到 P2P 网络，正在跑的
 * 云端 TLS 链路当场断。`AlbumClient` 用的是逐连接绑定，安全。
 */
internal class HighResPhoto(
    private val context: Context,
    private val log: (String) -> Unit,
) {

    companion object {
        /**
         * 长边上限。3072 比 2048 多留住约 2.2 倍像素（同一张 4032 底片），
         * 代价是上行从 ~500 KB 到 ~1.1 MB、端到端多 ~0.3 s —— 读小字明显更稳。
         * 注意视觉模型进门还会再缩到 ~1–1.5 MP，所以再往上加收益很小。
         */
        const val LONG_EDGE = 3072
        const val JPEG_QUALITY = 85
    }

    private var owner: P2pGroupOwner? = null
    private var client: AlbumClient? = null
    private var temp: File? = null

    /**
     * 建 P2P 组 → 让眼镜入组 → 连相册服务。
     *
     * @return null 表示成功；否则是失败原因（协议里的失败码）
     */
    fun connect(session: GlassesSession): PhotoFail? {
        val groupOwner = P2pGroupOwner(context)
        if (!groupOwner.isSupported) {
            log("高清拍照 ❌ P2P 不可用：${groupOwner.status}（${groupOwner.reason}）")
            return PhotoFail.P2p
        }
        val group = groupOwner.create()
        if (group == null) {
            log("高清拍照 ❌ 建组失败：${groupOwner.reason}")
            return PhotoFail.P2p
        }
        owner = groupOwner

        // 只发一次 id=99：重复发会让眼镜重新入组，等于白等一轮
        val joined = session.enableWifiP2pGcAndWait(group.ssid, group.passphrase, group.frequency)
        val info = (joined as? QueryResult.Ok)?.value
        if (info == null || !info.isOk || info.ip.isEmpty()) {
            log("高清拍照 ❌ 眼镜入组失败：$joined")
            return PhotoFail.Join
        }

        val album = AlbumClient(context)
        if (!album.connectP2pDefaultRoute(info.ip)) {
            log("高清拍照 ❌ 连不上相册服务 http://${info.ip}:8080")
            return PhotoFail.Connect
        }
        client = album
        return null
    }

    /** 相册条目（只留目录项，也就是一次拍摄）。列失败返回 null。 */
    fun list(): List<AlbumEntry>? {
        val album = client ?: return null
        return try {
            album.filelists().filter { it.isFolder }
        } catch (e: Throwable) {
            log("高清拍照 ❌ 列目录失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 把某个条目的主文件拉下来。之后 [encode] 会读它。 */
    fun download(entry: AlbumEntry): PhotoFail? {
        val album = client ?: return PhotoFail.Connect
        return try {
            val primary = AlbumApi.pickPrimary(album.files(entry))
            if (primary == null) {
                log("高清拍照 ❌ 条目 ${entry.identifier} 里没有可下载文件")
                return PhotoFail.Download
            }
            val file = File(context.cacheDir, "hq_${System.currentTimeMillis()}.heic")
            temp = file
            log("高清拍照拉取 ${primary.url.substringAfterLast('/')}（声明 ${primary.size}B）")
            val bytes = album.download(primary, file)
            log("高清拍照下载完成 ${bytes}B")
            null
        } catch (e: Throwable) {
            log("高清拍照 ❌ 下载失败：${e.javaClass.simpleName}: ${e.message}")
            PhotoFail.Download
        }
    }

    /** 解码 → 缩到长边 [LONG_EDGE] → JPEG。失败返回 null。 */
    fun encode(): ByteArray? {
        val file = temp ?: return null
        return shrink(file)
    }

    /** 用完必须调用：解散 P2P 组、关掉临时文件。 */
    fun close() {
        client?.close()
        client = null
        owner?.close() // 一次一开，不留常驻组
        owner = null
        temp?.delete()
        temp = null
    }

    /** 解码（HEIC/JPEG 都走 `ImageDecoder`）→ 缩到长边 [LONG_EDGE] → JPEG */
    private fun shrink(file: File): ByteArray? {
        val bitmap = decode(file) ?: return null
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longEdge <= LONG_EDGE) {
            bitmap
        } else {
            val scale = LONG_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== bitmap) bitmap.recycle() }
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            scaled.recycle()
            out.toByteArray()
        }
    }

    private fun decode(file: File): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // 先按 1/2 采样解码，省一半内存；后面还会再精确缩一次
                if (maxOf(info.size.width, info.size.height) > 4096) {
                    decoder.setTargetSampleSize(2)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            BitmapFactory.decodeFile(file.absolutePath)
        }
    } catch (e: Throwable) {
        log("高清拍照解码异常：${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
