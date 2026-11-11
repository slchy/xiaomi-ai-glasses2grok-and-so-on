package com.miaiglasses.core.sar

import com.miaiglasses.core.config.GlassesProtocol
import com.miaiglasses.core.l1.L1

/** 队列里的一个待发项。字段与 `参考实现` 一一对应。 */
internal class SendItem internal constructor(
    val type: Int,
    val payload: ByteArray,
    internal var seq: Int = 0,
    internal var assigned: Boolean = false,
    internal var waitAck: Boolean = false,
    internal var needRetrans: Boolean = false,
    internal var deadlineMs: Long? = null,
    private val onDone: ((Int) -> Unit)? = null,
    val tag: String = "",
    /** 背压车道。只影响**准入**，不改变发送顺序。 */
    val lane: Lane = Lane.CONTROL,
) {
    /** 参考实现：只有 DATA 需要 ACK；CMD 发送即完成。 */
    val requiresAck: Boolean get() = type == L1.TYPE_DATA

    val seqNumber: Int get() = seq
    val awaitingAck: Boolean get() = waitAck
    val needsRetransmission: Boolean get() = needRetrans

    internal fun complete(code: Int) {
        onDone?.invoke(code)
    }

    override fun toString(): String =
        "<${tag.ifEmpty { "TYPE$type" }} seq=$seq ack=$waitAck retrans=$needRetrans>"
}

/** 完成码。与参考实现常量对齐。 */
internal object SarResult {
    const val OK = 1
    const val ABORTED = -1
    const val SEND_FAILED = -2

    /** 超过对端 MPS。**不拆分** —— MPS 是单帧上限，不是分段大小。 */
    const val EXCEEDS_MPS = -3

    /** 命令/协商超时。 */
    const val CMD_TIMEOUT = -4

    /** 车道配额已满，本帧没有入队。 */
    const val QUEUE_FULL = -5
}

/**
 * L1 协商配置的 TLV 载荷（`L1START_REQ` / `L1START_RSP` 共用同一份）。
 *
 * 请求方（`HandshakeDriver`）与应答方（`SarEngine`）必须发**逐字节相同**的内容，
 * 两份实现迟早会漂移。值见 `SAR 规格`：
 * VERSION 1.0.0 / MPS 64512 / TX_WIN 32 / SEND_TIMEOUT 10000。
 *
 * > 原先 `HandshakeDriver.buildConfig` 与 `SarEngine.buildConfig` 是两份抄来的 TLV；
 * > 现在只有这一份（`SarEngine.buildConfig` 保留为薄委托）。同理，`SarConstants`
 * > 那套 channel/opcode 常量已删除 —— 它们是 `L2` 的复制品。
 */
internal fun buildNegotiationConfig(): ByteArray {
    fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    val tlvs = listOf(
        GlassesProtocol.Sar.CONFIG_TYPE_VERSION to byteArrayOf(1, 0, 0), // VERSION 1.0.0
        GlassesProtocol.Sar.CONFIG_TYPE_MPS to le16(SarConfig.DEFAULT_MPS), // MPS
        GlassesProtocol.Sar.CONFIG_TYPE_TX_WIN to le16(SarConfig.DEFAULT_TX_WIN_LOCAL), // TX_WIN
        GlassesProtocol.Sar.CONFIG_TYPE_SEND_TIMEOUT to le16(SarConfig.SEND_TIMEOUT_MS.toInt()), // SEND_TIMEOUT
    )
    val out = ByteArray(tlvs.sumOf { 3 + it.second.size })
    var i = 0
    for ((type, v) in tlvs) {
        out[i++] = type.toByte()
        out[i++] = (v.size and 0xFF).toByte()
        out[i++] = ((v.size ushr 8) and 0xFF).toByte()
        v.copyInto(out, i)
        i += v.size
    }
    return out
}

/** 8 位序号差 `(a - b) mod 256`，用于回绕安全比较。 */
internal fun seqDiff(a: Int, b: Int): Int = (a - b) and 0xFF

/** 参考实现：窗口的 2/3（整数除法）。 */
internal fun txWinAckThr(win: Int): Int = GlassesProtocol.Sar.cumAckThreshold(win)
