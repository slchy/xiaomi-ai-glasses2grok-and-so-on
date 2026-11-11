package com.miaiglasses.core.l2

import com.miaiglasses.core.config.GlassesProtocol

/**
 * L2 层的 `[channel][opCode]` 两字节头。
 *
 * 两条关键规则：
 * - **加密只由 `opCode == 2` 决定，与 channel 无关**（实测）。
 * - channel 是**开放集合**：未来固件可能给新值，所以用 Int 常量而不是 enum，
 *   未知 channel 必须能原样上报，不能崩。
 */
internal object L2 {
    // ---- channel
    const val CH_PB = GlassesProtocol.L2.CH_PB
    const val CH_MASS = GlassesProtocol.L2.CH_MASS
    const val CH_MASS_VOICE = GlassesProtocol.L2.CH_MASS_VOICE
    const val CH_FILE_SENSOR = GlassesProtocol.L2.CH_FILE_SENSOR
    const val CH_FILE_FITNESS = GlassesProtocol.L2.CH_FILE_FITNESS
    const val CH_OTA = GlassesProtocol.L2.CH_OTA
    const val CH_NETWORK = GlassesProtocol.L2.CH_NETWORK
    const val CH_LYRA = GlassesProtocol.L2.CH_LYRA
    const val CH_RESEARCH = GlassesProtocol.L2.CH_RESEARCH

    // ---- opCode
    const val OP_WRITE = GlassesProtocol.L2.OP_WRITE
    const val OP_WRITE_ENC = GlassesProtocol.L2.OP_WRITE_ENC
    const val OP_READ = GlassesProtocol.L2.OP_READ

    const val HEADER_SIZE = GlassesProtocol.L2.HEADER_SIZE

    fun name(channel: Int): String = when (channel) {
        CH_PB -> "PB"
        CH_MASS -> "MASS"
        CH_MASS_VOICE -> "MASS_VOICE"
        CH_FILE_SENSOR -> "FILE_SENSOR"
        CH_FILE_FITNESS -> "FILE_FITNESS"
        CH_OTA -> "OTA"
        CH_NETWORK -> "NETWORK"
        CH_LYRA -> "LYRA"
        CH_RESEARCH -> "RESEARCH"
        else -> "CH$channel"
    }

    /** 该 channel 收到数据是否需要回 ACK。channel 7 双向都不 ACK。 */
    fun requiresAck(channel: Int): Boolean = channel != CH_NETWORK

    /** 该 channel 是否走「立即 ACK」而不是累积 ACK。 */
    fun ackImmediately(channel: Int): Boolean = channel == CH_PB || channel == CH_LYRA

    /** 该 channel 是否需要解密。 */
    fun isEncrypted(opCode: Int): Boolean = opCode == OP_WRITE_ENC
}

/**
 * 已剥掉 L2 头的载荷。
 *
 * @property channel 通道号
 * @property opCode  1 明文 / 2 AES-CTR / 3 READ
 * @property body    业务载荷（加密帧在此处**尚未解密**，由会话层的密钥处理）
 */
internal class L2Packet(
    val channel: Int,
    val opCode: Int,
    val body: ByteArray,
) {
    val channelName: String get() = L2.name(channel)

    override fun toString(): String =
        "$channelName(ch=$channel, op=$opCode, len=${body.size})"
}

internal object L2Codec {

    fun build(channel: Int, opCode: Int, body: ByteArray): ByteArray {
        val out = ByteArray(L2.HEADER_SIZE + body.size)
        out[0] = (channel and 0xFF).toByte()
        out[1] = (opCode and 0xFF).toByte()
        body.copyInto(out, L2.HEADER_SIZE)
        return out
    }

    /** 解析 L1 载荷。不足 2 字节返回 null —— 短包一律丢弃，不猜。 */
    fun parse(data: ByteArray?): L2Packet? {
        if (data == null || data.size < L2.HEADER_SIZE) return null
        return L2Packet(
            channel = data[0].toInt() and 0xFF,
            opCode = data[1].toInt() and 0xFF,
            body = data.copyOfRange(L2.HEADER_SIZE, data.size),
        )
    }
}
