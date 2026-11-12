package com.miaiglasses.transport

import com.miaiglasses.core.config.GlassesOptions
import com.miaiglasses.core.sar.Clock
import com.miaiglasses.core.sar.SarConfig
import com.miaiglasses.core.session.ByteTransport
import com.miaiglasses.core.session.ErrorCode
import com.miaiglasses.core.session.GlassesEvent
import com.miaiglasses.core.session.SessionEngine
import com.miaiglasses.core.session.SessionState
import com.miaiglasses.core.voice.VoiceCodec
import com.miaiglasses.core.voice.VoiceIds
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 传输层编排：建连 / 断线重连 / 线程生命周期。
 *
 * 线程拓扑（实测）：
 *
 * ```text
 *  supervisor 线程  ──建连、退避、重连；不碰协议状态
 *  RX 线程          ──阻塞 read → EnginePump.submitRx（只入队）
 *  引擎线程         ──SessionEngine 独占：解帧、SAR、写 socket、定时器
 * ```
 *
 * **RX 必须独立**：`BluetoothSocket.write()` 在对端不读时会阻塞；若读写同线程，
 * 对端等我们读、我们等对端读 ⇒ 永久死锁。
 *
 * 事件回调在**产生事件的那个线程**上调用（引擎线程或 supervisor），
 * 主线程派发是上层（`:facade`）的职责 —— 传输层不认识 `Looper`。
 */
