package io.github.slchy.glasses2grok.core.sar

import io.github.slchy.glasses2grok.core.config.GlassesOptions
import io.github.slchy.glasses2grok.core.config.GlassesProtocol

/**
 * 时间源。注入而不是直接调 `System.currentTimeMillis()` ——
 * 有了它，测试可以把 10 秒的发送超时瞬间推进过去（`FakeClock`）。
 */
public fun interface Clock {
    public fun nowMs(): Long

    public companion object {
        public val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }

    /** 测试用：手动推进。 */
    public class Fake(private var ms: Long = 0L) : Clock {
        override fun nowMs(): Long = ms
        public fun advance(deltaMs: Long) { ms += deltaMs }
        public fun set(value: Long) { ms = value }
    }
}

/** 发送窗口策略。 */
public sealed interface TxWindowPolicy {
    /**
     * 参考实现：本地窗口硬编码 32，**忽略协商值**。
     * 用于与历史行为逐字节对账。
     */
    public data class Local(val window: Int = SarConfig.DEFAULT_TX_WIN_LOCAL) : TxWindowPolicy

    /**
     * **SDK 默认**（`ADR-008`）：在途帧数不超过对端协商的接收窗口。
     *
     * 好处：永不超窗 ⇒ NAK 路径根本不会触发。
     * 代价：吞吐受限于对端窗口（实测设备给 3），大流量下靠累积 ACK 推进。
     */
    public data object Negotiated : TxWindowPolicy
}

/**
 * SAR 参数。默认值与参考实现常量一致。
 *
 * 时间单位为**毫秒**（参考实现是秒的浮点，这里统一成整数毫秒，比较语义相同）。
 */
public data class SarConfig(
    val localTxWindow: Int = DEFAULT_TX_WIN_LOCAL,
    val defaultRemoteTxWindow: Int = DEFAULT_TX_WIN_REMOTE,
    val defaultMps: Int = DEFAULT_MPS,
    val cumAckTimeoutMs: Long = CUM_ACK_TIMEOUT_MS,
    val sendTimeoutMs: Long = SEND_TIMEOUT_MS,
    val txWindowPolicy: TxWindowPolicy = TxWindowPolicy.Negotiated,
) {
    public companion object {
        public const val CUM_ACK_TIMEOUT_MS: Long = GlassesOptions.CUM_ACK_TIMEOUT_MS
        public const val SEND_TIMEOUT_MS: Long = GlassesOptions.SEND_TIMEOUT_MS
        public const val DEFAULT_MPS: Int = GlassesOptions.DEFAULT_MPS
        public const val DEFAULT_TX_WIN_LOCAL: Int = GlassesOptions.LOCAL_TX_WINDOW
        public const val DEFAULT_TX_WIN_REMOTE: Int = GlassesOptions.DEFAULT_REMOTE_TX_WINDOW

        public const val CMD_L1START_REQ: Int = GlassesProtocol.Sar.CMD_L1START_REQ
        public const val CMD_L1START_RSP: Int = GlassesProtocol.Sar.CMD_L1START_RSP
        public const val CMD_L1STOP_REQ: Int = GlassesProtocol.Sar.CMD_L1STOP_REQ
        public const val CMD_L1STOP_RSP: Int = GlassesProtocol.Sar.CMD_L1STOP_RSP

        public val VALID: SarConfig = SarConfig()
    }
}
