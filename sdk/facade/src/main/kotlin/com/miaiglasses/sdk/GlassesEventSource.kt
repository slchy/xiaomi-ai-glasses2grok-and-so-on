package com.miaiglasses.sdk

import com.miaiglasses.core.session.GlassesEvent
import com.miaiglasses.core.session.SessionState

/**
 * 「能发出眼镜事件的东西」。
 *
 * 抽出这个接口有两个实际用途：
 * - `-ktx` 的 `Flow` 适配定义在它上面，于是**宿主可以在纯 JVM 单测里实现它**，
 *   不需要真机、不需要 `BluetoothDevice`；
 * - 宿主可以为自己已有的通道写测试替身（mock/fake），而不必伪造 `GlassesSession`。
 */
public interface GlassesEventSource {
    public val state: SessionState

    /** **替换**全部监听（传 null 清空）。实现方须保证线程安全。 */
    public fun setListener(listener: GlassesListener?)

    /**
     * 追加一个监听，不影响已有订阅者。
     *
     * 默认实现退化成 [setListener]（会顶掉别人）—— 只给「单订阅者」的替身用；
     * 支持多订阅的实现（如 `GlassesSession`）必须覆盖它。
     */
    public fun addListener(listener: GlassesListener) {
        setListener(listener)
    }

    /** 移除监听。默认空实现，仅为多订阅实现提供统一的退订口。 */
    public fun removeListener(listener: GlassesListener) {
        // 默认无操作：替身若只支持单订阅，用 setListener(null) 退订
    }
}
