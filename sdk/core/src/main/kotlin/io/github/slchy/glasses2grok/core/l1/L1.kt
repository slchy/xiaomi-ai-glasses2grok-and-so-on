package io.github.slchy.glasses2grok.core.l1

import io.github.slchy.glasses2grok.core.Crc16Arc
import io.github.slchy.glasses2grok.core.config.GlassesProtocol

/**
 * A5A5 帧层常量与帧模型。
 *
 * 8 字节头（实测）：
 * ```
 * [0:2]  A5 A5
 * [2]    (frx << 4) | type
 * [3]    seq
 * [4:6]  payload 长度（小端）
 * [6:8]  CRC-16/ARC(payload)（小端）
 * [8:]   payload
 * ```
 *
 * CRC **只覆盖 payload**，不含长度字段 —— 这是最容易写错的一处。
 */
internal object L1 {
    const val MAGIC: Int = GlassesProtocol.L1.MAGIC
    const val HEADER_SIZE: Int = GlassesProtocol.L1.HEADER_SIZE

    /** 长度字段是 16 位，单帧上限由协议决定，不由缓冲区决定。 */
    const val MAX_PAYLOAD: Int = GlassesProtocol.L1.MAX_PAYLOAD

    const val TYPE_NAK: Int = GlassesProtocol.L1.TYPE_NAK
    const val TYPE_ACK: Int = GlassesProtocol.L1.TYPE_ACK
    const val TYPE_CMD: Int = GlassesProtocol.L1.TYPE_CMD
    const val TYPE_DATA: Int = GlassesProtocol.L1.TYPE_DATA

    /** 帧头 + 最大载荷。超过它的「长度」只可能是伪魔数。 */
    const val MAX_FRAME_BYTES: Int = MAX_PAYLOAD + HEADER_SIZE
}

/**
 * 一帧已解析的 L1 帧。载荷是不可变副本，可安全跨线程传递。
 *
 * @property type 低 4 位（NAK/ACK/CMD/DATA）
 * @property frx  第 4 位：DATA 上是「序列重同步」，ACK/NAK 上是「force」
 * @property seq  序号（0..255）
 * @property crc  帧头里携带的 CRC（ACK/NAK 恒为 0）
 */
internal class L1Frame(
    val type: Int,
    val frx: Int,
    val seq: Int,
    val payload: ByteArray,
    val crc: Int,
) {
    val totalLength: Int get() = L1.HEADER_SIZE + payload.size

    val typeName: String
        get() = when (type) {
            L1.TYPE_NAK -> "NAK"
            L1.TYPE_ACK -> "ACK"
            L1.TYPE_CMD -> "CMD"
            L1.TYPE_DATA -> "DATA"
            else -> "?$type"
        }

    /**
     * 类型层面是否要求 ACK。参考实现规则是「只有 DATA 需要 ACK，且 channel 7 例外」——
     * channel 属于 L2，`core` 的 L1 层不感知，故此处只回答类型问题。
     */
    val requiresAck: Boolean get() = type == L1.TYPE_DATA

    override fun toString(): String = "$typeName(seq=$seq, frx=$frx, len=${payload.size})"
}

/** L1 帧构造。与 `docs/a5a5.md` 规范逐字节一致。 */
internal object L1Codec {

    fun build(
        type: Int,
        seq: Int,
        payload: ByteArray = ByteArray(0),
        frx: Int = 0,
    ): ByteArray = build(type, frx, seq, payload, crcOverride = -1)

    /**
     * @param crcOverride ≥ 0 时强制使用该 CRC。ACK / NAK 必须传 0 ——
     *   空载荷算出来本来就是 0，显式传值是为了让意图在代码里可见，
     *   也避免将来有人「顺手优化」掉它。
     */
    fun build(
        type: Int,
        frx: Int,
        seq: Int,
        payload: ByteArray,
        crcOverride: Int,
    ): ByteArray {
        require(payload.size <= L1.MAX_PAYLOAD) { "payload 过长: ${payload.size} > ${L1.MAX_PAYLOAD}" }
        require(type in 0..0x0F) { "非法 type: $type" }

        val out = ByteArray(L1.HEADER_SIZE + payload.size)
        out[0] = L1.MAGIC.toByte()
        out[1] = L1.MAGIC.toByte()
        out[2] = (((frx and 1) shl 4) or (type and 0x0F)).toByte()
        out[3] = (seq and 0xFF).toByte()
        out[4] = (payload.size and 0xFF).toByte()
        out[5] = ((payload.size ushr 8) and 0xFF).toByte()

        val crc = if (crcOverride >= 0) crcOverride else Crc16Arc.compute(payload)
        out[6] = (crc and 0xFF).toByte()
        out[7] = ((crc ushr 8) and 0xFF).toByte()

        payload.copyInto(out, L1.HEADER_SIZE)
        return out
    }

    /**
     * ACK / NAK：8 字节空载荷，CRC 字段填 0。
     *
     * @param ack   true = ACK(type 1)，false = NAK(type 0)
     * @param force 对应 frx=1，表示要求发送方立即响应
     */
    fun buildAck(seq: Int, ack: Boolean, force: Boolean = false): ByteArray =
        build(
            type = if (ack) L1.TYPE_ACK else L1.TYPE_NAK,
            frx = if (force) 1 else 0,
            seq = seq,
            payload = ByteArray(0),
            crcOverride = 0,
        )
}
