package com.miaiglasses.core.config

/**
 * Host-tunable session and flow-control defaults.
 * These numbers are the current behavior. Constructors that already accept
 * an override should default to the matching constant here.
 */
public object GlassesOptions {
    public const val DEFAULT_MPS: Int = 64_512
    public const val MEASURED_DEVICE_MPS: Int = 32_768
    public const val LOCAL_TX_WINDOW: Int = 32
    public const val DEFAULT_REMOTE_TX_WINDOW: Int = 4
    public const val CUM_ACK_TIMEOUT_MS: Long = 500L
    public const val SEND_TIMEOUT_MS: Long = 10_000L

    /** Seen once in a device log. That log is gone, so this is not the live timeout. */
    public const val SEND_TIMEOUT_LOG_MS: Long = 6_000L

    public const val NEGOTIATE_TIMEOUT_MS: Long = 10_000L
    public const val VERIFY_TIMEOUT_MS: Long = 15_000L
    public const val CONFIRM_TIMEOUT_MS: Long = 15_000L
    public const val CONNECT_TIMEOUT_MS: Int = 15_000
    public const val MAX_CONNECT_ATTEMPTS: Int = 5
    public const val TICK_MS: Long = 50L
    public const val BOND_RETRY_INTERVAL_MS: Long = 10_000L
    public const val BACKOFF_INITIAL_MS: Long = 500L
    public const val BACKOFF_MAX_MS: Long = 30_000L
    public const val BACKOFF_FACTOR: Double = 2.0
    public const val BACKOFF_JITTER_RATIO: Double = 0.2

    /**
     * 发送车道配额。SAR 只有一条有序 seq 流，所以这是「同一队列上的三类名额」：
     * 某条车道挤满只拒它自己，不会把控制命令饿死。
     */
    public const val CONTROL_LANE_CAPACITY: Int = 256
    public const val AUDIO_LANE_CAPACITY: Int = 64
    public const val IMAGE_LANE_CAPACITY: Int = 4

    /** 宿主回调侧的积压上限（facade 的 `EventDispatcher`）。超出后非关键事件被丢弃并计数。 */
    public const val CALLBACK_QUEUE_CAPACITY: Int = 256
}
