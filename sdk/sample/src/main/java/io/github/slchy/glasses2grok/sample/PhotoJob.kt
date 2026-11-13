package io.github.slchy.glasses2grok.sample

import android.content.Context
import android.content.SharedPreferences
import io.github.slchy.glasses2grok.core.album.AlbumEntry
import io.github.slchy.glasses2grok.sdk.GlassesSession
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 高清照的**执行器**：把 [PhotoFsm] 给出的动作真正做出来。
 *
 * 相位判断全在 [PhotoFsm]（纯逻辑、可单测），这里只负责：
 * 一个后台线程消费事件、按阶段起超时、执行 P2P/相册/解码/上行。
 *
 * <h2>为什么要等「人」</h2>
 *
 * 程序化快门不写相册（`实测`：`capturePhoto()` 之后 45 s、34 次
 * 轮询，相册始终 8 条）。所以这里改成了**协作式**：建好通道后上报 `hq_armed`，
 * 云端据此播一句「请按一下眼镜上的快门」，然后我们轮询等新文件。
 *
 * <h2>`known` 为什么不只是本次基线</h2>
 *
 * 只拿「本次基线」当判据，一旦上一轮超时之后用户才按下去，那张照片下次就会被当成
 * 「新拍的」发上去 —— 又是「从哪找来张图」。所以基线取**并集**：
 * `known = 上次持久化的已知集 ∪ 本次列到的`，于是「请求之前就在相册里的一切」
 * 永远是已知。代价只是建组那 ~5 s 里抢先按下的快门可能漏掉（提示语是在就绪之后
 * 才播的，正常使用碰不到）。
 */
