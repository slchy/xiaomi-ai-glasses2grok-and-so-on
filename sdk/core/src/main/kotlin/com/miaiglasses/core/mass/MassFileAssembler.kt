package com.miaiglasses.core.mass

import com.miaiglasses.core.config.GlassesProtocol
import java.io.ByteArrayOutputStream

internal class MassFileAssembler(private val sliceSize: Int = 4096) {

    private var identifier: String = ""
    private var mediaType: Int = 0
    private var out = ByteArrayOutputStream()
    private var expectedSeq = 1
    private var slices = 0
    private var pending = false

    /** 是否正在收一份文件（中间态）。 */
    public val isInProgress: Boolean get() = pending

    /**
     * 喂一片。返回非 null 表示这一片把文件收完了。
     *
     * 分片号不连续/首片缺失时**丢弃重来** —— 拼错的文件比没有文件更糟。
     */
    public fun feed(frame: ByteArray): MassFileData? {
        if (frame.size < HEADER) return null
        val type = frame[2].toInt() and 0xFF
        val seq = frame[4].toInt() and 0xFF
        if (seq != expectedSeq) {
            reset()
            return null
        }
        var bodyStart = HEADER
        if (seq == 1) {
            val idLen = frame[6].toInt() and 0xFF
            if (frame.size < HEADER + 1 + idLen + EXTRA_ZEROS) {
                reset()
                return null
            }
            identifier = String(frame, 7, idLen, Charsets.UTF_8)
            mediaType = type
            bodyStart = 7 + idLen + EXTRA_ZEROS
        } else if (!pending) {
            // 没有首片就来后续片：无从知道 identifier，直接丢
            return null
        }
        val body = frame.copyOfRange(bodyStart, frame.size)
        out.write(body)
        slices++
        pending = true
        expectedSeq = if (seq == 0xFF) 1 else seq + 1
        // 末片判据：**整帧**短于片长。首片的 body 天然比整帧短（片头 + identifier 占位），
        // 所以绝不能拿 body 长度来判断，否则第一片就会被当成完整文件吐出去（真机踩过）。
        if (frame.size < sliceSize) return finish()
        return null
    }

    /** 兜底收尾：把已累积的字节当成完整文件吐出来（超过 [flushTimeout] 仍没新片时用）。 */
    public fun flush(): MassFileData? = if (pending) finish() else null

    /** 丢弃中间态。 */
    public fun reset() {
        identifier = ""
        mediaType = 0
        out = ByteArrayOutputStream()
        expectedSeq = 1
        slices = 0
        pending = false
    }

    private fun finish(): MassFileData? {
        val all = out.toByteArray()
        val name = identifier
        val type = mediaType
        val n = slices
        reset()
        if (all.size <= TRAILER) return null
        return MassFileData(
            identifier = name,
            mediaType = type,
            bytes = all.copyOfRange(0, all.size - TRAILER),
            trailer = all.copyOfRange(all.size - TRAILER, all.size),
            slices = n,
        )
    }

    public companion object {
        /** 片头长度：`00 83 type 00 seq 00`。 */
        public const val HEADER: Int = 6

        /** 首片在 identifier 之后还有 5 个固定 0 字节。 */
        public const val EXTRA_ZEROS: Int = 5

        /** 文件本体之后的尾标长度（真机 4 B）。 */
        public const val TRAILER: Int = 4

        /** 建议的收尾等待：末片之后设备不再发帧，正常 200 ms 就够。 */
        public const val FLUSH_TIMEOUT_MS: Long = 800
    }
}
