package io.github.slchy.glasses2grok.core.control

import io.github.slchy.glasses2grok.core.config.GlassesProtocol
import io.github.slchy.glasses2grok.core.wear.WearPacket

/**
 * 控制面路由：眼镜 → 手机在 `ch=1` 上发来的**封闭命令枚举**。
 *
 * **不是开放式命令执行。** 眼镜无法让手机跑任意 Activity、读相册/联系人/位置。
 * 本类只按 `(type, id)` 路由，语义判断全部留给上层。
 *
 * 范围与禁令按 `控制面`：
 *
 * | 优先级 | 通道 | 处理 |
 * |---|---|---|
 * | P0 | Voice(14) | H→P 全部交给宿主（唤醒事件与录音状态是语音链路起点） |
 * | P0 | System(2) | 仅 `1/2/14/79/81/88/89/90/91/93/97` |
 * | P1 | Media(18) | 仅 `1/14/16/20/21/22` |
 * | ❌ | PaySDK(20) / Factory(13) / Interconnection(23) | 硬拒，理由见文档 |
 *
 * 三条纪律：
 * 1. 未知 `(type,id)` 一律丢弃 + 记日志，**不要猜语义**；
 * 2. 不实现的分支也要给出可记录的判定，便于以后定位；
 * 3. 不要为「功能完整」去实现 PaySDK / Factory。
 *
 * 本类**不持有连接、不写 SPP**，是纯路由，便于单测。
 */

public enum class ControlOutcome {
    /** 已交给宿主 */
    HANDLED,

    /** 通道实现了，但这条 id 没有实测样本，不猜语义 */
    DROPPED_UNVERIFIED,

    /** 明确不实现的通道（PaySDK / Factory / Interconnection） */
    DROPPED_FORBIDDEN,

    /** 完全不认识的通道 */
    DROPPED_UNKNOWN,
}

public class ControlDecision(
    public val type: Int,
    public val id: Int,
    public val outcome: ControlOutcome,
    public val channelName: String,
    public val reason: String = "",
) {
    public val handled: Boolean get() = outcome == ControlOutcome.HANDLED

    override fun toString(): String =
        "$channelName(type=$type, id=$id) $outcome" + if (reason.isEmpty()) "" else " — $reason"
}

/** 业务处理。实现方只该处理自己认识的 id。 */