internal class PhotoJob(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val log: (String) -> Unit,
    private val sendStatus: (String, Map<String, String>) -> Unit,
    private val sendImage: (ByteArray) -> Boolean,
) {

    companion object {
        /** 建组 + 入组 + 连相册（实测 5.4 s，留三倍余量） */
        const val ARM_TIMEOUT_MS = 15_000L

        /** 就绪后等用户按快门。人找按钮 + LLHDR 多曝光 + 落盘，20 s 够宽 */
        const val SHUTTER_TIMEOUT_MS = 20_000L

        const val FETCH_TIMEOUT_MS = 20_000L
        const val ENCODE_TIMEOUT_MS = 10_000L
        const val UPLOAD_TIMEOUT_MS = 5_000L

        /** 轮询间隔：设备是 LLHDR 多曝光，写盘本来就要 5 s 左右，1 s 一次足够 */
        const val POLL_GAP_MS = 1_000L

        /** 连续这么多次列目录失败才判定通道坏了（偶发一次不算） */
        const val LIST_FAIL_LIMIT = 3

        const val KEY_KNOWN = "hqKnownIds"
    }

    private val fsm = PhotoFsm()
    private val events = LinkedBlockingQueue<PhotoEvent>()
    private val timers: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "photo-timer").apply { isDaemon = true }
        }

    private var worker: Thread? = null
    private var stateTimer: ScheduledFuture<*>? = null
    private var tickTimer: ScheduledFuture<*>? = null

    private var session: GlassesSession? = null
    private var photo: HighResPhoto? = null
    private var jpeg: ByteArray? = null

    /** 请求之前就已经在相册里的条目（含历史上见过的） */
    private val known = mutableSetOf<String>()

    /** 之前存过 known（第一次跑的时候不能把整册旧照片当成新的） */
    private var hasPersistedKnown = false
    private var checkedBaseline = false
    private var listFailures = 0

    /** 正在跑：这期间要丢掉 SPP 的低清回图 */
    val isActive: Boolean get() = fsm.state.isRunning

    /** 云端请求高清照。已经在跑就忽略（单飞）。 */
    fun start(session: GlassesSession) {
        if (fsm.state.isRunning) {
            log("高清拍照 （已经在等一张了，忽略重复请求）")
            return
        }
        this.session = session
        ensureWorker()
        events.offer(PhotoEvent.Request)
    }

    /** 取消（云端 `hq_cancel` / 用户改主意）。 */
    fun cancel(why: String) {
        if (!fsm.state.isRunning) {
            return
        }
        log("高清拍照取消（$why）")
        events.offer(PhotoEvent.Cancel)
    }

    // ------------------------------------------------------------------ 线程

    private fun ensureWorker() {
        if (worker != null) {
            return
        }
        worker = Thread({ loop() }, "photo-job").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        while (true) {
            val event = try {
                events.take()
            } catch (e: InterruptedException) {
                break
            }
            val before = fsm.state
            val actions = fsm.dispatch(event)
            if (fsm.state != before) {
                onStateChanged(before)
            }
            for (action in actions) {
                try {
                    execute(action)
                } catch (e: Throwable) {
                    log("高清拍照 ❌ 执行 ${action.javaClass.simpleName} 出错：${e.javaClass.simpleName}: ${e.message}")
                    events.offer(PhotoEvent.Failed(PhotoFail.Connect))
                }
            }
        }
    }

    private fun onStateChanged(from: PhotoState) {
        cancelStateTimer()
        cancelTick()
        if (from == PhotoState.IDLE || from.isTerminal) {
            // 全新一次：把上一轮的残留清掉，并读回持久化的 known
            known.clear()
            known.addAll(prefs.getStringSet(KEY_KNOWN, null) ?: emptySet())
            hasPersistedKnown = prefs.contains(KEY_KNOWN)
            checkedBaseline = false
            listFailures = 0
            jpeg = null
        }
        when (fsm.state) {
            PhotoState.ARMING -> scheduleStateTimeout(ARM_TIMEOUT_MS)
            PhotoState.WAITING -> scheduleStateTimeout(SHUTTER_TIMEOUT_MS)
            PhotoState.FETCHING -> scheduleStateTimeout(FETCH_TIMEOUT_MS)
            PhotoState.ENCODING -> scheduleStateTimeout(ENCODE_TIMEOUT_MS)
            PhotoState.UPLOADING -> scheduleStateTimeout(UPLOAD_TIMEOUT_MS)
            else -> Unit
        }
        log("高清拍照 [${fsm.state}] ${elapsed()}ms  ${fsm.path}")
    }

    private fun scheduleStateTimeout(ms: Long) {
        val stage = fsm.state
        stateTimer?.cancel(false)
        stateTimer = timers.schedule(
            { events.offer(PhotoEvent.Timeout(stage)) },
            ms,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelStateTimer() {
        stateTimer?.cancel(false)
        stateTimer = null
    }

    private fun scheduleTick() {
        tickTimer?.cancel(false)
        tickTimer = timers.schedule(
            { events.offer(PhotoEvent.Tick) },
            POLL_GAP_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelTick() {
        tickTimer?.cancel(false)
        tickTimer = null
    }

    // ------------------------------------------------------------------ 动作

    private fun execute(action: PhotoAction) {
        when (action) {
            PhotoAction.Arm -> doArm()
            PhotoAction.Poll -> doPoll()
            PhotoAction.Download -> doDownload()
            PhotoAction.Encode -> doEncode()
            PhotoAction.Upload -> doUpload()
            is PhotoAction.Report -> sendStatus(action.event, action.fields)
            PhotoAction.Cleanup -> doCleanup()
        }
    }

    private fun doArm() {
        val s = session
        if (s == null) {
            log("高清拍照 ❌ 没有会话")
            events.offer(PhotoEvent.Failed(PhotoFail.Connect))
            return
        }
        val capability = HighResPhoto(context) { line -> log(line) }
        photo = capability
        val failure = capability.connect(s)
        if (failure != null) {
            events.offer(PhotoEvent.Failed(failure))
            return
        }
        log("高清拍照通道就绪 ${elapsed()}ms")

        val entries = capability.list()
        if (entries == null) {
            events.offer(PhotoEvent.Failed(PhotoFail.Connect))
            return
        }
        // 第一次列目录：只问「有没有不在上次已知里的」
        var fresh = false
        if (!checkedBaseline) {
            checkedBaseline = true
            fresh = hasPersistedKnown && entries.any { it.identifier !in known }
            known.addAll(entries.map { it.identifier })
            persistKnown()
            log("高清拍照基线 ${entries.size} 条，已有的新图=$fresh")
        }
        events.offer(PhotoEvent.Armed(fresh))
    }

    private fun doPoll() {
        val capability = photo
        if (capability == null) {
            events.offer(PhotoEvent.Failed(PhotoFail.Connect))
            return
        }
        val entries = capability.list()
        if (entries == null) {
            listFailures += 1
            if (listFailures >= LIST_FAIL_LIMIT) {
                log("高清拍照 ❌ 连续 $listFailures 次列目录失败，放弃")
                events.offer(PhotoEvent.Failed(PhotoFail.Connect))
            } else {
                scheduleTick()
            }
            return
        }
        listFailures = 0
        val fresh = entries
            .filter { it.identifier !in known }
            .maxByOrNull { maxOf(it.fileModifiedMillis, it.fileAddedMillis) }
        log(
            "高清拍照列目录：${entries.size} 条"
                + "（新 ${entries.count { it.identifier !in known }}），"
                + "新条目 ${fresh?.identifier ?: "无"} ${elapsed()}ms",
        )
        if (fresh == null) {
            scheduleTick()
            return
        }
        known.add(fresh.identifier)
        events.offer(PhotoEvent.Fresh(fresh.identifier))
    }

    private fun doDownload() {
        val capability = photo
        val identifier = fsm.identifier
        if (capability == null || identifier == null) {
            events.offer(PhotoEvent.Failed(PhotoFail.Download))
            return
        }
        val entry: AlbumEntry? = capability.list()
            ?.firstOrNull { it.identifier == identifier }
        if (entry == null) {
            log("高清拍照 ❌ 条目 $identifier 不见了")
            events.offer(PhotoEvent.Failed(PhotoFail.Download))
            return
        }
        val failure = capability.download(entry)
        if (failure != null) {
            events.offer(PhotoEvent.Failed(failure))
            return
        }
        events.offer(PhotoEvent.Fetched)
    }

    private fun doEncode() {
        val bytes = photo?.encode()
        if (bytes == null) {
            events.offer(PhotoEvent.Failed(PhotoFail.Decode))
            return
        }
        jpeg = bytes
        log("高清拍照缩图完成 ${bytes.size}B（长边 ${HighResPhoto.LONG_EDGE} / q${HighResPhoto.JPEG_QUALITY}）")
        events.offer(PhotoEvent.Encoded)
    }

    private fun doUpload() {
        val bytes = jpeg
        if (bytes == null) {
            events.offer(PhotoEvent.Failed(PhotoFail.Send))
            return
        }
        // 先公告再发图：云端靠这条把高清原图和 SPP 低清回图分开
        sendStatus("hq_image", emptyMap())
        if (sendImage(bytes)) {
            log("高清拍照上行 ${bytes.size}B ${elapsed()}ms")
            events.offer(PhotoEvent.Sent)
        } else {
            log("高清拍照 ❌ 上行被拒（云端通道未就绪）")
            events.offer(PhotoEvent.Failed(PhotoFail.Send))
        }
    }

    private fun doCleanup() {
        // 收尾时把相册现状记成已知：下一轮绝不会把这次的图当成新拍的
        val entries = photo?.list()
        if (entries != null) {
            known.addAll(entries.map { it.identifier })
        }
        persistKnown()
        photo?.close()
        photo = null
        jpeg = null
        session = null
        cancelStateTimer()
        cancelTick()
        val result = when (fsm.state) {
            PhotoState.DONE -> "DONE"
            PhotoState.ABORTED -> "ABORTED"
            else -> "FAILED"
        }
        log("高清拍照结束 $result ${elapsed()}ms  ${fsm.path}")
        startedAt = 0L
    }

    // ------------------------------------------------------------------ 杂项

    private var startedAt = 0L

    private fun elapsed(): Long {
        if (startedAt == 0L) {
            startedAt = System.currentTimeMillis()
            return 0L
        }
        return System.currentTimeMillis() - startedAt
    }

    private fun persistKnown() {
        prefs.edit().putStringSet(KEY_KNOWN, HashSet(known)).apply()
        hasPersistedKnown = true
    }
}
