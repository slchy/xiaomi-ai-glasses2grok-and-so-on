package com.miaiglasses.core.sar

import com.miaiglasses.core.config.GlassesOptions
import com.miaiglasses.core.l2.L2

/**
 * 发送车道。
 *
 * SAR 本身只有**一条有序 seq 流**（Go-Back-N 不允许乱序），所以「分车道」不是三个独立队列，
 * 而是**同一条有序队列上的三类配额**：某一类挤满只拒它自己，不会把控制命令饿死。
 *
 * 配额取定的 256 / 64 / 4：
 * - [CONTROL] 常规命令（PB / Wear），量小但**不能被音频堵住**
 * - [AUDIO] 语音上行（`L2.CH_MASS_VOICE`），高频、可丢
 * - [IMAGE] 图片/文件（`L2.CH_MASS`），单片大、绝不能堆积
 */
internal enum class Lane(val capacity: Int, val label: String) {
    CONTROL(GlassesOptions.CONTROL_LANE_CAPACITY, "control"),
    AUDIO(GlassesOptions.AUDIO_LANE_CAPACITY, "audio"),
    IMAGE(GlassesOptions.IMAGE_LANE_CAPACITY, "image"),
    ;

    internal companion object {
        /** 按 L2 通道归类；未知通道按控制处理（保守：宁可占控制配额，也不静默丢）。 */
        fun ofChannel(channel: Int): Lane = when (channel) {
            L2.CH_MASS_VOICE -> AUDIO
            L2.CH_MASS -> IMAGE
            else -> CONTROL
        }
    }
}
