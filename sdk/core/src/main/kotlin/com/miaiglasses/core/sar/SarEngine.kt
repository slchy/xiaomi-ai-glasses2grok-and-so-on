package com.miaiglasses.core.sar

import com.miaiglasses.core.config.GlassesProtocol
import com.miaiglasses.core.l1.L1
import com.miaiglasses.core.l1.L1Codec
import com.miaiglasses.core.l1.L1Frame
import com.miaiglasses.core.l1.L1Parser
import com.miaiglasses.core.l2.L2

/**
 * L1 SAR 状态机：滑窗、累积 ACK、NAK(Go-Back-N)、frx 强制重同步、发送超时。
 *
 * <h3>与参考实现的关系</h3>
 *
 * 逐条对齐 `参考实现`（该实现依据 `静态分析` 的
 * `参考实现传输层`，并已真机跑通）。**行为目标是与它逐字节一致** ——
 * `tests/golden/vectors.txt` 里 8 条 `sar_*` 向量就是对账基准。
 *
 * <h3>线程</h3>
 *
 * **不建线程、不加锁**。由引擎线程独占（实测）。时间全部由注入的 [Clock] 提供。
 *
 * <h3>容易写错的四条（都有对应测试）</h3>
 *
 * 1. **累积 ACK 的计数递增发生在阈值判定之后** —— 阈值用的是「到上一帧为止」的计数。
 * 2. **500 ms 定时器首帧启动后不重置**，否则连续小流量永远不确认。
 * 3. **NAK 的 seq 是「接收方期望值」**，不是实际收到的序号。
 * 4. **协商未完成时只允许发 CMD**；DATA 一律排队。
 */
