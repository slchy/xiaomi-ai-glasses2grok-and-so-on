package com.miaiglasses.core.session

/**
 * 一条会话的链路计数快照。
 *
 * 由 `GlassesSession.linkCounters` 提供，用于宿主的诊断界面 —— 它回答的是
 * 「链路是不是在正常搬运」这类问题，**不含任何音频或文本内容**。
 *
 * 判读办法（对同一条连接做差值即可）：
 *
 * | 现象 | 说明 |
 * |---|---|
 * | [rxFrames] 长时间不涨 | 设备不再发数据 —— 链路死了，或根本没唤醒 |
 * | [rxFrames] 涨但 [txFrames] 不涨 | 只在收；正常（例如只开麦不问答） |
 * | [nakSent] 持续增长 | 对端发来的 seq 有洞：SPP 层丢包或对端发送过快 |
 * | [retrans] 持续增长 | 我们发出去的东西没被确认，在重传 —— 反向丢包 |
 *
 * 这些数是**累计值**，不会因为一轮对话结束而清零；只看增量，不看绝对值。
 *
 * @property rxFrames 已交付给上层的 DATA 帧数。乱序被 NAK 掉的帧不计（它没有交付）。
 * @property txFrames 已交给链路的 DATA 帧数（含重传前的首发）。
 * @property nakSent 本端发出的 NAK 数。
 * @property retrans 本端重传次数。
 */
public data class LinkCounters(
    public val rxFrames: Long,
    public val txFrames: Long,
    public val nakSent: Long,
    public val retrans: Long,
) {
    public companion object {
        /** 未连接 / 已断开时的空值，宿主可以直接显示。 */
        public val EMPTY: LinkCounters = LinkCounters(0, 0, 0, 0)
    }
}
