package io.github.slchy.glasses2grok.sample

/**
 * 高清照的相位。终态有三个，但物理收尾只有一个出口（`Cleanup`）。
 */
internal enum class PhotoState {
    IDLE,
    ARMING,
    WAITING,
    FETCHING,
    ENCODING,
    UPLOADING,
    DONE,
    FAILED,
    ABORTED,
    ;

    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == ABORTED

    val isRunning: Boolean get() = !isTerminal && this != IDLE
}

internal sealed interface PhotoEvent {
    /** 云端要一张高清照 */
    data object Request : PhotoEvent

    /** P2P 与相册都通了；`fresh` 表示相机里**已经有**一张基线外的新图（用户提前按了） */
    data class Armed(val fresh: Boolean) : PhotoEvent

    /** 轮询发现了新条目 */
    data class Fresh(val identifier: String) : PhotoEvent

    data object Fetched : PhotoEvent
    data object Encoded : PhotoEvent
    data object Sent : PhotoEvent
    data class Failed(val reason: PhotoFail) : PhotoEvent
    data class Timeout(val stage: PhotoState) : PhotoEvent

    /** 轮询节拍：到点再列一次目录 */
    data object Tick : PhotoEvent

    /** 云端取消 / 用户改主意 */
    data object Cancel : PhotoEvent
}

internal sealed interface PhotoAction {
    /** 建组 + 入组 + 连相册 + 采基线 */
    data object Arm : PhotoAction

    /** 列一次目录 */
    data object Poll : PhotoAction

    data object Download : PhotoAction
    data object Encode : PhotoAction

    /** 先发 `hq_image` 公告再上行图片 */
    data object Upload : PhotoAction

    /** 发给云端的状态事件 */
    data class Report(val event: String, val fields: Map<String, String>) : PhotoAction

    /** 唯一出口：解散 P2P、删临时文件、记下本次见到的条目 */
    data object Cleanup : PhotoAction
}

/**
 * 协作式高清照的状态机（**纯逻辑**，不碰 Android、不起线程、不看时钟）。
 *
 * <h2>为什么要有它</h2>
 *
 * 程序化快门不落盘（`实测`），相册只由物理快门增长 ——
 * 所以「拍高清」天然是一段**要等人**的流程：建 P2P（~5 s）→ 提示 → 等人按（≤20 s）
 * → 拉图 → 解码 → 上行。以前这段是一个过程式 `capture()`，所有失败都 `return null`，
 * 云端只知道「没图」，不知道卡在哪一步（用户看到的就是「照片都没拍」）。
 *
 * ```text
 * IDLE ──Request──▶ ARMING ──Armed──▶ WAITING ──Fresh──▶ FETCHING ──▶ ENCODING ──▶ UPLOADING ──▶ DONE
 *                     │                 │                   │            │
 *                     │ Failed/Timeout  │ Failed/Timeout     │            └── 失败 ──▶ FAILED
 *                     └─────────────────┴────────────────────┴── Cancel ──▶ ABORTED
 * 任意终态 ──Cleanup──▶ IDLE（下一次 Request 重新开始）
 * ```
 *
 * 关键约定：
 *
 * 1. **终态之后一切事件都被吞掉**：超时和迟到的回调都可能在这时候到达。
 * 2. **单飞**：`ARMING..UPLOADING` 期间再来 `Request` 直接忽略（重复发 `id=99` 会让
 *    眼镜重新入组，等于白等一轮）。
 * 3. **超时是按阶段判的**：`Timeout(stage)` 里的 stage 必须等于当前状态才生效，
 *    否则就是上一阶段的定时器迟到 —— 这样不会出现「刚拉完图却被建组超时打死」。
 * 4. **`hq_image` 公告必须在图片之前**：云端靠它区分高清原图和 SPP 低清回图。
 *
 * <h2>它值不值这些行</h2>
 *
 * 这套相位机加执行器约 580 行，替代的是原 `capture()` 里约 90 行的线性流程。
 * 真正需要"判断"的只有 `pickFresh`（哪些条目算新）一处；超时、线程、相位迁移
 * 本身没有分支智慧。若将来要收敛，线性版约 150 行、行为等价：顺序执行，
 * 每个失败点直接返回 `PhotoFail`，只保留一个 20 s 的等快门 deadline。
 */
