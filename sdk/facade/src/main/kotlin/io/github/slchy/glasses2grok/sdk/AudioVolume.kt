package io.github.slchy.glasses2grok.sdk

import android.content.Context
import android.media.AudioManager

/**
 * ：**音量（前划 / 后划）** 的宿主侧 API。
 *
 * ## 为什么不是 SPP 命令
 *
 * 眼镜的前划/后划改音量走的是**蓝牙 AVRCP `SetAbsoluteVolume`（0–127）**，
 * 由手机的 AudioService 直接处理 —— SPP 上**一帧都没有**
 * （实测）。
 * 所以 SDK 不去「发命令」，而是把**系统媒体音量的读/写**包成一组稳定 API，
 * 让宿主对「眼镜滑动手势」和「App 内音量条」用同一套调用。
 *
 * ## 用法
 *
 * ```kotlin
 * val vol = AudioVolume(context)
 * vol.getPercent()          // 0–100
 * vol.step(+1)              // 前划一格（系统 adjustStreamVolume）
 * vol.setPercent(60)
 * ```
 *
 * ## 边界
 *
 * - `STREAM_MUSIC` 的读写**不需要权限**；
 * - 固定音量设备（部分蓝牙外放）或勿扰场景下系统会拒绝 [setPercent]/[step]，
 *   这时返回 false / -1，**不要当异常抛**；
 * - 想要「用户前划时收到通知」，宿主应监听自己的 `MediaSession`/`VolumeProvider`
 *   或自行轮询 [getStep]；本类不提供持续监听（`AudioManager` 没有公开的音量变化回调）。
 */
public class AudioVolume(context: Context) {

    private val am: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** 系统媒体音量是否可用。 */
    public val isSupported: Boolean get() = am != null

    /** 当前媒体音量百分比（0–100）；不可用时返回 -1。 */
    public fun getPercent(): Int {
        val m = am ?: return -1
        val max = m.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return -1
        return m.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    /** 当前媒体音量的 `档位 to 最大档位`；不可用时 `null`。 */
    public fun getStep(): Pair<Int, Int>? {
        val m = am ?: return null
        val max = m.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return null
        return m.getStreamVolume(AudioManager.STREAM_MUSIC) to max
    }

    /**
     * 设成百分比（0–100）。
     *
     * @return true = 系统接受了这次改动（固定音量设备 / 勿扰会拒绝，返回 false）
     */
    public fun setPercent(percent: Int): Boolean {
        val m = am ?: return false
        val max = m.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return false
        val target = (percent.coerceIn(0, 100) * max + 50) / 100
        return try {
            m.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            m.getStreamVolume(AudioManager.STREAM_MUSIC) == target
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * 按系统档位加减 [delta] 格（前划 +1 / 后划 -1 走的就是这条路）。
     *
     * @return 改动后的档位；不可用/被拒绝时 -1
     */
    public fun step(delta: Int): Int {
        val m = am ?: return -1
        val dir = when {
            delta > 0 -> AudioManager.ADJUST_RAISE
            delta < 0 -> AudioManager.ADJUST_LOWER
            else -> return m.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        return try {
            repeat(kotlin.math.abs(delta)) {
                m.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
            }
            m.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (e: SecurityException) {
            -1
        }
    }
}
