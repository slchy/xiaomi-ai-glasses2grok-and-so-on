package io.github.slchy.glasses2grok.sdk

/**
 * 回调派发方式。默认 [MainThread]（与参考实现一致：参考实现把事件 post 到 `主线程 Handler`）。
 */
public sealed interface CallbackDispatcher {

    /** 主线程。宿主可直接更新 UI。 */
    public data object MainThread : CallbackDispatcher

    /**
     * 专属单线程（自带 `Looper`）。
     *
     * 用于「音频高频回调不能卡 UI」的场景：133 事件/秒 × 570 B 的音频若在回调里做重活，
     * 主线程会掉帧。换成专属线程后串行语义不变（仍是单线程顺序回调）。
     */
    public class SingleThread(public val name: String = "glasses-callback") : CallbackDispatcher {
        override fun toString(): String = "SingleThread($name)"
    }

    /**
     * 在**产生事件的线程**上直接回调（不跳线程）。
     *
     * 只建议调试或宿主自己已有线程管理时使用 —— 事件会来自引擎线程/RX 线程，
     * 在该回调里做耗时操作会直接拖慢协议处理。
     */
    public data object CallingThread : CallbackDispatcher
}
