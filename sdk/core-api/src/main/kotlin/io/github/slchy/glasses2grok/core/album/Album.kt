package io.github.slchy.glasses2grok.core.album

import io.github.slchy.glasses2grok.core.config.GlassesEndpoints
import io.github.slchy.glasses2grok.core.json.MiniJson
import io.github.slchy.glasses2grok.core.json.int
import io.github.slchy.glasses2grok.core.json.long
import io.github.slchy.glasses2grok.core.json.str

/**
 * 眼镜 AP 上的 HTTP 相册接口。
 *
 * 全部结论出自 `参考协议`（2026-11-08/09 实测）与
 * `实测`（2026-11-11 PC 侧复核），这里只做**协议建模**，不碰 socket：
 *
 * ```text
 * GET  /v1/filelists             → 全部媒体条目（照片 = 一条 image/folder，= 一次拍摄）
 * GET  /v1/filelists/<stem>      → 该条目内部的文件清单（多曝光 heic + 边车）
 * GET  /v1/files/<url>           → 字节流
 * DELETE /v1/files/?id=<stem>.jpg → 参考实现拉完即删设备副本（SDK 不自动调）
 * ```
 *
 * 服务端是 `AndServer/2.1.12`、**无认证**、只做同网段校验 —— 只能在眼镜 AP 内用。
 */
public object AlbumApi {
    public const val PORT: Int = GlassesEndpoints.ALBUM_PORT

    /** 网关/服务地址（实测）。 */
    public const val HOST: String = GlassesEndpoints.ALBUM_HOST

    public const val PATH_FILELISTS: String = GlassesEndpoints.ALBUM_PATH_FILELISTS
    public const val PATH_FILES: String = GlassesEndpoints.ALBUM_PATH_FILES

    public fun baseUrl(host: String = HOST, port: Int = PORT): String = "http://$host:$port"

    /** 某个媒体条目的子清单路径 —— 直接取条目里的 `url` 字段（实测）。 */
    public fun filelistPath(entry: AlbumEntry): String = "/v1/" + entry.url.trim('/').removePrefix("v1/")

    /**
     * 下载路径：`/v1/files/` + `AlbumFile.url`（`url` 自带前缀时也不会拼两次）。
     *
     * 非 folder 的条目（录音 `audio/ogg`、单个文件）本身就是文件，`url` 直接可以走这条路。
     */
    public fun filePath(file: AlbumFile): String =
        PATH_FILES + file.url.trim('/').removePrefix("v1/files/").removePrefix("files/")

    /**
     * 删除设备副本：`DELETE /v1/files?id=<identifier>`。
     *
     * 参考实现拉完就删（实测）。SDK **不自动删**，由宿主决定。
     */
    public fun deletePath(identifier: String): String =
        "/v1/files?id=" + java.net.URLEncoder.encode(identifier, "UTF-8")

    /**
     * 一个条目里「主文件」的挑选规则：录像优先 `.mp4`，其次**最大的** `.heic`，
     * 最后退回最大的任意文件。照片条目是多曝光 HEIC，取最大那张当主图。
     */
    public fun pickPrimary(files: List<AlbumFile>): AlbumFile? {
        files.firstOrNull { it.url.endsWith(".mp4", ignoreCase = true) }?.let { return it }
        files.filter { it.isHeic }.maxByOrNull { it.size }?.let { return it }
        return files.maxByOrNull { it.size }
    }
}

/**
 * `/v1/filelists` 的一条。**照片 = `image/folder`**，`size` 是整组（5 档 EV 之和）。
 *
 * 真机样本见 `实测`。
 */
public data class AlbumEntry(
    val identifier: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val fileAddedMillis: Long,
    val fileModifiedMillis: Long,
    /** 子清单路径（相对 `/v1/`），如 `filelists/LLHDR_…_5`。 */
    val url: String,
    val thumbnailUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
) {
    /** 是不是「一次拍摄」的文件夹（照片/录像）。 */
    val isFolder: Boolean get() = mimeType.endsWith("/folder")

    /** 去掉扩展名的 stem —— 子清单与 `DELETE` 都用它。 */
    val stem: String get() = identifier.substringBeforeLast('.', identifier)

    /**
     * 非 folder 的条目（录音 `audio/ogg`、单个文件）—— 它自己就是文件，
     * 直接当 [AlbumFile] 下载；`size` 在列表里是 0，真长度靠 `Content-Length`。
     */
    val asFile: AlbumFile? get() = if (isFolder) null else AlbumFile(url = url, size = size, identifier = identifier)

    public companion object {
        public fun parseList(json: String): List<AlbumEntry> =
            MiniJson.parseArray(json).mapNotNull { parseOne(it) }

        private fun parseOne(any: Any?): AlbumEntry? {
            val m = any as? Map<String, Any?> ?: return null
            val identifier = m.str("identifier") ?: return null
            return AlbumEntry(
                identifier = identifier,
                fileName = m.str("fileName") ?: identifier,
                mimeType = m.str("mimeType") ?: "",
                size = m.long("size") ?: 0L,
                fileAddedMillis = m.long("fileAdded") ?: 0L,
                fileModifiedMillis = m.long("fileModified") ?: 0L,
                url = m.str("url") ?: "",
                thumbnailUrl = m.str("thumbnailUrl"),
                width = m.int("width") ?: 0,
                height = m.int("height") ?: 0,
                durationMs = m.long("duration") ?: 0L,
            )
        }
    }
}

/**
 * `/v1/filelists/<stem>` 的一条：条目内部的一个真实文件。
 *
 * 对**非 folder** 的条目（录音 `audio/ogg`、单个文件），列表里那一条本身就当 [AlbumFile] 用
 * —— 但它的 `size` 报 **0**（真机如此），实际长度只能从 HTTP `Content-Length` 取。
 */
public data class AlbumFile(
    /** 下载路径（相对 `/v1/files/`），如 `LLHDR_…_5-ARC…_ev0_0.heic`。 */
    val url: String,
    val size: Long,
    val identifier: String = "",
) {
    val isHeic: Boolean get() = url.endsWith(".heic", ignoreCase = true)
    val isJpeg: Boolean get() = url.endsWith(".jpg", ignoreCase = true) || url.endsWith(".jpeg", ignoreCase = true)

    public companion object {
        /**
         * 内部清单的 `identifier` 字段是**父条目的名字**（真机如此），真正的文件名在 `url` 里，
         * 所以这里以 `url` 为准；`identifier` 只作参考。
         */
        public fun parseList(json: String): List<AlbumFile> =
            MiniJson.parseArray(json).mapNotNull { any ->
                val m = any as? Map<String, Any?> ?: return@mapNotNull null
                val url = m.str("url") ?: return@mapNotNull null
                AlbumFile(
                    url = url,
                    size = m.long("size") ?: 0L,
                    identifier = m.str("identifier") ?: "",
                )
            }
    }
}
