package io.github.slchy.glasses2grok.sdk.ktx

import io.github.slchy.glasses2grok.core.session.GlassesEvent
import io.github.slchy.glasses2grok.core.session.SessionState
import io.github.slchy.glasses2grok.sdk.GlassesEventSource
import io.github.slchy.glasses2grok.sdk.GlassesListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** 等待 READY 超时。 */
public class SdkTimeoutException(message: String) : Exception(message)

/** 会话在 `READY` 之前就结束了（`FAILED` / `CLOSED`）。 */
public class SdkStateException(message: String) : Exception(message)

/**
 * 事件流。
 *
 * **默认 `buffer` 容量沿用 `callbackFlow` 的 64** —— 音频是高频事件，
 * 收集方太慢时会**丢最旧**而不是反压协议线程（与 `SDK-ARCHITECTURE.md`的
 * 「回调队列丢最旧」一致）。需要不丢就把 `buffer(Channel.UNLIMITED)` 加在宿主侧，
 * 但那意味着内存由宿主自己兜底。
 *
 * 取消收集即退订（`awaitClose` 里 `removeListener`）。
 */
public fun GlassesEventSource.events(): Flow<GlassesEvent> = callbackFlow {
    // 用 addListener/removeListener 而不是 setListener：多个 flow（events + awaitReady）
    // 可以并存，任一 collector 结束只摘自己那一个订阅。
    val listener = GlassesListener { event -> trySend(event) }
    addListener(listener)
    awaitClose { removeListener(listener) }
}

/**
 * 等到 `READY`。
 *
 * @throws SdkTimeoutException 超时
 * @throws SdkStateException 期间进入 `FAILED` 或 `CLOSED`
 */
public suspend fun GlassesEventSource.awaitReady(timeoutMs: Long = 15_000) {
    if (state == SessionState.READY) return
    val terminal = try {
        withTimeout(timeoutMs) {
            events().first { e ->
                e is GlassesEvent.StateChanged &&
                    (e.state == SessionState.READY ||
                        e.state == SessionState.FAILED ||
                        e.state == SessionState.CLOSED)
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw SdkTimeoutException("${timeoutMs}ms 内未进入 READY（当前 $state）")
    }
    val reached = (terminal as GlassesEvent.StateChanged).state
    if (reached != SessionState.READY) {
        throw SdkStateException("会话在 READY 之前结束：$reached")
    }
}

/** 订阅一段时间内的音频块（宿主自己做解码时用）。 */
public fun GlassesEventSource.audio(): Flow<ByteArray> = callbackFlow {
    val listener = GlassesListener { event ->
        if (event is GlassesEvent.Audio) trySend(event.data)
    }
    addListener(listener)
    awaitClose { removeListener(listener) }
}