class SessionDriver(
    private val newLink: () -> SppLink,
    token16: ByteArray,
    model: String,
    locale: String,
    private val emit: (GlassesEvent) -> Unit,
    private val clock: Clock = UptimeClock,
    config: SarConfig = SarConfig.VALID,
    private val tickMs: Long = GlassesOptions.TICK_MS,
    private val connectTimeoutMs: Int = GlassesOptions.CONNECT_TIMEOUT_MS,
    private val maxConnectAttempts: Int = GlassesOptions.MAX_CONNECT_ATTEMPTS,
    private val backoff: Backoff = Backoff(),
    /** 传输不可用（蓝牙关/未配对）时的**挂起轮询**间隔：不计数、不超时，只等恢复。 */
    private val suspendPollMs: Long = 500,
    /**
     * 跨层同步调用（回执 / 屏障）的等待上限。
     *
     * 超时不当异常处理成「引擎卡住」：关掉链路把它从阻塞写里逼出来，再走重试。
     * 所以这个值要短于 [connectTimeoutMs]，否则一次卡写会连带拖长整轮重连。
     */
    private val syncWaitMs: Long = 1_000,
    diagnostics: Boolean = false,
    private val threadPrefix: String = "glasses",
) {
    /**
     * 关闭前发「会话结束」帧的等待上限。帧很小，正常情况下是立刻可写；
     * 卡到这么久说明链路已经堵住，直接放弃、继续关连接。
     */
    private val goodbyeWaitMs: Long = 300

    private val lastState = AtomicReference(SessionState.IDLE)

    /** 只跑建连的守护线程池：让 [connectBounded] 的等待与底层 socket 解耦。 */
    private val connectPool = Executors.newCachedThreadPool { r ->
        Thread(r, "$threadPrefix-connect").apply { isDaemon = true }
    }

    @Volatile
    private var link: SppLink? = null

    /** 上一次给 [GlassesEvent.Audio] 盖 `droppedBefore` 时看到的 RX 丢弃计数。 */
    private val rxGap = RxGapTracker()

    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 收尾线程句柄，供 [closeAndWait] 等待。 */
    private val closeThreadRef = AtomicReference<Thread?>(null)

    private var reader: Thread? = null
    private var supervisor: Thread? = null

    private val transport = object : ByteTransport {
        override fun write(bytes: ByteArray) {
            // 状态机线程同步写；链路换了/断了都从异常走 SEND_FAILED 路径
            val current = link ?: throw IOException("链路未连接")
            current.write(bytes)
        }

        override fun close() {
            link?.close()
        }
    }

    private val engine = SessionEngine(
        transport = transport,
        clock = clock,
        token16 = token16,
        model = model,
        locale = locale,
        config = config,
        diagnostics = diagnostics,
        emit = ::dispatch,
    )

    // internal 而非 private：测试要能把自己 post 到引擎线程上，验证「引擎线程禁止阻塞查询」
    internal val pump = EnginePump(
        onBytes = { engine.feed(it) },
        onTick = { engine.tick() },
        onFailure = { onEngineFailure(it) },
        name = "$threadPrefix-engine",
        tickMs = tickMs,
        clock = clock,
    )

    val state: SessionState get() = lastState.get()

    /** 非阻塞：立即返回，建连与握手在后台线程推进。 */
    fun start() {
        if (supervisor != null || stopping.get()) return
        pump.start()
        supervisor = Thread({ supervise() }, "$threadPrefix-supervisor").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * 同步发送：返回 `ErrorCode.NONE` 表示 **SAR 已受理**（帧进了发送车道），
     * 其它值是这一帧没发出去的确切原因。
     *
     * 早先这里是 `pump.post`（`() -> Unit`），引擎的返回值被丢掉，于是调用方只拿到
     * 「任务已入队」——车道满 / 超 MPS 这类**当场可知**的失败被报成了成功。
     */
    fun sendWear(type: Int, id: Int, body: ByteArray): Int {
        if (lastState.get() != SessionState.READY) return ErrorCode.NOT_READY
        val code = pump.call(syncWaitMs) { engine.sendWearPacket(type, id, body) }
        return code ?: if (pump.isRunning) ErrorCode.TRANSPORT else ErrorCode.CLOSED
    }

    /**
     * 录音开关：`Media`(18) `id=20 RequestRecord`。
     *
     * 走 `pump.call` 保证写仍在引擎线程；**不等回执** —— 回执（`Media.f18 RecordResponse`）
     * 走查询接口，见 facade 的 `startRecording` / `stopRecording`。
     *
     * @return false 表示未就绪
     */
    fun requestRecord(type: Int, action: Int): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(syncWaitMs) { engine.requestRecord(type, action) } == true
    }

    /** `Media id=17`：按 identifier 要一个文件（参考实现写死 `thumbnail = true`）。 */
    fun requestMediaFile(identifier: String, thumbnail: Boolean = true): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(syncWaitMs) { engine.requestMediaFile(identifier, thumbnail) } == true
    }

    /** `Media id=18`：一次要多个文件。 */
    fun requestMediaFileList(identifiers: List<String>, thumbnail: Boolean = true): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(syncWaitMs) { engine.requestMediaFileList(identifiers, thumbnail) } == true
    }

    /** `Media id=19`：确认已收到某个文件。 */
    fun confirmMediaFile(identifier: String): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(syncWaitMs) { engine.confirmMediaFile(identifier) } == true
    }

    /** `System id=80 ReportData`：触发一次基础状态上报（回包走 `GlassesEvent.ReportDataReceived`）。 */
    fun requestReportData(): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(syncWaitMs) { engine.requestReportData() } == true
    }

    /**
     * 打开眼镜热点（`System id=88`，空载荷）。
     *
     *  的前置：热点默认是关的，不开就没有 AP 可连、也没有 HTTP 8080。
     */
    fun enableWifiAp(): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(syncWaitMs) { engine.enableWifiAp() } == true
    }

    /** 关闭眼镜热点（`System id=89`，载荷 `f57{ssid,password,gateway}`）。 */
    fun disableWifiAp(ssid: String, password: String, gateway: String): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(syncWaitMs) { engine.disableWifiAp(ssid, password, gateway) } == true
    }

    /** 写 WiFi 配置（`System id=56`）：让**眼镜自己连上**指定 WiFi（STA），不是改热点凭据。 */
    fun setWifiConfig(ssid: String, password: String, authMode: Int, frequency: Int): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(syncWaitMs) { engine.setWifiConfig(ssid, password, authMode, frequency) } == true
    }

    /**
     * 让眼镜作为 P2P client 加入手机自建的 Wi-Fi Direct 组（`System id=99`）。
     *
     * [ssid]/[password]/[frequency] 必须是**手机侧** `WifiP2pManager.createGroup()` 用的那一组；
     * 结果以 `GlassesEvent.WifiP2pChanged` 回来（含眼镜的 P2P IP）。
     */
    fun enableWifiP2pGc(ssid: String, password: String, frequency: Int, taskId: Int = 0): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(syncWaitMs) { engine.enableWifiP2pGc(ssid, password, frequency, taskId) } == true
    }

    /**
     * 结束语音会话：`Voice`(14) `SYNC_VOICE_STATUS`(5)，载荷 `Voice{f5{f1=0}}`。
     *
     * 走 `pump.call` 保证写仍在引擎线程；**不等待回执** —— 这条命令的回执从未观测到。
     *
     * @return false 表示未就绪（此时什么都不发）
     */
    fun endVoiceSession(): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(goodbyeWaitMs) {
            engine.sendWearPacket(VoiceIds.TYPE, VoiceIds.ID_SYNC_STATUS, VoiceCodec.endVoicePayload())
        } == ErrorCode.NONE
    }

    /**
     * **异步**关闭：立即把状态推到 `CLOSING` 并返回，真正的收尾（发结束帧、断链路、
     * 停引擎线程、join 监督线程）在后台线程上做。
     *
     * 早先这里写成「幂等、非阻塞」但实际同步 join 了 4.3 s（300 + 1000 + 2000 + 1000），
     * 主线程 `onDestroy` 调用就是一次 ANR 赌局。现在名副其实：**本方法不做任何等待**。
     *
     * 需要「关闭真的做完」再继续（测试、进程退出前）用 [closeAndWait]。
     *
     * 代价：返回后**可能还有极短暂的回调**（收尾线程还没跑到停回调那一步）。
     * 要「返回即静默」用 [closeAndWait]。
     */
    fun close() {
        if (!stopping.compareAndSet(false, true)) return
        lastState.set(SessionState.CLOSING)
        val t = Thread({ teardown() }, "$threadPrefix-close")
        t.isDaemon = true
        closeThreadRef.set(t) // 必须在 start 之前，否则 closeAndWait 可能读到 null 直接返回
        t.start()
    }

    /**
     * **同步**关闭：做完 [close] 的全部收尾再返回，最坏约 4.3 s。
     *
     * 只在「关闭后必须立刻安全地复用资源」（测试、进程退出、同进程重开会话）时用；
     * UI 线程不要调。
     */
    fun closeAndWait() {
        close()
        closeThreadRef.get()?.join(5_000)
    }

    private fun teardown() {
        // 优雅退出：先告诉眼镜语音会话结束，再断链路。失败（未就绪/已断）不影响关闭。
        runCatching { endVoiceSession() }
        link?.close() // 打断阻塞读；read() 只能靠关闭 socket 停下
        reader?.join(1_000)
        pump.stop(2_000) { engine.close() }
        supervisor?.join(1_000)
        connectPool.shutdownNow()
        // 不清空 closeThreadRef：留着让并发的 closeAndWait 总能 join 到（已结束的线程 join 立即返回）
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 带硬超时地建连。
     *
     * 为什么不能直接 `candidate.connect(timeoutMs)`：真机上 `BluetoothSocket.connect()`
     * **可以被无限期阻塞**，而 `close()` 并不总能把它打断（2026-11-10 实测：关蓝牙再开，
     * 第 5 次尝试卡死，supervisor 线程跟着卡死 —— 既不重试、也不报终态，
     * 宿主永远停在 `CONNECTING`）。
     *
     * 所以把建连丢到单独线程，用 `Future.get` 卡死上限：**监督线程必须永远能往下走**，
     * 哪怕底层 socket 已经不可救药。
     */
    private fun connectBounded(link: SppLink) {
        val budget = connectTimeoutMs.coerceAtLeast(1) + 2_000L
        val task = connectPool.submit { link.connect(connectTimeoutMs) }
        try {
            task.get(budget, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            // 关掉 socket 尽力打断底层阻塞；即使打断不了，本线程也不再等它
            runCatching { link.close() }
            throw IOException("建连超时 ${budget}ms（底层 connect 未在期限内返回）", e)
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: IOException("建连失败", e)
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("建连被中断", e)
        }
    }

    /**
     * 发一条查询并等回包（**阻塞**）。
     *
     * 发送走 [connectPool]→`pump.call`，保证写 socket 仍发生在引擎线程；
     * 等待发生在调用方线程，所以**不要在主线程调用**（会 ANR）。
     *
     * @param responseId 回包 id，默认与请求 id 相同。`Media 15` 那种「请求 15、回 16」
     *   的必须显式指定。
     */
    fun request(
        type: Int,
        id: Int,
        body: ByteArray,
        timeoutMs: Long = 3_000,
        responseId: Int = id,
    ): ByteArray? {
        // 在引擎线程上做阻塞等待必然拿不到回包（回包也要靠这个线程解析），
        // 而且会把引擎卡住一整个 timeout —— 直接拒绝，别让人白等 3 秒。
        // 先于 READY 判定：这是编程错误，不该被「未就绪→null」掩盖。
        check(!pump.isOnEngineThread) {
            "不要在引擎线程（CallingThread 回调）上做阻塞查询：回包要由同一个线程解析，必然超时"
        }
        if (lastState.get() != SessionState.READY) return null
        val q = engine.beginWait(type, responseId)
        return try {
            val code = pump.call(syncWaitMs) { engine.sendWearPacket(type, id, body) }
            val sent = code == ErrorCode.NONE
            if (!sent) null else q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            engine.endWait(type, responseId)
        }
    }

    /**
     * 是否已具备拍照条件（收到过 `Voice id=10`）。
     *
     * 只是「现在能不能发」的快照；真正发送时引擎会再判断一次。
     */
    val canCapturePhoto: Boolean get() = engine.canCapturePhoto

    /**
     * 触发拍照。发送走 `pump.call`，保证写 socket 仍发生在引擎线程。
     *
     * 不需要 `Voice id=10` 上下文：没有时引擎自造 ID（真机实测设备不校验，见 `实测`）。
     *
     * @return false 表示未就绪
     */
    fun capturePhoto(): Boolean {
        if (lastState.get() != SessionState.READY) return false
        val salt = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
        return pump.call(syncWaitMs) { engine.capturePhoto(salt) } == true
    }

    /**
     * 触发拍照，ID 由调用方自造（实验用：验证设备是否校验 `Voice id=10` 下发的 ID）。
     *
     * @return false 表示未就绪
     */
    fun capturePhotoRaw(transactionId: String, requestId: ByteArray, salt: ByteArray): Boolean {
        if (lastState.get() != SessionState.READY) return false
        return pump.call(syncWaitMs) { engine.capturePhotoRaw(transactionId, requestId, salt) } == true
    }

    private fun dispatch(event: GlassesEvent) {
        if (event is GlassesEvent.StateChanged) lastState.set(event.state)
        emit(if (event is GlassesEvent.Audio) stamp(event) else event)
    }

    /**
     * 给语音块盖上「这块之前被 RX 队列丢了多少块」。
     *
     * RX 队列在 [EnginePump] 里，引擎看不到它，所以这个字段只能在这一层补：
     * 溢出丢的是**整块字节**，宿主如果不被告知，听起来就是「随机吞字」，
     * 而且没有任何办法自证是链路问题还是设备没说话。
     */
    private fun stamp(audio: GlassesEvent.Audio): GlassesEvent.Audio {
        val gap = rxGap.onChunk(pump.droppedRxChunks)
        return if (gap == audio.droppedBefore) audio
        else GlassesEvent.Audio(audio.data, audio.seq, audio.timestampMs, gap)
    }

    /** 因 RX 队列满被丢掉的字节数（宿主可用它判断「音频空洞是不是本端造成的」）。 */
    val rxDroppedBytes: Long get() = pump.droppedRxBytes

    /** 因 RX 队列满被丢掉的块数。 */
    val rxDroppedChunks: Long get() = pump.droppedRxChunks

    /** 因世代不符（上一轮 socket 的残留）被丢弃的块数，不计入音频空洞。 */
    val rxStaleChunks: Long get() = pump.staleRxChunks

    private fun supervise() {
        var reachedReadyBefore = false
        var failures = 0
        try {
            while (!stopping.get()) {
                val candidate = newLink()

                // ★ 门禁：传输不可用 ⇒ 挂起。**不计数、不设超时、不投递建连**。
                // 这一条正是真机 bug 的根因：蓝牙关着时 createRfcommSocket 立刻抛，
                // 4 次无意义失败把重试预算烧光，恢复后再也没有尝试机会。
                if (!candidate.isAvailable()) {
                    val why = candidate.unavailableReason?.let { "：$it" } ?: ""
                    candidate.close()
                    pump.post { engine.onDisconnected("传输未就绪（蓝牙关闭或未配对）$why，等待恢复") }
                    if (!pause(suspendPollMs)) return
                    continue
                }

                pump.post { engine.onConnecting() }

                val connectError = try {
                    connectBounded(candidate)
                    null
                } catch (e: Exception) {
                    candidate.close()
                    e
                }

                if (connectError != null) {
                    pump.post { engine.onConnectFailed(connectError.message ?: "未知原因") }
                    failures++
                    if (failures >= maxConnectAttempts) {
                        pump.post { engine.onGiveUp("连续 $failures 次连接失败，已停止尝试") }
                        return
                    }
                    if (!pause(backoff.nextDelayMs())) return
                    continue
                }

                // ★ 回执：引擎是否**真的**受理了这次「socket 就绪」。
                // 以前这里是 `post`（投递出去就当成功），被调方静默 return 时监督线程
                // 会照旧进读循环干等 —— 真机因此永久停在 CONNECTING。
                //
                // `link` 必须先于这次调用赋值：引擎在 `onConnected()` 里就会立刻写出
                // 第一个协商帧，晚一步就是「链路未连接」。
                link = candidate
                val accepted = pump.call(syncWaitMs) {
                    if (reachedReadyBefore) engine.onReconnected() else engine.onConnected()
                }
                if (accepted != true) {
                    link = null
                    candidate.close()
                    if (stopping.get()) return
                    failures++
                    if (failures >= maxConnectAttempts) {
                        pump.post { engine.onGiveUp("引擎未受理建连（可能已停止），已放弃") }
                        return
                    }
                    if (!pause(backoff.nextDelayMs())) return
                    continue
                }

                backoff.reset()
                val epoch = pump.beginRxEpoch()
                rxGap.reset()   // 新链路：上一轮的 RX 丢弃不该算进这条链路的音频空洞
                startReader(candidate, epoch)

                reader?.join()
                reader = null
                link = null
                candidate.close()

                if (stopping.get()) return

                // ★ 屏障后再读引擎状态：保证本轮投递的 onConnected/onReconnected 已被
                // 引擎线程处理。`lastState.get()` 是跨线程的滞后快照，读它等于猜。
                val stateAtDrop = pump.call(syncWaitMs) { engine.state } ?: lastState.get()

                // 链路断了。握手判死（token 错）是终态，继续重连只会拿同一个错凭据反复撞墙。
                reachedReadyBefore = reachedReadyBefore || stateAtDrop == SessionState.READY
                if (stateAtDrop == SessionState.FAILED) return

                pump.post { engine.onDisconnected("链路中断") }
                if (stateAtDrop != SessionState.READY) failures++ else failures = 0
                if (failures >= maxConnectAttempts) {
                    pump.post { engine.onGiveUp("连续 $failures 次连接尝试未成功，已停止尝试") }
                    return
                }
                if (!pause(backoff.nextDelayMs())) return
            }
        } finally {
            ensureTerminal()
        }
    }

    /**
     * 监督线程退出前**必须**留下终态。
     *
     * 少了这一步，任何一条没写好 `onGiveUp` 的退出路径都会让宿主永远停在
     * `CONNECTING`/`RECONNECTING` —— 和最初那个真机 bug 的表现一模一样，只是没日志。
     */
    private fun ensureTerminal() {
        if (stopping.get()) return
        val st = pump.call(syncWaitMs) { engine.state } ?: lastState.get()
        if (st == SessionState.CLOSED || st == SessionState.CLOSING || st == SessionState.FAILED) return
        pump.post { engine.onGiveUp("监督线程已退出，会话未建立（$st）") }
    }

    private fun startReader(l: SppLink, epoch: Long) {
        reader = Thread({
            val buf = ByteArray(4_096)
            try {
                while (!stopping.get()) {
                    val n = l.read(buf, 0, buf.size)
                    if (n < 0) break
                    // 副本：RX 复用同一缓冲区；带世代号：换 socket 后的残留会被引擎侧丢弃
                    if (n > 0) pump.submitRx(buf.copyOf(n), epoch)
                }
            } catch (e: Exception) {
                if (!stopping.get()) {
                    val why = l.closeReason?.let { "（$it）" } ?: ""
                    dispatch(GlassesEvent.Error(ErrorCode.TRANSPORT, "读取失败: ${e.message}$why"))
                }
            }
        }, "$threadPrefix-rx").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun onEngineFailure(e: Throwable) {
        dispatch(GlassesEvent.Error(ErrorCode.TRANSPORT, "引擎异常: ${e.message}", e))
        // 引擎线程已不可信：关掉链路让 supervisor 重连
        link?.close()
    }

    /** 可被打断的等待。返回 false 表示正在关闭。 */
    private fun pause(ms: Long): Boolean {
        val until = System.nanoTime() + ms * 1_000_000
        while (!stopping.get()) {
            val left = (until - System.nanoTime()) / 1_000_000
            if (left <= 0) return true
            try {
                Thread.sleep(left.coerceAtMost(100))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }
}
