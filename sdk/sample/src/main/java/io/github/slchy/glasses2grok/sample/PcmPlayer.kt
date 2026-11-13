package io.github.slchy.glasses2grok.sample

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

/**
 * 下行语音播放：云端给 PCM16 单声道，这里边收边播。
 *
 * 走 `AudioTrack` 而不是 SPP —— SPP 只承担**上行** mic（mSBC 压缩帧），
 * 下行播报的路子是手机自己发声（参考实现也是这条路，只是它播 URL）。
 *
 * 采样率由云端 TTS provider 声明（内置 `mimo` / `silent` 都是 24 kHz）。
 */
internal class PcmPlayer(
    private val sampleRate: Int = 24_000,
    private val log: (String) -> Unit,
) {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 已写进 AudioTrack 的帧数（含尚在硬件缓冲里的） */
    @Volatile
    private var enqueuedFrames = 0L

    fun start() {
        if (running) return
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            log("播放器：设备不支持 ${sampleRate} Hz 输出（$minBuffer）")
            return
        }
        // 200 ms 缓冲起步：太小会断续，太大会让「打断」反应变慢
        val bufferBytes = max(minBuffer, sampleRate / 5 * 2)
        val created = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // 用 MEDIA 而不是 ASSISTANT：后者走的是「助手/通话」那一路音量，
                        // 用户按媒体音量键根本调不到它 —— 这就是「音量偏小」的一半原因。
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            log("播放器创建失败：${e.message}")
            return
        }
        track = created
        running = true
        created.setVolume(1.0f)
        created.play()
        log("播放器就绪 ${sampleRate} Hz / ${bufferBytes}B 缓冲")

        enqueuedFrames = 0
        worker = Thread({
            while (running) {
                val chunk = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    break
                }
                if (!running) break
                try {
                    created.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    enqueuedFrames += chunk.size / 2L
                } catch (e: Exception) {
                    log("播放失败：${e.message}")
                }
            }
        }, "voice-player").apply { isDaemon = true }
        worker?.start()
    }

    fun write(pcm: ByteArray) {
        if (running) queue.offer(pcm)
    }

    /** 打断：把还没播的和排队的一起丢掉。 */
    fun flush() {
        queue.clear()
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (e: Exception) {
            log("播放器 flush 失败：${e.message}")
        }
        enqueuedFrames = 0
    }

    /**
     * 还有多少毫秒没播完。
     *
     * 用 `AudioTrack.getPlaybackHeadPosition()`（**已经真正出声**的帧数）而不是
     * 「已写入的字节数」—— 后者在云端一次性把整轮音频推下来时会立刻到顶，
     * 于是指示灯提前熄灭（真机就是这么翻的：常亮只闪了一下）。
     */
    fun remainingMs(): Long {
        val t = track ?: return 0
        val played = try {
            t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } catch (e: Exception) {
            return 0
        }
        val pending = enqueuedFrames - played
        return if (pending <= 0) 0 else pending * 1000 / sampleRate
    }

    fun release() {
        running = false
        worker?.interrupt()
        worker = null
        queue.clear()
        try {
            track?.stop()
            track?.release()
        } catch (e: Exception) {
            // 已经释放过
        }
        track = null
    }
}
