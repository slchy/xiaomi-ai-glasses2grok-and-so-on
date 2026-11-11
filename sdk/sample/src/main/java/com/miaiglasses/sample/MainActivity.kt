package com.miaiglasses.sample

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.miaiglasses.core.live.LiveStreaming
import com.miaiglasses.core.query.MusicControlTap
import com.miaiglasses.core.query.RecordGesture
import com.miaiglasses.core.query.ShootingMode
import com.miaiglasses.core.session.GlassesEvent
import com.miaiglasses.core.session.SessionState
import com.miaiglasses.core.voice.VoiceEvent
import com.miaiglasses.sdk.GlassesSession
import com.miaiglasses.sdk.SessionOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 真机冒烟工具（`docs/` ）：连上 → 读佩戴 → 收事件 → 优雅断开。
 *
 * token 只从输入框来 —— SDK 不获取、不持久化（`ADR-008`）。这个 App 也不保存它。
 */
class MainActivity : Activity() {

    private val devices = mutableListOf<BluetoothDevice>()
    private lateinit var spinner: Spinner
    private lateinit var tokenInput: EditText
    private lateinit var stateView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private var session: GlassesSession? = null
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 通用探针：`--es probe "type,id,hex;type,id,hex"`，连上 READY 后按顺序发（见 [applyIntentExtras]）。 */
    private val pendingProbes = ArrayDeque<Triple<Int, Int, ByteArray>>()

    /** `--ez queryAll true`：READY 后在后台线程把 7 个查询全跑一遍并打结果。 */
    private var queryAllOn = false

    /** `--ez settingsWrite true`：READY 后跑一遍写设置的读写回环（改 musicControl 再改回）。 */
    private var settingsWriteOn = false

    /** `--ez autoEndVoice true`：收到 Voice id=10 后 4 s 自动发 `Voice id=5{f1=0}`（验证优雅退出）。 */
    private var autoEndVoiceOn = false

    /** `--es endVoiceRaw <hex>`：改发这段原始 f4，用来对比不同的 `VoiceStatus` 写法。 */
    private var endVoiceRawHex: String? = null

    /** `--ez verifySystem true`：READY 后读电量 + 拨快/写回时间，验证 `syncTime` 是否被采纳。 */
    private var verifySystemOn = false

    /**
     * `--ez verifyRecord true`：READY 后读录音状态 → 发 id=20 开始 → 读 → 发停止 → 读。
     *
     * 用途：回答两个问题 —— ①`Media id=20` 的回执是不是也走 id=20；②手机能不能触发录音
     * （按静态分析推测「不能」，眼镜自己判定双指）。
     */
    private var verifyRecordOn = false

    /**
     * `--ez watchRecord true`：每 2 s 轮询一次 `recordStatus()`，只读不发。
     *
     * 用途：验证**设备侧**（双指）触发的录音能不能被手机观测到 —— 音频是眼镜自己录的，
     * 手机不发任何命令，只能靠 `System id=91` 的 `recorder` 与 `Media id=21` 的轮询。
     */
    private var watchRecordOn = false

    /**
     * `--ez probeDuration true`：连续采样 `recordStatus()`，专门搞清
     * `duration.current` 在**空闲**时到底是什么。
     *
     * 脚本：基线 30 s（每 2 s 一行）→ 开始录音 → 录 6 s（每 2 s）→ 停止 → 之后 20 s。
     * 如果空闲值随时间漂移 ⇒ 动态计数（剩余时间/计时器）；不动 ⇒ 静态值（上次时长等）。
     */
    private var probeDurationOn = false

    /** `--ez autoCapture true`：收到 Voice id=10 后自动发一次 id=4 拍照（验证复用两个 ID）。 */
    private var autoCaptureOn = false

    /**
     * `--ez selfCapture true`：**不等** `Voice id=10`，直接自造 ID 发 id=4 拍照。
     *
     * 用途：参考实现的两个 ID 都是调用方入参，若设备不校验归属，
     * 手机就能自己触发拍照（拍照回归不再需要人按触控板）。两轮：随机 ID → 再来一次。
     */
    private var selfCaptureOn = false

    /**
     * `--ez watchBattery true`：**什么都不发**，只挂 90 s 记录 `System id=97` 电池上报。
     *
     * 用途：确认这条是设备主动推的、以及推送节奏（收到时间戳直接看日志）。
     */
    private var watchBatteryOn = false

    /** `--ez captureRounds N`：selfCapture 拍几张（默认 3）。 */
    private var selfCaptureRounds = 3

    /** `--ez watchBatterySec N`：watchBattery 盯多久（默认 90 s）。 */
    private var watchBatterySec = 90

    /** `--ez tailSec N`：selfCapture 最后一张之后再多等多久（默认 8 s，用来收拍照后的尾巴）。 */
    private var tailSec = 8

    /**
     * `--ez probeSave true`：拍一张后每 300 ms 轮询媒体列表，看**照片什么时候在设备侧可见**。
     *
     * 已知结论：照片**会**进 `Media id=16` 列表（名字形如
     * `LLHDR_<yyyyMMddHHmmssSSS>_<W>x<H>_<n>.jpg`），只是要等一阵才出现；
     * 拍照后指示灯闪一次是**正常设计**（实测）。
     */
    private var probeSaveOn = false

    /**
     * `--ez enableAp true`：READY 后读一次 `wifiConfig()`，再发 `System id=88 EnableWiFiAP`。
     *
     * 用途：验证热点默认是关的、以及开起来之后能不能从 PC 连上。
     */
    private var enableApOn = false

    /**
     * `--ez albumExisting true`：**不弹窗**路径 —— 手机已经连上眼镜 AP（`adb shell cmd wifi
     * connect-network` 或用户手动连过）时，直接走默认路由读列表/下载。
     */
    private var albumExistingOn = false

    /**
     * `--ez albumProbe true`：`id=88` 拿凭据 → `AlbumClient` 入网 → 列表 → 下载第一张 heic 到
     * 应用缓存，打字节数/MD5。
     */
    private var albumProbeOn = false

    /** `--ez pinAp true`：试把热点凭据钉死（`System id=56`），验证能否走「一次批准」。 */
    private var pinApOn = false

    /** `--ez p2pAlbum true`：复刻参考实现「无感」路径 —— 手机当 P2P GO，眼镜当 client。 */
    private var p2pAlbumOn = false

    /** `--ez reportData true`：`System id=80` 基础状态上报 + `AudioVolume` 读音量。 */
    private var reportDataOn = false

    /** `--ei p2pHoldSec <n>`：建组后保活 n 秒，方便从 adb 侧检查 `p2p0`。 */
    private var p2pHoldSec = 0

    /** `--ez mediaThumb true`：验 `Media id=15/17` 那条「按 id 取缩略图」的路。 */
    private var mediaThumbOn = false

    /** `--ez thumbFull true`：`Media id=17` 里 `thumbnail=false`（原图实验，非参考实现用法）。 */
    private var thumbFullOn = false

    /** `--ez thumbSingle true`：只请求第一条，用来拿干净的「单文件分片流」。 */
    private var thumbSingleOn = false

    /** 语音上行统计：逐帧打印会 133 行/秒淹没日志，改成累计 + 每秒一行。 */
    private var audioFrames = 0
    private var audioBytes = 0L
    private var audioFirstAt = 0L
    private var audioLastAt = 0L
    private var audioLastLogAt = 0L
    private val audioSizes = LinkedHashMap<Int, Int>()
    private var audioFirstHex: String? = null