internal class PhotoFsm {

    /** 主线程会读它决定「要不要丢掉 SPP 低清回图」，所以必须 volatile */
    @Volatile
    var state = PhotoState.IDLE
        private set

    /** 本次要拉的那一条相册条目 */
    var identifier: String? = null
        private set

    private val trail = mutableListOf<PhotoState>()

    /** 走过的相位，排障时一行就能看出卡在哪（`ARMING>WAITING>FETCHING`） */
    val path: String get() = trail.joinToString(">")

    fun dispatch(event: PhotoEvent): List<PhotoAction> {
        if (event is PhotoEvent.Request) {
            // 终态也接受 Request：一条新请求就是一次全新的开始
            if (state.isRunning) {
                return emptyList()
            }
            trail.clear()
            identifier = null
            enter(PhotoState.ARMING)
            return listOf(PhotoAction.Arm)
        }
        if (state == PhotoState.IDLE || state.isTerminal) {
            return emptyList()
        }
        // 上一阶段的定时器迟到：阶段对不上就作废，否则「刚拉完图」会被「建组超时」打死
        if (event is PhotoEvent.Timeout && event.stage != state) {
            return emptyList()
        }

        return when (state) {
            PhotoState.ARMING -> when (event) {
                is PhotoEvent.Armed -> {
                    enter(PhotoState.WAITING)
                    listOf(
                        PhotoAction.Report("hq_armed", mapOf("fresh" to event.fresh.toString())),
                        PhotoAction.Poll,
                    )
                }
                is PhotoEvent.Failed -> fail(event.reason)
                is PhotoEvent.Timeout -> fail(PhotoFail.Arm)
                PhotoEvent.Cancel -> abort()
                else -> emptyList()
            }

            PhotoState.WAITING -> when (event) {
                PhotoEvent.Tick -> listOf(PhotoAction.Poll)
                is PhotoEvent.Fresh -> {
                    identifier = event.identifier
                    enter(PhotoState.FETCHING)
                    listOf(PhotoAction.Download)
                }
                // 就绪之后还是没等到新图：最常见的失败，而且不是故障
                is PhotoEvent.Failed -> fail(event.reason)
                is PhotoEvent.Timeout -> fail(PhotoFail.NoShutter)
                PhotoEvent.Cancel -> abort()
                else -> emptyList()
            }

            PhotoState.FETCHING -> when (event) {
                PhotoEvent.Fetched -> {
                    enter(PhotoState.ENCODING)
                    listOf(PhotoAction.Encode)
                }
                is PhotoEvent.Failed -> fail(event.reason)
                is PhotoEvent.Timeout -> fail(PhotoFail.Download)
                PhotoEvent.Cancel -> abort()
                else -> emptyList()
            }

            PhotoState.ENCODING -> when (event) {
                PhotoEvent.Encoded -> {
                    enter(PhotoState.UPLOADING)
                    listOf(PhotoAction.Upload)
                }
                is PhotoEvent.Failed -> fail(event.reason)
                is PhotoEvent.Timeout -> fail(PhotoFail.Decode)
                PhotoEvent.Cancel -> abort()
                else -> emptyList()
            }

            PhotoState.UPLOADING -> when (event) {
                PhotoEvent.Sent -> {
                    enter(PhotoState.DONE)
                    listOf(PhotoAction.Cleanup)
                }
                is PhotoEvent.Failed -> fail(event.reason)
                is PhotoEvent.Timeout -> fail(PhotoFail.Send)
                PhotoEvent.Cancel -> abort()
                else -> emptyList()
            }

            else -> emptyList()
        }
    }

    private fun fail(reason: PhotoFail): List<PhotoAction> {
        enter(PhotoState.FAILED)
        return listOf(
            PhotoAction.Report("hq_failed", mapOf("reason" to reason.wire)),
            PhotoAction.Cleanup,
        )
    }

    private fun abort(): List<PhotoAction> {
        enter(PhotoState.ABORTED)
        return listOf(
            PhotoAction.Report("hq_failed", mapOf("reason" to "cancelled")),
            PhotoAction.Cleanup,
        )
    }

    private fun enter(to: PhotoState) {
        if (state == to) {
            return
        }
        state = to
        trail.add(to)
    }
}
