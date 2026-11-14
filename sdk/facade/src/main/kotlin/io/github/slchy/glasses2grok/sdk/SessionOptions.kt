package io.github.slchy.glasses2grok.sdk

import io.github.slchy.glasses2grok.core.config.GlassesOptions

/**
 * 会话选项。
 *
 * 没有 `token` 字段是刻意的：token 属「用户账号 ↔ 设备绑定凭据」域，
 * SDK 只接收、不产出、不持久化（设计记录）。
 */
public data class SessionOptions(
    val callbackDispatcher: CallbackDispatcher = CallbackDispatcher.MainThread,
    /** 单次建连超时。超时后按退避重试，直到 `maxConnectAttempts`。 */
    val connectTimeoutMs: Int = GlassesOptions.CONNECT_TIMEOUT_MS,
    /** 连续失败多少次后放弃（转 `FAILED`，不再自动重试）。 */
    val maxConnectAttempts: Int = GlassesOptions.MAX_CONNECT_ATTEMPTS,
    /** 引擎线程的定时器周期。默认 50 ms（参考实现同量级）。 */
    val tickMs: Long = GlassesOptions.TICK_MS,
    /** 诊断模式：额外发出 `Diagnostic` 事件（原始帧摘要，不含 payload）。 */
    val diagnostics: Boolean = false,
    /**
     * 主线程护栏：阻塞类接口（返回 `QueryResult` / `SettingWriteResult` 的查询与写设置，
     * 以及 `closeAndWait()`）在 **Android 主线程**上被调用时直接抛 `IllegalStateException`。
     *
     * 默认开。这类调用最多等 8 s，主线程上占满 5 s 就是 ANR —— 当场抛异常比陪等一个
     * 必到的 ANR 更容易定位。只有「明知会卡主线程也要跑」的临时调试才关掉。
     */
    val threadGuards: Boolean = true,
)