    /** `--ez audioDump true`：把 mSBC 字节原样落盘，供 ffmpeg 解码验证（真正的「收到语音」证据）。 */
    private var audioDumpOn = false
    private var audioFile: java.io.File? = null
    private var audioOut: java.io.OutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 相册验证时屏幕不能睡：`WifiNetworkSpecifier` 的系统弹窗要人工点，锁屏就点不到
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        spinner = findViewById(R.id.devices) as Spinner
        tokenInput = findViewById(R.id.token) as EditText
        stateView = findViewById(R.id.state) as TextView
        logView = findViewById(R.id.log) as TextView
        scroll = findViewById(R.id.scroll) as ScrollView

        (findViewById(R.id.connect) as Button).setOnClickListener { connect() }
        (findViewById(R.id.wear) as Button).setOnClickListener {
            val r = session?.requestWearStatus()
            log("requestWearStatus → $r")
        }
        (findViewById(R.id.close) as Button).setOnClickListener {
            session?.close()
            session = null
            log("close()")
        }

        // 直播自检：默认 mode=HOTSPOT（唯一实测通的通道），蓝牙档只为验 liveMode=4
        val modeSpinner = findViewById(R.id.liveMode) as Spinner
        modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            LiveStreaming.Mode.entries.map { "${it.name}(${it.wire})" + if (it == LiveStreaming.Mode.BLUETOOTH) " ⚠️未证" else "" },
        )
        val resSpinner = findViewById(R.id.liveRes) as Spinner
        resSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            LiveStreaming.Resolution.entries.map { "${it.name}(${it.wire})" },
        )
        resSpinner.setSelection(1) // P720

        (findViewById(R.id.liveQuery) as Button).setOnClickListener {
            log("queryLiveStream → ${session?.queryLiveStream()}")
        }
        (findViewById(R.id.liveStart) as Button).setOnClickListener {
            val url = (findViewById(R.id.liveUrl) as EditText).text.toString().trim()
            val mode = LiveStreaming.Mode.entries[modeSpinner.selectedItemPosition]
            val res = LiveStreaming.Resolution.entries[resSpinner.selectedItemPosition]
            log("startLive(mode=${mode.name}, res=${res.name}, url=$url) → " +
                "${session?.startLive(url, resolution = res, mode = mode)}")
        }
        (findViewById(R.id.liveStop) as Button).setOnClickListener {
            log("stopLive → ${session?.stopLive()}")
        }

        requestPermissionsIfNeeded()
        listBondedDevices()
        buildPanel()
        applyIntentExtras()
    }

    /**
     * 用 Intent 参数驱动，便于 adb 一条命令跑完一次冒烟：
     *
     * ```
     * am start -n com.miaiglasses.sample/.MainActivity \
     *   --es token <32 hex> --ez connect true --es liveUrl rtmp://… --ez startLive true
     * ```
     *
     * 为什么不靠点 UI：真机上的 EditText 焦点/输入法极易被吞，自动化不可复现。
     */
    private fun applyIntentExtras() {
        val tokenExtra = intent?.getStringExtra("token")
        if (!tokenExtra.isNullOrBlank()) tokenInput.setText(tokenExtra)
        intent?.getStringExtra("liveUrl")?.let {
            (findViewById(R.id.liveUrl) as EditText).setText(it)
        }
        intent?.getStringExtra("liveMode")?.let { name ->
            val i = LiveStreaming.Mode.entries.indexOfFirst { it.name.equals(name, true) || it.wire.toString() == name }
            if (i >= 0) (findViewById(R.id.liveMode) as Spinner).setSelection(i)
        }
        intent?.getStringExtra("liveRes")?.let { name ->
            val i = LiveStreaming.Resolution.entries.indexOfFirst { it.name.equals(name, true) || it.wire.toString() == name }
            if (i >= 0) (findViewById(R.id.liveRes) as Spinner).setSelection(i)
        }
        val wantConnect = intent?.getBooleanExtra("connect", false) == true
        val wantLive = intent?.getBooleanExtra("startLive", false) == true
        intent?.getStringExtra("probe")?.let { spec ->
            spec.split(";").mapNotNull { parseProbe(it.trim()) }.forEach { pendingProbes.addLast(it) }
        }
        audioDumpOn = intent?.getBooleanExtra("audioDump", false) == true
        queryAllOn = intent?.getBooleanExtra("queryAll", false) == true
        settingsWriteOn = intent?.getBooleanExtra("settingsWrite", false) == true
        autoCaptureOn = intent?.getBooleanExtra("autoCapture", false) == true
        selfCaptureOn = intent?.getBooleanExtra("selfCapture", false) == true
        watchBatteryOn = intent?.getBooleanExtra("watchBattery", false) == true
        selfCaptureRounds = intent?.getIntExtra("captureRounds", 3) ?: 3
        watchBatterySec = intent?.getIntExtra("watchBatterySec", 90) ?: 90
        tailSec = intent?.getIntExtra("tailSec", 8) ?: 8
        probeSaveOn = intent?.getBooleanExtra("probeSave", false) == true
        enableApOn = intent?.getBooleanExtra("enableAp", false) == true
        albumProbeOn = intent?.getBooleanExtra("albumProbe", false) == true
        pinApOn = intent?.getBooleanExtra("pinAp", false) == true
        albumExistingOn = intent?.getBooleanExtra("albumExisting", false) == true
        p2pAlbumOn = intent?.getBooleanExtra("p2pAlbum", false) == true
        p2pHoldSec = intent?.getIntExtra("p2pHoldSec", 0) ?: 0
        mediaThumbOn = intent?.getBooleanExtra("mediaThumb", false) == true
        thumbFullOn = intent?.getBooleanExtra("thumbFull", false) == true
        thumbSingleOn = intent?.getBooleanExtra("thumbSingle", false) == true
        reportDataOn = intent?.getBooleanExtra("reportData", false) == true
        autoEndVoiceOn = intent?.getBooleanExtra("autoEndVoice", false) == true
        endVoiceRawHex = intent?.getStringExtra("endVoiceRaw")?.replace(" ", "")
        verifySystemOn = intent?.getBooleanExtra("verifySystem", false) == true
        verifyRecordOn = intent?.getBooleanExtra("verifyRecord", false) == true
        watchRecordOn = intent?.getBooleanExtra("watchRecord", false) == true
        probeDurationOn = intent?.getBooleanExtra("probeDuration", false) == true
        if (wantConnect) {
            tokenInput.post {
                connect()
                if (wantLive) {
                    // 等 READY 再发直播命令；超时也照发，让拒绝原因进日志
                    tokenInput.postDelayed({ (findViewById(R.id.liveStart) as Button).performClick() }, 6000)
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            }
        }
    }

    private fun listBondedDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = try {
            adapter?.bondedDevices.orEmpty()
        } catch (e: SecurityException) {
            log("读已配对设备失败（缺 BLUETOOTH_CONNECT）：${e.message}")
            emptySet<BluetoothDevice>()
        }
        devices.clear()
        devices += bonded.sortedBy { it.name ?: it.address }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            devices.map { "${it.name ?: "?"}  ${it.address}" },
        )
    }

    private fun connect() {
        val token = parseToken(tokenInput.text.toString()) ?: run {
            Toast.makeText(this, "token 必须是 32 位 hex", Toast.LENGTH_SHORT).show()
            return
        }
        val device = devices.getOrNull(spinner.selectedItemPosition) ?: run {
            Toast.makeText(this, "先选一台已配对设备", Toast.LENGTH_SHORT).show()
            return
        }

        session?.close()
        log("open(${device.name} ${device.address})")
        val s = GlassesSession.open(
            this,
            device,
            token,
            SessionOptions(diagnostics = true),
        )
        s.setListener { event -> runOnUiThread { onEvent(event) } }
        session = s
    }

    /**
     * 能力面板：把**所有已实现的能力**做成按钮（真机上不再需要敲 adb 参数）。
     *
     * 面板插在日志区上方，每组一行标题 + 若干按钮；所有阻塞调用都丢到后台线程，
     * 结果统一进 [log]（同时进 logcat）。
     */
    private fun buildPanel() {
        val root = (findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as LinearLayout
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        fun title(text: String) {
            col.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 8, 0, 2)
                },
            )
        }

        fun row(vararg items: Pair<String, () -> Unit>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEach { (label, action) ->
                r.addView(
                    Button(this).apply {
                        text = label
                        textSize = 10f
                        setPadding(2, 0, 2, 0)
                        setOnClickListener { action() }
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
            }
            col.addView(r)
        }

        /** 需要会话的阻塞调用 → 后台线程。 */
        fun bg(name: String, block: (com.miaiglasses.sdk.GlassesSession) -> Unit) {
            val s = session
            if (s == null) {
                log("未连接：先点「连接」")
                return
            }
            Thread({ runCatching { block(s) }.onFailure { log("$name 失败：${it.javaClass.simpleName}: ${it.message}") } }, name)
                .start()
        }

        title("基础")
        row(
            "设备信息" to { bg("device-info") { s -> log("deviceInfo=${s.deviceInfo()}") } },
            "状态/电量" to { bg("device-status") { s -> log("deviceStatus=${s.deviceStatus()}") } },
            "读设置" to { bg("read-settings") { s -> log("systemSetting=${s.systemSetting()}") } },
        )
        row(
            "时间同步" to { bg("sync-time") { s -> log("syncTime=${s.syncTime()}") } },
            "基础上报(id=80)" to { bg("report-data") { s -> log("reportData=${s.requestReportDataAndWait()}") } },
            "读佩戴" to { bg("wear") { s -> log("wearStatus=${s.wearStatus()}") } },
        )

        title("拍摄 / 语音")
        row(
            "拍照" to { bg("capture") { s -> log("capturePhoto=${s.capturePhoto()}") } },
            "结束语音" to { bg("end-voice") { s -> log("endVoiceSession=${s.endVoiceSession()}") } },
            "录音状态" to { bg("record-status") { s -> log("recordStatus=${s.recordStatus()}") } },
        )
        row(
            "开始录音" to { bg("rec-start") { s -> log("startRecording=${s.startRecording()}") } },
            "停止录音" to { bg("rec-stop") { s -> log("stopRecording=${s.stopRecording()}") } },
            "媒体摘要" to { bg("media-summary") { s -> log("mediaSummary=${s.mediaSummary()}") } },
        )

        title("手势 / 设置写入")
        row(
            "单击=媒体" to { bg("music-1") { s -> log("setMusicControl(SINGLE)=${s.setMusicControl(MusicControlTap.SINGLE)}") } },
            "双击=媒体" to { bg("music-2") { s -> log("setMusicControl(DOUBLE)=${s.setMusicControl(MusicControlTap.DOUBLE)}") } },
            "拍照模式" to { bg("shoot") { s -> log("setShootingMode(SHORT_PRESS_PHOTO)=${s.setShootingMode(ShootingMode.SHORT_PRESS_PHOTO)}") } },
        )
        row(
            "录像模式" to { bg("record-mode") { s -> log("setRecordControl(DOUBLE_TAP)=${s.setRecordControl(RecordGesture.DOUBLE_TAP)}") } },
            "唤醒开" to { bg("wake-on") { s -> log("setVoiceWakeup(true)=${s.setVoiceWakeup(true)}") } },
            "唤醒关" to { bg("wake-off") { s -> log("setVoiceWakeup(false)=${s.setVoiceWakeup(false)}") } },
        )

        title("相册 / 音量（P2P 零弹窗）")
        row(
            "P2P 取图" to { p2pAlbum() },
            "缩略图(BLE)" to { mediaThumb() },
            "音量 +10%" to { volumeStep(+10) },
        )
        row(
            "音量 -10%" to { volumeStep(-10) },
            "AP 模式取图" to { albumExisting() },
            "列表(id=15)" to { bg("media-list") { s -> log("listMedia=${s.listMedia()}") } },
        )

        title("WiFi / 热点")
        row(
            "读 WiFi" to { bg("wifi-config") { s -> log("wifiConfig=${s.wifiConfig()}") } },
            "开热点" to { bg("ap-on") { s -> log("enableWifiAp=${s.enableWifiApAndWait()}") } },
            "关热点" to { bg("ap-off") { s -> log("disableWifiAp=${s.disableWifiAp("", "", "")}") } },
        )

        title("其它")
        row(
            "清空日志" to {
                logView.text = ""
            },
            "手表电量监听" to { watchBattery() },
            "电池上报监听" to { log("电量变化会以 battery … 打在这里") },
        )

        root.addView(scroll, root.childCount - 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))
    }

    /** 音量：走 `AudioManager`，AVRCP 由系统处理。 */
    private fun volumeStep(deltaPercent: Int) {
        val vol = com.miaiglasses.sdk.AudioVolume(applicationContext)
        val before = vol.getPercent()
        val target = (before + deltaPercent).coerceIn(0, 100)
        val ok = vol.setPercent(target)
        log("volume $before% → 目标 $target%（ok=$ok，实际 ${vol.getPercent()}%）")
    }

    private fun onEvent(event: GlassesEvent) {        when (event) {
            is GlassesEvent.StateChanged -> {
                stateView.text = "state: ${event.state}  (${event.reason})"
                log("state=${event.state} ${event.reason}")
                if (event.state == SessionState.READY) {
                    fireProbe()
                    if (queryAllOn) runAllQueries()
                    if (settingsWriteOn) runSettingsRoundTrip()
                    if (verifySystemOn) runSystemVerify()
                    if (verifyRecordOn) runRecordVerify()
                    if (watchRecordOn) watchRecord()
                    if (probeDurationOn) probeDuration()
                    if (selfCaptureOn) selfCapture()
                    if (watchBatteryOn) watchBattery()
                    if (probeSaveOn) probeSave()
                    if (enableApOn) enableAp()
                    if (albumProbeOn) albumProbe()
                    if (albumExistingOn) albumExisting()
                    if (pinApOn) pinAp()
                    if (p2pAlbumOn) p2pAlbum()
                    if (mediaThumbOn) mediaThumb()
                    if (reportDataOn) reportData()
                }
            }
            is GlassesEvent.MisSpecEvent -> {
                // 每次按键一条；packet 里只有递增计数，按键类型不在这条报文里
                log("mis ${event} raw=${event.misSpec.raw}")
            }
            is GlassesEvent.CaptureAlertEvent -> log("alert ${event} raw=${event.alert.raw}")
            is GlassesEvent.WearChanged -> log("wear ${event} raw=${event.status.raw}")
            is GlassesEvent.BatteryChanged -> log(
                "battery ${event.status.batteryPercent}% charge=${event.status.chargeStatus} " +
                    "info=${event.status.chargeInfoState}/${event.status.chargeInfoTimestamp}",
            )
            is GlassesEvent.WifiApChanged -> log(
                "wifiAp code=${event.info.code} ssid=${event.info.ssid} " +
                    "psk=${event.info.password} gw=${event.info.gateway}",
            )
            is GlassesEvent.WifiP2pChanged -> log(
                "wifiP2p code=${event.result.code} ip=${event.result.ip} " +
                    "taskId=${event.result.taskId}",
            )
            is GlassesEvent.MassFile -> {
                val f = event.file
                val out = java.io.File(cacheDir, f.identifier.ifEmpty { "mass.bin" })
                runCatching { out.writeBytes(f.bytes) }
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size, o)
                log(
                    "massFile ${f.identifier} type=${f.mediaType} ${f.bytes.size}B " +
                        "slices=${f.slices} trailer=${hex(f.trailer)} " +
                        "head=${hex(f.bytes.copyOf(minOf(8, f.bytes.size)))} " +
                        "decoded=${o.outWidth}x${o.outHeight} → ${out.absolutePath}",
                )
            }
            is GlassesEvent.RecordStatusEvent -> {
                // 双指录音由眼镜自己触发（手机没有触发权），这条上报是唯一观测点
                log(
                    "record status=${event.status.status} type=${event.status.mediaFile?.type} " +
                        "size=${event.status.mediaFile?.size} dur=${event.status.duration?.current} " +
                        "ts=${event.status.mediaFile?.timestamp} raw=${event.status.raw}",
                )
            }
            is GlassesEvent.Control -> log(
                "control ${event.decision} | type=${event.packet.type} id=${event.packet.id} " +
                    "len=${event.packet.payload.size} hex=" +
                    // 支付链路的载荷里有可读 JSON（action 等），实验期全量打出来
                    if (event.packet.type == 20) hexFull(event.packet.payload) else hex(event.packet.payload),
            )
            is GlassesEvent.Audio -> onAudio(event.data)
            is GlassesEvent.Image -> {
                // JPEG 头解尺寸：确认 SPP 上拿到的是 576×432 小图而不是原图
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(event.jpeg, 0, event.jpeg.size, o)
                log(
                    "image ${event.jpeg.size}B ${o.outWidth}x${o.outHeight} " +
                        "head=${hex(event.jpeg.copyOfRange(0, minOf(4, event.jpeg.size)))}",
                )
            }
            is GlassesEvent.ReportDataReceived -> log(
                "reportData type=${event.result.type} status=${event.result.status} " +
                    "ok=${event.result.isOk}",
            )
            is GlassesEvent.LiveStateChanged -> log(
                "live code=${event.state.code} reason=${event.state.reason}",
            )
            is GlassesEvent.Error -> log("ERROR ${event.message}")
            is GlassesEvent.Voice -> {
                log("voice ${event.event}")
                if (autoCaptureOn && event.event is VoiceEvent.OpenDevice) {
                    tokenInput.postDelayed({ doCapture() }, 500)
                }
                if (autoEndVoiceOn && event.event is VoiceEvent.OpenDevice) {
                    // 会话起来后等 4 s（让音频先流一段做基线）再发结束帧
                    tokenInput.postDelayed({ doEndVoice() }, 4_000)
                }
            }
            is GlassesEvent.Diagnostic -> log("diag ${event.message}")
        }
    }

    /** READY 后按顺序把排队的探针命令发完，每条间隔 1.5 s（等上一条回包，便于对号）。 */
    private fun fireProbe() {
        if (pendingProbes.isEmpty()) return
        val (type, id, body) = pendingProbes.removeFirst()
        val r = session?.sendWear(type, id, body)
        log("probe send type=$type id=$id len=${body.size} → $r")
        if (pendingProbes.isNotEmpty()) {
            tokenInput.postDelayed({ fireProbe() }, 1_500)
        }
    }

    /**
     * 把 7 个查询在这台设备上全跑一遍，结果打到日志。
     *
     * 必须放在**后台线程**：这些方法是阻塞的（等回包），在 UI 线程会 ANR。
     */
    private fun runAllQueries() {
        Thread({
            val s = session ?: return@Thread
            fun put(name: String, r: Any) = runOnUiThread { log("query $name = $r") }
            put("deviceInfo", s.deviceInfo())
            put("deviceStatus", s.deviceStatus())
            put("mediaSummary", s.mediaSummary())
            put("listMedia", s.listMedia())
            put("recordStatus", s.recordStatus())
            put("wifiConfig", s.wifiConfig())
            put("systemSetting", s.systemSetting())
        }, "query-all").start()
    }

    /**
     * 写设置的读写回环：读原值 → 改 → 读回 → 改回 → 再读回。
     *
     * 不动用户设置：无论中途是否失败，最后都会把 `musicControl` 写回原值。
     */
    private fun runSettingsRoundTrip() {
        Thread({
            val s = session ?: return@Thread
            fun put(name: String, r: Any) = runOnUiThread { log("settings $name = $r") }
            val before = s.systemSetting()
            put("读原值", before)
            val original = (before as? com.miaiglasses.core.query.QueryResult.Ok)?.value?.value(8, 4)
            if (original == null) {
                put("拿不到 f8.f4，放弃", before)
                return@Thread
            }
            val target = if (original == 1L) 2 else 1L
            put("写 musicControl=$target", s.setMusicControl(MusicControlTap.entries.first { it.wire == target.toInt() }))
            put("读回", s.systemSetting().let { r ->
                (r as? com.miaiglasses.core.query.QueryResult.Ok)?.value?.value(8, 4) ?: r
            })
            put("写回原值 $original", s.setMusicControl(MusicControlTap.entries.first { it.wire == original.toInt() }))
            put("最终读回", s.systemSetting().let { r ->
                (r as? com.miaiglasses.core.query.QueryResult.Ok)?.value?.value(8, 4) ?: r
            })
        }, "settings-rt").start()
    }

    /**
     * 发一次「会话结束」：`Voice id=5{f1=0}`。日志里带发送前后的音频帧数，
     * 用来判断眼镜是不是**立刻**停止采集。
     */
    private fun doEndVoice() {
        val s = session ?: return
        log("endVoice 发送前 audioFrames=$audioFrames bytes=$audioBytes")
        val raw = endVoiceRawHex
        if (raw != null) {
            val body = hexToBytes(raw)
            if (body == null) {
                log("endVoiceRaw 解析失败：$raw")
                return
            }
            log("endVoice raw(len=${body.size}) → ${s.sendWear(14, 5, body)}")
        } else {
            log("endVoice → ${s.endVoiceSession()}")
        }
        log("endVoice 已发（audioFrames=$audioFrames）继续观察 12 s")
        tokenInput.postDelayed({ log("endVoice 12s 后 audioFrames=$audioFrames bytes=$audioBytes") }, 12_000)
    }


    /**
     * 电量 + 时间同步验证。
     *
     * 时间同步用「拨快 1 小时」验证，不是直接发正确时间 —— 设备时钟本来就可能是对的，
     * 发正确时间什么也证明不了。最后无论成败都把真实时间写回去。
     */
    private fun runSystemVerify() {
        Thread({
            val s = session ?: return@Thread
            fun put(name: String, r: Any) = runOnUiThread { log("verify $name = $r") }
            fun deviceClock(): Long {
                val r = s.recordStatus()
                return (r as? com.miaiglasses.core.query.QueryResult.Ok)?.value?.mediaFile?.timestamp ?: 0L
            }

            put("电量", s.deviceStatus())
            val phone = System.currentTimeMillis()
            val t0 = deviceClock()
            put("设备时钟 t0", "$t0（本机 $phone，差 ${t0 - phone} ms）")

            val skewMs = 3_600_000L
            put("syncTime 拨快1h", s.syncTime(phone + skewMs))
            Thread.sleep(1_500)
            val t1 = deviceClock()
            put("设备时钟 t1", "$t1（相对 t0 变化 ${t1 - t0} ms，期望≈${skewMs + 1_500}）")

            put("syncTime 写回真实", s.syncTime(phone))
            Thread.sleep(1_500)
            val t2 = deviceClock()
            put("设备时钟 t2", "$t2（相对本机 $phone 差 ${t2 - phone} ms）")
            put("电量收尾", s.deviceStatus())
        }, "system-verify").start()
    }

    /**
     * 录音链路验证：**只读状态 + 试探 `Media id=20`**。
     *
     * 要回答两个问题：
     * 1. `id=20` 的回执到底走不走 id=20（我们按同 id 期待）；
     * 2. 手机能不能触发录音 —— 静态分析里 `开始录音` 无调用点，推测**不能**
     *    （双指由眼镜自己判定），所以这里发出去大概率只有回执、没有实际录音。
     *
     * 真正的判据是 `recordStatus()` 的 `duration.current` 与 `mediaFile.timestamp` 有没有变。
     */
    private fun runRecordVerify() {
        Thread({
            val s = session ?: return@Thread
            fun put(name: String, r: Any) = runOnUiThread { log("record $name = $r") }

            put("状态#1(基线)", s.recordStatus())
            Thread.sleep(1_000)

            put("佩戴(93) 基线", s.wearStatus())
            put("startRecording(id=20 action=0)", s.startRecording())
            // 录音期间连采几帧：既确认 status 语义，也确认文件在长大
            for (i in 1..4) {
                Thread.sleep(1_500)
                put("状态#2.$i(录音中)", s.recordStatus())
            }

            put("stopRecording(id=20 action=1)", s.stopRecording())
            Thread.sleep(1_500)
            put("状态#3(收尾)", s.recordStatus())
            put("==== 结束 ====", "")
        }, "record-verify").start()
    }

    /**
     * 只读轮询：每 2 s 一次 `recordStatus()`，用来观测**设备侧（双指）触发**的录音。
     *
     * 手机不发任何命令，所以这里出现的状态变化只可能来自眼镜自己。
     */
    private fun watchRecord() {
        Thread({
            val s = session ?: return@Thread
            var lastKey = ""
            while (session === s) {
                val r = s.recordStatus()
                val st = (r as? com.miaiglasses.core.query.QueryResult.Ok)?.value
                if (st != null) {
                    val key = "${st.status}/${st.mediaFile?.id}"
                    if (key != lastKey) {
                        lastKey = key
                        runOnUiThread {
                            log(
                                "watch status=${st.status} rec=${st.isRecording} " +
                                    "type=${st.mediaFile?.type} size=${st.mediaFile?.size} " +
                                    "dur=${st.duration?.current} id=${st.mediaFile?.id}",
                            )
                        }
                    }
                } else {
                    runOnUiThread { log("watch $r") }
                }
                Thread.sleep(2_000)
            }
        }, "watch-record").start()
    }

    /**
     * `duration.current` 语义探针：连续采样，看它是动态计数还是静态值。
     *
     * 基线 30 s → 开始 → 录 6 s → 停止 → 再采 20 s。每 2 s 一行，全量打印。
     */
    private fun probeDuration() {
        Thread({
            val s = session ?: return@Thread
            fun sample(tag: String) {
                val st = (s.recordStatus() as? com.miaiglasses.core.query.QueryResult.Ok)?.value
                runOnUiThread {
                    if (st == null) {
                        log("dur $tag 查询失败")
                    } else {
                        log(
                            "dur $tag cur=${st.duration?.current} max=${st.duration?.max} " +
                                "status=${st.status} size=${st.mediaFile?.size} " +
                                "fileDur=${st.mediaFile?.duration} ts=${st.mediaFile?.timestamp}",
                        )
                    }
                }
            }

            repeat(15) { i ->
                sample("基线+${i * 2}s")
                Thread.sleep(2_000)
            }

            runOnUiThread { log("dur ==== 开始录音 ====") }
            runOnUiThread { log("dur start = ${s.startRecording()}") }
            repeat(3) { i ->
                Thread.sleep(2_000)
                sample("录音中+${(i + 1) * 2}s")
            }

            runOnUiThread { log("dur stop = ${s.stopRecording()}") }
            repeat(10) { i ->
                Thread.sleep(2_000)
                sample("停止后+${(i + 1) * 2}s")
            }
            runOnUiThread { log("dur ==== 结束 ====") }
        }, "probe-duration").start()
    }

    /** 触发一次拍照（id=4）。结果不是返回值 —— 图片走 MASS，以 `Image` 事件到达。 */
    private fun doCapture() {
        val s = session ?: return
        log("capture canCapture=${s.canCapturePhoto}")
        log("capture → ${s.capturePhoto()}")
    }

    /**
     * 安卓侧端到端：`id=88` 拿凭据 → 入 AP → 列表 → 下载第一张 heic 到缓存。
     *
     * 判据：日志出现 `album 下载 …B` 且字节数与列表里的 size 一致，头是 `ftypheic`。
     */
    private fun albumProbe() {
        Thread({
            val s = session ?: return@Thread
            val ap = (s.enableWifiApAndWait() as? com.miaiglasses.core.query.QueryResult.Ok)?.value
            runOnUiThread { log("album AP=$ap") }
            if (ap == null) return@Thread

            val client = com.miaiglasses.sdk.AlbumClient(applicationContext)
            try {
                val t0 = System.currentTimeMillis()
                runOnUiThread { log("album 请求入网（系统弹窗需人工点「连接」，等 120 s）") }
                val ok = client.connect(ap.ssid, ap.password, timeoutMs = 120_000)
                runOnUiThread { log("album connect=$ok（${System.currentTimeMillis() - t0}ms）") }
                if (!ok) return@Thread

                val shots = client.filelists()
                runOnUiThread {
                    log("album filelists=${shots.size} 条")
                    shots.forEach { e ->
                        log(
                            "album  · ${e.mimeType} ${e.size}B ${e.identifier}" +
                                " mod=${e.fileModifiedMillis}",
                        )
                    }
                }

                // 增量：拿最后一条的时间戳当水位，应当只回它自己（含）之后的
                val watermark = shots.maxByOrNull { it.fileModifiedMillis }?.fileModifiedMillis ?: 0L
                val delta = client.filelists(watermark)
                runOnUiThread { log("album 增量（>= $watermark）=${delta.size} 条") }

                // 挑全库最大的条目当「大文件流式」验证对象（录像是 mp4，照片是多曝光 heic）
                val biggest = shots.maxByOrNull { it.size } ?: return@Thread
                val files = client.files(biggest)
                val primary = com.miaiglasses.core.album.AlbumApi.pickPrimary(files)
                runOnUiThread {
                    log("album 最大条目 ${biggest.identifier}（${biggest.mimeType} ${biggest.size}B，内部 ${files.size} 个）")
                    log("album 主文件=${primary?.url} ${primary?.size}B")
                }
                if (primary == null) return@Thread

                val out = java.io.File(cacheDir, "album_primary.bin")
                val t1 = System.currentTimeMillis()
                var lastPct = -1
                val n = client.download(primary, out) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct / 25 != lastPct / 25) {
                        lastPct = pct
                        runOnUiThread { log("album 进度 $done/$total（$pct%）") }
                    }
                }
                val ms = System.currentTimeMillis() - t1
                val head = out.inputStream().use { ins -> ByteArray(12).also { ins.read(it) } }
                val hexHead = head.joinToString(" ") { String.format(Locale.US, "%02x", it) }
                val md5 = java.security.MessageDigest.getInstance("MD5").run {
                    out.inputStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val r = ins.read(buf); if (r <= 0) break; update(buf, 0, r) }
                    }
                    digest().joinToString("") { String.format(Locale.US, "%02x", it) }
                }
                runOnUiThread {
                    log("album 下载 ${n}B / 声明 ${primary.size}B，${ms}ms，头=$hexHead")
                    log("album md5=$md5 落盘=$out")
                }

                // DELETE 只探路由：用一个不存在的 id，绝不删真文件
                val okDel = client.delete("sdk-probe-does-not-exist.jpg")
                runOnUiThread { log("album DELETE（不存在 id）=$okDel") }

                // 录像：video/folder 的子清单里应有 mp4（可能还有 gyro 边车）
                shots.lastOrNull { it.mimeType.startsWith("video/") }?.let { v ->
                    val vf = client.files(v)
                    runOnUiThread {
                        log("album 录像条目 ${v.identifier} ${v.size}B，内部 ${vf.size} 个")
                        vf.forEach { log("album   · ${it.size}B ${it.url}") }
                    }
                    val media = vf.firstOrNull { it.url.endsWith(".mp4") } ?: vf.maxByOrNull { it.size }
                    if (media != null) {
                        val out2 = java.io.File(cacheDir, "album_video.bin")
                        val t2 = System.currentTimeMillis()
                        val n2 = client.download(media, out2)
                        val ms2 = System.currentTimeMillis() - t2
                        val h2 = out2.inputStream().use { ins -> ByteArray(12).also { ins.read(it) } }
                        runOnUiThread {
                            log(
                                "album 录像下载 ${n2}B / 声明 ${media.size}B，${ms2}ms，头=" +
                                    h2.joinToString(" ") { String.format(Locale.US, "%02x", it) } +
                                    " ascii=" + String(h2.copyOfRange(4, 12), Charsets.ISO_8859_1),
                            )
                        }
                    }
                }

                // 录音：audio/ogg 条目的 size 报 0，真文件在子清单里
                shots.lastOrNull { it.mimeType == "audio/ogg" }?.let { a ->
                    val af = client.files(a)
                    runOnUiThread {
                        log("album 录音条目 ${a.identifier}（列表 size=${a.size}B），内部 ${af.size} 个")
                        af.forEach { log("album   · ${it.size}B ${it.url}") }
                    }
                    val ogg = af.maxByOrNull { it.size }
                    if (ogg != null) {
                        val out3 = java.io.File(cacheDir, "album_audio.bin")
                        val t3 = System.currentTimeMillis()
                        val n3 = client.download(ogg, out3)
                        val ms3 = System.currentTimeMillis() - t3
                        val h3 = out3.inputStream().use { ins -> ByteArray(4).also { ins.read(it) } }
                        runOnUiThread {
                            log(
                                "album 录音下载 ${n3}B / 声明 ${ogg.size}B，${ms3}ms，头=" +
                                    String(h3, Charsets.ISO_8859_1),
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { log("album 失败：${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                client.close()
                runOnUiThread { log("album 断开 AP") }
            }
        }, "album-probe").start()
    }

    /**
     * 不弹窗路径：手机已经连上眼镜 AP 时直接读列表 + 下一个小文件，验证默认路由可用。
     */
    private fun albumExisting() {
        Thread({
            val client = com.miaiglasses.sdk.AlbumClient(applicationContext)
            try {
                val t0 = System.currentTimeMillis()
                val ok = client.connectExisting()
                runOnUiThread { log("albumExisting connect=${ok}（${System.currentTimeMillis() - t0}ms，无弹窗）") }
                if (!ok) return@Thread
                val shots = client.filelists()
                runOnUiThread { log("albumExisting filelists=${shots.size} 条") }
                val e = shots.firstOrNull { it.isFolder } ?: return@Thread
                val files = client.files(e)
                val f = com.miaiglasses.core.album.AlbumApi.pickPrimary(files) ?: return@Thread
                val out = java.io.File(cacheDir, "existing.bin")
                val t1 = System.currentTimeMillis()
                val n = client.download(f, out)
                runOnUiThread {
                    log("albumExisting 下载 ${n}B / 声明 ${f.size}B，${System.currentTimeMillis() - t1}ms")
                }
            } catch (e: Throwable) {
                runOnUiThread { log("albumExisting 失败：${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                client.close()
            }
        }, "album-existing").start()
    }

    /**
     * `--ez p2pAlbum true`：**参考实现「无感」路径复刻** —— 手机当 P2P GO 自建组（静默无弹窗），
     * 发 `System id=99` 让眼镜加入，拿回包里的眼镜 IP，再在这个 p2p 网络上拉文件列表。
     */
    private fun p2pAlbum() {
        Thread({
            val s = session ?: return@Thread
            val owner = com.miaiglasses.sdk.P2pGroupOwner(applicationContext)
            try {
                if (!owner.isSupported) {
                    runOnUiThread { log("p2pAlbum ❌ ${owner.status}（${owner.reason}）") }
                    return@Thread
                }
                val t0 = System.currentTimeMillis()
                val group = owner.create()
                runOnUiThread {
                    if (group == null) {
                        log("p2pAlbum ❌ createGroup 失败：${owner.reason}")
                    } else {
                        log(
                            "p2pAlbum createGroup ✅ ${group.ssid} / ${group.passphrase} / " +
                                "${group.frequency}MHz / owner=${group.ownerAddress} " +
                                "（${System.currentTimeMillis() - t0}ms，network=${group.network != null}）",
                        )
                        log("p2pAlbum 网络：${owner.describeNetworks()}")
                    }
                }
                if (group == null) return@Thread

                if (p2pHoldSec > 0) {
                    runOnUiThread { log("p2pAlbum 保活 ${p2pHoldSec}s（期间可从 adb 检查 p2p0）") }
                    Thread.sleep(p2pHoldSec * 1000L)
                    runOnUiThread { log("p2pAlbum 保活结束，网络：${owner.describeNetworks()}") }
                }

                val t1 = System.currentTimeMillis()
                val r = s.enableWifiP2pGcAndWait(group.ssid, group.passphrase, group.frequency)
                val res = (r as? com.miaiglasses.core.query.QueryResult.Ok)?.value
                runOnUiThread {
                    log("p2pAlbum id=99 → $r（${System.currentTimeMillis() - t1}ms）")
                }
                if (res == null || !res.isOk) {
                    runOnUiThread { log("p2pAlbum ❌ 眼镜没加入组：$res") }
                    return@Thread
                }
                val net = group.network
                runOnUiThread { log("p2pAlbum 建组后网络：${owner.describeNetworks()}") }
                val client = com.miaiglasses.sdk.AlbumClient(applicationContext)
                try {
                    val t2 = System.currentTimeMillis()
                    // 参考实现的做法不绑定 Network（P2P 分支里没有 bindProcessToNetwork），
                    // 先按默认路由试；不行再由宿主显式 bindTo(p2p Network)。
                    val ok = client.connectP2pDefaultRoute(res.ip)
                    runOnUiThread {
                        log(
                            "p2pAlbum HTTP(默认路由) http://${res.ip}:8080 connect=$ok" +
                                "（${System.currentTimeMillis() - t2}ms，net=${net != null}）",
                        )
                    }
                    if (!ok) return@Thread
                    val shots = client.filelists()
                    runOnUiThread { log("p2pAlbum filelists=${shots.size} 条") }
                    val e = shots.firstOrNull { it.isFolder } ?: return@Thread
                    val f = com.miaiglasses.core.album.AlbumApi.pickPrimary(client.files(e)) ?: return@Thread
                    val out = java.io.File(cacheDir, "p2p.bin")
                    val t3 = System.currentTimeMillis()
                    val n = client.download(f, out)
                    val head = ByteArray(8)
                    val got = java.io.FileInputStream(out).use { it.read(head) }
                    runOnUiThread {
                        log(
                            "p2pAlbum 下载 ${n}B / 声明 ${f.size}B，" +
                                "${System.currentTimeMillis() - t3}ms，head=${hex(head.copyOf(got))}",
                        )
                    }
                } finally {
                    client.close()
                }
            } catch (e: Throwable) {
                runOnUiThread { log("p2pAlbum 失败：${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                owner.close()
                runOnUiThread { log("p2pAlbum 组已解散") }
            }
        }, "p2p-album").start()
    }

    /**
     * `--ez reportData true`：`System id=80` 基础状态上报（空载荷）等回包；
     * 顺带把的 `AudioVolume` 读一遍（前划/后划走的是 AVRCP，不在 SPP 上）。
     */
    private fun reportData() {
        Thread({
            val s = session ?: return@Thread
            val vol = com.miaiglasses.sdk.AudioVolume(applicationContext)
            runOnUiThread {
                log("reportData AudioVolume supported=${vol.isSupported} " +
                    "step=${vol.getStep()} percent=${vol.getPercent()}%")
            }
            val t0 = System.currentTimeMillis()
            val r = s.requestReportDataAndWait()
            runOnUiThread { log("reportData id=80 → $r（${System.currentTimeMillis() - t0}ms）") }
        }, "report-data").start()
    }

    /**
     * `--ez mediaThumb true`：验「BLE 上按 id 取缩略图」这条路（`Media id=15/17/18/19`）。
     *
     * 步骤：`id=15` 取列表（回包 id=16）→ 取第一条的 identifier → `id=17`（参考实现）
     * → 观察 8 s 内回来的东西是 `Media` 回包还是 MASS 帧。加 `--ez thumbFull true` 则试
     * `thumbnail=false`（想要原图，属实验）。
     */
    private fun mediaThumb() {
        Thread({
            val s = session ?: return@Thread
            val r = s.listMedia()
            val list = (r as? com.miaiglasses.core.query.QueryResult.Ok)?.value
            runOnUiThread { log("mediaThumb listMedia=$r") }
            if (list == null) return@Thread
            runOnUiThread {
                log("mediaThumb 条目 ${list.files.size}/${list.entries.size}（解析/原始）")
                list.files.take(5).forEach {
                    log("  ${it.kind} ${it.identifier} type=${it.type} size=${it.size} ts=${it.timestamp}")
                }
            }
            val first = list.files.firstOrNull() ?: return@Thread
            val targets = if (thumbSingleOn) listOf(first) else list.files
            runOnUiThread {
                log("mediaThumb → id=17 ${first.identifier} thumbnail=${!thumbFullOn}")
                log("mediaThumb send=${s.requestMediaFile(first.identifier, thumbnail = !thumbFullOn)}")
            }
            // 逐条再试一遍（ident 类型不同：照片缩略图 18 923 B、音频缩略图 7 641 B）
            targets.drop(1).take(3).forEach {
                Thread.sleep(1_500)
                runOnUiThread {
                    log("mediaThumb → id=17 ${it.identifier} → " +
                        s.requestMediaFile(it.identifier, thumbnail = !thumbFullOn))
                }
            }
            if (!thumbSingleOn) {
                Thread.sleep(1_500)
                runOnUiThread {
                    log(
                        "mediaThumb → id=18 批量 ${list.files.size} 条 → " +
                            s.requestMediaFileList(list.files.map { it.identifier }, !thumbFullOn),
                    )
                }
            }
            // 12 s 内把回来的东西都打出来（MASS / Image / Control 都会进 onEvent）
            for (i in 1..12) {
                Thread.sleep(1_000)
                runOnUiThread { log("mediaThumb tail +${i}s") }
            }
            runOnUiThread { log("mediaThumb 结束（回显见上面的 image/control/mass 日志）") }
        }, "media-thumb").start()
    }

    /**
     * `--ez pinAp true`：**决定性实验** —— 先用 `System id=56 SetWiFIConfig` 把热点凭据设成固定值，
     * 再发 `id=88`，看回包里的密码是不是我们设的那个。
     *
     * 若相同 ⇒ 热点凭据可钉死 ⇒ `WifiNetworkSuggestion` 能做到「批准一次、以后自动连」；
     * 若仍随机 ⇒ 只能每次用 `WifiNetworkSpecifier` 让用户点一下确认。
     */
    private fun pinAp() {
        Thread({
            val s = session ?: return@Thread
            val ssid = "GlassesSDK-AP"
            val pwd = "glasses1234"
            runOnUiThread { log("pinAp setWifiConfig(ssid=$ssid, psk=$pwd) → ${s.setWifiConfig(ssid, pwd)}") }
            Thread.sleep(1_500)
            val ap = (s.enableWifiApAndWait() as? com.miaiglasses.core.query.QueryResult.Ok)?.value
            runOnUiThread { log("pinAp 之后的 id=88 回包=$ap") }
            runOnUiThread {
                log(
                    if (ap != null && ap.ssid == ssid && ap.password == pwd) {
                        "pinAp ✅ 凭据被采纳（可走 WifiNetworkSuggestion 一次批准）"
                    } else {
                        "pinAp ❌ 设备没采纳我们设的凭据"
                    },
                )
            }
        }, "pin-ap").start()
    }

    /**
     * 读 `wifiConfig()` → 发 `System id=88 EnableWiFiAP`，然后在 60 s 内每 5 s 复读一次配置，
     * 看设备有没有回报热点起来。
     */
    private fun enableAp() {
        Thread({
            val s = session ?: return@Thread
            val cfg = s.wifiConfig()
            runOnUiThread { log("enableAp 配置=$cfg") }
            runOnUiThread { log("enableAp → ${s.enableWifiAp()}") }
            for (i in 1..12) {
                Thread.sleep(5_000)
                if (session !== s) return@Thread
                val again = s.wifiConfig()
                runOnUiThread { log("enableAp +${i * 5}s 配置=$again") }
            }
            runOnUiThread { log("enableAp 结束") }
        }, "enable-ap").start()
    }

    /**
     * 拍一张后每 300 ms 轮询媒体列表，量「照片在设备侧何时可见」。
     *
     * 实测结论：照片**不进** `Media id=14/15`（summary 与列表 25 s 都不变），要拿落盘时刻
     * 只能走 HTTP 8080。拍照后指示灯闪一次是正常设计，见 `实测`。
     */
    private fun probeSave() {
        Thread({
            val s = session ?: return@Thread
            fun summary(): String {
                val r = s.mediaSummary() as? com.miaiglasses.core.query.QueryResult.Ok
                return r?.value?.entries?.joinToString(",") { "${it.kind}:${it.count}" } ?: "?"
            }
            fun lastEntry(): String {
                val r = s.listMedia() as? com.miaiglasses.core.query.QueryResult.Ok
                return r?.value?.entries?.lastOrNull() ?: "?"
            }
            val before0 = summary()
            val last0 = lastEntry()
            runOnUiThread { log("probeSave 拍前 summary=$before0 last=$last0") }
            runOnUiThread { log("probeSave capture → ${s.capturePhoto()}") }
            val t0 = System.currentTimeMillis()
            var last = last0
            while (System.currentTimeMillis() - t0 < 25_000) {
                Thread.sleep(300)
                val now = lastEntry()
                if (now != last) {
                    val ms = System.currentTimeMillis() - t0
                    runOnUiThread { log("probeSave +${ms}ms 列表变化 last=$now") }
                    last = now
                }
            }
            runOnUiThread { log("probeSave 结束 summary=${summary()}") }
        }, "probe-save").start()
    }

    /**
     * 长时间盯 `System id=97`：每 30 s 轮询一次 `deviceStatus()` 记下当前电量，
     * 这样任何一次推送都能和「电量到底变没变」对上。
     *
     * 时长由 `--ez watchBatterySec N`（默认 90 s）控制。
     */
    private fun watchBattery() {
        Thread({
            val s = session ?: return@Thread
            val total = watchBatterySec
            var elapsed = 0
            while (elapsed < total) {
                Thread.sleep(30_000)
                elapsed += 30
                if (session !== s) return@Thread
                val st = (s.deviceStatus() as? com.miaiglasses.core.query.QueryResult.Ok)?.value
                runOnUiThread {
                    log("watchBattery +${elapsed}s 轮询=${st?.batteryPercent}%/charge=${st?.chargeStatus}")
                }
            }
            runOnUiThread { log("watchBattery 结束") }
        }, "watch-battery").start()
    }

    /**
     * 自造 ID 触发拍照，每轮间隔 8 s。
     *
     * 奇数轮 `capturePhotoWithIds`（ID 完全自造），偶数轮 `capturePhoto()`（引擎自造兜底）。
     *
     * 判据：日志出现 `MASS 图片交付 …B（声明 …B，truncated=false）` + `image …B WxH`。
     * 轮数由 `--ez captureRounds N`（默认 3）控制。
     */
    private fun selfCapture() {
        Thread({
            val s = session ?: return@Thread
            val rnd = java.security.SecureRandom()
            for (round in 1..selfCaptureRounds) {
                val requestId = ByteArray(16).also { rnd.nextBytes(it) }
                val txn = requestId.joinToString("") { "%02x".format(it) }
                val salt = ByteArray(6).also { rnd.nextBytes(it) }
                runOnUiThread {
                    if (round == 1) log("selfCapture#1 canCapture=${s.canCapturePhoto} txn=$txn")
                    log(
                        if (round % 2 == 1) "selfCapture#$round → ${s.capturePhotoWithIds(txn, requestId, salt)}"
                        else "selfCapture#$round → ${s.capturePhoto()}",
                    )
                }
                if (round == selfCaptureRounds) {
                    // 尾巴里每秒一行心跳：把「第几秒闪的灯」直接对齐到日志，肉眼可数
                    for (t in 1..tailSec) {
                        Thread.sleep(1_000)
                        runOnUiThread { log("tail +${t}s") }
                    }
                } else {
                    Thread.sleep(8_000)
                }
            }
            runOnUiThread { log("selfCapture 结束") }
        }, "self-capture").start()
    }

    /**
     * 语音上行累计统计。
     *
     * 基线（`VOICE-FRAME-FORMAT.md`）：mSBC **57 B/帧**，MASS 包 = 12 B 头 + 10×57 = **570 B**，
     * 16 kHz mSBC 每帧 7.5 ms ⇒ **133.3 帧/秒 ≈ 7.6 KB/s**。真机对不上这个量级就是有问题。
     */
    private fun onAudio(data: ByteArray) {
        val now = System.currentTimeMillis()
        if (audioFrames == 0) {
            audioFirstAt = now
            audioFirstHex = hex(data)
            if (audioDumpOn) openAudioDump()
        }
        audioFrames++
        audioBytes += data.size
        audioLastAt = now
        audioSizes[data.size] = (audioSizes[data.size] ?: 0) + 1
        runCatching { audioOut?.write(data) } // 原样拼接：57 B mSBC 帧首尾相接
        if (now - audioLastLogAt >= 1_000) {
            audioLastLogAt = now
            log("audio ${audioSummary()}")
        }
    }

    private fun openAudioDump() {
        runCatching {
            val f = java.io.File(getExternalFilesDir(null), "audio-${System.currentTimeMillis()}.sbc")
            audioFile = f
            audioOut = java.io.BufferedOutputStream(java.io.FileOutputStream(f))
            log("audio dump → ${f.absolutePath}")
        }.onFailure { log("audio dump 打不开：${it.message}") }
    }

    /** 音频时长按 mSBC 帧算：每帧 57 B、7.5 ms（16 kHz / 120 samples）。 */
    private fun audioSummary(): String {
        val ms = (audioLastAt - audioFirstAt).coerceAtLeast(1)
        val mSbcFrames = audioBytes / 57
        val audioMs = mSbcFrames * 15 / 2 // 7.5 ms/帧，整数运算避免浮点
        val dist = audioSizes.entries.sortedByDescending { it.value }
            .joinToString(",") { "${it.key}B×${it.value}" }
        return "包=${audioFrames} 字节=${audioBytes} mSBC=${mSbcFrames}帧 " +
            "音频=${"%.1f".format(audioMs / 1000.0)}s 墙钟=${"%.1f".format(ms / 1000.0)}s " +
            "(${audioBytes * 1000 / ms} B/s) 帧长[$dist]"
    }

    /**
     * `probe` 参数格式：`type,id[,hex]`，hex 忽略空格与冒号。
     * 例：`--es probe "2,2,"`（查设备信息，空 payload）、`--es probe "2,3,0801"`。
     */
    private fun parseProbe(spec: String): Triple<Int, Int, ByteArray>? {
        val parts = spec.split(",", limit = 3)
        if (parts.size < 2) {
            log("probe 参数格式错：$spec")
            return null
        }
        val type = parts[0].trim().toIntOrNull()
        val id = parts[1].trim().toIntOrNull()
        val body = if (parts.size == 3) hexToBytes(parts[2]) else ByteArray(0)
        if (type == null || id == null || body == null) {
            log("probe 参数解析失败：$spec")
            return null
        }
        return Triple(type, id, body)
    }

    private fun hexToBytes(s: String): ByteArray? {
        val clean = s.replace(" ", "").replace(":", "").replace("-", "")
        if (clean.isEmpty()) return ByteArray(0)
        if (clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** 回包 hex；超过 96 字节只打头部，避免刷屏挤掉状态行。 */
    private fun hex(bytes: ByteArray): String {
        val head = if (bytes.size > 96) bytes.copyOfRange(0, 96) else bytes
        val s = head.joinToString(" ") { String.format(Locale.US, "%02x", it) }
        return if (bytes.size > 96) "$s …(+${bytes.size - 96}B)" else s
    }

    /** 全量 hex（不加省略），只给实验期需要看完整载荷的少数链路用。 */
    private fun hexFull(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format(Locale.US, "%02x", it) }

    private fun parseToken(text: String): ByteArray? {
        val clean = text.trim().replace(" ", "").replace(":", "")
        if (clean.length != 32) return null
        return try {
            ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun log(line: String) {
        // 同时进 logcat —— 真机跑长流程时比 uiautomator dump 方便得多
        android.util.Log.i("MiaiglassSample", line)
        logView.append("${stamp.format(Date())}  $line\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        runCatching { audioOut?.flush() }
        runCatching { audioOut?.close() }
        audioOut = null
        if (audioFrames > 0) log("audio 最终：${audioSummary()} 文件=${audioFile?.absolutePath ?: "-"}")
        super.onDestroy()
    }
}
