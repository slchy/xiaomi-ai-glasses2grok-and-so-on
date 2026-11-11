package com.miaiglasses.sdk

import com.miaiglasses.core.session.GlassesEvent

/** 事件监听。回调在 [SessionOptions.callbackDispatcher] 指定的线程上**串行**执行。 */
public fun interface GlassesListener {
    public fun onEvent(event: GlassesEvent)
}
