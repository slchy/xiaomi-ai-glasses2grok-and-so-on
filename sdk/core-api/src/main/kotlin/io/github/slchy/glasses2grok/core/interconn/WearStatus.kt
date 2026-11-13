package io.github.slchy.glasses2grok.core.interconn

import io.github.slchy.glasses2grok.core.pb.Pb

/**
 * 佩戴状态上报：`System`(2) `id=79`，载荷 `System.f49{ f2 = state }`。
 *
 * **取值已由真机确证**（实测）：
 *
 * | 值 | 含义 |
 * |---:|---|
 * | 1 | **佩戴中** |
 * | 2 | **未佩戴** |
 *
 * 补充事实（同批实测）：摘下（`f2=2`）会立刻触发 `Voice id=0 SYNC_INSTRUCTION_LIST` +
 * `id=6 SYNC_VOICE_ERROR{errorCode=4}`（4/4 命中）；戴上（`f2=1`）零帧。
 *
 * 为什么它重要：`Media id=20` 的「开始录音」在**未佩戴**时会被设备拒绝
 * （`RecordResponse.code = 5`，见 `实测`）。宿主判断「能不能录」
 * 应该看这个状态，而不是靠猜或靠试错。
 */
public data class WearStatus(val state: Int, val raw: String) {

    /** `state == 1`（佩戴中）。见类文档的取值表。 */
    val isWorn: Boolean get() = state == STATE_WORN

    /** `state == 2`（未佩戴）。 */
    val isOff: Boolean get() = state == STATE_OFF

    override fun toString(): String = "WearStatus(${if (isWorn) "佩戴中" else if (isOff) "未佩戴" else "state=$state"})"

    public companion object {
        public const val STATE_WORN: Int = 1
        public const val STATE_OFF: Int = 2

        /**
         * `System id=79` 主动上报的载荷：`f49{ f2 = state }`。
         *
         * 真机样本 `8a 03 02 10 01` / `…10 02`。
         */
        public fun fromReport(payload: ByteArray): WearStatus? {
            val f49 = Pb.fields(payload)[49] ?: return null
            val state = Pb.varintValue(Pb.fields(f49)[2] ?: ByteArray(0)).toInt()
            return WearStatus(state = state, raw = io.github.slchy.glasses2grok.core.Hex.encode(payload))
        }

        /**
         * `System id=93`（`GetWearStatus`）**回包**的载荷：`f60 = enum`（varint，不是子消息）。
         *
         * 真机闭环（`命令图谱`）：请求空载荷，响应 `e0 03 01` → `f60 = 1`（当时正佩戴）。
         * 这条是**权威值**，`id=79` 只是变化通知。
         */
        public fun fromQuery(payload: ByteArray): WearStatus? {
            val f60 = Pb.fields(payload)[60] ?: return null
            return WearStatus(
                state = Pb.varintValue(f60).toInt(),
                raw = io.github.slchy.glasses2grok.core.Hex.encode(payload),
            )
        }
    }
}
