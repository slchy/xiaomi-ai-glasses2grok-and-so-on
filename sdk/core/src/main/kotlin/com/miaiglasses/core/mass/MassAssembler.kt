package com.miaiglasses.core.mass

import com.miaiglasses.core.config.GlassesProtocol
import java.io.ByteArrayOutputStream

/**
 * 把 `ch=3` 的 MASS 帧重组为两条流：图片与语音。
 *
 * 这是 `原有的 Java 实现` 的移植，修正了它在长连接上的
 * 两个结构问题：
 *
 * 1. **原实现一个实例只服务一次唤醒**，靠调用方 `beginTransaction()` 手工重置；
 *    这里拆成 [reset] 由会话层在「新一轮唤醒」时调用，漏调不会产出拼接损坏的图片
 *    （因为 `firstImage` 会因 `total` 变化而被识别为新流，见 [feed]）。
 * 2. **超限只停吃、继续计帧** —— 调用方需要知道设备还在推，而不是静默失联。
 *
 * 语音（mSBC）与图片（JPEG）**都不解码**，原样交给宿主。
 */
internal class MassAssembler(
    private val maxImageBytes: Int = DEFAULT_MAX_IMAGE_BYTES,
    private val maxVoiceBytes: Int = DEFAULT_MAX_VOICE_BYTES,
) {
    companion object {
        const val MSBC_FRAME_BYTES = GlassesProtocol.Mass.MSBC_FRAME_BYTES
        const val MSBC_FRAME_MS = GlassesProtocol.Mass.MSBC_FRAME_MS

        /** 实测一帧 JPEG 29 KB；1080p 量级几百 KB。8 MB 留两个数量级余量。 */
        const val DEFAULT_MAX_IMAGE_BYTES = 8 shl 20

        /** 4 MB ≈ 8.7 分钟 mSBC，远超单次唤醒的合理长度。 */
        const val DEFAULT_MAX_VOICE_BYTES = 4 shl 20
    }

    private val image = ByteArrayOutputStream()
    private val voice = ByteArrayOutputStream()
    private val counts = LinkedHashMap<Int, Int>()

    private var imageFrames = 0
    private var voiceFrames = 0
    private var declaredImageBytes = 0
    private var imageComplete = false
    private var imageTruncated = false
    private var voiceTruncated = false
    private var lastFrame: MassFrame? = null

    /**
     * **短 END 到达那一刻**的图片进度快照（诊断用）。
     *
     * v0 对齐参考实现口径：END **不交付**图片（实测）。这里保留快照，是为了万一真机上
     * 声明总长永远凑不满，能立刻看出「END 时差了多少」，而不是干等一张不来的图。
     */
    var endAtImageSize: Int = 0
        private set
    var endAtDeclaredTotal: Int = 0
        private set
    var shortEndCount: Int = 0
        private set

    /**
     * 被丢弃的**不完整**图片流计数，以及最后一次丢弃时的进度。
     *
     * 与参考实现同构：凑不满声明总长的图不交付。区别只是参考实现用 15 s 定时器，我们用
     * 「下一条图片流声明了不同的总长」这个天然边界。丢弃必须可观测，否则就是静默丢图。
     */
    var droppedIncompleteCount: Int = 0
        private set
    var lastDroppedImageSize: Int = 0
        private set
    var lastDroppedDeclaredTotal: Int = 0
        private set

    /**
     * 语音增量回调：每收到一帧语音就交出**该帧的载荷副本**。
     *
     * 为什么要有它：宿主需要边收边送 ASR，不能等整轮结束。图片走的是
     * 「拼接完成后一次性交付」，语音走流式 —— 两者需求不同。
     */
    var onVoiceChunk: ((ByteArray) -> Unit)? = null

    /**
     * 喂一帧 `ch=3` 载荷。
     *
     * @return `true` 表示**这一帧**让图片首次收完（调用方据此落盘一次）。
     *   已完成后继续到来的补帧返回 `false`，避免重复写文件。
     *
     * 完成判据只有一条：**收到声明的总长**（参考实现）。
     * 短 END 不参与判定 —— 参考实现收到 END 什么都不做（实测）。
     */
    fun feed(body: ByteArray): Boolean {
        val m = MassCodec.parse(body) ?: return false
        lastFrame = m

        // 先统计所有类型再分流：任何分支提前返回都不该让分布失真
        if (m.massType >= 0) counts[m.massType] = (counts[m.massType] ?: 0) + 1

        // 短 END：只记录进度，不交付。参考实现 case 4/5 是空分支，这里保持一致。
        if (m.isEnd && !m.isImage && !m.isVoice) {
            shortEndCount++
            endAtImageSize = image.size()
            endAtDeclaredTotal = declaredImageBytes
            return false
        }

        if (m.isImage) {
            if (m.total > 0) {
                // 总长变化 ⇒ 设备开始了一条新图片流。上一条若没凑满声明总长，
                // 在这里被丢弃（参考实现用 15 s 定时器丢，等价效果）。
                if (declaredImageBytes != 0 && m.total != declaredImageBytes) {
                    if (!imageComplete && image.size() > 0) {
                        droppedIncompleteCount++
                        lastDroppedImageSize = image.size()
                        lastDroppedDeclaredTotal = declaredImageBytes
                    }
                    image.reset()
                    imageComplete = false
                }
                declaredImageBytes = m.total
            }
            if (m.payloadLen > 0) {
                if (image.size() + m.payloadLen > maxImageBytes) {
                    imageTruncated = true
                } else {
                    image.write(body, m.payloadOff, m.payloadLen)
                }
            }
            imageFrames++
            if (!imageComplete && declaredImageBytes > 0 && image.size() >= declaredImageBytes) {
                imageComplete = true
                return true
            }
            return false
        }

        if (m.isVoice) {
            if (m.payloadLen > 0) {
                if (voice.size() + m.payloadLen > maxVoiceBytes) {
                    voiceTruncated = true
                } else {
                    voice.write(body, m.payloadOff, m.payloadLen)
                }
                onVoiceChunk?.invoke(body.copyOfRange(m.payloadOff, m.payloadOff + m.payloadLen))
            }
            voiceFrames++
        }
        return false
    }

    /** 只清图片侧，语音累计与统计保留（同一轮里语音可能还在继续）。 */
    fun clearImage() {
        image.reset()
        imageFrames = 0
        declaredImageBytes = 0
        imageComplete = false
        imageTruncated = false
    }

    /**
     * 新一轮唤醒：清空两条流与统计。
     *
     * **只清数据，不动 [onVoiceChunk]** —— 那个回调属于装配器的构造配置，
     * 归会话层所有；在这里置 null 会让下一轮语音流静默断掉。
     */
    fun reset() {
        image.reset()
        voice.reset()
        counts.clear()
        imageFrames = 0
        voiceFrames = 0
        declaredImageBytes = 0
        imageComplete = false
        imageTruncated = false
        voiceTruncated = false
        lastFrame = null
    }

    val isImageComplete: Boolean get() = imageComplete
    val isImageTruncated: Boolean get() = imageTruncated
    val isVoiceTruncated: Boolean get() = voiceTruncated
    val frame: MassFrame? get() = lastFrame
    val imageFrameCount: Int get() = imageFrames
    val voiceFrameCount: Int get() = voiceFrames
    val imageSize: Int get() = image.size()
    val voiceSize: Int get() = voice.size()

    /** 眼镜声明的图片总长；0 = 未声明。 */
    val declaredTotal: Int get() = declaredImageBytes

    fun imageBytes(): ByteArray = image.toByteArray()
    fun voiceBytes(): ByteArray = voice.toByteArray()

    /** 按 mSBC 帧长换算出的语音时长（秒）。 */
    val voiceSeconds: Double
        get() = voice.size().toDouble() / MSBC_FRAME_BYTES * MSBC_FRAME_MS / 1000.0

    fun massCounts(): Map<Int, Int> = counts.toMap()
}
