package com.miaiglasses.sample

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
import com.miaiglasses.core.query.DeviceInfo
import com.miaiglasses.core.query.DeviceStatus
import com.miaiglasses.core.query.QueryResult
import com.miaiglasses.core.session.GlassesEvent
import com.miaiglasses.core.session.LinkCounters
import com.miaiglasses.core.session.SessionState
import com.miaiglasses.core.voice.VoiceEvent
import com.miaiglasses.sdk.GlassesSession
import com.miaiglasses.sdk.SessionOptions
import java.util.concurrent.Executors

/**
 * 单页仪表盘：**连接 / 握手 / 状态 / 诊断**，不碰音频。
 *
 * 与 [MainActivity] 的分工：那边是协议探针（几十个 `--ez` 开关 + 能力按钮），
 * 这边是给「出问题时看一屏」用的。探针面板从本页进入，`am start -n …/.MainActivity`
 * 仍然可用。
 *
 * 三条设计原则（见 `docs/application/`docs/a5a5.md` 规范`）：
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
        const val LOG_MAX_LINES = 200

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

    private val devices = mutableListOf<BluetoothDevice>()
    private var session: GlassesSession? = null

    /** 本轮连接的起点，用来算「已运行」。 */
    private var readyAtMs = 0L
    private var audioFrames = 0L
    private var audioBytes = 0L
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
            main.postDelayed(this, 1_000)
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

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        (findViewById(R.id.connect) as Button).setOnClickListener { connect() }
        (findViewById(R.id.disconnect) as Button).setOnClickListener { disconnect() }
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
        main.removeCallbacks(ticker)
        session?.close()
        session = null
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 连接

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        listBondedDevices()
    }

    private fun loadPrefs() {
        tokenInput.setText(prefs.getString(KEY_TOKEN, "") ?: "")
        remember.isChecked = prefs.getBoolean(KEY_REMEMBER, true)
    }

    private fun listBondedDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = try {
            adapter?.bondedDevices.orEmpty()
        } catch (e: SecurityException) {
            log("读已配对设备失败（缺 BLUETOOTH_CONNECT）")
            emptySet<BluetoothDevice>()
        }
        devices.clear()
        devices += bonded.sortedBy { it.name ?: it.address }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            devices.map { "${it.name ?: "?"}  ${it.address}" },
        )
        // 上次连过的那台优先选中
        val saved = prefs.getString(KEY_ADDRESS, null)
        val idx = devices.indexOfFirst { it.address == saved }
        if (idx >= 0) spinner.setSelection(idx)
    }

    private fun connect() {
        val token = parseToken(tokenInput.text.toString())
        if (token == null) {
            toast("token 必须是 32 位 hex")
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
        persist(device, tokenInput.text.toString())

        log("连接 ${device.name ?: device.address}")
        val s = GlassesSession.open(this, device, token, SessionOptions())
        s.setListener { event -> main.post { onEvent(event) } }
        session = s
        sessionBox.visibility = LinearLayout.VISIBLE
        sessionLine.text = "正在连接…"
    }

    private fun disconnect() {
        session?.close()
        session = null
        readyAtMs = 0
        log("已请求断开")
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
                    queryDeviceFacts()
                }
                if (event.state == SessionState.CLOSED || event.state == SessionState.FAILED) {
                    readyAtMs = 0
                }
            }

            is GlassesEvent.BatteryChanged -> {
                battery = "${event.status.batteryPercent}%" + chargeSuffix(event.status)
                renderFacts()
            }

            is GlassesEvent.Audio -> {
                audioFrames++
                audioBytes += event.data.size
                if (event.droppedBefore > 0) {
                    log("音频丢块 ${event.droppedBefore} 个（seq=${event.seq}）")
                }
            }

            is GlassesEvent.Voice -> log(humanVoice(event.event))

            is GlassesEvent.Image -> log("收到照片 ${event.jpeg.size} 字节")

            is GlassesEvent.Error -> {
                log("错误 ${event.code}：${event.message}")
                if (event.code == 108) {
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
        stateLine.text = humanState(state, reason)
        light.setTextColor(
            when (state) {
                SessionState.READY -> GREEN
                SessionState.FAILED -> RED
                SessionState.CONNECTING, SessionState.HANDSHAKING, SessionState.RECONNECTING -> YELLOW
                else -> GREY
            },
        )
        deviceName.text = when (state) {
            SessionState.IDLE, SessionState.CLOSED -> "未连接"
            else -> devices.getOrNull(spinner.selectedItemPosition)?.name ?: "眼镜"
        }
        sessionBox.visibility =
            if (state == SessionState.IDLE || state == SessionState.CLOSED) LinearLayout.GONE
            else LinearLayout.VISIBLE
        sessionLine.text = humanState(state, reason)
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
        facts.text = "电量 $battery\u3000固件 $firmware\u3000型号 $model"
    }

    private fun refreshUptime() {
        uptime.text = if (readyAtMs == 0L) {
            "已运行 —"
        } else {
            val sec = (System.currentTimeMillis() - readyAtMs) / 1000
            "已运行 %d 小时 %02d 分".format(sec / 3600, (sec % 3600) / 60)
        }
    }

    /** 计数器在引擎线程上，别在主线程同步等（读数会占任务队列）。 */
    private fun refreshCounters() {
        val s = session ?: run {
            diagLink.text = "收帧 —\u3000发帧 —\u3000NAK —\u3000重传 —"
            diagAudio.text = "音频帧 —\u3000音频 —"
            return
        }
        io.execute {
            val c: LinkCounters = s.linkCounters()
            val frames = audioFrames
            val bytes = audioBytes
            main.post {
                diagLink.text = "收帧 ${c.rxFrames}\u3000发帧 ${c.txFrames}\u3000NAK ${c.nakSent}\u3000重传 ${c.retrans}"
                diagAudio.text = "音频帧 ${frames}\u3000音频 ${"%.1f".format(bytes / 1024.0)} KB"
            }
        }
    }

    // ------------------------------------------------------------------ 日志

    private fun log(line: String) {
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

    /** 32 位 hex，忽略空格与冒号。 */
    private fun parseToken(text: String): ByteArray? {
        val clean = text.trim().replace(" ", "").replace(":", "")
        if (clean.length != 32) return null
        return try {
            ByteArray(16) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
