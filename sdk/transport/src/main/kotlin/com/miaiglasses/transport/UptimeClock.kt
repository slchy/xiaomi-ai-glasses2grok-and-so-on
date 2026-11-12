package com.miaiglasses.transport

import com.miaiglasses.core.sar.Clock

/**
 * 单调时钟 —— **`CLOCK_BOOTTIME`（含深度睡眠）**，与 Android 侧的节流/超时同基准。
 *
 * 用 `SystemClock.elapsedRealtime()` 而不是 `System.nanoTime()`：后者是 `CLOCK_MONOTONIC`，
 * **休眠期间不前进**，而 SAR 的重传超时、退避、连接门禁都建立在这条时间轴上。
 * 息屏几分钟后醒来，`nanoTime` 会少算整段休眠 —— 在途帧的 10 s 发送超时被拉长成
 * 「10 s + 休眠时长」，表现为「唤醒后半天不重传」。同一个包里 [AndroidSppLink] 的
 * 重配对节流本来就用 `elapsedRealtime()`，之前两处基准不一致（2026-11-12 统一）。
 *
 * 不使用 `System.currentTimeMillis()`：它会被用户改时间/NTP 校正，一旦回拨，
 * 超时会变成「永远不到」或「立刻到期」。
 *
 * ⚠️ **JVM 单元测试里 `elapsedRealtime()` 返回 0**（`unitTests.isReturnDefaultValues = true`
 * 下的桩值），时间不会前进。测试请注入自己的 `Clock`（`Clock { System.nanoTime() / 1_000_000 }`
 * 或 `Clock.Fake`），不要依赖本对象。
 */
object UptimeClock : Clock {
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
}
