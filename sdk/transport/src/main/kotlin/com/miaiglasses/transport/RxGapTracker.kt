package com.miaiglasses.transport

/**
 * 把「RX 队列累计丢弃块数」换算成「两块语音之间丢了多少」。
 *
 * 累计计数是 [EnginePump] 的，语音块是引擎线程串行产出的 —— 两者之间只有这一层
 * 知道怎么对上，所以单独拆出来，可以脱离会话直接测。
 */
internal class RxGapTracker {
    private var seen: Long? = null

    /** @param droppedNow 当前的累计丢弃块数；返回自上次调用以来新增的丢弃数（≥ 0）。 */
    fun onChunk(droppedNow: Long): Long {
        val prev = seen
        seen = droppedNow
        // 第一块只建立基线：会话开始前（握手期间）丢的块不是「音频流里的空洞」
        if (prev == null) return 0
        val delta = droppedNow - prev
        return if (delta < 0) 0 else delta
    }

    /** 换链路时重置（上一轮 socket 的丢弃不该算进新链路的音频空洞）。 */
    fun reset() {
        seen = null
    }
}
