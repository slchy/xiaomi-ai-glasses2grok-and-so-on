package io.github.slchy.glasses2grok.core.session

import io.github.slchy.glasses2grok.core.config.GlassesProtocol
import io.github.slchy.glasses2grok.core.control.ControlRouter
import io.github.slchy.glasses2grok.core.crypto.SessionKeys
import io.github.slchy.glasses2grok.core.handshake.HandshakeDriver
import io.github.slchy.glasses2grok.core.l1.L1
import io.github.slchy.glasses2grok.core.l1.L1Frame
import io.github.slchy.glasses2grok.core.l2.L2
import io.github.slchy.glasses2grok.core.l2.L2Codec
import io.github.slchy.glasses2grok.core.live.LiveStreaming
import io.github.slchy.glasses2grok.core.mass.MassAssembler
import io.github.slchy.glasses2grok.core.mass.MassCodec
import io.github.slchy.glasses2grok.core.sar.Clock
import io.github.slchy.glasses2grok.core.sar.Lane
import io.github.slchy.glasses2grok.core.sar.SarResult
import io.github.slchy.glasses2grok.core.sar.SarConfig
import io.github.slchy.glasses2grok.core.sar.SarEngine
import io.github.slchy.glasses2grok.core.wear.WearCodec
import io.github.slchy.glasses2grok.core.wear.WearPacket
import io.github.slchy.glasses2grok.core.wifi.WifiApCodec
import io.github.slchy.glasses2grok.core.wifi.WifiApIds

/**
 * 会话编排：把 L1/SAR/L2/MASS/握手/控制面串成一条会话。
 *
 * **不建线程、不做 I/O、不加锁** —— 由宿主（`:transport`）在**单个引擎线程**上
 * 依次调用 [feed] / [tick] / [send]。这与参考实现同构
 * （见 `参考协议`）：状态机单线程 ⇒ 全程无锁。
 *
 * 时间由 [Clock] 注入；测试可以把 10 秒超时瞬间推过去。
 */
