package io.github.slchy.glasses2grok.core.mass

import java.io.ByteArrayOutputStream

/**
 * `L2 ch=2 MASS` 下行的文件分片组装（`Media 17/18` 的响应）。
 *
 * ## 帧格式（2026-11-11 真机反复抓出来的，见 `实测`）
 *
 * ```text
 * 片头 6 B: 00 | 83 | <1B 媒体类> | 00 | <1B 分片号, 从 1 开始> | 00
 * 仅第 1 片额外:  <1B 标识符长度> | <identifier ASCII> | <5 B 固定 0>
 * 之后就是文件字节；整份文件的末尾另有 4 B 尾标（不属于文件本体）
 * ```
 *
 * 真机核对（`LLHDR_20261111211521821_4032x3024_5.jpg`）：
 * 5 片共 19 002 B = 5×6(片头) + (1+39+5)(首片额外) + **18 923**(=列表 `size`) + 4(尾标)。
 *
 * ## 结束判定
 *
 * 协议里**没有**显式的「最后一片」标志（5 片全是 `0x83`），真机观测到唯一的信号是
 * **末片整帧短于 4 096 B**（前 4 片都是 4 096，末片 2 618）。注意判据是**整帧**长度，
 * 不是正文长度 —— 首片的正文天然短（要先吃掉片头 + identifier）。
 * 所以这里用「短帧即结束」+ [flush] 兜底：若设备恰好整 4 096×N 结尾，等调用方 flush。
 *
 * 本类只处理**单文件串行流**（参考实现行为：一个请求一个文件、分片号 1..N）。
 */
/** 组装完成的一份文件。 */
public data class MassFileData(
    /** 首片里带的 identifier（`MediaFile.Identifier.id`）。 */
    val identifier: String,
    /** 片头第 3 字节，真机：2=音频、4=视频、5=图片。 */
    val mediaType: Int,
    /** 文件本体字节（**已去掉 4 B 尾标**）。 */
    val bytes: ByteArray,
    /** 尾标（4 B），语义未确证，原样留着便于将来核对。 */
    val trailer: ByteArray,
    /** 分片数。 */
    val slices: Int,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun toString(): String =
        "MassFileData(identifier=$identifier, mediaType=$mediaType, ${bytes.size}B, slices=$slices)"
}
