package io.github.slchy.glasses2grok.sample

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.slchy.glasses2grok.core.query.DeviceInfo
import io.github.slchy.glasses2grok.core.query.DeviceStatus
import io.github.slchy.glasses2grok.core.query.QueryResult
import io.github.slchy.glasses2grok.core.session.GlassesEvent
import io.github.slchy.glasses2grok.core.session.LinkCounters
import io.github.slchy.glasses2grok.core.session.SessionState
import io.github.slchy.glasses2grok.core.voice.VoiceEvent
import io.github.slchy.glasses2grok.sdk.GlassesSession
import io.github.slchy.glasses2grok.sdk.SdkErrorCode
import io.github.slchy.glasses2grok.sdk.SessionOptions
import java.util.concurrent.Executors

/**
 * 单页仪表盘：**连接 / 握手 / 状态 / 诊断**，不碰音频。
 *
 * 与 [MainActivity] 的分工：那边是协议探针（几十个 `--ez` 开关 + 能力按钮），
 * 这边是给「出问题时看一屏」用的。探针面板从本页进入，`am start -n …/.MainActivity`
 * 仍然可用。
 *
 * 三条设计原则（见私有文档）：
 * 1. 颜色只有灰 / 绿 / 黄 / 红，不做动画 —— 0.5 秒内要能读懂。
 * 2. 日志说人话、能一键复制 —— 用户不会 `adb logcat`，但会复制粘贴。
 * 3. 配置不藏 —— 设备、token 就在首屏。
 */
class DashboardActivity : Activity() {

