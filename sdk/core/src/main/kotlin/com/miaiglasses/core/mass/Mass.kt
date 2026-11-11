package com.miaiglasses.core.mass

import com.miaiglasses.core.config.GlassesProtocol

/**
 * MASS：`ch=3` 上复用的流（语音 / 图片 / OCR 元数据共用一个通道）。
 *
 * 12 字节头，**大端**：
 * ```
 * [0]      1   保留（App 完全不读，发送时填 0）
 * [1]      1   command：1 START / 2 DATA / 3 END
 * [2:4]    2   帧长 − 4（自描述长度，App 不读）
 * [4:6]    2   MassType（仅 DATA）
 * [6:10]   4   本流总数据长度（首片携带，后续片为 0）
 * [10:12]  2   本帧数据长度
 * [12:]    -   载荷
 * ```
 *
 * 两条铁律：
 * 1. **只能按 MassType 分流，不能按帧长** —— 图片分片可能恰好 582 字节（12 + 570）。
 * 2. **短包合法**：设备真实的 END 只有 2 字节 `00 03`，不带 MassType 与总长。
 */
internal object MassType {
    const val FIRST_VOICE = GlassesProtocol.Mass.FIRST_VOICE
    const val FOLLOW_VOICE = GlassesProtocol.Mass.FOLLOW_VOICE
    const val FIRST_IMAGE = GlassesProtocol.Mass.FIRST_IMAGE
    const val FOLLOW_IMAGE = GlassesProtocol.Mass.FOLLOW_IMAGE
    const val IMAGE_OCR = GlassesProtocol.Mass.IMAGE_OCR
    const val WAKEUP_FIRST = GlassesProtocol.Mass.WAKEUP_FIRST
    const val WAKEUP_FOLLOW = GlassesProtocol.Mass.WAKEUP_FOLLOW
    const val SMALL_PICTURE = GlassesProtocol.Mass.SMALL_PICTURE
    const val LARGE_PICTURE = GlassesProtocol.Mass.LARGE_PICTURE
    const val XIAO_AI_TRACK = GlassesProtocol.Mass.XIAO_AI_TRACK
    const val IMAGE_HD_FOLLOW = GlassesProtocol.Mass.IMAGE_HD_FOLLOW

    /** 短包不携带 MassType。 */
    const val UNKNOWN = -1

    fun isImage(t: Int): Boolean = t == FIRST_IMAGE || t == FOLLOW_IMAGE || t == IMAGE_HD_FOLLOW

    fun isVoice(t: Int): Boolean =
        t == FIRST_VOICE || t == FOLLOW_VOICE || t == WAKEUP_FIRST || t == WAKEUP_FOLLOW
}

internal object MassCommand {
    const val START = GlassesProtocol.Mass.CMD_START
    const val DATA = GlassesProtocol.Mass.CMD_DATA
    const val END = GlassesProtocol.Mass.CMD_END
}

/**
 * 一帧 MASS 头的解析结果。
 *
 * @property cmd        command（短包也一定有）
 * @property massType   MassType；短包为 [MassType.UNKNOWN]
 * @property total      本流总长；0 = 设备未声明
 * @property declaredLen 头里声明的本帧长度（诊断用，可能被设备截断）
 * @property payloadOff 载荷在原始数组中的起点
 * @property payloadLen **实际可用**长度（已按数组长度截断），可直接喂给下游
 */
internal class MassFrame internal constructor(
    val cmd: Int,
    val massType: Int,
    val total: Int,
    val declaredLen: Int,
    val payloadOff: Int,
    val payloadLen: Int,
) {
    val isEnd: Boolean get() = cmd == MassCommand.END
    val isImage: Boolean get() = MassType.isImage(massType)
    val isVoice: Boolean get() = MassType.isVoice(massType)

    override fun toString(): String =
        "Mass(cmd=$cmd, type=$massType, total=$total, len=$payloadLen)"
}

internal object MassCodec {
    const val HEADER_SIZE = GlassesProtocol.Mass.HEADER_SIZE

    /**
     * 解析一帧 `ch=3` 载荷。连 command 都读不到（< 2 字节）时返回 null。
     *
     * **不抛异常**：设备在流边界上偶尔给短包，丢弃比中断整条链路安全。
     */
    fun parse(body: ByteArray?): MassFrame? {
        if (body == null || body.size < 2) return null
        val cmd = body[1].toInt() and 0xFF
        if (body.size < HEADER_SIZE) {
            // 短包：只有 command 可信
            return MassFrame(cmd, MassType.UNKNOWN, 0, 0, body.size, 0)
        }
        val massType = be16(body, 4)
        val total = be32(body, 6)
        val declaredLen = be16(body, 10)
        val avail = body.size - HEADER_SIZE
        return MassFrame(
            cmd = cmd,
            massType = massType,
            total = total,
            declaredLen = declaredLen,
            payloadOff = HEADER_SIZE,
            payloadLen = minOf(declaredLen, avail),
        )
    }

    /**
     * 构造 DATA 帧。
     *
     * `[2:4]` 按 `8 + payloadLen` 填写（满足 `[2:4] = 8 + [10:12]` 的自校验关系），
     * `[0]` 保留位填 0 —— App 不读它，但惯例是 0。
     */
    fun buildData(massType: Int, total: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        val out = ByteArray(HEADER_SIZE + length)
        out[0] = 0
        out[1] = MassCommand.DATA.toByte()
        putBe16(out, 2, 8 + length)
        putBe16(out, 4, massType)
        putBe32(out, 6, total)
        putBe16(out, 10, length)
        payload.copyInto(out, HEADER_SIZE, offset, offset + length)
        return out
    }

    /** 构造短 END（设备实际用的就是 2 字节）。 */
    fun buildEnd(): ByteArray = byteArrayOf(0, MassCommand.END.toByte())

    fun be16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    internal fun putBe16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    internal fun putBe32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }
}
