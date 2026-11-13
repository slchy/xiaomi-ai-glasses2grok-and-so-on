package io.github.slchy.glasses2grok.sample

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 手机侧云端通道：把眼镜上行 mSBC 原样转发给云端，把云端 PCM 播出来。
 *
 * 它**不碰蓝牙**：`GlassesEvent.Audio.data` 已经是 mSBC 原始字节（10×57 B / 75 ms），
 * 眼镜已经压好了，手机只管搬运 —— 这里不需要任何编解码器。
 *
 * 两条线程各管一件事：
 * - **writer**：唯一往 socket 写字节的地方（`Hello`、心跳、音频都排同一个队列）
 * - **supervisor**：连接/断线重连（1 s → 15 s 退避），并为每条连接配一个心跳定时器
 *
 * 为什么非要单独一条写线程：`pushAudio` 是从**主线程**调来的（宿主的事件回调），
 * Android 不允许在主线程做网络 IO（`NetworkOnMainThreadException`）。真机上第一次
 * 按眼镜按钮就是这么炸的 —— 现在写操作全在这条线程上，调用方只做一次入队。
 *
 * 线协议见 [Wire]（与 `cloud/tools/fake-phone.ts` 同构）。TLS 走 [Tls]：
 * 勾上就用自签 CA 严格校验（等价 pinning），不勾是明文，仅供本机调试。
 */
