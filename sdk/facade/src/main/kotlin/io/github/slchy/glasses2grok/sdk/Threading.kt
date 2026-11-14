package io.github.slchy.glasses2grok.sdk

/**
 * 阻塞接口的**主线程护栏**。
 *
 * 查询类接口要等设备回包才有结果，天生是阻塞的（默认 3 s，[GlassesSession.enableWifiP2pGcAndWait] 8 s），
 * 而 Android 主线程被占满 5 s 就是 ANR。这类调用在主线程上**没有任何正确的用法**，
 * 所以直接拒绝，而不是让宿主陪等一个迟早会到的 ANR。
 *
 * 判定放门面而没放传输层，是因为 `SessionDriver` 明确不认识 `Looper`
 * （实测）；门面把判定以回调形式注入驱动，所有阻塞入口就自动收口了。
 */
internal object Threading {

    /** 当前线程是不是 Android 主线程。纯 JVM 单测里没有 `Looper`，一律按「不是」处理。 */
    internal fun isMainThread(): Boolean {
        val main = android.os.Looper.getMainLooper() ?: return false
        val current = android.os.Looper.myLooper() ?: return false
        return current === main
    }

    internal fun checkNotBlockingMainThread(timeoutMs: Long): Unit =
        checkNotBlockingMainThread(isMainThread(), timeoutMs)

    /** 判定与报错分开，纯 JVM 单测才能验证「主线程上会抛」这条约定。 */
    internal fun checkNotBlockingMainThread(onMainThread: Boolean, timeoutMs: Long) {
        if (!onMainThread) return
        throw IllegalStateException(
            "在 Android 主线程上调了阻塞接口：它要等设备回包，最多 ${timeoutMs}ms；" +
                "主线程被占满 5 s 就是 ANR，所以这里直接拒绝，不陪你等。" +
                "改法：放后台线程（协程 withContext(Dispatchers.IO) / 线程池），" +
                "或改用不等回包的那组接口（返回 SendResult 的 requestXxx）。" +
                "确实要在主线程这么做，传 SessionOptions(threadGuards = false)。",
        )
    }
}
