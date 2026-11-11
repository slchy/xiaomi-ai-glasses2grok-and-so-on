package com.miaiglasses.sdk

import com.miaiglasses.core.session.ErrorCode

/**
 * 稳定错误码。`108` 沿用参考实现语义（token 无效），其余为 SDK 自定。
 *
 * 数字的唯一来源是契约层的 [ErrorCode]（`GlassesEvent.Error.code` 用的就是它）；
 * 这里保留门面的 FQN，方便宿主按 `SdkErrorCode.XXX` 写代码。
 */
public object SdkErrorCode {
    /** token 无效 / 设备签名校验失败（参考实现同码）。 */
    public const val AUTH_FAILED_TOKEN_INVALID: Int = ErrorCode.AUTH_FAILED_TOKEN_INVALID

    /** socket / IO 失败。 */
    public const val TRANSPORT: Int = ErrorCode.TRANSPORT

    /** 帧长异常、CRC 连续失败、魔数丢失。 */
    public const val PROTOCOL: Int = ErrorCode.PROTOCOL

    /** 握手或命令超时。 */
    public const val TIMEOUT: Int = ErrorCode.TIMEOUT

    /** 会话未就绪，命令被拒（**不排队**）。 */
    public const val NOT_READY: Int = ErrorCode.NOT_READY

    /** 会话已关闭。 */
    public const val CLOSED: Int = ErrorCode.CLOSED

    /** 保留未用（v0 早期 `capturePhoto()` 缺 `Voice id=10` 上下文时用过；现已不需要上下文）。 */
    public const val NO_WAKEUP: Int = ErrorCode.NO_WAKEUP

    /** 发送车道配额已满，该帧没有入队；重试由宿主决定。 */
    public const val QUEUE_FULL: Int = ErrorCode.QUEUE_FULL
}

/** `send` 的结果。**不排队**：未就绪立即拒绝，避免宿主误以为发成功了。 */
public sealed interface SendResult {
    public data object Queued : SendResult

    public data class Rejected(public val code: Int, public val reason: String) : SendResult
}
