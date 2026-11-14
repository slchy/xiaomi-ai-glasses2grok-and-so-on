package io.github.slchy.glasses2grok.sdk

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.slchy.glasses2grok.core.config.GlassesEndpoints
import io.github.slchy.glasses2grok.core.config.GlassesProtocol
import io.github.slchy.glasses2grok.core.interconn.WearStatus
import io.github.slchy.glasses2grok.core.live.LiveStreaming
import io.github.slchy.glasses2grok.core.media.RecordAction
import io.github.slchy.glasses2grok.core.query.ShootingMode
import io.github.slchy.glasses2grok.core.query.RecordGesture
import io.github.slchy.glasses2grok.core.query.MusicControlTap
import io.github.slchy.glasses2grok.core.media.RecordKind
import io.github.slchy.glasses2grok.core.media.RecordCommand
import io.github.slchy.glasses2grok.core.media.RecordCodec
import io.github.slchy.glasses2grok.core.media.RecordIds
import io.github.slchy.glasses2grok.core.media.RecordResponse
import io.github.slchy.glasses2grok.core.media.RecordType
import io.github.slchy.glasses2grok.core.query.DeviceInfo
import io.github.slchy.glasses2grok.core.query.DeviceStatus
import io.github.slchy.glasses2grok.core.query.MediaList
import io.github.slchy.glasses2grok.core.query.MediaSummary
import io.github.slchy.glasses2grok.core.query.QueryIds
import io.github.slchy.glasses2grok.core.query.QueryResult
import io.github.slchy.glasses2grok.core.query.ReportDataIds
import io.github.slchy.glasses2grok.core.query.ReportDataResult
import io.github.slchy.glasses2grok.core.query.RecordStatus
import io.github.slchy.glasses2grok.core.query.SystemSetting
import io.github.slchy.glasses2grok.core.query.SystemSettingRequest
import io.github.slchy.glasses2grok.core.query.SystemSettingWrite
import io.github.slchy.glasses2grok.core.session.LinkCounters
import io.github.slchy.glasses2grok.core.wifi.WifiApInfo
import io.github.slchy.glasses2grok.core.query.WifiConfig
import io.github.slchy.glasses2grok.core.session.GlassesEvent
import io.github.slchy.glasses2grok.core.session.SessionState
import io.github.slchy.glasses2grok.core.system.SystemTimeCodec
import io.github.slchy.glasses2grok.core.voice.VoiceCodec
import io.github.slchy.glasses2grok.core.voice.VoiceIds
import io.github.slchy.glasses2grok.transport.AndroidSppLink
import io.github.slchy.glasses2grok.transport.SessionDriver
import io.github.slchy.glasses2grok.transport.SppLink
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 一副眼镜的会话 —— SDK 的唯一入口。
 *
 * ```kotlin
 * val session = GlassesSession.open(context, device, token16)
 * session.setListener { event ->
 *     when (event) {
 *         is GlassesEvent.StateChanged ->
 *             if (event.state == SessionState.READY) session.requestWearStatus()
 *         is GlassesEvent.Audio -> player.write(event.data)   // mSBC 原始帧，未解码
 *         is GlassesEvent.Image -> saveJpeg(event.jpeg)
 *         else -> Unit
 *     }
 * }
 * // ...
 * session.close()
 * ```
 *
 * 四条硬约束：
 * - [open] **非阻塞**（建连与握手在后台线程 ⇒ 无 ANR 风险）。
 * - **阻塞与非阻塞两族方法并存，别混着用**：返回 [QueryResult] / [SettingWriteResult] 的
 *   （`deviceInfo()` / `wearStatus()` / `systemSetting()` / `setVoiceWakeup()` …）要等设备回包，
 *   默认等 3 s、[listMedia] 与 [enableWifiApAndWait] 5 s、[enableWifiP2pGcAndWait] 8 s，
 *   **必须在后台线程调用** —— 主线程上占满 5 s 就是 ANR，所以 [`SessionOptions.threadGuards`]
 *   默认让它们在主线程直接抛 `IllegalStateException`。返回 [SendResult] 的那组（`capturePhoto()` /
 *   `requestWearStatus()` / `startLive()` …）只入队、立即返回，主线程可调。
 *   拿不准用哪个就用后者 + `GlassesEvent`，或 `-ktx` 的 `Flow`。
 * - 回调**不得重入等待**：在回调里调 [sendWear] 只入队、立即返回。
 * - [close] **幂等且立即返回**（要「返回即静默」用 [closeAndWait]，那是阻塞的）。
 */
