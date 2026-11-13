package io.github.slchy.glasses2grok.sdk

import io.github.slchy.glasses2grok.core.session.GlassesEvent

/** 事件监听。回调在 [SessionOptions.callbackDispatcher] 指定的线程上**串行**执行。 */
public fun interface GlassesListener {
    public fun onEvent(event: GlassesEvent)
}
