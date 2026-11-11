package com.miaiglasses.core.query

/**
 * 手势设置的类型化取值。
 *
 * 数值就是线上 wire 值，来自参考实现的字段表；用枚举包住是为了让**传错立刻编译错**，
 * 而不是等设备静默不采纳。
 */

/** `headset.musicControl`（`f8.f4`）：媒体控制手势。 */
public enum class MusicControlTap(public val wire: Int) {
    /** 单指单击播放/暂停（真机双向验证）。 */
    SINGLE(1),

    /** 单指双击播放/暂停（真机双向验证）。 */
    DOUBLE(2),
}

/** `mediaSetting.shootingMode`（`f7.f1`）：拍摄模式。值表为静态来源，功能效果未实测。 */
public enum class ShootingMode(public val wire: Int) {
    /** 短按拍照 / 长按录像。 */
    SHORT_PRESS_PHOTO(1),

    /** 短按录像 / 长按拍照。 */
    SHORT_PRESS_RECORD(2),
}

/** `mediaSetting.recordControl`（`f7.f8`）：双指录音手势。值表为静态来源，功能效果未实测。 */
public enum class RecordGesture(public val wire: Int) {
    /** 双指单击。 */
    DOUBLE_TAP(1),

    /** 双指长按。 */
    DOUBLE_HOLD(2),

    /** 关闭双指录音手势。 */
    OFF(3),
}
