package com.miaiglasses.core.media

import com.miaiglasses.core.pb.Pb

/**
 * `Media` 通道上「按 identifier 取文件」的两条命令。
 *
 * 参考实现：
 *
 * | id | 类 | 载荷 |
 * |---:|---|---|
 * | 15 | `SyncMediaFileList` | 空 `Media{}`；**回包走 id=16**、外层 `f14 mediaFileList` |
 * | 17 | `RequestMediaFile(id)` | `f15 Identifier{ f1 id, f2 **thumbnail = true** }` |
 * | 18 | `RequestMediaFileList(list)` | `f16 Identifier.List{ f1 = repeated Identifier }` |
 * | 19 | `ConfirmMediaFile` | 收到后确认 |
 *
 * `Media` 的 oneof 号：`f13 summary` / `f14 fileList` / `f15 identifier` / `f16 identifiers`。
 *
 * **用途**：17 里参考实现硬编码 `thumbnail = true`，所以它要的是**缩略图**（KB 级），
 * 用来在 BLE 上把相册列表先画出来；原图（8.6 MB）/ 录像（26 MB）走这条不现实，
 * 大文件仍然只能 P2P + `/v1/...`（实测）。
 */
internal object MediaFileIds {
    public const val ID_SYNC_LIST: Int = 15
    public const val ID_LIST_RESPONSE: Int = 16
    public const val ID_REQUEST_FILE: Int = 17
    public const val ID_REQUEST_FILE_LIST: Int = 18
    public const val ID_CONFIRM_FILE: Int = 19
}

internal object MediaFileCodec {

    /** `Media.f15`（`设置媒体标识` 把 `载荷类型` 设成 15）。 */
    public const val FIELD_IDENTIFIER: Int = 15

    /** `Media.f16`（`设置媒体标识` → 16）。 */
    public const val FIELD_IDENTIFIERS: Int = 16

    /** `MediaFile.Identifier{ f1 id, f2 thumbnail }`。 */
    private fun identifier(id: String, thumbnail: Boolean): ByteArray =
        java.io.ByteArrayOutputStream().also { out ->
            Pb.string(out, 1, id)
            if (thumbnail) Pb.uint(out, 2, 1)
        }.toByteArray()

    /** `System id=17 RequestMediaFile` 的载荷：`Media{ f15 Identifier{id, thumbnail} }`。 */
    public fun requestFilePayload(identifier: String, thumbnail: Boolean = true): ByteArray {
        val media = java.io.ByteArrayOutputStream()
        Pb.bytes(media, FIELD_IDENTIFIER, identifier(identifier, thumbnail))
        return media.toByteArray()
    }

    /**
     * `System id=18 RequestMediaFileList` 的载荷：
     * `Media{ f16 Identifier.List{ f1 = repeated Identifier{id, thumbnail} } }`。
     */
    public fun requestFileListPayload(identifiers: List<String>, thumbnail: Boolean = true): ByteArray {
        val list = java.io.ByteArrayOutputStream()
        identifiers.forEach { Pb.bytes(list, 1, identifier(it, thumbnail)) }
        val media = java.io.ByteArrayOutputStream()
        Pb.bytes(media, FIELD_IDENTIFIERS, list.toByteArray())
        return media.toByteArray()
    }

    /** `System id=19 ConfirmMediaFile`：确认已收到，载荷同样是 `f15 Identifier`。 */
    public fun confirmPayload(identifier: String): ByteArray =
        requestFilePayload(identifier, thumbnail = false)
}