public class SessionEngine(
    private val transport: ByteTransport,
    private val clock: Clock,
    private val token16: ByteArray,
    private val model: String,
    private val locale: String,
    private val config: SarConfig = SarConfig.VALID,
    private val diagnostics: Boolean = false,
    private val random: (Int) -> ByteArray = { io.github.slchy.glasses2grok.core.crypto.Crypto.randomBytes(it) },
    private val emit: (GlassesEvent) -> Unit,
) {
    public var state: SessionState = SessionState.IDLE
        private set

    private val sar = SarEngine(
        sink = { bytes ->
            try {
                transport.write(bytes)
            } catch (e: Exception) {
                // 写失败必须冒泡成传输错误：吞掉会让 SAR 以为发出去了
                throw TransportWriteException(e.message ?: "write failed", e)
            }
        },
        clock = clock,
        config = config,
    )

    private val assembler = MassAssembler()

    /** 本会话语音块序号（MASS 头无 seq，这是本端计数，见 [GlassesEvent.Audio]）。 */
    private var voiceSeq: Long = 0

    /** `L2 ch=2` 上的文件分片（`Media 17/18` 的响应）。 */
    private val massFileAssembler = io.github.slchy.glasses2grok.core.mass.MassFileAssembler()
    private val handshake: HandshakeDriver

    internal var keys: SessionKeys? = null
        private set

    /** 诊断：握手与 SAR 的当前内部状态（不打日志，仅供测试与调试观察）。 */
    internal val handshakeState: HandshakeDriver.State get() = handshake.state
    internal val sarSummary: String
        get() = "exchanged=${sar.isCmdExchanged} pending=${sar.pending} mps=${sar.remoteMps} win=${sar.remoteTxWindow} lanes=[${sar.laneSummary}]"
    internal val handshakeFailure: HandshakeDriver.Failure? get() = handshake.failure

    // ---- 链路计数：宿主仪表盘用。都是原子性无要求的 Long 自增，读侧允许略滞后。
    /** 已交付的 DATA 帧数。 */
    public val rxFrameCount: Long get() = sar.rxFrameCount
    /** 本端发出的 NAK 数。持续增长 = 对端收不全/乱序。 */
    public val nakSentCount: Long get() = sar.nakSentCount
    /** 重传次数。持续增长 = 丢包严重。 */
    public val retransCount: Long get() = sar.retransCount
    /** 已发出（进链路）的 DATA 帧数。 */
    public val txFrameCount: Long get() = sar.sentCount

    init {
        handshake = HandshakeDriver(
            token16 = token16,
            model = model,
            locale = locale,
            sendCmd = { cmd, body -> sar.sendCmd(cmd, body) },
            sendWear = { type, id, body, encrypted -> sendWear(type, id, body, encrypted) },
            now = { clock.nowMs() },
            random = random,
            negotiateTimeoutMs = config.sendTimeoutMs,
        )

        sar.onDeliver = { l2 -> handleL2(l2) }
        sar.onFrame = { f -> if (diagnostics) emit(GlassesEvent.Diagnostic("rx ${f.typeName} seq=${f.seq} len=${f.payload.size}")) }
        sar.onError = { message -> reportError(message) }
        sar.onSinkFailure = { e -> onTransportFailure(e) }
        //  分车道背压：某条车道配额满时该帧**不入队**，用类型化错误告诉宿主去重试/降速
        sar.onQueueFull = { lane ->
            emit(
                GlassesEvent.Error(
                    ErrorCode.QUEUE_FULL,
                    "发送车道 ${lane.label} 已满（${lane.capacity}），本帧未入队",
                ),
            )
        }

        assembler.onVoiceChunk = { chunk ->
            // 序号与时间戳只能本端生成：MASS 头里两者都没有（见 `Mass` 的 12 字节头）。
            // 宿主的 ASR/AEC 需要它们判断「这串音频是不是连续的」。
            emit(GlassesEvent.Audio(chunk, voiceSeq++, clock.nowMs()))
        }
    }

    /**
     * 传输已就绪（socket 连上）。开始握手。
     *
     * @return 是否**受理**。`false` 只表示会话已在终态（`CLOSED`/`CLOSING`），
     *   调用方（`SessionDriver`）必须据此放弃这条链路，而不是等一个永不到来的握手。
     */
    public fun onConnected(): Boolean = beginHandshake("socket 已连接")

    /** 传输层开始建连（socket 尚未就绪）。由 `:transport` 在建连前调用。 */
    public fun onConnecting() {
        if (state == SessionState.IDLE || state == SessionState.RECONNECTING ||
            state == SessionState.FAILED
        ) {
            setState(SessionState.CONNECTING, "正在连接")
        }
    }

    /**
     * 建连失败（会按退避重试，**不是**终态）。只发错误事件，状态保持 `CONNECTING`。
     */
    public fun onConnectFailed(message: String) {
        emit(GlassesEvent.Error(ErrorCode.TRANSPORT, "连接失败: $message"))
    }

    /**
     * 放弃重试 ⇒ `FAILED`。
     *
     * 只用于「重试策略判定不该再试」的场景（次数耗尽）；token 无效由握手自己转 `FAILED`。
     */
    public fun onGiveUp(message: String) {
        if (state == SessionState.CLOSED || state == SessionState.CLOSING) return
        emit(GlassesEvent.Error(ErrorCode.TRANSPORT, message))
        sar.reset()
        handshake.reset()
        dropKeys()
        setState(SessionState.FAILED, message)
    }

    /**
     * 喂入收到的原始字节。
     *
     * @return 本次解析出的 L1 帧数
     */
    public fun feed(data: ByteArray): Int {
        if (state == SessionState.CLOSED) return 0
        val n = try {
            sar.feed(data)
        } catch (e: TransportWriteException) {
            onTransportFailure(e)
            return 0
        }
        advanceHandshake()
        return n
    }

    /**
     * 驱动两套定时器。**必须由同一个引擎线程周期调用**（建议 50 ms）。
     *
     * 顺序：先握手超时（可能已失败），再 SAR 超时/累积 ACK。反过来会让一次
     * 已经超时的握手仍去发数据帧。
     */
    public fun tick() {
        if (state == SessionState.CLOSED) return
        try {
            handshake.tick()
            advanceHandshake()
            sar.tick()
            assembler.tick(clock.nowMs())   // 图片流空闲超时（对齐参考实现 15 s 定时器）
            if (diagnostics) reportStall()
        } catch (e: TransportWriteException) {
            onTransportFailure(e)
        }
    }

    /** 解析停滞的诊断上报（仅在 `diagnostics = true` 时）。 */
    private fun reportStall() {
        val s = sar.stallStats
        if (s.currentMs > 0) {
            // 按 500ms → 2s → 8s 的间隔上报，避免每 50ms 一条把日志刷爆
            if (s.currentMs >= nextStallReportMs) {
                emit(
                    GlassesEvent.Diagnostic(
                        "解析停滞 ${s.currentMs}ms pending=${s.maxPendingBytes}B",
                    ),
                )
                nextStallReportMs = if (nextStallReportMs == 0L) 500L else nextStallReportMs * 4
            }
            return
        }
        nextStallReportMs = 0L
        if (s.maxMs > lastReportedStallMaxMs) {
            lastReportedStallMaxMs = s.maxMs
            emit(
                GlassesEvent.Diagnostic(
                    "停滞峰值 ${s.maxMs}ms（共 ${s.occurrences} 次，单次最多吃掉 " +
                        "${s.maxStalledFeedBytes}B，残留峰值 ${s.maxPendingBytes}B）",
                ),
            )
        }
    }

    private var lastReportedStallMaxMs = 0L
    private var nextStallReportMs = 0L

    /**
     * 发一条业务命令（自动加密）。
     *
     * @return [ErrorCode.NONE] 表示**已受理**（帧进了发送车道）；其它值说明这一帧根本没发出去，
     *   调用方**不必**等异步 `Error` 事件才知道失败。
     *
     * 早先这里是 `Boolean`，而 `:transport` 用 `pump.post` 投递（`() -> Unit`），返回值被丢掉，
     * 于是「车道满 / 超过远端 MPS / 引擎已停」全部对外表现为成功 —— 公开 API 的
     * `SendResult.Queued` 因此名不副实。现在把原因一路带上去。
     */
    public fun sendWearPacket(type: Int, id: Int, body: ByteArray): Int {
        if (state != SessionState.READY) {
            emit(GlassesEvent.Error(ErrorCode.NOT_READY, "未就绪（当前 ${state.name}），请等待 READY 后重试"))
            return ErrorCode.NOT_READY
        }
        if (keys == null) {
            // READY 但拿不到密钥属于内部不一致，按业务错误上报，不能当成链路故障
            emit(GlassesEvent.Error(ErrorCode.PROTOCOL, "会话密钥缺失，请重新连接"))
            return ErrorCode.PROTOCOL
        }
        return try {
            if (sendWear(type, id, body, encrypted = true)) ErrorCode.NONE else sendRejectCode()
        } catch (e: TransportWriteException) {
            onTransportFailure(e)
            ErrorCode.TRANSPORT
        }
    }

    /**
     * [sendWear] 返回 false 时读 SAR 的拒绝原因。
     *
     * 必须**紧跟着** `sendWear` 在同一次调用里读（这里是引擎线程），否则下一次发送
     * 会把 [SarEngine.lastRejectCode] 覆盖掉。
     */
    private fun sendRejectCode(): Int = when (sar.lastRejectCode) {
        SarResult.QUEUE_FULL -> ErrorCode.QUEUE_FULL
        else -> ErrorCode.PROTOCOL          // 空载荷 / 超过远端 MPS，都不该悄悄成功
    }

    /** 读佩戴状态：`System`(2) `GetWearStatus`(93)，空 payload。 */
    public fun requestWearStatus(): Boolean =
        sendWearPacket(TYPE_SYSTEM, ID_GET_WEAR_STATUS, ByteArray(0)) == ErrorCode.NONE

    /**
     * **请求**眼镜进入语音态：`Voice`(14) id=10 `WakeUpEvent`。
     *
     * 字段号、cmd、requestId 全部对齐参考实现（见
     * {@link io.github.slchy.glasses2grok.core.voice.VoiceCodec.buildWakeUp} 的 KDoc）。
     * 参考实现触发链：AI SDK 发 `VoiceEventName.START_LISTENING` →
     * `GlassesDeviceProxy.handleVoiceEvent` → `IMiWearModuleApi.wakeupDevice()` → 本帧。
     *
     * **真机闭环（2026-11-14，`实测`）**：发出后 **0.62 s** 内眼镜开始推实时
     * mSBC，全程无上行 `OpenDevice`；实测连续推流 ≥29.5 s，落盘解码得 14.69 s 真实环境声。
     * 也就是说**手机侧可以主动开麦**，不必等用户长按或唤醒词。
     *
     * 首版（同一天）按 `Voice.f1` 发、requestId 留空，设备 ACK 后**静默忽略** ——
     * 字段 1 是 `INSTRUCTION_LIST`，整帧从 74 B 缩到 42 B。
     *
     * @param transactionId 会话事务 id，建议 32 字符小写 hex
     * @param requestId 默认与 `transactionId` 相同（参考实现行为）
     * @returns 是否排进发送队列；`Queued` + 0.6 s 后出现音频帧才算成功
     */
    public fun requestWakeUp(transactionId: String, requestId: ByteArray? = null): Boolean =
        sendWearPacket(
            io.github.slchy.glasses2grok.core.voice.VoiceIds.TYPE,
            GlassesProtocol.Voice.ID_WAKE,
            io.github.slchy.glasses2grok.core.voice.VoiceCodec.buildWakeUp(transactionId, requestId),
        ) == ErrorCode.NONE

    /**
     * 基础状态上报：`System id=80 ReportData`，载荷是**空的** `System{}`（参考实现同款）。
     *
     * 回包 `System.f51 = ReportData.Result{type, status}`；参考实现每 1 小时自动发一次
     * 。
     */
    public fun requestReportData(): Boolean =
        sendWearPacket(TYPE_SYSTEM, io.github.slchy.glasses2grok.core.query.ReportDataIds.ID_REQUEST, ByteArray(0)) == ErrorCode.NONE

    /**
     * 打开眼镜热点：`System id=88 EnableWiFiAP`，payload 是**空的** `System`。
     *
     * 参考实现就是这么发的；热点起来后
     * `wifiConfig()`（`id=86`）给的 SSID/PSK 才能用，HTTP 8080 也才可达。
     */
    public fun enableWifiAp(): Boolean =
        sendWearPacket(TYPE_SYSTEM, WifiApIds.ID_ENABLE, ByteArray(0)) == ErrorCode.NONE

    /**
     * 关闭眼镜热点：`System id=89 DisableWiFiAP`，payload `f57 WiFiAP{ssid, password, gateway}`。
     */
    public fun disableWifiAp(ssid: String, password: String, gateway: String): Boolean =
        sendWearPacket(TYPE_SYSTEM, WifiApIds.ID_DISABLE, WifiApCodec.disablePayload(ssid, password, gateway)) ==
            ErrorCode.NONE

    /**
     * 让**眼镜作为 P2P Group Client** 加入一个由手机自己建的 Wi-Fi Direct 组：     * `System id=99 EnableWiFiP2PGC`，载荷 `f65 WiFiP2P{ssid, password, frequency, taskId}`。
     *
     * 参考实现「无感」路径：手机 `WifiP2pManager.createGroup()` 当 GO（无系统弹窗），
     * 再把组的 SSID/密码/频率发给眼镜；眼镜回 `f66 WiFiP2P.Result{code, ipAddress}`，
     * 之后直接用 `http://<ipAddress>:8080/v1/...` 取文件（实测）。
     */
    public fun enableWifiP2pGc(
        ssid: String,
        password: String,
        frequency: Int = io.github.slchy.glasses2grok.core.wifi.WifiP2pIds.DEFAULT_FREQUENCY_MHZ,
        taskId: Int = 0,
    ): Boolean =
        sendWearPacket(
            TYPE_SYSTEM,
            io.github.slchy.glasses2grok.core.wifi.WifiP2pIds.ID_ENABLE_P2P_GC,
            io.github.slchy.glasses2grok.core.wifi.WifiP2pCodec.enablePayload(ssid, password, frequency, taskId),
        ) == ErrorCode.NONE

    /**
     * 写 WiFi 配置：`System id=56 SetWiFIConfig`（`f32 WiFiConfig{basicInfo, pskInfo}`）。
     *
     * 语义是让**眼镜自己连上指定 WiFi（STA）**，不是「改眼镜热点凭据」——
     * 参考实现调用方是 `WLAN 设置页` 与直播页
     * `直播页的两个 ViewModel`（实测）。
     * 早期把它当成「钉死热点密码」用过，`id=88` 回包不变属正常，不能拿来证伪。
     */
    public fun setWifiConfig(ssid: String, password: String, authMode: Int, frequency: Int): Boolean =
        sendWearPacket(
            TYPE_SYSTEM,
            WifiApCodec.ID_SET_WIFI_CONFIG,
            WifiApCodec.setConfigPayload(ssid, password, authMode, frequency),
        ) == ErrorCode.NONE

    // ------------------------------------------------------------ 拍照

    /**
     * 最近一次 `Voice id=10 OpenDevice` 的两个 ID。
     *
     * 拍照**必须原样复用**它们：设备会无视自己生成的值（实测）。
     * 链路断开时清空 —— 换一轮会话后旧 ID 已失效。
     */
    @Volatile
    private var lastOpenDevice: io.github.slchy.glasses2grok.core.voice.VoiceEvent.OpenDevice? = null

    /** 当前是否具备拍照条件（已收到过一次 `OpenDevice`）。跨线程读，靠上面字段的 volatile。 */
    public val canCapturePhoto: Boolean get() = lastOpenDevice != null

    /**
     * 触发拍照：`Voice id=4 multiModel`。有 `Voice id=10` 上下文时复用设备下发的两个 ID
     * （云端能把图和那次 AI 请求关联起来）；没有上下文时自造 ID —— 真机实测设备**不校验** ID 归属。
     *
     * @param salt 让 `resourceId` 唯一的随机字节（6 字节足够，client 版用 16）
     * @return false 表示状态不允许发送
     */
    public fun capturePhoto(salt: ByteArray): Boolean {
        val od = lastOpenDevice
        if (od != null) return capturePhotoRaw(od.transactionId, od.requestIdBytes, salt)
        // 没有 Voice id=10 上下文：自造 ID。真机实测设备不校验 ID 归属
        // （canCapture=false 时照样出图，见实测）
        val requestId = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val transactionId = requestId.joinToString("") { "%02x".format(it) }
        return capturePhotoRaw(transactionId, requestId, salt)
    }

    /**
     * 触发拍照，但 ID 由调用方自造（不依赖 `Voice id=10`）。
     *
     * 参考实现的两个 ID 都是**入参**，不是设备回显；这里要验证的正是
     * 「设备是否校验 ID 归属」。结论见 `实测`。
     */
    public fun capturePhotoRaw(transactionId: String, requestId: ByteArray, salt: ByteArray): Boolean =
        sendWearPacket(
            TYPE_VOICE,
            io.github.slchy.glasses2grok.core.voice.VoiceIds.ID_MULTIMODAL,
            io.github.slchy.glasses2grok.core.voice.VoiceCodec.buildMultiModel(transactionId, requestId, salt),
        ) == ErrorCode.NONE

    // ------------------------------------------------------------ 请求-响应

    /**
     * 等待某个 `(type, id)` 回包的登记表。
     *
     * 只由 `:facade` 通过 [SessionDriver.request] 使用；**键是回包的 id**，
     * 因为有的查询请求 id 与回包 id 不同（`Media 15` 请求 → `Media 16` 回包）。
     */
    private val waiters = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.LinkedBlockingQueue<ByteArray>>()

    private fun waiterKey(type: Int, id: Int): Int = type * 1000 + id

    /** 登记一个等待槽。返回的队列由调用方在有界时间内 poll。 */
    public fun beginWait(type: Int, responseId: Int): java.util.concurrent.LinkedBlockingQueue<ByteArray> {
        val q = java.util.concurrent.LinkedBlockingQueue<ByteArray>(1)
        waiters[waiterKey(type, responseId)] = q
        return q
    }

    public fun endWait(type: Int, responseId: Int) {
        waiters.remove(waiterKey(type, responseId))
    }

    // ------------------------------------------------------------ 直播

    /** 查询直播状态：`Interconnection`(23) `id=45`。回复里带眼镜热点 SSID/密码。 */
    public fun queryLiveStream(): Boolean =
        sendWearPacket(LiveStreaming.TYPE, LiveStreaming.ID_QUERY, LiveStreaming.queryPayload()) == ErrorCode.NONE

    /**
     * 开始推流：`Interconnection`(23) `id=46`。
     *
     * ⚠️ `mode = HOTSPOT` 时**眼镜会自己开热点**（`GLASSCAP`），`url` 指向的主机
     * 必须在这张热点网络里，否则固件会回 `errorCode=5 / RTMP 连接失败`。
     * `Mode.BLUETOOTH` 尚未实测。
     */
    public fun startLive(
        url: String,
        resolution: LiveStreaming.Resolution = LiveStreaming.Resolution.P720,
        fps: LiveStreaming.FrameRate = LiveStreaming.FrameRate.FPS30,
        streamMode: LiveStreaming.StreamMode = LiveStreaming.StreamMode.AUTO,
        mode: LiveStreaming.Mode = LiveStreaming.Mode.HOTSPOT,
    ): Boolean = sendWearPacket(
        LiveStreaming.TYPE,
        LiveStreaming.ID_REQUEST,
        LiveStreaming.startPayload(url, resolution, fps, streamMode, mode),
    ) == ErrorCode.NONE

    /** 停止推流：`Interconnection`(23) `id=46`，`f34 { f1 = 2 }`。 */
    public fun stopLive(): Boolean =
        sendWearPacket(LiveStreaming.TYPE, LiveStreaming.ID_REQUEST, LiveStreaming.stopPayload()) == ErrorCode.NONE

    /**
     * 录音开关：`Media`(18) `id=20 RequestRecord`，载荷 `Media{f17 = RecordRequest{type, action}}`。
     *
     * `type` 见 [io.github.slchy.glasses2grok.core.media.RecordType]，`action` 见
     * [io.github.slchy.glasses2grok.core.media.RecordAction]（0 = 开始、1 = 停止，来自参考实现
     * `开始录音` / `停止录音`）。
     */
    public fun requestRecord(type: Int, action: Int): Boolean =
        sendWearPacket(
            io.github.slchy.glasses2grok.core.media.RecordIds.TYPE,
            io.github.slchy.glasses2grok.core.media.RecordIds.ID_REQUEST_RECORD,
            io.github.slchy.glasses2grok.core.media.RecordCodec.requestPayload(type, action),
        ) == ErrorCode.NONE

    /**
     * `Media id=17 RequestMediaFile`：按 identifier 向眼镜要一个文件。
     *
     * 参考实现在这里写死 `thumbnail = true`，所以它要的是**缩略图**；想试原图就传
     * [thumbnail] = false（未见参考实现这么用过，属实验）。
     */
    public fun requestMediaFile(identifier: String, thumbnail: Boolean = true): Boolean =
        sendWearPacket(
            TYPE_MEDIA,
            io.github.slchy.glasses2grok.core.media.MediaFileIds.ID_REQUEST_FILE,
            io.github.slchy.glasses2grok.core.media.MediaFileCodec.requestFilePayload(identifier, thumbnail),
        ) == ErrorCode.NONE

    /** `Media id=18 RequestMediaFileList`：一次要多个文件（同 [requestMediaFile] 的语义）。 */
    public fun requestMediaFileList(identifiers: List<String>, thumbnail: Boolean = true): Boolean =
        sendWearPacket(
            TYPE_MEDIA,
            io.github.slchy.glasses2grok.core.media.MediaFileIds.ID_REQUEST_FILE_LIST,
            io.github.slchy.glasses2grok.core.media.MediaFileCodec.requestFileListPayload(identifiers, thumbnail),
        ) == ErrorCode.NONE

    /** `Media id=19 ConfirmMediaFile`：告诉眼镜「这个文件我收到了」。 */
    public fun confirmMediaFile(identifier: String): Boolean =
        sendWearPacket(
            TYPE_MEDIA,
            io.github.slchy.glasses2grok.core.media.MediaFileIds.ID_CONFIRM_FILE,
            io.github.slchy.glasses2grok.core.media.MediaFileCodec.confirmPayload(identifier),
        ) == ErrorCode.NONE

    public fun close() {
        if (state == SessionState.CLOSED || state == SessionState.CLOSING) return
        setState(SessionState.CLOSING, "close()")
        sar.reset()
        handshake.reset()
        dropKeys()
        try {
            transport.close()
        } catch (ignored: Exception) {
            // 关闭失败不影响状态推进
        }
        setState(SessionState.CLOSED, "closed")
    }

    /** 传输层报告连接断开（不是我们主动关闭的）。 */
    public fun onDisconnected(reason: String = "连接断开") {
        if (state == SessionState.CLOSED || state == SessionState.CLOSING) return
        sar.reset()          // ★ isCmdExchanged 一并归零 ⇒ 重连必须重做协商
        handshake.reset()    // ★ 否则下次 start() 会因为「状态不是 IDLE」直接返回
        dropKeys()
        lastOpenDevice = null // 唤醒上下文属于上一轮会话，重连后旧 ID 已失效
        setState(SessionState.RECONNECTING, reason)
    }

    /**
     * 重连成功：**必须重做 CMD 协商**，所以状态回到 HANDSHAKING。
     *
     * 不能只接受 `RECONNECTING`：`SessionDriver.supervise` 在真正建连之前会先调
     * `onConnecting()`，此时状态已经是 `CONNECTING`。前置条件太窄曾让本方法变成
     * 静默空操作 —— 握手永不开始、也没有 deadline 兜底，宿主永远停在 `CONNECTING`。
     *
     * @return 是否受理，语义同 [onConnected]。
     */
    public fun onReconnected(): Boolean = beginHandshake("重连成功，重做协商")

    /**
     * socket 就绪的共同处理 —— 两个入口只在 `reason` 上不同，语义都是「协商必须重做」。
     *
     * 这里**不**按当前状态做前置判断（除了终态），因为调用方无法感知静默拒绝；
     * 能挡住重复握手的是 [SessionState.READY] 的幂等挡。
     */
    private fun beginHandshake(reason: String): Boolean {
        if (state == SessionState.CLOSED || state == SessionState.CLOSING) return false
        // READY 上重开握手会打乱已建立的密码状态；迟到的「已连接」按成功处理。
        if (state == SessionState.READY) return true
        setState(SessionState.HANDSHAKING, reason)
        handshake.start()
        return true
    }

    // ------------------------------------------------------------------ 内部

    private fun sendWear(type: Int, id: Int, body: ByteArray, encrypted: Boolean): Boolean {
        val wear = WearCodec.build(type, id, body)
        val sent = if (encrypted) {
            val k = keys ?: throw TransportWriteException("握手未完成，无法加密", null)
            sar.sendData(L2.CH_PB, L2.OP_WRITE_ENC, k.encryptApp(wear))
        } else {
            sar.sendData(L2.CH_PB, L2.OP_WRITE, wear)
        }
        // sar.sendData 在「payload 为空」「payload+2 > MPS」「车道配额满」三种情况下返回 null，
        // 帧根本不会发出去。这个返回值必须一路上报到公开 API，否则宿主会看到 Queued。
        if (sent == null) {
            // 车道满已经由 sar.onQueueFull 报过一条 QUEUE_FULL，这里不重复报
            if (sar.lastRejectCode != SarResult.QUEUE_FULL) {
                emit(
                    GlassesEvent.Error(
                        ErrorCode.PROTOCOL,
                        "发送失败：帧未发出（载荷为空或超过远端 MPS ${sar.remoteMps}）",
                    ),
                )
            }
            return false
        }
        return true
    }

    private fun handleL2(l2Raw: ByteArray) {
        val l2 = L2Codec.parse(l2Raw) ?: return reportError("L2 头不足 2 字节，已丢弃")

        val plain: ByteArray = if (L2.isEncrypted(l2.opCode)) {
            val k = keys ?: return reportError("收到加密帧但握手未完成，已丢弃")
            try {
                k.decryptDevice(l2.body)
            } catch (e: Exception) {
                return reportError("解密失败: ${e.message}")
            }
        } else {
            l2.body
        }

        when (l2.channel) {
            L2.CH_PB -> handleControl(plain)
            L2.CH_MASS_VOICE -> handleMass(plain)
            L2.CH_MASS -> handleMassChannel(plain)
            else -> if (diagnostics) {
                emit(GlassesEvent.Diagnostic("未处理通道 ${l2.channelName} ${plain.size}B"))
            }
        }
    }

    /**
     * `L2 ch=2 MASS`：事件日志 CSV 与**缩略图分片**共用这条通道。
     *
     * 分片帧统一以 `00 83 <type> 00 <seq> 00` 开头，所以先按 [MassFileAssembler] 拼；
     * 拼不出文件的（事件日志等）只在诊断模式报个摘要。
     */
    private fun handleMassChannel(plain: ByteArray) {
        val assembled = massFileAssembler.feed(plain)
        if (assembled != null) {
            emit(GlassesEvent.MassFile(assembled))
            return
        }
        if (diagnostics) {
            val isFragment = plain.size > io.github.slchy.glasses2grok.core.mass.MassFileAssembler.HEADER &&
                (plain[1].toInt() and 0xFF) == 0x83
            emit(
                GlassesEvent.Diagnostic(
                    if (isFragment) {
                        "MASS 分片 ${plain.size}B seq=${plain[4].toInt() and 0xFF}（组装中/已丢弃）"
                    } else {
                        "MASS 其它 ${plain.size}B"
                    },
                ),
            )
        }
    }

    private fun handleControl(plain: ByteArray) {
        val p: WearPacket = WearCodec.parse(plain) ?: return reportError("WearPacket 解析失败，已丢弃")

        // 先喂等待者：查询类 API 靠它把回包交回调用方（不消费事件，下面照常路由）
        waiters[waiterKey(p.type, p.id)]?.offer(p.payload)

        // 握手报文先给驱动（它只认自己状态下的那几种 type/id）
        handshake.onPacket(p)

        val decision = ControlRouter.dispatch(p, controlSink)
        emit(GlassesEvent.Control(p, decision))
    }

    /** MASS 分流：只能按 MassType，不能按帧长。 */
    private fun handleMass(plain: ByteArray) {
        if (diagnostics) {
            // 语音/图片分片太多不逐个打；其余 MassType（OCR 矩形、小爱追踪、埋点…）全都留痕，
            // 便于对齐拍照后设备的后续动作（实测）
            val frame = MassCodec.parse(plain)
            if (frame != null && !frame.isVoice && !frame.isImage) {
                emit(
                    GlassesEvent.Diagnostic(
                        "MASS 其他帧 ${frame} head=${io.github.slchy.glasses2grok.core.Hex.encode(plain.copyOf(minOf(plain.size, 24)))}",
                    ),
                )
            }
        }
        val droppedBefore = assembler.droppedIncompleteCount
        val completed = assembler.feed(plain)
        if (diagnostics && assembler.droppedIncompleteCount != droppedBefore) {
            // 与参考实现同构：凑不满声明总长的图不交付。参考实现用 15 s 定时器丢，
            // 我们用「下一条流声明了不同总长」这个边界丢 —— 丢弃必须可观测。
            emit(
                GlassesEvent.Diagnostic(
                    "MASS 丢弃不完整图片 ${assembler.lastDroppedImageSize}B" +
                        "（声明 ${assembler.lastDroppedDeclaredTotal}B）",
                ),
            )
        }
        if (completed) {
            val composed = assembler.imageSize
            val declared = assembler.declaredTotal
            emit(GlassesEvent.Image(assembler.imageBytes()))
            if (diagnostics) {
                emit(
                    GlassesEvent.Diagnostic(
                        "MASS 图片交付 ${composed}B（声明 ${declared}B，truncated=${assembler.isImageTruncated}）",
                    ),
                )
            }
            // 只清图片侧：同一轮里语音可能还在继续
            assembler.clearImage()
        }
    }

    private val controlSink = object : io.github.slchy.glasses2grok.core.control.ControlSink {
        override fun onVoice(id: Int, payload: ByteArray) {
            val parsed = io.github.slchy.glasses2grok.core.voice.VoiceCodec.parse(id, payload)
            // 缓存最近一次 OpenDevice 的两个 ID —— 拍照必须原样复用（图片链路）
            if (parsed is io.github.slchy.glasses2grok.core.voice.VoiceEvent.OpenDevice) lastOpenDevice = parsed
            emit(GlassesEvent.Voice(parsed))
        }
        override fun onSystem(id: Int, payload: ByteArray) {
            // id=91 的 AlertStatus 里 camera/recorder 会随拍摄变化，交给宿主
            if (id == GlassesProtocol.System.ID_ALERT_STATUS) {
                io.github.slchy.glasses2grok.core.interconn.CaptureAlert.parse(payload)
                    ?.let { emit(GlassesEvent.CaptureAlertEvent(it)) }
            }
            // id=79 = 佩戴状态变化上报，载荷 System.f49{ f2 = state }
            if (id == 79) {
                io.github.slchy.glasses2grok.core.interconn.WearStatus.fromReport(payload)
                    ?.let { emit(GlassesEvent.WearChanged(it)) }
            }
            // id=93 = GetWearStatus 的**回包**，载荷 System.f60 = enum（权威当前态）
            if (id == ID_GET_WEAR_STATUS) {
                io.github.slchy.glasses2grok.core.interconn.WearStatus.fromQuery(payload)
                    ?.let { emit(GlassesEvent.WearChanged(it)) }
            }
            // id=97 = 设备主动上报电池状态，载荷 System.f63 = DeviceStatus.Battery
            if (id == GlassesProtocol.System.ID_BATTERY) {
                io.github.slchy.glasses2grok.core.query.DeviceStatus.parseBatteryReport(payload)
                    ?.let { emit(GlassesEvent.BatteryChanged(it)) }
            }
            // id=88/89 = 热点开/关的回包：System.f56 WiFiAP.Result{code, WiFiAP{ssid,password,gateway}}
            if (id == WifiApIds.ID_ENABLE || id == WifiApIds.ID_DISABLE) {
                WifiApCodec.parseResult(payload)?.let { emit(GlassesEvent.WifiApChanged(it)) }
            }
            // id=99 = P2P 建组回包：System.f66 WiFiP2P.Result{code, ipAddress, taskId}
            if (id == io.github.slchy.glasses2grok.core.wifi.WifiP2pIds.ID_ENABLE_P2P_GC) {
                io.github.slchy.glasses2grok.core.wifi.WifiP2pCodec.parseResult(payload)
                    ?.let { emit(GlassesEvent.WifiP2pChanged(it)) }
            }
            // id=80 = 基础状态上报的回包：System.f51 ReportData.Result{type, status}
            if (id == io.github.slchy.glasses2grok.core.query.ReportDataIds.ID_REQUEST) {
                io.github.slchy.glasses2grok.core.query.ReportDataResult.parse(payload)
                    ?.let { emit(GlassesEvent.ReportDataReceived(it)) }
            }
        }

        override fun onMedia(id: Int, payload: ByteArray) {
            // id=22 是设备**主动上报**的录音状态（参考实现设备侧录音状态上报）。
            // 双指录音由眼镜自己判定/自己开始（见实测），手机拿不到触发权，
            // 所以「正在录音」这件事只能靠上报与轮询来观测。
            if (id == io.github.slchy.glasses2grok.core.media.RecordIds.ID_REPORT_RECORD_STATUS) {
                io.github.slchy.glasses2grok.core.query.RecordStatus.parse(payload)
                    ?.let { emit(GlassesEvent.RecordStatusEvent(it)) }
            }
        }

        override fun onLive(id: Int, payload: ByteArray) {
            if (id == io.github.slchy.glasses2grok.core.interconn.InterconnectionIds.ID_PUBLISH_MIS_SPEC) {
                io.github.slchy.glasses2grok.core.interconn.MisSpec.parse(payload)
                    ?.let { emit(GlassesEvent.MisSpecEvent(it)) }
            }
            // id=47 = 直播状态回执：Interconnection.f35 = LiveStreaming.Status{code, rtspUrl}
            if (id == LiveStreaming.ID_STATUS) {
                LiveStreaming.parseLiveState(payload)
                    ?.let { emit(GlassesEvent.LiveStateChanged(it)) }
            }
        }
    }

    private fun advanceHandshake() {
        when (handshake.state) {
            HandshakeDriver.State.DONE -> {
                if (state == SessionState.HANDSHAKING) {
                    keys = handshake.keys
                    setState(SessionState.READY, "握手完成")
                }
            }
            HandshakeDriver.State.FAILED -> {
                if (state == SessionState.HANDSHAKING) {
                    val why = when (val f = handshake.failure) {
                        HandshakeDriver.Failure.NegotiateTimeout -> "协商超时"
                        HandshakeDriver.Failure.VerifyTimeout -> "等待 DeviceVerify 超时"
                        HandshakeDriver.Failure.ConfirmTimeout -> "等待 DeviceConfirm 超时"
                        HandshakeDriver.Failure.NoDeviceVerify -> "DeviceVerify 报文缺失或格式不符"
                        HandshakeDriver.Failure.BadDeviceSign -> "设备签名校验失败（token 不匹配？）"
                        is HandshakeDriver.Failure.Error -> f.message
                        null -> "握手失败"
                    }
                    emit(GlassesEvent.Error(handshakeErrorCode(handshake.failure), why))
                    dropKeys() // 握手失败不该留下任何可用密钥
                    setState(SessionState.FAILED, why)
                }
            }
            HandshakeDriver.State.NEGOTIATING -> {
                // SAR 协商完成 ⇒ 进入 AppVerify
                if (sar.isCmdExchanged) handshake.onNegotiated()
            }
            else -> Unit
        }
    }

    private fun onTransportFailure(e: Throwable) {
        if (state == SessionState.CLOSED || state == SessionState.CLOSING) return
        emit(GlassesEvent.Error(ErrorCode.TRANSPORT, "传输失败: ${e.message}"))
        sar.reset()
        handshake.reset()
        dropKeys()
        setState(SessionState.RECONNECTING, "传输失败")
    }

    /**
     * 丢掉会话密钥并**抹掉内容**。
     *
     * `keys = null` 只丢引用，四个密钥数组还留在堆里等 GC；而 `handshake.keys`
     * 与本对象拿的是**同一个实例**，所以抹除只在引擎这一侧做（引擎是会话密钥的
     * 生命周期所有者），`HandshakeDriver` 只清自己的引用。
     */
    private fun dropKeys() {
        keys?.destroy()
        keys = null
    }

    /**
     * 握手失败归类。
     *
     * 三分法（语义，不只是数字）：
     * - `108` **token 问题**：设备签名对不上，重试无意义；
     * - `208` **瞬时**：没等到应答（超时 / 报文缺失），重开一次可能就好；
     * - `202` 其余契约/协议不符。
     *
     * 会话在这三种情况下都是终态 `FAILED`（不自作主张重连，见 `ErrorCode.HANDSHAKE_TRANSIENT`
     * 的说明），但宿主可以凭码决定「换 token」还是「退避重试」。
     */
    private fun handshakeErrorCode(failure: HandshakeDriver.Failure?): Int = when (failure) {
        HandshakeDriver.Failure.NegotiateTimeout,
        HandshakeDriver.Failure.VerifyTimeout,
        HandshakeDriver.Failure.ConfirmTimeout,
        HandshakeDriver.Failure.NoDeviceVerify,
        -> ErrorCode.HANDSHAKE_TRANSIENT

        HandshakeDriver.Failure.BadDeviceSign -> ErrorCode.AUTH_FAILED_TOKEN_INVALID
        else -> ErrorCode.PROTOCOL
    }

    private fun reportError(message: String) {
        emit(GlassesEvent.Error(ErrorCode.PROTOCOL, message))
    }

    private fun setState(next: SessionState, reason: String) {
        if (state == next) return
        state = next
        emit(GlassesEvent.StateChanged(next, reason))
    }

    /** 写失败包装：让 SAR 的 `sink` 异常在引擎边界被识别出来。 */
    public class TransportWriteException(message: String, cause: Throwable?) : RuntimeException(message, cause)

    public companion object {
        public const val TYPE_SYSTEM: Int = GlassesProtocol.Wear.SYSTEM
        public const val TYPE_VOICE: Int = GlassesProtocol.Wear.VOICE
        public const val TYPE_MEDIA: Int = GlassesProtocol.Wear.MEDIA

        /** `System.GetWearStatus`：空 payload，回包在 `System.f60`。 */
        public const val ID_GET_WEAR_STATUS: Int = GlassesProtocol.System.ID_WEAR_STATUS
        public const val FIELD_WEAR_STATUS: Int = GlassesProtocol.System.FIELD_WEAR_STATUS
    }
}