    private companion object {
        const val PREFS = "dashboard"
        const val KEY_TOKEN = "token"
        const val KEY_ADDRESS = "address"
        const val KEY_REMEMBER = "remember"
        const val KEY_CLOUD_HOST = "cloudHost"
        const val KEY_CLOUD_PORT = "cloudPort"
        const val KEY_CLOUD_TLS = "cloudTls"
        const val KEY_SESSION_IDLE_MS = "sessionIdleMs"
        const val DEFAULT_CLOUD_HOST = "127.0.0.1"
        const val DEFAULT_CLOUD_PORT = "8766"

        /**
         * 一轮说完多久没动静就让眼镜回待机（发 `Voice id=5` 六字段全 0）。
         *
         * 8 s 是「留一点余量给追问，又不至于让灯闪一整天」的折中。参考实现更激进：
         * `参考协议`的抓包里会话从 `f1=2` 到 `f1=0` 只有 **6 秒**。
         * `<= 0` 表示关掉这个行为（做掉电对照实验时用）。
         * 可用 `am start … --el sessionIdleMs 20000` 临时覆盖。
         */
        const val DEFAULT_SESSION_IDLE_MS = 8_000L
        const val LOG_MAX_LINES = 200

        /** 连续这么多个 tick（1 tick = 1 s）音频帧数不增，就报一次「采集停止」 */
        const val QUIET_TICKS_TO_REPORT = 3

        /** 摘下之后每丢这么多帧报一次：用来确认设备还在推流（不是"停了"） */
        const val DROPPED_LOG_EVERY = 100L

        val GREEN = 0xFF2E7D32.toInt()
        val YELLOW = 0xFFF9A825.toInt()
        val RED = 0xFFC62828.toInt()
        val GREY = 0xFF9E9E9E.toInt()
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var light: TextView
    private lateinit var deviceName: TextView
    private lateinit var stateLine: TextView
    private lateinit var facts: TextView
    private lateinit var uptime: TextView
    private lateinit var sessionBox: LinearLayout
    private lateinit var sessionLine: TextView
    private lateinit var diagLink: TextView
    private lateinit var diagAudio: TextView
    private lateinit var diagCloud: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var spinner: Spinner
    private lateinit var tokenInput: EditText
    private lateinit var remember: CheckBox
    private lateinit var cloudHost: EditText
    private lateinit var cloudPort: EditText
    private lateinit var voiceToggle: Button
    private lateinit var voiceTls: CheckBox

    private val devices = mutableListOf<BluetoothDevice>()
    private var session: GlassesSession? = null

    /** 云端语音通道：开着时把上行 mSBC 转发出去、把下行 PCM 播出来。 */
    private var voiceLink: VoiceLink? = null

    /**
     * 协作式高清照：建 P2P → 上报就绪 → 等用户按快门 → 拉图 → 缩图 → 上行。
     *
     * 相位判断在 [PhotoFsm]（纯逻辑），这里只做 Android 侧的接线。
     */
    private val photoJob: PhotoJob by lazy {
        PhotoJob(
            context = this,
            prefs = prefs,
            log = { line -> main.post { log(line) } },
            sendStatus = { event, fields -> voiceLink?.sendStatus(event, fields) ?: false },
            sendImage = { jpeg -> voiceLink?.sendImage(jpeg) ?: false },
        )
    }

    /** 本轮连接的起点，用来算「已运行」。 */
    private var readyAtMs = 0L
    private var audioFrames = 0L
    private var audioBytes = 0L

    /**
     * 「mic 还活着吗」的探针：连续 3 个 tick（3 s）音频帧数没增长就报一次「停了」，
     * 再涨回来报一次「恢复」。
     *
     * 只报**状态变化**，不报数值 —— 正常对话时每 5 s 打一行会把日志淹掉，而
     * 「mic 什么时候停的」恰恰是排查采集问题最需要的那条时间线。
     */
    private var lastAudioFrames = 0L
    private var quietTicks = 0
    private var audioQuiet = false

    /**
     * 是否转发上行音频。**只在「摘下」这个明确信号上关**。
     *
     * 不能用 `Voice SyncStatus.voice == 0` 判断：真机上这条帧每 30 s 就来一次，
     * 会话活跃时也会来（`实测` 的日志里 01:41 正在播报/打断，
     * `f1=0` 照样每 30 s 一条）。按它关转发会把正常对话掐断。
     */
    @Volatile
    private var forwarding = true
    private var droppedWhileOff = 0L

    /**
     * 空闲就结束会话，让眼镜睡回去。**这一组状态只在主线程读写**
     * （ticker / onEvent / 云端的 `onSpeaking` 都已经 post 到主线程）。
     */
    private var sessionIdleMs = DEFAULT_SESSION_IDLE_MS

    /** 最后一次「有动静」的时刻：音频帧、云端播报、唤醒、拍照流程都会刷新它。 */
    private var lastActiveAtMs = 0L

    /** 本轮会话是否已经发过结束帧 —— 防止每 8 s 一帧刷屏。 */
    private var idleEnded = false

    /** 设备是否还处在「会话中」（见过 `id=10 OPEN_DEVICE` 或云端播报过）。 */
    private var sessionSeen = false

    /** 结束帧之后还收到多少上行音频帧：用来判断 `f1=0` 到底有没有让设备停下。 */
    private var framesAfterIdle = 0L

    private var firmware = "—"
    private var model = "—"
    private var battery = "—"

    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dashboard-io").apply { isDaemon = true } }

    private val ticker = object : Runnable {
        override fun run() {
            refreshUptime()
            refreshCounters()
            watchCapture()
            main.postDelayed(this, 1_000)
        }
    }

    /**
     * 空闲就把 Voice 会话结束掉，让眼镜回待机。
     *
     * 为什么必须做：参考实现是「一次长按 = 一次会话」（实测）。我们以前只在蓝牙断开时发结束帧，只要 App 还开着，
     * 眼镜就一直停在「会话中」—— 灯一直闪、固件不进低功耗。
     *
     * 载荷**必须是六字段全量**：只写 `f1` 发出去毫无效果，采集照跑
     * （`实测`）；六字段全 0 才是设备认的「结束」（）。
     */
    private fun watchIdle() {
        val s = session ?: return
        if (sessionIdleMs <= 0L) return          // 这个行为关掉了（掉电对照实验用）
        if (idleEnded) return
        if (!sessionSeen) return                 // 设备根本没开会话，不用结束
        val now = System.currentTimeMillis()
        // 等快门、云端还在播报：都算「有动静」，关不得
        if (photoJob.isActive || voiceLink?.isSpeaking == true) {
            lastActiveAtMs = now
            return
        }
        val quietMs = now - lastActiveAtMs
        if (quietMs < sessionIdleMs) return
        idleEnded = true
        sessionSeen = false
        framesAfterIdle = 0
        io.execute {
            val r = s.endVoiceSession()
            main.post { log("空闲 ${quietMs / 1000} s → 结束会话（f1=0），让眼镜回待机：$r") }
        }
    }

    /** 真人或云端的动静：清掉空闲计时，并允许「下轮再关一次」。 */
    private fun touchActive() {
        lastActiveAtMs = System.currentTimeMillis()
        idleEnded = false
        framesAfterIdle = 0
    }

    /**
     * 音频帧算「有动静」，但只算**会话还开着的时候**。
     *
     * 会话已经被我们结束后设备若还在推流（的实测说不会，只有 1 个在途包），
     * 那就不能再拿它重新计时 —— 否则每 8 s 一帧 `f1=0` 会把日志刷满。这种情况只记一行。
     */
    private fun touchAudio() {
        if (!forwarding) return                  // 已摘下：推的多半是监视级流，不算在对话
        if (idleEnded) {
            framesAfterIdle++
            if (framesAfterIdle == 1L) {
                log("会话结束后仍有上行音频（f1=0 没让设备停下？）")
            }
            return
        }
        lastActiveAtMs = System.currentTimeMillis()
    }

    /** 采集是否还在流：连续 3 s 帧数不增就报「停了」，恢复时再报一次。 */
    private fun watchCapture() {
        val frames = audioFrames
        if (session == null) {
            lastAudioFrames = frames
            return
        }
        if (frames != lastAudioFrames) {
            lastAudioFrames = frames
            quietTicks = 0
            if (audioQuiet) {
                audioQuiet = false
                log("采集恢复（音频帧 $frames）")
            }
        } else {
            quietTicks++
            if (!audioQuiet && quietTicks >= QUIET_TICKS_TO_REPORT) {
                audioQuiet = true
                log("采集停止（音频帧停在 $frames，已 3 s 未增）")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        light = findViewById(R.id.light)
        deviceName = findViewById(R.id.deviceName)
        stateLine = findViewById(R.id.stateLine)
        facts = findViewById(R.id.facts)
        uptime = findViewById(R.id.uptime)
        sessionBox = findViewById(R.id.sessionBox)
        sessionLine = findViewById(R.id.sessionLine)
        diagLink = findViewById(R.id.diagLink)
        diagAudio = findViewById(R.id.diagAudio)
        diagCloud = findViewById(R.id.diagCloud)
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        spinner = findViewById(R.id.devices)
        tokenInput = findViewById(R.id.token)
        remember = findViewById(R.id.remember)
        cloudHost = findViewById(R.id.cloudHost)
        cloudPort = findViewById(R.id.cloudPort)
        voiceToggle = findViewById(R.id.voiceToggle)
        voiceTls = findViewById(R.id.voiceTls)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        (findViewById(R.id.connect) as Button).setOnClickListener { connect() }
        (findViewById(R.id.disconnect) as Button).setOnClickListener { disconnect() }
        voiceToggle.setOnClickListener { toggleVoice() }
        (findViewById(R.id.copyLog) as Button).setOnClickListener { copyLog() }
        (findViewById(R.id.probePanel) as Button).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        requestPermissionsIfNeeded()
        loadPrefs()
        listBondedDevices()
        renderState(SessionState.IDLE, "")
        main.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        // 从探针面板回来时设备表可能变了（新配对）
        if (devices.isEmpty()) listBondedDevices()
    }

    override fun onDestroy() {
        voiceLink?.close("页面退出")
        voiceLink = null
        main.removeCallbacks(ticker)
        session?.close()
        session = null
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 连接

    private fun requestPermissionsIfNeeded() {
        val want = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            want += Manifest.permission.BLUETOOTH_CONNECT
        }
        // Wi-Fi Direct（高清照）在 Android 12 及以下**必须有定位权限**，否则
        // `createGroup` 只回一个光秃秃的 ERROR(0)，日志里看不出是权限问题。
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            want += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (want.isNotEmpty()) {
            requestPermissions(want.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        listBondedDevices()
    }

    private fun loadPrefs() {
        tokenInput.setText(prefs.getString(KEY_TOKEN, "") ?: "")
        remember.isChecked = prefs.getBoolean(KEY_REMEMBER, true)
        cloudHost.setText(prefs.getString(KEY_CLOUD_HOST, DEFAULT_CLOUD_HOST) ?: DEFAULT_CLOUD_HOST)
        cloudPort.setText(prefs.getString(KEY_CLOUD_PORT, DEFAULT_CLOUD_PORT) ?: DEFAULT_CLOUD_PORT)
        voiceTls.isChecked = prefs.getBoolean(KEY_CLOUD_TLS, false)
        // 命令行临时覆盖优先（`am start -n …/.DashboardActivity --el sessionIdleMs 20000`）
        val override = intent?.getLongExtra(KEY_SESSION_IDLE_MS, -1L) ?: -1L
        sessionIdleMs = if (override >= 0L) {
            override
        } else {
            prefs.getLong(KEY_SESSION_IDLE_MS, DEFAULT_SESSION_IDLE_MS)
        }
    }

    private fun listBondedDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = try {
            adapter?.bondedDevices.orEmpty()
        } catch (e: SecurityException) {
            log("读已配对设备失败（缺 BLUETOOTH_CONNECT），请到系统设置授权后回来")
            emptySet<BluetoothDevice>()
        }
        devices.clear()
        devices += bonded.sortedBy { deviceLabel(it) }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            devices.map { "${deviceLabel(it)}  ${it.address}" },
        )
        // 上次连过的那台优先选中
        val saved = prefs.getString(KEY_ADDRESS, null)
        val idx = devices.indexOfFirst { it.address == saved }
        if (idx >= 0) spinner.setSelection(idx)
    }

    /**
     * `BluetoothDevice.name` 会抛 `SecurityException` —— 用户撤销蓝牙权限后点名就闪退
     * （真机上踩到过：`pm clear` 之后点「连接」直接 FATAL）。名字只是显示用，拿不到就退化成地址。
     */
    private fun deviceLabel(device: BluetoothDevice): String = try {
        device.name ?: device.address
    } catch (e: SecurityException) {
        device.address
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun connect() {
        if (!hasBluetoothPermission()) {
            toast("缺蓝牙权限，先授权")
            requestPermissionsIfNeeded()
            return
        }
        val device = devices.getOrNull(spinner.selectedItemPosition) ?: run {
            toast("先配对一台眼镜")
            return
        }

        session?.close()
        audioFrames = 0
        audioBytes = 0
        readyAtMs = 0
        firmware = "—"
        model = "—"
        battery = "—"
        lastActiveAtMs = 0
        idleEnded = false
        sessionSeen = false
        framesAfterIdle = 0

        log("连接 ${deviceLabel(device)}")
        log(
            if (sessionIdleMs <= 0L) {
                "空闲结束会话：已关闭（眼镜会一直停在会话中）"
            } else {
                "空闲结束会话：${sessionIdleMs / 1000} s 无动静就让眼镜回待机"
            },
        )
        val s = try {
            // 门面自己解 hex（B-001），这里不再抄一份 parseToken
            GlassesSession.open(this, device, tokenInput.text.toString(), SessionOptions())
        } catch (e: IllegalArgumentException) {
            toast("token 必须是 32 位 hex：${e.message}")
            return
        }
        persist(device, tokenInput.text.toString())
        s.setListener { event -> main.post { onEvent(event) } }
        session = s
        sessionBox.visibility = LinearLayout.VISIBLE
        sessionLine.text = "正在连接…"
    }

    private fun disconnect() {
        stopVoice("随会话断开")
        session?.close()
        session = null
        readyAtMs = 0
        // 断开后不留上一轮的电量/固件 —— 和「未连接」并排显示只会误导
        firmware = "—"
        model = "—"
        battery = "—"
        renderFacts()
        log("已请求断开")
    }

    // ------------------------------------------------------------------ 云端通道

    private fun toggleVoice() {
        voiceLink?.let {
            stopVoice("手动关闭")
            return
        }
        if (session == null) {
            toast("先连上眼镜，语音通道才有数据")
            return
        }
        val host = cloudHost.text.toString().trim()
        val parsedPort = cloudPort.text.toString().trim().toIntOrNull()
        if (host.isEmpty() || parsedPort == null || parsedPort !in 1..65535) {
            toast("云端地址要填 IP 与端口")
            return
        }
        val useTls = voiceTls.isChecked
        prefs.edit()
            .putString(KEY_CLOUD_HOST, host)
            .putString(KEY_CLOUD_PORT, parsedPort.toString())
            .putBoolean(KEY_CLOUD_TLS, useTls)
            .apply()

        val link = VoiceLink(
            context = this,
            host = host,
            port = parsedPort,
            token = tokenInput.text.toString().trim(),
            deviceId = Build.MODEL ?: "android",
            useTls = useTls,
            log = { line -> main.post { log(line) } },
            onControl = { text -> main.post { onCloudControl(text) } },
            onSpeaking = { speaking ->
                main.post {
                    // 云端开口/收声都是「有动静」：播报期间不能让空闲计时把会话关掉
                    touchActive()
                    if (speaking) sessionSeen = true
                    setAssistantSpeaking(speaking)
                }
            },
            onClosed = { reason -> main.post { stopVoice(reason) } },
        )
        voiceLink = link
        link.start()
        renderVoice("连接 ${if (useTls) "tls" else "tcp"}://$host:$parsedPort…")
    }

    private fun stopVoice(reason: String) {
        val link = voiceLink ?: return
        voiceLink = null
        // 通道断了，还在等的那张高清照也就不用等了（P2P 得散掉）
        photoJob.cancel("云端通道关闭")
        link.close(reason)
        renderVoice("已关闭（$reason）")
        voiceToggle.text = "语音通道：关"
    }

    private fun renderVoice(state: String) {
        put(diagCloud, "云端    $state")
        voiceToggle.text = if (voiceLink == null) "语音通道：关" else "语音通道：开"
    }

    /**
     * 把「云端正在播报」转成眼镜内侧指示灯的状态。
     *
     * 真机实测（2026-11-13）：`Voice id=5` 的 `ttsStatus` 就是参考实现那盏灯的信号源 ——
     * 会话进行中下发 `tts=1` 灯变常亮，`tts=0` 回到闪烁；没有活跃会话时这条帧
     * 完全不动灯。所以只在「眼镜会话已就绪」时才发。
     */
    private fun setAssistantSpeaking(speaking: Boolean) {
        val s = session ?: return
        io.execute {
            val result = s.syncVoiceStatus(
                voice = 1,
                tts = if (speaking) 1 else 0,
                operating = 2,
            )
            if (result is io.github.slchy.glasses2grok.sdk.SendResult.Rejected) {
                main.post { log("指示灯下发被拒：${result.code} ${result.reason}") }
            }
        }
    }

    /** 云端下发的控制串：`stop` / `take_photo` / `auth_*` / `end_session`。 */
    private fun onCloudControl(text: String) {
        when {
            text == "stop" -> {
                voiceLink?.stopPlayback()
                log("云端要求停止播放")
            }

            text == "take_photo" -> {
                val result = session?.capturePhoto()
                log("云端请求拍照：${result?.javaClass?.simpleName ?: "无会话"}")
            }

            // 模型调了「退出」工具：立刻让眼镜停采集（Voice 六字段全 0）。
            // 不断开蓝牙会话 —— 告别语还要靠它播完，播完云端会关连接。
            text == "stop_capture" -> {
                log("云端要求停止采集")
                val s = session
                if (s != null) {
                    io.execute {
                        val r = s.endVoiceSession()
                        main.post { log("停止采集：$r") }
                    }
                }
            }

            // ⚠️ 这里以前有个 `start_capture`（发 `Voice id=5 f1=2`）。
            // **实测无效**：`f1=1`(WAKE_UP) 与 `f1=2`(会话开始) 都只回 `Queued`，
            // 一帧音频都不来 —— 采集只能由设备发起（长按/唤醒），手机假冒不了。
            // 留着只会让人以为有"远端开麦"能力，已删除。见 `实测`。

            // 高清照：程序化快门**不写相册**（），所以先建通道、上报就绪，
            // 等云端提示用户按快门，再轮询相册里出现的新条目。
            text == "take_photo_hq" -> {
                log("云端请求高清照（P2P → 等用户按快门 → 缩图 → 上行）")
                touchActive()
                val s = session
                if (s == null) {
                    log("高清拍照 ❌ 没有会话")
                } else {
                    photoJob.start(s)
                }
            }

            // 用户改主意 / 会话收尾：立刻解散 P2P，别继续占着
            text == "hq_cancel" -> {
                log("云端取消高清照")
                photoJob.cancel("云端取消")
            }

            text == "end_session" -> {
                log("云端结束会话")
                disconnect()
            }

            text.startsWith("auth_") -> {
                log("云端拒绝鉴权（$text），检查 token")
                toast("云端拒绝鉴权")
                stopVoice("鉴权失败")
            }

            else -> log("云端控制：$text")
        }
    }

    private fun persist(device: BluetoothDevice, tokenText: String) {
        val editor = prefs.edit()
        if (remember.isChecked) {
            editor.putString(KEY_TOKEN, tokenText.trim())
                .putString(KEY_ADDRESS, device.address)
        } else {
            editor.remove(KEY_TOKEN).remove(KEY_ADDRESS)
        }
        editor.putBoolean(KEY_REMEMBER, remember.isChecked).apply()
    }

    // ------------------------------------------------------------------ 事件

    private fun onEvent(event: GlassesEvent) {
        when (event) {
            is GlassesEvent.StateChanged -> {
                renderState(event.state, event.reason)
                if (event.state == SessionState.READY) {
                    readyAtMs = System.currentTimeMillis()
                    // 刚连上就开始计时：没有会话的话到点会先发一次结束帧，然后静默等唤醒
                    lastActiveAtMs = readyAtMs
                    queryDeviceFacts()
                }
                if (event.state == SessionState.CLOSED || event.state == SessionState.FAILED) {
                    readyAtMs = 0
                    idleEnded = false
                    sessionSeen = false
                    lastActiveAtMs = 0
                }
            }

            is GlassesEvent.BatteryChanged -> {
                battery = "${event.status.batteryPercent}%" + chargeSuffix(event.status)
                renderFacts()
            }

            is GlassesEvent.Audio -> {
                audioFrames++
                audioBytes += event.data.size
                if (!forwarding) {
                    // 眼镜已摘下：它可能还在推「监视级」音频。这类流只会白烧 ASR
                    // （39 那次「说了半分钟没响应」就是这种流：峰值 −69 dBFS，ASR 只出碎片）。
                    droppedWhileOff++
                    if (droppedWhileOff % DROPPED_LOG_EVERY == 0L) {
                        log("已摘下，累计丢弃 ${droppedWhileOff} 帧上行音频")
                    }
                } else {
                    // 原样转发：眼镜已经压成 mSBC，手机不重编码
                    voiceLink?.pushAudio(event.data)
                }
                if (event.droppedBefore > 0) {
                    log("音频丢块 ${event.droppedBefore} 个（seq=${event.seq}）")
                }
            }

            is GlassesEvent.Voice -> {
                val voice = event.event
                log(humanVoice(voice))
                when (voice) {
                    is VoiceEvent.Error -> if (voice.isTakenOff) {
                        forwarding = false
                        droppedWhileOff = 0
                        log("已摘下 → 暂停转发上行音频（等下次唤醒）")
                    }
                    is VoiceEvent.OpenDevice -> {
                        if (!forwarding) {
                            forwarding = true
                            log("重新唤醒 → 恢复转发上行音频")
                        }
                    }
                    else -> Unit
                }
            }

            is GlassesEvent.Image -> {
                if (photoJob.isActive) {
                    // 高清流程途中，用户按快门会让相机回一张 SPP 低清图 —— 别把它当成
                    // 结果上行，否则云端先收到低清图就提前结束等待（实测拿到 17 KB 糊图）。
                    log("（高清流程中，忽略 SPP 低清回图 ${event.jpeg.size} 字节）")
                } else {
                    log("收到照片 ${event.jpeg.size} 字节")
                    voiceLink?.sendImage(event.jpeg)
                }
            }

            is GlassesEvent.Error -> {
                log("错误 ${event.code}：${event.message}")
                if (event.code == SdkErrorCode.AUTH_FAILED_TOKEN_INVALID) {
                    toast("token 无效，请重新获取")
                }
            }

            is GlassesEvent.Diagnostic -> Unit // 诊断模式下才发，默认关；不刷屏

            else -> Unit
        }
    }

    private fun humanVoice(event: VoiceEvent): String = when (event) {
        is VoiceEvent.OpenDevice -> "唤醒 / 长按（kind=${event.kind}）"
        is VoiceEvent.Error -> if (event.isTakenOff) "已摘下" else "会话错误 ${event.errorCode}"
        is VoiceEvent.Status -> "会话状态 ${event.state}"
        is VoiceEvent.SyncStatus -> "会话同步 f1=${event.voice}"
        is VoiceEvent.Unknown -> "未解事件 id=${event.id}"
    }

    private fun renderState(state: SessionState, reason: String) {
        put(stateLine, humanState(state, reason))
        light.setTextColor(
            when (state) {
                SessionState.READY -> GREEN
                SessionState.FAILED -> RED
                SessionState.CONNECTING, SessionState.HANDSHAKING, SessionState.RECONNECTING -> YELLOW
                else -> GREY
            },
        )
        put(
            deviceName,
            when (state) {
                SessionState.IDLE, SessionState.CLOSED -> "未连接"
                else -> devices.getOrNull(spinner.selectedItemPosition)?.let { deviceLabel(it) } ?: "眼镜"
            },
        )
        sessionBox.visibility =
            if (state == SessionState.IDLE || state == SessionState.CLOSED) LinearLayout.GONE
            else LinearLayout.VISIBLE
        put(sessionLine, humanState(state, reason))
        log(humanState(state, reason))
    }

    private fun humanState(state: SessionState, reason: String): String = when (state) {
        SessionState.IDLE -> "未连接"
        SessionState.CONNECTING -> "正在连接…"
        SessionState.HANDSHAKING -> "正在握手…（协商 → 校验 → 确认）"
        SessionState.READY -> "已连接"
        SessionState.RECONNECTING -> "连接中断，正在重连…"
        SessionState.CLOSING -> "正在断开…"
        SessionState.CLOSED -> "已断开"
        SessionState.FAILED -> if (reason.isBlank()) "故障（可重试）" else "故障：$reason"
    }

    // ------------------------------------------------------------------ 查询

    private fun queryDeviceFacts() {
        val s = session ?: return
        io.execute {
            val info = s.deviceInfo()
            val status = s.deviceStatus()
            main.post {
                if (info is QueryResult.Ok<DeviceInfo>) {
                    firmware = info.value.firmware.ifBlank { "—" }
                    model = info.value.model.ifBlank { "—" }
                }
                if (status is QueryResult.Ok<DeviceStatus>) {
                    battery = "${status.value.batteryPercent}%" + chargeSuffix(status.value)
                }
                renderFacts()
            }
        }
    }

    private fun chargeSuffix(status: DeviceStatus): String = when {
        status.isCharging -> "（充电中）"
        status.isFull -> "（已充满）"
        else -> ""
    }

    private fun renderFacts() {
        put(facts, "电量 $battery\u3000固件 $firmware\u3000型号 $model")
    }

    /** 只在文本真的变了才写 —— 每秒无条件 setText 会让界面永远不「空闲」：既白费重排，
     *  也让 uiautomator 之类的自动化拿不到 idle state（真机上踩到过）。 */
    private fun put(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun refreshUptime() {
        put(
            uptime,
            if (readyAtMs == 0L) {
                "已运行 —"
            } else {
                val sec = (System.currentTimeMillis() - readyAtMs) / 1000
                "已运行 %d 小时 %02d 分".format(sec / 3600, (sec % 3600) / 60)
            },
        )
    }

    /** 计数器在引擎线程上，别在主线程同步等（读数会占任务队列）。 */
    private fun refreshCounters() {
        val s = session ?: run {
            put(diagLink, "收帧 —\u3000发帧 —\u3000NAK —\u3000重传 —")
            put(diagAudio, "音频帧 —\u3000音频 —")
            return
        }
        io.execute {
            val c: LinkCounters = s.linkCounters()
            val frames = audioFrames
            val bytes = audioBytes
            main.post {
                put(diagLink, "收帧 ${c.rxFrames}\u3000发帧 ${c.txFrames}\u3000NAK ${c.nakSent}\u3000重传 ${c.retrans}")
                put(diagAudio, "音频帧 ${frames}\u3000音频 ${"%.1f".format(bytes / 1024.0)} KB")
            }
        }
    }

    // ------------------------------------------------------------------ 日志

    private fun log(line: String) {
        // 同一条也打到 logcat：仪表盘的日志只在界面上，远程/无头排查时看不到
        android.util.Log.i("MiaiglassSample", line)
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.addLast("$ts  $line")
        while (lines.size > LOG_MAX_LINES) lines.removeFirst()
        logView.text = lines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyLog() {
        val text = lines.joinToString("\n")
        if (text.isBlank()) {
            toast("日志是空的")
            return
        }
        val clip = getSystemService(ClipboardManager::class.java)
        clip.setPrimaryClip(ClipData.newPlainText("miai-log", text))
        toast("已复制 ${lines.size} 行")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