internal class SarEngine(
    private val sink: (ByteArray) -> Unit,
    private val clock: Clock = Clock.SYSTEM,
    private val config: SarConfig = SarConfig.VALID,
) {
    /** 已解析的帧回调（诊断用）。在引擎线程上调用，不要阻塞。 */
    var onFrame: ((L1Frame) -> Unit)? = null

    /** 交付给 L2 的载荷（已剥掉 L1 头）。在引擎线程上调用。 */
    var onDeliver: ((ByteArray) -> Unit)? = null

    var onError: ((String) -> Unit)? = null

    /**
     * 底层写失败回调。**必须与 [onError] 分开**：
     * 协议错误（CRC、序号越界）可以让会话继续，而写失败意味着链路已断，
     * 上层必须转入重连。把两者混在一个字符串回调里，引擎就只能靠猜。
     */
    var onSinkFailure: ((Throwable) -> Unit)? = null

    /**
     * 标记协商已完成。**仅用于扮演被叫方**（测试里的假设备 / 将来的服务端角色）：
     * 本端作为发起方时，`isCmdExchanged` 只能由收到 `L1START_RSP` 置位。
     */
    internal fun markNegotiated() {
        isCmdExchanged = true
        runNext()
    }

    private val parser = L1Parser()

    // ---- TX 状态
    private var txBase = 0
    private var txNextSeq = 0
    private var txBusy = false
    private val txQueue = ArrayDeque<SendItem>()

    // ---- RX 状态
    private var rxExpect = 0
    private var rxCumAckIndex = 0
    private var cumAckSeq: Int? = null
    private var cumAckDeadlineMs: Long? = null

    // ---- 协商态
    var isCmdExchanged: Boolean = false
        private set
    var remoteMps: Int = config.defaultMps
        private set
    var remoteTxWindow: Int = config.defaultRemoteTxWindow
        private set

    // ---- 统计
    var sentCount = 0L; private set
    var ackedCount = 0L; private set
    var nakSentCount = 0L; private set
    var nakRecvCount = 0L; private set
    var timeoutCount = 0L; private set
    var retransCount = 0L; private set

    /** 已交付给上层的 DATA 帧数。乱序被 NAK 掉的帧不算（没交付）。 */
    var rxFrameCount = 0L; private set

    /**
     * 「不需要 ACK 的 DATA 帧」计数（参考实现）。
     *
     * 参考实现也是不 ACK、不推进 `rxExpect`；这里只观测，用来回答「设备到底发不发
     * ch=7 的 DATA」——没有数据就改行为等于凭直觉动协议。
     */
    var noAckDataFrames = 0L; private set

    /** 其中**正好落在 `rxExpect` 上**的帧数（最危险的一种：它占了序号却不推进）。 */
    var noAckDataOnExpectedSeq = 0L; private set

    val pending: Int get() = txQueue.size

    // ---- 分车道背压
    /** 入队被车道配额拒绝时回调（参数是被挤满的车道）。默认只计数，由会话层决定怎么报错。 */
    var onQueueFull: ((Lane) -> Unit)? = null

    private val laneDepths = IntArray(Lane.entries.size)
    private val laneRejects = LongArray(Lane.entries.size)

    /** 最近一次被拒的原因（`SarResult.EXCEEDS_MPS` / `SarResult.QUEUE_FULL`），0 表示没有拒绝。 */
    internal var lastRejectCode: Int = 0
        private set

    /** 该车道当前占用（已入队、含等待 ACK 的）。 */
    internal fun laneDepth(lane: Lane): Int = laneDepths[lane.ordinal]

    /** 该车道历史拒绝次数。 */
    internal fun laneRejects(lane: Lane): Long = laneRejects[lane.ordinal]

    /** 诊断串：`control=3/256 audio=0/64 image=0/4`。 */
    internal val laneSummary: String
        get() = Lane.entries.joinToString(" ") { "${it.label}=${laneDepth(it)}/${it.capacity}" }

    private fun admit(lane: Lane): Boolean {
        lastRejectCode = 0
        if (laneDepth(lane) >= lane.capacity) {
            laneRejects[lane.ordinal]++
            lastRejectCode = SarResult.QUEUE_FULL
            onQueueFull?.invoke(lane)
            return false
        }
        laneDepths[lane.ordinal]++
        return true
    }

    private fun releaseLane(item: SendItem) {
        val i = item.lane.ordinal
        if (laneDepths[i] > 0) laneDepths[i]--
    }

    /** 当前生效的发送窗口上限。 */
    val sendWindowLimit: Int
        get() = when (val p = config.txWindowPolicy) {
            is TxWindowPolicy.Local -> p.window.coerceAtLeast(1)
            TxWindowPolicy.Negotiated -> remoteTxWindow.coerceAtLeast(1)
        }

    // ------------------------------------------------------------------ 复位

    /**
     * 等价参考实现 —— **`isCmdExchanged` 也归零**。
     * 因此任何重连都必须重做三步协商（实测）。
     */
    fun reset() {
        txBase = 0
        txNextSeq = 0
        rxExpect = 0
        rxCumAckIndex = 0
        txBusy = false
        isCmdExchanged = false
        cancelCumAck()
        for (item in txQueue) item.complete(SarResult.ABORTED)
        txQueue.clear()
        laneDepths.fill(0)
        parser.reset()
    }

    // ------------------------------------------------------------------ 接收

    /**
     * 解析停滞统计（诊断用，不影响任何解析/重传决策）。
     *
     * **为什么需要它**：`L1Parser` 遇到「声明长度 ≤ maxFrame 的伪帧头」时会一直等，
     * 这在字节上与「大帧还没收完」不可区分，于是会有一段时间产不出任何帧（失明）。
     * 这段失明是**自愈**的（攒够声明长度 → CRC 失败 → 前进 1 字节 → 重新对齐），
     * 但时长取决于流量速率，只有实测才能判断要不要加固。
     *
     * 采集到的 `maxMs` / `maxStalledFeedBytes` 就是 ``  定阈值要的数据。
     */
    data class StallStats(
        /** 当前这一次停滞已持续多久（未停滞为 0）。 */
        val currentMs: Long,
        /** 会话内最长一次停滞。 */
        val maxMs: Long,
        /** 停滞发生次数（每次「残留从 0 变正、到再次产出帧」算一次）。 */
        val occurrences: Long,
        /** 停滞期间又喂入的字节数 —— 近似「失明期间吃掉的流量」，与 maxMs 一起看才准。 */
        val maxStalledFeedBytes: Long,
        /** 解析器残留字节数的峰值，用于区分「大帧」与「假帧头」。 */
        val maxPendingBytes: Int,
    )

    private var stallStartMs: Long? = null
    private var stallFedBytes = 0L
    private var stallMaxMs = 0L
    private var stallOccurrences = 0L
    private var stallMaxFedBytes = 0L
    private var pendingPeak = 0

    val stallStats: StallStats
        get() {
            val now = clock.nowMs()
            val start = stallStartMs
            return StallStats(
                currentMs = if (start == null) 0L else now - start,
                maxMs = stallMaxMs,
                occurrences = stallOccurrences,
                maxStalledFeedBytes = stallMaxFedBytes,
                maxPendingBytes = pendingPeak,
            )
        }

    /** 喂入原始字节流，返回本次解析出的帧数。 */
    fun feed(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val before = parser.frameCount
        val badBefore = parser.badCrcCount
        parser.feed(data)
        while (true) {
            val f = parser.next() ?: break
            handleFrame(f)
        }
        if (parser.badCrcCount != badBefore) {
            error("CRC 校验失败 ${parser.badCrcCount - badBefore} 帧，已丢弃")
        }
        val produced = (parser.frameCount - before).toInt()
        trackStall(data.size, produced)
        return produced
    }

    /**
     * 停滞计时：只统计**连续多次喂入都没有产出任何帧、且缓冲区有残留**的那一段。
     *
     * 刻意不用「残留 > 0」当判据 —— 正常流里每次喂入后都残留下一帧的开头，
     * 那样会把 `occurrences` 变成喂入次数、毫无意义。
     * 也不看坏 CRC 数：假帧头在等长度期间既不产出帧、也不报坏 CRC，
     * 正是要观测的那一段。
     */
    private fun trackStall(fedBytes: Int, produced: Int) {
        val pending = parser.pendingBytes
        if (pending > pendingPeak) pendingPeak = pending
        if (produced > 0 || pending == 0) {
            closeStall()
            return
        }
        if (stallStartMs == null) {
            stallStartMs = clock.nowMs()
            stallFedBytes = fedBytes.toLong()
            return
        }
        stallFedBytes += fedBytes
    }

    private fun closeStall() {
        val start = stallStartMs ?: return
        stallStartMs = null
        stallOccurrences++
        val elapsed = clock.nowMs() - start
        if (elapsed > stallMaxMs) stallMaxMs = elapsed
        if (stallFedBytes > stallMaxFedBytes) stallMaxFedBytes = stallFedBytes
        stallFedBytes = 0L
    }

    private fun handleFrame(f: L1Frame) {
        onFrame?.invoke(f)
        when (f.type) {
            L1.TYPE_DATA -> handleData(f)
            L1.TYPE_ACK -> handleAck(f.seq)
            L1.TYPE_NAK -> handleNak(f.seq)
            L1.TYPE_CMD -> handleCmd(f)
            else -> error("未知帧类型 ${f.type}")
        }
    }

    /** 参考实现：协商未完成不回；NETWORK 通道不回。 */
    private fun needSendAck(payload: ByteArray): Boolean {
        if (!isCmdExchanged) return false
        if (payload.isEmpty()) return true
        return (payload[0].toInt() and 0xFF) != L2.CH_NETWORK
    }

    /** 参考实现：PB / LYRA 立即确认，其余走累积 ACK。 */
    private fun ackImmediately(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return true
        val ch = payload[0].toInt() and 0xFF
        if (ch == L2.CH_PB || ch == L2.CH_LYRA) return true
        return rxCumAckIndex >= txWinAckThr(remoteTxWindow)
    }

    private fun handleData(f: L1Frame) {
        if (f.frx == 1) {
            // 强制序列重同步：直接采纳对端序号
            rxExpect = (f.seq + 1) and 0xFF
        } else if (needSendAck(f.payload)) {
            if (f.seq != rxExpect) {
                if (seqDiff(f.seq, rxExpect) >= 128) {
                    // 差值 ≥ 128 ⇒ 旧的重复帧，静默忽略（参考实现行为）
                    return
                }
                sendAck(rxExpect, ack = false)
                nakSentCount++
                error("seq 乱序: got=${f.seq} expect=$rxExpect → 已发 NAK")
                return
            }
            if (ackImmediately(f.payload)) {
                cancelCumAck()
                sendAck(f.seq, ack = true)
            } else {
                startCumAck(f.seq)
            }
            rxExpect = (rxExpect + 1) and 0xFF
        } else {
            // 参考实现同样「不 ACK、不推进 rxExpect」（`参考实现传输层.needSendAck`: payload[0] != 7）。
            // 但要能回答「设备到底发不发这种帧」，否则将来出现连环 NAK 只能靠猜。
            noAckDataFrames++
            if (f.seq == rxExpect) {
                // 它正好占了我们要的下一个序号却没被消耗 ⇒ 下一帧正常数据会被判乱序
                noAckDataOnExpectedSeq++
            }
        }
        rxFrameCount++
        onDeliver?.invoke(f.payload)
    }

    private fun handleCmd(f: L1Frame) {
        if (f.payload.isEmpty()) return
        when (f.payload[0].toInt() and 0xFF) {
            SarConfig.CMD_L1START_REQ -> {
                handleConfig(f.payload.copyOfRange(1, f.payload.size))
                sendCmd(SarConfig.CMD_L1START_RSP, buildConfig())
            }
            SarConfig.CMD_L1START_RSP -> {
                handleConfig(f.payload.copyOfRange(1, f.payload.size))
                isCmdExchanged = true
                runNext()
            }
            SarConfig.CMD_L1STOP_REQ -> sendCmd(SarConfig.CMD_L1STOP_RSP, ByteArray(0))
            SarConfig.CMD_L1STOP_RSP -> Unit    // 参考实现空实现
        }
    }

    /**
     * 协商配置 TLV：`[type:1][len:2 LE][value:len]` 重复。
     * 未识别的项忽略，不抛异常 —— 新固件加字段不该让旧客户端崩。
     */
    private fun handleConfig(data: ByteArray) {
        var i = 0
        while (i + 3 <= data.size) {
            val ctype = data[i].toInt() and 0xFF
            val clen = (data[i + 1].toInt() and 0xFF) or ((data[i + 2].toInt() and 0xFF) shl 8)
            i += 3
            if (i + clen > data.size) break
            val v = data.copyOfRange(i, i + clen)
            i += clen
            when {
                ctype == CONFIG_TYPE_MPS && clen == 2 -> {
                    remoteMps = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
                    // 协商后收紧单帧上限：噪声里「看起来合法」的假长度就没机会堵死解析。
                    parser.setMaxFrameBytes(remoteMps + L1.HEADER_SIZE)
                }
                ctype == CONFIG_TYPE_TX_WIN && clen == 2 ->
                    remoteTxWindow = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
                // VERSION(1) / SEND_TIMEOUT(4) / DEVICE_TYPE(5) / DEVICE_NAME(6) 只记录不下发
            }
        }
    }

    // ------------------------------------------------------------------ ACK

    private fun sendAck(seq: Int, ack: Boolean, force: Boolean = false) {
        try {
            sink(L1Codec.buildAck(seq and 0xFF, ack = ack, force = force))
        } catch (e: Exception) {
            onSinkFailure?.invoke(e)
            error("发送 ${if (ack) "ACK" else "NAK"} 失败: ${e.message}")
        }
    }

    /** @return 是否被接受（NAK 路径复用） */
    private fun handleAck(seq: Int): Boolean {
        if (seqDiff(seq, txBase) > seqDiff(txNextSeq, txBase)) {
            error("ack 越界: seq=$seq base=$txBase next=$txNextSeq")
            return false
        }
        var guard = 0
        while (txBase != ((seq + 1) and 0xFF)) {
            val item = txQueue.firstOrNull { it.assigned && it.seq == txBase } ?: break
            complete(item, SarResult.OK)
            txBase = (txBase + 1) and 0xFF
            ackedCount++
            if (++guard > 256) break
        }
        runNext()
        return true
    }

    private fun handleNak(seq: Int) {
        nakRecvCount++
        val ok = handleAck((seq - 1) and 0xFF)
        if (seq == txBase || ok) {
            for (item in txQueue) {
                if (item.waitAck) {
                    item.needRetrans = true
                    item.deadlineMs = null
                }
            }
            runNext()
        }
    }

    // ------------------------------------------------------------------ 累积 ACK

    /**
     * 参考实现。
     *
     * 两个细节都不能省：
     * 1) 计数递增发生在 `ackImmediately()` 判定**之后** ⇒ 阈值判定用的是旧计数；
     * 2) 500 ms 定时器**首帧启动后不重置**，否则连续小流量永远不确认。
     */
    private fun startCumAck(seq: Int) {
        rxCumAckIndex++
        cumAckSeq = seq
        if (cumAckDeadlineMs == null) {
            cumAckDeadlineMs = clock.nowMs() + config.cumAckTimeoutMs
        }
    }

    private fun cancelCumAck() {
        cumAckSeq = null
        cumAckDeadlineMs = null
    }

    // ------------------------------------------------------------------ 发送

    /** CMD 帧 seq 恒为 0（参考实现硬编码），且不需要 ACK。 */
    fun sendCmd(
        cmd: Int,
        data: ByteArray = ByteArray(0),
        onDone: ((Int) -> Unit)? = null,
        lane: Lane = Lane.CONTROL,
    ): SendItem? {
        val payload = ByteArray(1 + data.size)
        payload[0] = cmd.toByte()
        data.copyInto(payload, 1)
        val item = SendItem(
            type = L1.TYPE_CMD,
            payload = payload,
            seq = 0,
            assigned = true,
            onDone = onDone,
            tag = "CMD$cmd",
            lane = lane,
        )
        if (!admit(lane)) return null
        txQueue.addLast(item)
        runNext()
        return item
    }

    /**
     * 发送一帧 DATA。对齐参考实现的两道前置检查：
     *
     * 1. payload 为空 → **直接丢弃**（不产生帧、不回调）
     * 2. `len(payload) + 2 > remoteMps` → 回调 [SarResult.EXCEEDS_MPS]，**不拆分**
     *
     * @return null 表示被前置检查拒绝
     */
    fun sendData(
        channel: Int,
        opcode: Int,
        payload: ByteArray,
        onDone: ((Int) -> Unit)? = null,
        lane: Lane = Lane.ofChannel(channel),
    ): SendItem? {
        if (payload.isEmpty()) {
            error("sendData: payload 为空，参考实现会直接丢弃")
            return null
        }
        val need = payload.size + 2          // +2 = L2 头（channel + opCode）
        if (need > remoteMps) {
            error("sendData: payload 过长 $need > MPS $remoteMps（参考实现不拆分）")
            lastRejectCode = SarResult.EXCEEDS_MPS
            onDone?.invoke(SarResult.EXCEEDS_MPS)
            return null
        }
        val l2 = ByteArray(2 + payload.size)
        l2[0] = (channel and 0xFF).toByte()
        l2[1] = (opcode and 0xFF).toByte()
        payload.copyInto(l2, 2)
        val item = SendItem(
            type = L1.TYPE_DATA,
            payload = l2,
            onDone = onDone,
            tag = "CH$channel/OP$opcode",
            lane = lane,
        )
        if (!admit(lane)) {
            onDone?.invoke(SarResult.QUEUE_FULL)
            return null
        }
        txQueue.addLast(item)
        runNext()
        return item
    }

    /**
     * 协商配置段（`CMD_L1START_REQ/RSP` 的 body）。
     *
     * `[type:1][len:2 LE][value:len]` 重复，值与参考实现常量一致：
     * VERSION 1.0.0 / MPS 64512 / TX_WIN 32 / SEND_TIMEOUT 10000。
     */
    /**
     * L1 协商配置载荷。内容由 [buildNegotiationConfig] 单点定义 ——
     * 与 `HandshakeDriver` 发的 `L1START_REQ` 必须逐字节相同。
     */
    fun buildConfig(): ByteArray = buildNegotiationConfig()

    private fun pickSendable(): SendItem? {
        for (item in txQueue) {
            // 协商未完成时只允许发 CMD（参考实现 runNext 的门槛）
            if (!isCmdExchanged && item.type != L1.TYPE_CMD) return null
            if (item.needRetrans) return item
            if (!item.waitAck && !item.assigned) return item
            if (!item.waitAck && item.type == L1.TYPE_CMD) return item
        }
        return null
    }

    private fun runNext() {
        if (txBusy) return                    // 重入保护：CMD 回包会在 transmit 中途进来
        txBusy = true
        try {
            while (true) {
                val item = pickSendable() ?: break
                val isNewData = item.type == L1.TYPE_DATA && !item.needRetrans
                if (isNewData && seqDiff(txNextSeq, txBase) >= sendWindowLimit) {
                    break                     // 窗口满
                }
                transmit(item)
            }
        } finally {
            txBusy = false
        }
    }

    private fun transmit(item: SendItem) {
        if (item.needRetrans) {
            retransCount++
        } else if (item.type == L1.TYPE_DATA && !item.assigned) {
            item.seq = txNextSeq
            txNextSeq = (txNextSeq + 1) and 0xFF
            item.assigned = true
        } else if (item.type == L1.TYPE_CMD) {
            item.assigned = true
        }

        val frame = L1Codec.build(item.type, seq = item.seq, payload = item.payload)
        try {
            sink(frame)
            sentCount++
        } catch (e: Exception) {
            complete(item, SarResult.SEND_FAILED)
            onSinkFailure?.invoke(e)
            error("发送失败 $item: ${e.message}")
            return
        }

        if (item.requiresAck) {
            item.waitAck = true
            item.needRetrans = false
            item.deadlineMs = clock.nowMs() + config.sendTimeoutMs
        } else {
            complete(item, SarResult.OK)      // CMD 发送即完成
        }
    }

    private fun complete(item: SendItem, code: Int) {
        if (!txQueue.remove(item)) return
        releaseLane(item)
        item.complete(code)
    }

    // ------------------------------------------------------------------ 定时器

    fun tick() = tick(clock.nowMs())

    /**
     * 驱动超时。**先处理累积 ACK，再处理发送超时** —— 顺序影响同一次 tick 内
     * 「刷出 ACK」与「重传」的相对次序，进而影响线上字节顺序。
     */
    fun tick(nowMs: Long) {
        cumAckDeadlineMs?.let { deadline ->
            if (nowMs >= deadline) {
                val seq = cumAckSeq
                cancelCumAck()
                if (seq != null) sendAck(seq, ack = true)
            }
        }

        var timedOut: SendItem? = null
        for (item in txQueue) {
            if (item.waitAck && !item.needRetrans && item.deadlineMs?.let { nowMs >= it } == true) {
                timedOut = item
                break
            }
        }
        timedOut?.let { onSendTimeout(it) }
    }

    private fun onSendTimeout(item: SendItem) {
        timeoutCount++
        error("发送超时 seq=${item.seq}，Go-Back-N 重传")
        if (seqDiff(item.seq, txBase) < 128 && item.waitAck) {
            for (it in txQueue) {
                if (it.waitAck && seqDiff(it.seq, item.seq) < 128) {
                    it.needRetrans = true
                    it.deadlineMs = null
                }
            }
        }
        runNext()
    }

    private fun error(message: String) {
        onError?.invoke(message)
    }

    private companion object {
        const val CONFIG_TYPE_MPS = GlassesProtocol.Sar.CONFIG_TYPE_MPS
        const val CONFIG_TYPE_TX_WIN = GlassesProtocol.Sar.CONFIG_TYPE_TX_WIN
    }
}