public class GlassesSession internal constructor(
    private val driver: SessionDriver,
    private val closed: AtomicBoolean,
    private val listeners: java.util.concurrent.CopyOnWriteArrayList<GlassesListener>,
    private val dispatcher: EventDispatcher,
) : GlassesEventSource {
    public companion object {
        private const val TOKEN_SIZE = GlassesProtocol.Crypto.TOKEN_SIZE

        /**
         * 建立会话。**立即返回** `CONNECTING` 状态的会话；握手成败通过事件通知。
         *
         * @param token16 账号↔设备绑定凭据（16 字节）。SDK **不获取、不持久化**；
         *   获取 token 属破坏性操作（会打断参考实现的绑定），由宿主负责。
         */
        public fun open(
            context: Context,
            device: BluetoothDevice,
            token16: ByteArray,
            options: SessionOptions = SessionOptions(),
        ): GlassesSession {
            require(token16.size == TOKEN_SIZE) {
                "token16 必须是 $TOKEN_SIZE 字节，实际 ${token16.size}"
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            // applicationContext：链路比 Activity 活得久，广播接收器不能挂 Activity
            val appContext = context.applicationContext
            return openWith({ AndroidSppLink(device, appContext, adapter) }, token16, options)
        }

        /**
         * 同 [open]，但接受 32 位 hex 字符串（token 的常见存法）。
         *
         * 宿主拿到的 token 几乎总是 hex 文本；早先每个宿主都得自己抄一份解码器
         * （`sdk/sample` 里就抄了两份）。空格与冒号会被忽略：
         * `"0123 4567:89ab…"` 与 `"0123456789ab…"` 等价。
         *
         * @throws IllegalArgumentException token 不是 32 位 hex（或含非 hex 字符）
         */
        public fun open(
            context: Context,
            device: BluetoothDevice,
            tokenHex: String,
            options: SessionOptions = SessionOptions(),
        ): GlassesSession = open(context, device, parseTokenHex(tokenHex), options)

        /** 解析 32 位 hex token；忽略空格与冒号，其余非法输入抛 [IllegalArgumentException]。 */
        internal fun parseTokenHex(tokenHex: String): ByteArray {
            val clean = tokenHex.replace(" ", "").replace(":", "").trim()
            require(clean.length == TOKEN_SIZE * 2) {
                "tokenHex 必须是 ${TOKEN_SIZE * 2} 位 hex，实际 ${clean.length} 位（已忽略空格与冒号）"
            }
            return ByteArray(TOKEN_SIZE) { i ->
                val hi = hexDigit(clean[i * 2])
                val lo = hexDigit(clean[i * 2 + 1])
                ((hi shl 4) or lo).toByte()
            }
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalArgumentException("tokenHex 含非 hex 字符 '$c'")
        }

        internal fun openWith(
            newLink: () -> SppLink,
            token16: ByteArray,
            options: SessionOptions,
            initialListener: GlassesListener? = null,
            clock: io.github.slchy.glasses2grok.core.sar.Clock = io.github.slchy.glasses2grok.transport.UptimeClock,
        ): GlassesSession {
            require(token16.size == TOKEN_SIZE) {
                "token16 必须是 $TOKEN_SIZE 字节，实际 ${token16.size}"
            }
            val dispatcher = EventDispatcher(options.callbackDispatcher)
            val listeners = java.util.concurrent.CopyOnWriteArrayList<GlassesListener>().apply { if (initialListener != null) add(initialListener) }
            val closed = AtomicBoolean(false)

            val driver = SessionDriver(
                newLink = newLink,
                token16 = token16.copyOf(), // 拷贝：宿主返回后即可复用/清零原数组
                model = android.os.Build.MODEL ?: "unknown",
                locale = Locale.getDefault().toString(),
                clock = clock,
                tickMs = options.tickMs,
                connectTimeoutMs = options.connectTimeoutMs,
                maxConnectAttempts = options.maxConnectAttempts,
                diagnostics = options.diagnostics,
                onBlockingCall = if (options.threadGuards) {
                    { timeoutMs -> Threading.checkNotBlockingMainThread(timeoutMs) }
                } else {
                    null
                },
                emit = { event ->
                    val isFinal = event is GlassesEvent.StateChanged &&
                        event.state == SessionState.CLOSED
                    dispatcher.dispatch(
                        critical = isFinal,
                        block = dispatch@{
                            // close() 之后只放行最后那条 CLOSED 事件，其余全部丢弃
                            if (closed.get() && !isFinal) return@dispatch
                            for (l in listeners) runCatching { l.onEvent(event) }
                            // CLOSED 是最后一件事：派发完就收掉专用线程，
                            // 否则每个 SingleThread 会话都漏一个 HandlerThread。
                            if (isFinal) dispatcher.shutdown()
                        },
                    )
                },
            )

            val session = GlassesSession(driver, closed, listeners, dispatcher)
            driver.start()
            return session
        }
    }

    /** 当前状态。任意线程可读。 */
    override val state: SessionState get() = driver.state

    /**
     * 因**回调队列满**而没有派发给宿主的事件数。
     *
     * 非零说明宿主的 [GlassesListener.onEvent] 处理不过来（或回调线程被占住）。
     * 终态 `CLOSED` 永远不会被丢。
     */
    public val callbackDrops: Long get() = dispatcher.dropped

    /**
     * RX 队列溢出丢掉的**块数** / 字节数。
     *
     * 溢出丢弃发生在解析之前，表现为「音频整块消失、图片缺分片」。宿主拿它与
     * `GlassesEvent.Audio.droppedBefore` 对账：若这里在涨而语音块不连续，
     * 空洞是本端造成的，不是设备没说话。
     */
    public val rxDroppedChunks: Long get() = driver.rxDroppedChunks
    public val rxDroppedBytes: Long get() = driver.rxDroppedBytes

    /** 上一轮 socket 的残留字节被丢弃的块数（换链路时的正常现象，不算音频空洞）。 */
    public val rxStaleChunks: Long get() = driver.rxStaleChunks

    private val rawSends = java.util.concurrent.atomic.AtomicLong()

    /**
     * 调用 [sendWear] 逃生口的累计次数。
     *
     * 非零且持续增长，说明宿主（或 sample）在用裸 `(type, id)` 发命令 ——
     * 那就是「还没封装成具名能力」的清单来源。
     */
    /** 当前链路计数快照。**未连接时返回 [LinkCounters.EMPTY]。**
     *
     * 取值会占用一次引擎任务队列（最多 200 ms），别在高频循环里调 —— 仪表盘一秒一次足够。
     */
    public fun linkCounters(): LinkCounters = driver.linkCounters() ?: LinkCounters.EMPTY

    public val rawSendCount: Long get() = rawSends.get()

    /**
     * **替换**全部监听（传 null 清空）。线程安全，立即生效。
     *
     * 只想要「再加一个」用 [addListener] —— 早先这里只有一个 `AtomicReference`，
     * 多个订阅者（尤其 `-ktx` 的 `events()` 与 `awaitReady()`）会互相顶掉，
     * 而且任一 flow 结束时会把别人的订阅一起摘掉。
     */
    override fun setListener(listener: GlassesListener?) {
        listeners.clear()
        if (listener != null) listeners.add(listener)
    }

    /** 追加一个监听，不影响已有订阅者。配对 [removeListener]。 */
    override fun addListener(listener: GlassesListener) {
        listeners.addIfAbsent(listener)
    }

    /** 移除指定监听；其余订阅者不受影响。 */
    override fun removeListener(listener: GlassesListener) {
        listeners.remove(listener)
    }

    /**
     * **逃生口**：发一条业务报文 `Wear{type, id, payload}`，自动加密（`ch=1, op=2`）。
     *
     * 给「SDK 还没封装成具名方法」的能力用。**不做任何参数校验**：`type`/`id` 写错不会
     * 编译错、不会抛异常，设备只会静默不响应 —— 能用具名方法就别用这个。
     * 每次调用都会计入 [rawSendCount]，用来发现「还有哪些能力没封装」。
     *
     * 刻意不暴露 `channel/opCode`：加密与否**只由 opCode 决定**，把它交给宿主
     * 就等于允许发出未加密的业务帧，直接破坏协议。
     */
    public fun sendWear(type: Int, id: Int, payload: ByteArray): SendResult {
        rawSends.incrementAndGet()
        return sendWearInternal(type, id, payload)
    }

    /** 具名能力的公共发送路径（**不计入** [rawSendCount]）。 */
    private fun sendWearInternal(type: Int, id: Int, payload: ByteArray): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        return sendResultOf(driver.sendWear(type, id, payload))
    }

    /**
     * 读佩戴状态（**阻塞**）：`System`(2) `GetWearStatus`(93)，空载荷，回包 `System.f60 = enum`。
     *
     * `1 = 佩戴中 / 2 = 未佩戴`（真机闭环，见 [WearStatus]）。这是**权威当前态**；
     * 变化通知走 `System id=79`（[GlassesEvent.WearChanged]）。
     *
     * ⚠️ **阻塞**，勿在主线程调用；只关心变化就等 [GlassesEvent.WearChanged]。
     */
    public fun wearStatus(timeoutMs: Long = 3_000): QueryResult<WearStatus> =
        query(QueryIds.SYSTEM, QueryIds.ID_WEAR_STATUS, ByteArray(0), timeoutMs, WearStatus::fromQuery)

    /**
     * 触发一次佩戴状态读取但**不等回包**（结果以 [GlassesEvent.WearChanged] 到达）。
     *
     * 只是「请设备报一次」，真正的值由 `id=93` 回包或 `id=79` 上报带来。
     */
    public fun requestWearStatus(): SendResult = sendWearInternal(2, 93, ByteArray(0))

    /**
     * 同步时间到眼镜：`System`(2) `SetSystemTime`(3)。
     *
     * 参考实现 **每次连接都发**（固件日志 539 次），且 `needResponse = false` ——
     * 所以这里只发不等回包，要确认是否采纳就调 [recordStatus] 看它的时间戳。
     *
     * 载荷结构见 [SystemTimeCodec]（含 `offset` 以 15 分钟为单位的坑）。
     *
     * @param epochMs 毫秒时间戳，默认取本机当前时间
     * @param zone 时区，默认本机时区
     * @param is24Hour 12/24 小时制，默认 24 小时
     */
    public fun syncTime(
        epochMs: Long = System.currentTimeMillis(),
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
        is24Hour: Boolean = true,
    ): SendResult = sendWearInternal(
        QueryIds.SYSTEM,
        QueryIds.ID_SET_SYSTEM_TIME,
        SystemTimeCodec.encode(epochMs, zone, is24Hour),
    )

    /**
     * **结束语音会话**：`Voice`(14) `SYNC_VOICE_STATUS`(5)，载荷 `Voice{f5{f1=0}}`。
     *
     * 作用是把「对话结束」明确告诉眼镜，而不是只让上行音频停掉、等它自己 VAD 收尾。
     * 云端 agent 判定结束时（MCP `end_session`）应由宿主调这一句，再断连接。
     *
     * [close] 会自动带上这一步；只有「不断连接、只结束本轮会话」时才需要单独调。
     *
     * ⚠️ **未真机验证**：原始字节从未抓到（只有参考实现抓包的符号记录
     * `参考协议`），容器结构照上行 `id=6` 同型推断。回执也没有观测到，
     * 所以只发不等。见 `VoiceCodec.endVoicePayload` 的证据清单。
     */
    public fun endVoiceSession(): SendResult =
        sendWearInternal(VoiceIds.TYPE, VoiceIds.ID_SYNC_STATUS, VoiceCodec.endVoicePayload())

    /**
     * 下发一条 `Voice id=5 SYNC_VOICE_STATUS`（`VoiceStatus` 六字段）。
     *
     * 这是参考实现用来告诉眼镜「小爱现在处于什么状态」的报文，**双向同构**：
     * 设备也会主动上报同一结构（[GlassesEvent.Voice] 里的 `SyncStatus`）。
     *
     * 已知确定的行为：六个字段全 0（= [endVoiceSession]）会让眼镜立刻停止采集。
     * **未知**：各字段与内侧/外侧指示灯的关系（`tts` 是否驱动「说话时常亮」）——
     * 属于待实测项，别把它当稳定契约用。
     *
     * @param voice 0 `VOICE_IDLE` / 1 `WAKE_UP`
     * @param tts 0 `TTS_IDLE` / 1 `TTS_PLAYING` / 2 `TTS_THINKING`
     * @param operating 0 空闲 / 1 执行中 / 2 录音中 / 3–6 待机类
     */
    public fun syncVoiceStatus(
        voice: Long = 0,
        tts: Long = 0,
        mediaPlay: Long = 0,
        image: Long = 0,
        operating: Long = 0,
        device: Long = 0,
    ): SendResult = sendWearInternal(
        VoiceIds.TYPE,
        VoiceIds.ID_SYNC_STATUS,
        VoiceCodec.syncStatusPayload(voice, tts, mediaPlay, image, operating, device),
    )

    // ------------------------------------------------------------ 直播（Interconnection, type=23）

    /**
     * 查询直播状态：`id=45`。回复里带眼镜热点 SSID/密码（真机实测为
     * `System id=86` 的 `f4 → f33`，可用 `LiveStreaming.parseStatus` 解）。
     */
    public fun queryLiveStream(): SendResult =
        sendWearInternal(LiveStreaming.TYPE, LiveStreaming.ID_QUERY, LiveStreaming.queryPayload())

    /**
     * 开始推流：`id=46`。
     *
     * ⚠️ 默认 `Mode.HOTSPOT` 下**眼镜自己开热点**，`url` 指向的主机必须在该热点网络内，
     * 否则固件回报 `errorCode=5 / RTMP 连接失败`（实测）。`Mode.BLUETOOTH` 尚未实测。
     */
    public fun startLive(
        url: String,
        resolution: LiveStreaming.Resolution = LiveStreaming.Resolution.P720,
        fps: LiveStreaming.FrameRate = LiveStreaming.FrameRate.FPS30,
        streamMode: LiveStreaming.StreamMode = LiveStreaming.StreamMode.AUTO,
        mode: LiveStreaming.Mode = LiveStreaming.Mode.HOTSPOT,
    ): SendResult = sendWearInternal(
        LiveStreaming.TYPE,
        LiveStreaming.ID_REQUEST,
        LiveStreaming.startPayload(url, resolution, fps, streamMode, mode),
    )

    /** 停止推流：`id=46`。 */
    public fun stopLive(): SendResult =
        sendWearInternal(LiveStreaming.TYPE, LiveStreaming.ID_REQUEST, LiveStreaming.stopPayload())

    /**
     * `System id=2` 设备信息。**阻塞**，勿在主线程调用。
     */
    public fun deviceInfo(timeoutMs: Long = 3_000): QueryResult<DeviceInfo> =
        query(QueryIds.SYSTEM, QueryIds.ID_DEVICE_INFO, ByteArray(0), timeoutMs, DeviceInfo::parse)

    /**
     * `System id=1` 设备状态。⚠️ 两个 int 的语义未确认，见 `DeviceStatus`。
     *
     * ⚠️ **阻塞**，勿在主线程调用。
     */
    public fun deviceStatus(timeoutMs: Long = 3_000): QueryResult<DeviceStatus> =
        query(QueryIds.SYSTEM, QueryIds.ID_DEVICE_STATUS, ByteArray(0), timeoutMs, DeviceStatus::parse)

    /**
     * `Media id=14` 媒体数量摘要。
     *
     * ⚠️ **阻塞**，勿在主线程调用。
     */
    public fun mediaSummary(timeoutMs: Long = 3_000): QueryResult<MediaSummary> =
        query(QueryIds.MEDIA, QueryIds.ID_MEDIA_SUMMARY, ByteArray(0), timeoutMs, MediaSummary::parse)

    /**
     * `Media id=15` 媒体文件列表。
     *
     * ⚠️ 回包 id 是 **16**，不是 15 —— 这是真机实测的，不是笔误。
     * ⚠️ **阻塞**，勿在主线程调用。
     */
    public fun listMedia(timeoutMs: Long = 5_000): QueryResult<MediaList> =
        query(
            QueryIds.MEDIA,
            QueryIds.ID_MEDIA_LIST_REQUEST,
            ByteArray(0),
            timeoutMs,
            MediaList::parse,
            responseId = QueryIds.ID_MEDIA_LIST_RESPONSE,
        )

    /**
     * `Media id=21` 录音状态。字段名已按参考实现对齐。
     *
     * ⚠️ **阻塞**，勿在主线程调用。
     */
    public fun recordStatus(timeoutMs: Long = 3_000): QueryResult<RecordStatus> =
        query(QueryIds.MEDIA, QueryIds.ID_RECORD_STATUS, ByteArray(0), timeoutMs, RecordStatus::parse)

    /**
     * `Media id=17 RequestMediaFile`：按 `identifier` 向眼镜要一个文件。
     *
     * 参考实现在载荷里写死 `thumbnail = true`（`MediaFile.Identifier.f2`），所以这条命令实际是
     * **要缩略图**（KB 级，`参考协议`记的 7 641 B 相册缩略图）。
     * 原图/录像请走 P2P + `/v1/...`（实测）。
     *
     * 这是**单向发送**：文件内容不会以 `Media 17` 回包形式回来（回包形态未确证），
     * 想观测它请监听 `GlassesEvent`（MASS / Image / Control）。
     */
    public fun requestMediaFile(identifier: String, thumbnail: Boolean = true): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        return if (driver.requestMediaFile(identifier, thumbnail)) {
            SendResult.Queued
        } else {
            SendResult.Rejected(SdkErrorCode.NOT_READY, "未就绪（当前 ${driver.state}）")
        }
    }

    /** `Media id=18 RequestMediaFileList`：一次要多个文件（语义同 [requestMediaFile]）。 */
    public fun requestMediaFileList(identifiers: List<String>, thumbnail: Boolean = true): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        return if (driver.requestMediaFileList(identifiers, thumbnail)) {
            SendResult.Queued
        } else {
            SendResult.Rejected(SdkErrorCode.NOT_READY, "未就绪（当前 ${driver.state}）")
        }
    }

    /** `Media id=19 ConfirmMediaFile`：告诉眼镜「这个文件我收到了」（参考实现拉完会确认）。 */
    public fun confirmMediaFile(identifier: String): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        return if (driver.confirmMediaFile(identifier)) {
            SendResult.Queued
        } else {
            SendResult.Rejected(SdkErrorCode.NOT_READY, "未就绪（当前 ${driver.state}）")
        }
    }

    /**
     * 基础状态上报：`System id=80 ReportData`，空载荷。
     *
     * 参考实现每 1 小时自动发一次；
     * 回包是 `System.f51 ReportData.Result{type, status}`，从 `GlassesEvent.ReportDataReceived` 拿。
     * 阻塞等回包用 [requestReportDataAndWait]。
     */
    public fun requestReportData(): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        return if (driver.requestReportData()) {
            SendResult.Queued
        } else {
            SendResult.Rejected(SdkErrorCode.NOT_READY, "未就绪（当前 ${driver.state}）")
        }
    }

    /** 发 `System id=80` 并等回包（默认 5 s）。**阻塞**，勿在主线程调用。 */
    public fun requestReportDataAndWait(timeoutMs: Long = 5_000): QueryResult<ReportDataResult> {
        if (closed.get()) return QueryResult.Failed("会话已关闭")
        if (driver.state != SessionState.READY) return QueryResult.Failed("未就绪（当前 ${driver.state}）")
        return query(
            QueryIds.SYSTEM,
            ReportDataIds.ID_REQUEST,
            ByteArray(0),
            timeoutMs,
            ReportDataResult::parse,
        )
    }

    /**
     * 录音开关：`Media`(18) `id=20 RequestRecord`，载荷 `Media{f17{type, action}}`。
     *
     * 参考实现就是这么发的：`START` → action 0、`STOP` → action 1；
     * 类型见 [RecordKind]。
     *
     * ⚠️ **`code != 0` 一律返回 `Failed`** —— 设备会用 `RecordResponse.code` 拒绝请求
     * （`RecordCode`：4 = 没在录音、10 = 已在录音、11 电量低…），把它包成 `Ok` 就是撒谎。
     *
     * ⚠️ **阻塞**，勿在主线程调用。
     */
    public fun requestRecord(
        kind: RecordKind,
        command: RecordCommand,
        timeoutMs: Long = 3_000,
    ): QueryResult<RecordResponse> = requestRecord(kind.wire, command.wire, timeoutMs)

    /**
     * ⚠️ **阻塞**，勿在主线程调用。
     * @deprecated 用 [requestRecord] 的枚举重载。
     */
    @Deprecated(
        "改用 requestRecord(RecordKind, RecordCommand, …)",
        ReplaceWith(
            "requestRecord(RecordKind.entries.first { it.wire == type }," +
                " RecordCommand.entries.first { it.wire == action }, timeoutMs)",
        ),
    )
    public fun requestRecord(
        type: Int,
        action: Int,
        timeoutMs: Long = 3_000,
    ): QueryResult<RecordResponse> {
        val r = query(
            RecordIds.TYPE,
            RecordIds.ID_REQUEST_RECORD,
            RecordCodec.requestPayload(type, action),
            timeoutMs,
            RecordCodec::parseResponse,
        )
        return when {
            r is QueryResult.Failed -> r
            r is QueryResult.Ok && !r.value.isOk ->
                QueryResult.Failed("设备拒绝：code=${r.value.code}（${r.value.codeName}）")
            else -> r
        }
    }

    /**
     * 开始录音（默认 `RECORD_NORMAL`）。
     *
     * ⚠️ **阻塞**，勿在主线程调用。
     */
    public fun startRecording(
        kind: RecordKind = RecordKind.NORMAL,
        timeoutMs: Long = 3_000,
    ): QueryResult<RecordResponse> = requestRecord(kind, RecordCommand.START, timeoutMs)

    /**
     * ⚠️ **阻塞**，勿在主线程调用。
     * @deprecated 用 [startRecording] 的枚举重载。
     */
    @Deprecated("改用 startRecording(RecordKind, …)", ReplaceWith("startRecording(RecordKind.entries.first { it.wire == type }, timeoutMs)"))
    public fun startRecordingInt(
        type: Int = RecordType.NORMAL,
        timeoutMs: Long = 3_000,
    ): QueryResult<RecordResponse> = requestRecord(type, RecordAction.START, timeoutMs)

    /**
     * 停止录音（默认 `RECORD_NORMAL`）。
     *
     * ⚠️ **阻塞**，勿在主线程调用。
     */
    public fun stopRecording(
        kind: RecordKind = RecordKind.NORMAL,
        timeoutMs: Long = 3_000,
    ): QueryResult<RecordResponse> = requestRecord(kind, RecordCommand.STOP, timeoutMs)

    /**
     * ⚠️ **阻塞**，勿在主线程调用。
     * @deprecated 用 [stopRecording] 的枚举重载。
     */
    @Deprecated("改用 stopRecording(RecordKind, …)", ReplaceWith("stopRecording(RecordKind.entries.first { it.wire == type }, timeoutMs)"))
    public fun stopRecordingInt(
        type: Int = RecordType.NORMAL,
        timeoutMs: Long = 3_000,
    ): QueryResult<RecordResponse> = requestRecord(type, RecordAction.STOP, timeoutMs)

    /**
     * `System id=86` 读 WiFi 配置 —— 回包是**明文 SSID/PSK**。
     *
     * ⚠️ 宿主拿到后不要落盘/打日志；SDK 只负责取回。
     * ⚠️ **阻塞**，勿在主线程调用。
     */
    public fun wifiConfig(timeoutMs: Long = 3_000): QueryResult<WifiConfig> =
        query(QueryIds.SYSTEM, QueryIds.ID_WIFI_CONFIG, ByteArray(0), timeoutMs, WifiConfig::parse)

    /**
     * 打开眼镜热点（`System id=88 EnableWiFiAP`，载荷为空）。
     *
     *  的前置：真机 2026-11-11 扫描确认热点**默认是关的**（附近没有任何
     * 眼镜 MAC 前缀的 BSSID）。开起来之后 [wifiConfig] 给的 SSID/PSK 才能连，
     * 也才有 `:8080` 的 HTTP 文件服务。
     */
    public fun enableWifiAp(): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        return if (driver.enableWifiAp()) {
            SendResult.Queued
        } else {
            SendResult.Rejected(SdkErrorCode.NOT_READY, "未就绪（当前 ${driver.state}）")
        }
    }

    /**
     * 打开热点并**等回包**（`System id=88`）。
     *
     * 回包里就是连 AP 需要的全部凭据（实测）。拿到后立刻 [AlbumClient.connect] —— 眼镜热点空闲约 30 s 就关。
     *
     * **阻塞**，勿在主线程调用（默认等 5 s）。
     */
    public fun enableWifiApAndWait(timeoutMs: Long = 5_000): QueryResult<WifiApInfo> {
        if (closed.get()) return QueryResult.Failed("会话已关闭")
        if (driver.state != SessionState.READY) {
            return QueryResult.Failed("未就绪（当前 ${driver.state}）")
        }
        return query(
            QueryIds.SYSTEM,
            io.github.slchy.glasses2grok.core.wifi.WifiApIds.ID_ENABLE,
            ByteArray(0),
            timeoutMs,
            io.github.slchy.glasses2grok.core.wifi.WifiApCodec::parseResult,
        )
    }

    /**
     * `System id=56 SetWiFIConfig` —— 让**眼镜自己连上**指定 WiFi（STA）。
     *
     * 参考实现调用方是眼镜 WLAN 设置页和直播页
     * 。它**不是**「改眼镜热点凭据」，
     * 发完 `id=88` 的回包不会变（实测）。
     */
    public fun setWifiConfig(
        ssid: String,
        password: String,
        authMode: Int = 2,
        frequency: Int = 2,
    ): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        return if (driver.setWifiConfig(ssid, password, authMode, frequency)) {
            SendResult.Queued
        } else {
            SendResult.Rejected(SdkErrorCode.NOT_READY, "未就绪（当前 ${driver.state}）")
        }
    }

    /**
     * 让眼镜作为 P2P client 加入手机自建的 Wi-Fi Direct 组（`System id=99 EnableWiFiP2PGC`）。
     *
     * 参考实现「无感」路径：手机 `WifiP2pManager.createGroup()` 当 GO（静默、无系统确认框），
     * 把组的 SSID/密码/频率发过来；眼镜加入后回 `f66 WiFiP2P.Result{code, ipAddress}`。
     * 手机侧的建组工具见 `P2pGroupOwner`。
     */
    public fun enableWifiP2pGc(ssid: String, password: String, frequency: Int, taskId: Int = 0): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        return if (driver.enableWifiP2pGc(ssid, password, frequency, taskId)) {
            SendResult.Queued
        } else {
            SendResult.Rejected(SdkErrorCode.NOT_READY, "未就绪（当前 ${driver.state}）")
        }
    }

    /**
     * 发 `System id=99` 并**等回包**（眼镜的 P2P IP，通常是 `192.168.49.x`）。
     *
     * **阻塞**，勿在主线程调用（默认等 8 s —— 眼镜加入 P2P 组比开热点慢）。
     */
    public fun enableWifiP2pGcAndWait(
        ssid: String,
        password: String,
        frequency: Int,
        timeoutMs: Long = 8_000,
        taskId: Int = 0,
    ): QueryResult<io.github.slchy.glasses2grok.core.wifi.WifiP2pResult> {
        if (closed.get()) return QueryResult.Failed("会话已关闭")
        if (driver.state != SessionState.READY) {
            return QueryResult.Failed("未就绪（当前 ${driver.state}）")
        }
        return query(
            QueryIds.SYSTEM,
            io.github.slchy.glasses2grok.core.wifi.WifiP2pIds.ID_ENABLE_P2P_GC,
            io.github.slchy.glasses2grok.core.wifi.WifiP2pCodec.enablePayload(ssid, password, frequency, taskId),
            timeoutMs,
            io.github.slchy.glasses2grok.core.wifi.WifiP2pCodec::parseResult,
        )
    }

    /** 关闭眼镜热点（`System id=89 DisableWiFiAP`，载荷 `f57{ssid,password,gateway}`）。 */
    public fun disableWifiAp(
        ssid: String,
        password: String,
        gateway: String = GlassesEndpoints.ALBUM_HOST,
    ): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        return if (driver.disableWifiAp(ssid, password, gateway)) {
            SendResult.Queued
        } else {
            SendResult.Rejected(SdkErrorCode.NOT_READY, "未就绪（当前 ${driver.state}）")
        }
    }

    /**
     * `System id=14` 读设置。请求带 bitmask（默认 `ALL_SUPPORTED` = `f5`–`f8`）。
     *
     * ⚠️ **不要传 0**：空 payload 等价于 bitmask 0，设备只回 `f35{f9{f1=0}}` 7 字节。
     * `f9`（静默升级）无条件返回，不需要置位。
     *
     * ⚠️ **阻塞**，勿在主线程调用。
     */
    public fun systemSetting(
        bits: Int = SystemSettingRequest.ALL_SUPPORTED,
        timeoutMs: Long = 3_000,
    ): QueryResult<SystemSetting> =
        query(
            QueryIds.SYSTEM,
            QueryIds.ID_SYSTEM_SETTING,
            SystemSettingRequest.encode(bits),
            timeoutMs,
            SystemSetting::parse,
        )

    /**
     * 写一段设置：`System id=15`，`f35{ segment { group } }`。
     *
     * **必须先读**：调 [systemSetting] 拿到 [SystemSetting.groups]、改目标子字段、把整段传进来。
     * 直接构造只含一个子字段的段会把它余下的字段清成默认值（实测）。
     *
     * **阻塞**，勿在主线程调用。回执是空 payload 的 DATA 帧。
     */
    public fun writeSystemSetting(
        segment: Int,
        group: ByteArray,
        timeoutMs: Long = 3_000,
    ): SettingWriteResult {
        if (group.isEmpty()) return SettingWriteResult.Failed("段内容为空，拒绝写（会清空整段）")
        driver.request(
            QueryIds.SYSTEM,
            QueryIds.ID_SYSTEM_SETTING_WRITE,
            SystemSettingWrite.encode(segment, group),
            timeoutMs,
        ) ?: return SettingWriteResult.Failed(
            if (driver.state != SessionState.READY) "未就绪（当前 ${driver.state}）" else "${timeoutMs}ms 内没有回执",
        )
        return SettingWriteResult.Ok(segment, null)
    }

    /**
     * 改**一个子字段**并**读回校验**：读 → 改 → 写 → 再读 → 比对。
     *
     * 这是最不容易用错的形式 —— 底稿永远是设备当前值，未知子字段自然保住。
     * 读回不一致时返回 `Failed`（写到了但设备没采纳，不是网络问题）。
     *
     * **阻塞**，勿在主线程调用。
     */
    public fun setSystemSettingField(
        segment: Int,
        field: Int,
        value: Long,
        timeoutMs: Long = 3_000,
    ): SettingWriteResult {
        val before = when (val r = systemSetting(timeoutMs = timeoutMs)) {
            is QueryResult.Failed -> return SettingWriteResult.Failed("读原值失败：${r.reason}")
            is QueryResult.Ok -> r.value
        }
        val draft = before.groups[segment]
            ?: return SettingWriteResult.Failed("回包里没有段 $segment，不写（无法保证其余字段不被清空）")
        val patched = SystemSettingWrite.patch(draft, field, value)
        when (val w = writeSystemSetting(segment, patched, timeoutMs)) {
            is SettingWriteResult.Failed -> return w
            is SettingWriteResult.Ok -> Unit
        }
        val after = when (val r = systemSetting(timeoutMs = timeoutMs)) {
            is QueryResult.Failed -> return SettingWriteResult.Failed("写后读回失败：${r.reason}")
            is QueryResult.Ok -> r.value
        }
        val got = after.value(segment, field)
            ?: return SettingWriteResult.Failed("写后读回里没有段$segment.子$field")
        if (got != value) return SettingWriteResult.Failed("写后读回 = $got，期望 $value（设备没采纳）")
        return SettingWriteResult.Ok(segment, got)
    }

    /**
     * 媒体控制手势：`headset.musicControl`（`f8.f4`）。
     *
     * ⚠️ **阻塞**，勿在主线程调用（读 → 改 → 写 → 再读）。
     *
     * @param tap [MusicControlTap.SINGLE] 单指单击播放/暂停，[MusicControlTap.DOUBLE] 单指双击
     *   （1↔2 已真机双向验证）
     */
    public fun setMusicControl(tap: MusicControlTap, timeoutMs: Long = 3_000): SettingWriteResult =
        setSystemSettingField(8, 4, tap.wire.toLong(), timeoutMs)

    /**
     * ⚠️ **阻塞**，勿在主线程调用。
     * @deprecated 用 [setMusicControl] 的枚举重载；裸数字传错只会在设备侧静默不采纳。
     */
    @Deprecated("改用 setMusicControl(MusicControlTap, …)", ReplaceWith("setMusicControl(MusicControlTap.entries.first { it.wire == tap }, timeoutMs)"))
    public fun setMusicControl(tap: Int, timeoutMs: Long = 3_000): SettingWriteResult =
        setSystemSettingField(8, 4, tap.toLong(), timeoutMs)

    /**
     * 拍摄模式：`mediaSetting.shootingMode`（`f7.f1`）。
     *
     * ⚠️ **阻塞**，勿在主线程调用（读 → 改 → 写 → 再读）。
     *
     * @param mode [ShootingMode.SHORT_PRESS_PHOTO] 短按拍照/长按录像，
     *   [ShootingMode.SHORT_PRESS_RECORD] 短按录像/长按拍照（值表为静态来源，未实测功能效果）
     */
    public fun setShootingMode(mode: ShootingMode, timeoutMs: Long = 3_000): SettingWriteResult =
        setSystemSettingField(7, 1, mode.wire.toLong(), timeoutMs)

    /**
     * ⚠️ **阻塞**，勿在主线程调用。
     * @deprecated 用 [setShootingMode] 的枚举重载。
     */
    @Deprecated("改用 setShootingMode(ShootingMode, …)", ReplaceWith("setShootingMode(ShootingMode.entries.first { it.wire == mode }, timeoutMs)"))
    public fun setShootingMode(mode: Int, timeoutMs: Long = 3_000): SettingWriteResult =
        setSystemSettingField(7, 1, mode.toLong(), timeoutMs)

    /**
     * 录音手势：`mediaSetting.recordControl`（`f7.f8`）。
     *
     * ⚠️ **阻塞**，勿在主线程调用（读 → 改 → 写 → 再读）。
     *
     * @param gesture [RecordGesture.DOUBLE_TAP] 双指单击、[RecordGesture.DOUBLE_HOLD] 双指长按、
     *   [RecordGesture.OFF] 无（值表为静态来源，未实测功能效果）
     */
    public fun setRecordControl(gesture: RecordGesture, timeoutMs: Long = 3_000): SettingWriteResult =
        setSystemSettingField(7, 8, gesture.wire.toLong(), timeoutMs)

    /**
     * ⚠️ **阻塞**，勿在主线程调用。
     * @deprecated 用 [setRecordControl] 的枚举重载。
     */
    @Deprecated("改用 setRecordControl(RecordGesture, …)", ReplaceWith("setRecordControl(RecordGesture.entries.first { it.wire == mode }, timeoutMs)"))
    public fun setRecordControl(mode: Int, timeoutMs: Long = 3_000): SettingWriteResult =
        setSystemSettingField(7, 8, mode.toLong(), timeoutMs)

    /**
     * 语音唤醒开关：`aiAssistant.voiceWakeup`（`f6.f1`）。
     *
     * ⚠️ **阻塞**，勿在主线程调用（读 → 改 → 写 → 再读）。
     *
     * @param enabled 写入 `1` / `0`。⚠️ **只验证了「设备采纳并读回」**，没有验证关掉后真的
     *   不再响应唤醒词 —— 后者要人工对设备说话。
     */
    public fun setVoiceWakeup(enabled: Boolean, timeoutMs: Long = 3_000): SettingWriteResult =
        setSystemSettingField(6, 1, if (enabled) 1L else 0L, timeoutMs)

    /** 查询的公共流程：发 → 等 → 解析。解析失败也算 `Failed`，不抛异常。 */
    private fun <T : Any> query(
        type: Int,
        id: Int,
        body: ByteArray,
        timeoutMs: Long,
        parse: (ByteArray) -> T?,
        responseId: Int = id,
    ): QueryResult<T> {
        val payload = driver.request(type, id, body, timeoutMs, responseId)
            ?: return QueryResult.Failed(
                if (driver.state != SessionState.READY) "未就绪（当前 ${driver.state}）" else "${timeoutMs}ms 内没有回包",
            )
        val parsed = parse(payload) ?: return QueryResult.Failed("回包解析失败（${payload.size}B）")
        return QueryResult.Ok(parsed)
    }

    /**
     * 触发拍照（`Voice id=4`）。
     *
     * 不需要先唤醒：有 `Voice id=10` 上下文时复用设备下发的两个 ID（图片能与那次 AI 请求关联），
     * 没有时自造 ID —— 真机实测设备不校验 ID 归属（实测）。
     *
     * 拍照结果不是返回值 —— 图片仍走 MASS 上行，以 `GlassesEvent.Image` 交付。
     */
    public fun capturePhoto(): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        if (driver.state != SessionState.READY) {
            return SendResult.Rejected(SdkErrorCode.NOT_READY, "未就绪（当前 ${driver.state}）")
        }
        val ok = driver.capturePhoto()
        return if (ok) SendResult.Queued else SendResult.Rejected(SdkErrorCode.NOT_READY, "发送失败")
    }

    /** 是否已有 `Voice id=10` 上下文（有则拍照会复用它的 ID；没有也能拍）。 */
    public val canCapturePhoto: Boolean get() = driver.canCapturePhoto

    /**
     * 触发拍照，ID 由调用方自造（**实验用**：验证设备是否校验 `Voice id=10` 下发的 ID）。
     *
     * @param transactionId 自造的 transactionId（参考实现里它是调用方入参）
     * @param requestId 自造的 requestId
     * @param salt 让 `resourceId` 唯一的字节
     */
    public fun capturePhotoWithIds(
        transactionId: String,
        requestId: ByteArray,
        salt: ByteArray = ByteArray(6),
    ): SendResult {
        if (closed.get()) return SendResult.Rejected(SdkErrorCode.CLOSED, "会话已关闭")
        if (driver.state != SessionState.READY) {
            return SendResult.Rejected(SdkErrorCode.NOT_READY, "未就绪（当前 ${driver.state}）")
        }
        return if (driver.capturePhotoRaw(transactionId, requestId, salt)) {
            SendResult.Queued
        } else {
            SendResult.Rejected(SdkErrorCode.NOT_READY, "发送失败")
        }
    }

    /**
     * 幂等、**立即返回**：把状态推到 `CLOSING` 就回来了，收尾在后台线程上做
     * （发结束帧、断链路、停引擎线程、join 监督线程）。UI 线程（`onDestroy`）可以安全调用。
     *
     * 早先这里写成「幂等、非阻塞」但实际同步 join 了 4.3 s，是在赌 ANR。
     *
     * 代价：返回后**可能还有极短暂的回调**（收尾线程还没跑到停回调那一步），
     * 最后那条 `CLOSED` 事件就是这么来的。需要「返回即静默」用 [closeAndWait]。
     *
     * 这里**不清空 listener**（清空会让派发到主线程的 `CLOSED` 丢失）。
     * 要在 `close()` 之后立刻释放宿主对象，请自行 `setListener(null)`。
     *
     * **优雅退出**：收尾前会 best-effort 发一次 [endVoiceSession]（`Voice id=5` 六字段全 0），
     * 让眼镜知道语音会话结束了，而不是只看到上行音频断掉。发不出去（已断线/未就绪）不影响关闭。
     */
    public fun close() {
        if (!closed.compareAndSet(false, true)) return
        driver.close()
    }

    /**
     * **同步**关闭：等收尾做完（最坏约 4.3 s）。
     *
     * 只在「关闭后必须立刻安全复用资源」时用（测试、进程退出、同进程重开）。
     * **阻塞**，勿在主线程调用 —— UI 线程请用 [close]。
     */
    public fun closeAndWait() {
        // 不能用 compareAndSet 早退：先调过 close() 的宿主再调 closeAndWait() 时
        // 必须照样等收尾做完，否则这个方法就成了无声的空操作。
        closed.set(true)
        driver.closeAndWait()
    }
}
