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

    /** 【保留未用】v0 早期 `capturePhoto()` 缺唤醒上下文时用过；现已不需要上下文。 */
    public const val NO_WAKEUP: Int = ErrorCode.NO_WAKEUP

    /** 发送车道配额已满（同步返回），该帧**没有**入队；重试由宿主决定。 */
    public const val QUEUE_FULL: Int = ErrorCode.QUEUE_FULL

    /**
     * 握手因**瞬时**原因失败（超时 / 没等到 DeviceVerify）。
     *
     * 会话仍是终态 `FAILED`，但这类失败**重试有意义**，区别于 [AUTH_FAILED_TOKEN_INVALID]
     * 的「token 错了，重试无用」。宿主可据此决定是否再 `open()` 一次。
     */
    public const val HANDSHAKE_TRANSIENT: Int = ErrorCode.HANDSHAKE_TRANSIENT
}

/**
 * `send` 的结果。
 *
 * [Queued] 的语义是 **SAR 已受理**：帧已进对应发送车道、字节已交给链路。
 * 它**不**表示「设备已收到」—— 那是 ACK 的事，用查询接口或事件确认。
 *
 * 当场就能判定的失败一律走 [Rejected]，不用等异步 `Error` 事件：
 * 未就绪 / 已关闭 / 车道满 / 载荷超过远端 MPS / 链路写失败。
 */
public sealed interface SendResult {
    public data object Queued : SendResult

    public data class Rejected(public val code: Int, public val reason: String) : SendResult
}

/** 把引擎/驱动返回的错误码翻成 [SendResult]。`ErrorCode.NONE` 才算受理。 */
internal fun sendResultOf(code: Int): SendResult = when (code) {
    ErrorCode.NONE -> SendResult.Queued
    ErrorCode.QUEUE_FULL -> SendResult.Rejected(code, "发送车道已满，该帧未入队；退避后重试")
    ErrorCode.NOT_READY -> SendResult.Rejected(code, "会话未就绪，命令未发送")
    ErrorCode.CLOSED -> SendResult.Rejected(code, "会话已关闭")
    ErrorCode.TRANSPORT -> SendResult.Rejected(code, "链路写入失败")
    ErrorCode.PROTOCOL -> SendResult.Rejected(code, "帧未发出（载荷为空或超过远端 MPS，协议不拆分）")
    else -> SendResult.Rejected(code, "发送被拒（code=$code）")
}