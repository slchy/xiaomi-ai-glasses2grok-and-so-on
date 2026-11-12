package com.miaiglasses.core.session

/**
 * 错误分类码。
 *
 * 放在契约层而不是 `:facade`：`GlassesEvent.Error` 需要带码，而事件类型住在契约层；
 * facade 的 `SdkErrorCode` 反过来引用这里的常量，保证全链路只有一个数字来源。
 *
 * `108` 沿用参考实现语义（token 无效 / 设备签名校验失败），其余为 SDK 自定。
 */
public object ErrorCode {
    /** 无错误（仅用于默认值，实际事件不会带它）。 */
    public const val NONE: Int = 0

    /** token 无效 / 设备签名校验失败（参考实现同码）。 */
    public const val AUTH_FAILED_TOKEN_INVALID: Int = 108

    /** socket / IO / 建连失败、重连耗尽。 */
    public const val TRANSPORT: Int = 201

    /** 帧长异常、CRC 连续失败、魔数丢失、解密失败、契约报文解析失败。 */
    public const val PROTOCOL: Int = 202

    /** 握手或命令超时（协商 / 验签 / 确认 / 命令回执）。 */
    public const val TIMEOUT: Int = 203

    /** 会话未就绪，命令被拒（**不排队**）。 */
    public const val NOT_READY: Int = 204

    /** 会话已关闭。 */
    public const val CLOSED: Int = 205

    /** 保留未用（v0 早期 `capturePhoto()` 缺 `Voice id=10` 上下文时用过；现已不需要上下文）。 */
    public const val NO_WAKEUP: Int = 206

    /** 发送车道配额已满，该帧没有入队。重试由宿主决定。 */
    public const val QUEUE_FULL: Int = 207

    /**
     * 握手在**超时 / 报文缺失**这一类「瞬时」原因上失败 —— 会话仍是终态 `FAILED`，
     * 但重开一次很可能就好了（射频抖动、设备刚醒、上一轮链路还没清干净）。
     *
     * 与 [AUTH_FAILED_TOKEN_INVALID] 的区别是**语义**而不只是数字：
     * - `108`：设备签名对不上 ⇒ token 错了，重试多少次都一样，必须换凭据；
     * - `208`：根本没等到应答 ⇒ 重试有意义，宿主按自己的退避策略再 `open()` 一次。
     *
     * 为什么不做自动重试：握手超时往往意味着对端根本没在听（没开机 / 被别的手机占着），
     * 自动重连会把「设备不可用」伪装成「正在连接」，还会烧掉电量和连接预算。
     * 这跟 `maxConnectAttempts` 管的是两件事 —— 那个管的是「socket 建不起来」。
     */
    public const val HANDSHAKE_TRANSIENT: Int = 208
}
