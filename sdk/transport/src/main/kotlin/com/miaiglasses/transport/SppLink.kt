package com.miaiglasses.transport

/**
 * SPP 链路的最小抽象：**只做字节 I/O，不含任何协议语义**。
 *
 * 抽出接口的目的是让 [SessionDriver] 能在纯 JVM 单测里跑（假 link + 真线程），
 * 而 `android.bluetooth.*` 只出现在 [AndroidSppLink] 里。
 *
 * 实现约定：
 * - [read] **阻塞**直到拿到至少 1 字节；返回 `-1` 表示对端关闭。
 * - [write] **同步阻塞**写完全部字节；失败抛异常（由引擎归为传输错误）。
 * - [close] 幂等，且必须能**打断正在阻塞的 [read]**（这是停线程的唯一可靠手段，
 *   `Thread.interrupt()` 对 `BluetoothSocket` 的阻塞读无效）。
 */
interface SppLink {
    /**
     * 传输是否**现在就能建连**（蓝牙已开 + 设备已配对）。
     *
     * 状态机用它在建连前做门禁：不可用时进挂起态，**不消耗重试预算、也不设超时**，
     * 只等条件恢复。参考实现同样不在蓝牙关闭时硬试（`SPP 客户端` 里
     * `createRfcommSocketToServiceRecord` 抛异常只报一次，靠 `ACL_*` / 配对广播恢复）。
     */
    fun isAvailable(): Boolean = true

    /**
     * [isAvailable] 返回 false 的**原因**，供状态机把错误信息写准。
     *
     * 最典型的是 Android 12+ 少了 `BLUETOOTH_CONNECT`：早先实现用
     * `runCatching{}.getOrDefault(false)` 把 `SecurityException` 吞成「不可用」，
     * 于是宿主看到的是「蓝牙关闭或未配对」—— 提示完全误导，而且会无限挂起。
     */
    val unavailableReason: String? get() = null

    /** 建连。失败抛异常。`timeoutMs` 只作用于本次建连，不含重试。 */
    fun connect(timeoutMs: Int)

    fun read(buf: ByteArray, off: Int, len: Int): Int

    fun write(bytes: ByteArray)

    fun close()

    /**
     * 最近一次**外部**关闭的原因（例如 `ACL_DISCONNECTED` 广播）。诊断用。
     *
     * 存在它的理由：`ACL 断开导致的关 socket` 与 `read() 自己返回 -1` 在日志上几乎
     * 无法区分，而没有这个区分就无法证明广播链路真的生效。
     */
    val closeReason: String? get() = null
}
