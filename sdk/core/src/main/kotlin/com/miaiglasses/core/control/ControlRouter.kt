package com.miaiglasses.core.control

import com.miaiglasses.core.config.GlassesProtocol
import com.miaiglasses.core.media.MediaFileIds
import com.miaiglasses.core.query.MediaFileInfo
import com.miaiglasses.core.voice.VoiceIds
import com.miaiglasses.core.wear.WearPacket

internal interface ControlSink {
    fun onVoice(id: Int, payload: ByteArray)
    fun onSystem(id: Int, payload: ByteArray)
    fun onMedia(id: Int, payload: ByteArray)

    /**
     * `Interconnection`(23) 的直播相关回报（`id` 见 `ControlRouter.LIVE_IDS`）。
     *
     * 默认空实现，避免只关心语音/媒体的宿主被迫实现。
     * 语义未确证 —— 宿主应按 `id` 自行解读，SDK 不猜。
     */
    fun onLive(id: Int, payload: ByteArray) = Unit
}

internal object ControlRouter {

    private val SYSTEM_P0 = setOf(1, 2, 14, 79, 81, 88, 89, 90, 91, 93, 97, 99)
    private val MEDIA_P1 = setOf(1, 14, 16, 20, 21, 22)

    /**
     * `System id=15` 的**写命令回执**：`DATA` 帧但 **payload 为空**（0 字节）。
     *
     * 2026-11-11 真机实测：写 `f35{f8{…}}` 改 `musicControl` 后紧接一条
     * `08 02 10 0f 22 00`（`type=2 id=15 len=0`），随后 `id=14` 读回确认值已变。
     *
     * 之前 `参考协议`记的回执是 `a0 06 00`（`f100=0`），**与本次实测不符** ——
     * 按实测走：空 payload。既然空到什么也没有，就没有东西可交给宿主，也不该再报
     * 「无实测样本」。
     */
    private val SYSTEM_WRITE_ACK = setOf(15)

    /**
     * `Interconnection`(23) 上**真机见过**的 id。
     *
     * | id | 何时出现 | 证据 |
     * |---|---|---|
     * | 30 | `PUBLISH_MIS_SPEC` —— **按键动作走这条**（拍照/录像），不是直播周期回报 | ✅ 2026-11-11 真机按键抓帧 |
     * | 45 | `queryLiveStream` 的回复（16 B）| ✅ 2026-11-10 SDK 自连实测 |
     * | 47 | 起播/停播后各一次（14 B）| ✅ 同上 |
     *
     * ⚠️ **`id=30` 不是 `CAMERA_EVENT`**：`CAMERA_EVENT` 是容器**字段** 30
     * （`Interconnection.f30`），命令编号 30 是 `PUBLISH_MIS_SPEC`，载荷在
     * `f22 MIS_PAYLOAD`。真机 tag `B2 01` = field 22 已证实（实测）。
     */
    private val LIVE_IDS = setOf(30, 45, 47)

    fun dispatch(packet: WearPacket?, sink: ControlSink?): ControlDecision {
        if (packet == null) {
            return ControlDecision(-1, -1, ControlOutcome.DROPPED_UNKNOWN, "?", "WearPacket 解析失败")
        }
        return dispatch(packet.type, packet.id, packet.payload, sink)
    }

    fun dispatch(type: Int, id: Int, payload: ByteArray, sink: ControlSink?): ControlDecision =
        when (type) {
            GlassesProtocol.Wear.VOICE -> {
                sink?.onVoice(id, payload)
                ControlDecision(type, id, ControlOutcome.HANDLED, "voice")
            }

            GlassesProtocol.Wear.SYSTEM -> {
                if (id in SYSTEM_P0) {
                    sink?.onSystem(id, payload)
                    ControlDecision(type, id, ControlOutcome.HANDLED, "system")
                } else if (id in SYSTEM_WRITE_ACK) {
                    ControlDecision(type, id, ControlOutcome.HANDLED, "system", "写命令回执（空 payload）")
                } else {
                    ControlDecision(
                        type, id, ControlOutcome.DROPPED_UNVERIFIED, "system",
                        "该 id 无实测样本，不猜语义",
                    )
                }
            }

            GlassesProtocol.Wear.MEDIA -> {
                if (id in MEDIA_P1) {
                    sink?.onMedia(id, payload)
                    ControlDecision(type, id, ControlOutcome.HANDLED, "media")
                } else {
                    ControlDecision(
                        type, id, ControlOutcome.DROPPED_UNVERIFIED, "media",
                        "该 id 无实测样本，不猜语义",
                    )
                }
            }

            GlassesProtocol.Wear.PAYSDK ->
                ControlDecision(type, id, ControlOutcome.DROPPED_FORBIDDEN, "paysdk", "支付链路不实现")

            GlassesProtocol.Wear.FACTORY ->
                ControlDecision(type, id, ControlOutcome.DROPPED_FORBIDDEN, "factory", "工厂模式不实现")

            GlassesProtocol.Wear.INTERCONNECTION ->
                if (id in LIVE_IDS) {
                    sink?.onLive(id, payload)
                    ControlDecision(type, id, ControlOutcome.HANDLED, "interconnection")
                } else {
                    ControlDecision(
                        type, id, ControlOutcome.DROPPED_FORBIDDEN, "interconnection",
                        "该 id 无实测样本，不猜语义",
                    )
                }

            GlassesProtocol.Wear.ACCOUNT ->
                ControlDecision(
                    type, id, ControlOutcome.DROPPED_UNVERIFIED, "account",
                    "握手通道，运行期不应出现",
                )

            else -> ControlDecision(
                type, id, ControlOutcome.DROPPED_UNKNOWN, "?",
                "未知通道，参考实现同样忽略",
            )
        }
}