internal class VoiceLink(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val token: String,
    private val deviceId: String,
    private val useTls: Boolean,
    private val log: (String) -> Unit,
    private val onControl: (String) -> Unit,
    /** 云端开始/结束下发语音。用来驱动眼镜内侧指示灯（说话时常亮）。 */
    private val onSpeaking: (Boolean) -> Unit,
    private val onClosed: (String) -> Unit,
) {

    private companion object {
        const val PING_INTERVAL_S = 10L
        /** 指示灯/心跳共用的节拍间隔 */
        const val TICK_MS = 200L
        /** 队列空了之后还要再静这么久才算播完 —— 否则包在途的空档会让灯抖 */
        const val SPEAKING_TAIL_MS = 1_000L
        /** 这么久没收到任何字节就认为连接已死（云端每收一个 Ping 会回 Pong） */
        const val RX_DEADLINE_MS = 30_000L
        const val BACKOFF_START_MS = 1_000L
        const val BACKOFF_MAX_MS = 15_000L
        /**
         * 待发队列上限按**字节**算：高清照一次就 500–800 KB，按条数限（160 条）
         * 等于允许上百 MB 堆在内存里。8 MB 够放一帧高清 + 一串音频帧。
         */
        const val OUTBOX_MAX_BYTES = 8 * 1024 * 1024
    }

    private val player = PcmPlayer(24_000) { line -> log("  $line") }
    private val decoder = Wire.Decoder()
    private val writeLock = Any()
    private val outbox = LinkedBlockingQueue<ByteArray>()
    private val outboxBytes = java.util.concurrent.atomic.AtomicLong()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastRxAt = 0L

    /** 指示灯当前是否处于「常亮」（云端正在播报） */
    @Volatile
    private var speaking = false

    @Volatile
    private var lastAudioAt = 0L


    /** 队列里已经有 Hello 了：在它之前不许插音频帧，否则会被云端当「未鉴权就发帧」拒掉 */
    @Volatile
    private var helloQueued = false

    private var supervisor: Thread? = null
    private var writer: Thread? = null

    fun start() {
        if (running) return
        running = true
        player.start()
        writer = Thread({ writeLoop() }, "voice-link-writer").apply { isDaemon = true }
        writer?.start()
        supervisor = Thread({ supervise() }, "voice-link").apply { isDaemon = true }
        supervisor?.start()
    }

    /** 眼镜上行的一块 mSBC（`GlassesEvent.Audio.data`）。可能在主线程被调用。 */
    fun pushAudio(data: ByteArray) {
        if (!helloQueued) return
        send(Wire.frame(Wire.UP_AUDIO, data))
    }

    /** 设备回的一张 JPEG（`GlassesEvent.Image.jpeg`）。 */
    fun sendImage(jpeg: ByteArray): Boolean {
        if (!helloQueued) return false
        send(Wire.frame(Wire.UP_IMAGE, jpeg))
        return true
    }

    /** 状态事件（高清照流程的「就绪 / 出图 / 失败」）。通道没就绪时返回 false。 */
    fun sendStatus(event: String, fields: Map<String, String>): Boolean {
        if (!helloQueued) return false
        send(Wire.status(event, *fields.map { it.key to it.value }.toTypedArray()))
        return true
    }

    fun stopPlayback() {
        player.flush()
    }

    /**
     * 云端是否正在播报。
     *
     * 手机上用它来判断「现在别关会话」—— 播报中途结束 Voice 会话会让指示灯/状态机
     * 在句子中间跳变，而且播完之前本来也不该让眼镜睡。
     */
    val isSpeaking: Boolean get() = speaking

    fun close(reason: String = "本地关闭") {
        if (!running) return
        running = false
        writer?.interrupt()
        supervisor?.interrupt()
        writer = null
        supervisor = null
        helloQueued = false
        dropSocket()
        player.release()
        onClosed(reason)
    }

    // ------------------------------------------------------------------ 写

    private fun writeLoop() {
        while (running) {
            val bytes = try {
                outbox.take()
            } catch (e: InterruptedException) {
                break
            }
            outboxBytes.addAndGet(-bytes.size.toLong())
            val s = socket ?: continue // 断开期间直接丢
            try {
                synchronized(writeLock) {
                    s.getOutputStream().write(bytes)
                }
            } catch (e: Exception) {
                // 不在这里重连：只丢这一帧，交给 supervisor
                log("  发送失败：${describe(e)}")
                dropSocket()
            }
        }
    }

    private fun send(bytes: ByteArray) {
        if (bytes.size > OUTBOX_MAX_BYTES) {
            log("  发送失败：单帧 ${bytes.size}B 超过队列上限")
            return
        }
        // 满了先丢最旧的（旧音频已经没有意义）
        while (outboxBytes.get() + bytes.size > OUTBOX_MAX_BYTES) {
            val dropped = outbox.poll() ?: break
            outboxBytes.addAndGet(-dropped.size.toLong())
        }
        outboxBytes.addAndGet(bytes.size.toLong())
        outbox.offer(bytes)
    }

    // ------------------------------------------------------------------ 连接

    /** 连接管理者：连上 → 跑到断 → 退避 → 再连。 */
    private fun supervise() {
        var backoff = BACKOFF_START_MS
        while (running) {
            val connectedAt = System.currentTimeMillis()
            try {
                connectOnce()
            } catch (e: Exception) {
                if (running) {
                    log("  云端断开：${describe(e)}")
                }
            }
            if (!running) break

            if (System.currentTimeMillis() - connectedAt < 30_000) {
                log("  ${backoff / 1000} s 后重连…")
                try {
                    Thread.sleep(backoff)
                } catch (e: InterruptedException) {
                    break
                }
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
            } else {
                backoff = BACKOFF_START_MS
                log("  立即重连…")
            }
        }
    }

    private fun connectOnce() {
        val s = Tls.open(host, port, useTls, context) { line -> log(line) }
        outbox.clear()
        outboxBytes.set(0)
        socket = s
        lastRxAt = System.currentTimeMillis()
        log("已连接 ${if (useTls) "tls" else "tcp"}://$host:$port")

        // Hello 也走同一个队列，保证它排在所有音频帧前面
        helloQueued = true
        send(Wire.hello(deviceId, token))
        send(Wire.frame(Wire.UP_PING, ByteArray(0)))
        log("已发送 Hello" + if (token.isEmpty()) "（无 token）" else "")

        // 一条 200 ms 的节拍线程干两件事：心跳（每 10 s 一次）+ 指示灯熄灭判定
        val beatsPerPing = PING_INTERVAL_S * 1_000 / TICK_MS
        val pinger: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "voice-tick").apply { isDaemon = true }
        }
        var beat = 0L
        pinger.scheduleWithFixedDelay({
            if (System.currentTimeMillis() - lastRxAt > RX_DEADLINE_MS) {
                log("  ${RX_DEADLINE_MS / 1000} s 没收到任何回应，主动断开重连")
                dropSocket()
                return@scheduleWithFixedDelay
            }
            // 播报结束的判定看**实际出声进度**，不是网络到包：云端会把整轮音频一口气推下来，
            // 按到包算会让常亮在播放刚开始时就结束（真机就是这么翻的）。
            if (speaking 
                && player.remainingMs() <= 150 
                && System.currentTimeMillis() - lastAudioAt > SPEAKING_TAIL_MS
            ) {
                speaking = false
                log("  指示灯常亮 → 闪烁（播放完）")
                onSpeaking(false)
            }
            beat += 1
            if (beat % beatsPerPing == 0L) {
                send(Wire.frame(Wire.UP_PING, ByteArray(0)))
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)

        try {
            readLoop(s.getInputStream())
        } finally {
            pinger.shutdownNow()
            helloQueued = false
            outbox.clear()
            outboxBytes.set(0)
            // 断开时把「正在播报」清掉：否则这个标志会永久停在 true，
            // 手机侧的空闲结束会话就永远等不到（眼镜醒着不睡）。
            stopSpeaking("通道断开")
            dropSocket()
        }
    }

    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(8 * 1024)
        while (running) {
            val n = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (running) throw e else return
            }
            if (n <= 0) {
                if (running) throw IllegalStateException("对端关闭")
                return
            }
            lastRxAt = System.currentTimeMillis()
            for ((type, payload) in decoder.push(buffer, 0, n)) {
                when (type) {
                    Wire.DOWN_AUDIO -> {
                        player.write(payload)
                        onAudioStage()
                    }
                    Wire.DOWN_CONTROL -> {
                        val text = payload.toString(Charsets.UTF_8)
                        log("← 控制 $text")
                        if (text == "stop") stopSpeaking("被打断")
                        onControl(text)
                    }
                    Wire.DOWN_PONG -> Unit
                    else -> log("← 未知帧 $type（${payload.size}B）")
                }
            }
        }
    }

    private fun stopSpeaking(why: String) {
        if (!speaking) return
        speaking = false
        log("  指示灯常亮 → 闪烁（$why）")
        onSpeaking(false)
    }

    /** 收到一块下行音频：首块把指示灯点成常亮。 */
    private fun onAudioStage() {
        lastAudioAt = System.currentTimeMillis()
        if (!speaking) {
            speaking = true
            log("  指示灯闪烁 → 常亮（云端开始播报）")
            onSpeaking(true)
        }
    }

    private fun dropSocket() {
        helloQueued = false
        val s = socket ?: return
        socket = null
        try {
            s.close()
        } catch (e: Exception) {
            // 已关闭
        }
    }

    /** `IOException` 的 message 常常是 null，补上类型，否则日志只有「发送失败：null」。 */
    private fun describe(e: Exception): String {
        val message = e.message
        return if (message.isNullOrBlank()) {
            e.javaClass.simpleName
        } else {
            "${e.javaClass.simpleName}: $message"
        }
    }
}
