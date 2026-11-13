package io.github.slchy.glasses2grok.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.github.slchy.glasses2grok.core.config.GlassesOptions
import io.github.slchy.glasses2grok.core.config.GlassesProtocol
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * `android.bluetooth.*` 的唯一落点。
 *
 * 几个刻意的选择：
 * - **`createRfcommSocketToServiceRecord`（SPP UUID）**，不用反射版 `createRfcommSocket(1)` ——
 *   那是早年绕过 UUID 不匹配的偏方，在 Android 12 上不稳定。
 * - **建连前 `cancelDiscovery()`**：发现过程会占住射频，`connect()` 容易超时。
 * - **`connect()` 没有超时参数**，只能靠 `BluetoothSocket.close()` 打断。
 *   所以这里自带看门狗线程：到点就 `close()`，把「挂死」变成一次可重试的失败。
 * - **靠广播而不是轮询**：`ACL_DISCONNECTED` 一到就关 socket，避免半开连接让
 *   读线程永远阻塞（参考实现同一套路）；配对丢了就重配对
 *   （参考实现同一套路）。
 */
class AndroidSppLink(
    private val device: BluetoothDevice,
    private val context: Context? = null,
    private val adapter: BluetoothAdapter? = null,
    private val uuid: UUID = SPP_UUID,
) : SppLink {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** 建连与看门狗的竞态裁决（见 [ConnectGate]）。 */
    private val gate = ConnectGate()

    /** 重配对的节流：`isAvailable()` 每 500 ms 被问一次，不能每次都 `createBond()`。 */
    private val rebond = BondRetry()

    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var externalClose: String? = null

    override val closeReason: String? get() = externalClose

    @Volatile
    private var whyUnavailable: String? = null

    override val unavailableReason: String? get() = whyUnavailable

    override fun isAvailable(): Boolean {
        whyUnavailable = null
        return try {
            if (adapter?.isEnabled != true) {
                whyUnavailable = "蓝牙关闭"
                return false
            }
            if (device.bondState == BluetoothDevice.BOND_BONDED) return true
            // 蓝牙开着但配对没了：参考实现会自发 createBond() 重配对，我们同样自愈，
            // 否则门禁会永远返回 false ⇒ 永久挂起在「等待恢复」。
            maybeRebond()
            whyUnavailable = "设备未配对"
            false
        } catch (e: SecurityException) {
            // Android 12+ 没给 BLUETOOTH_CONNECT 就是这里 —— 必须报出来，
            // 不能吞成「蓝牙关闭或未配对」，否则宿主会一直等一个永远不来的恢复。
            whyUnavailable = "缺少 BLUETOOTH_CONNECT 权限（Android 12+ 需运行时授权）"
            false
        }
    }

    private fun maybeRebond() {
        if (device.bondState != BluetoothDevice.BOND_NONE) return
        if (!rebond.shouldRetry(android.os.SystemClock.elapsedRealtime())) return
        runCatching { device.createBond() } // 没有 BLUETOOTH_CONNECT 时静默放弃
    }

    @SuppressLint("MissingPermission")
    override fun connect(timeoutMs: Int) {
        runCatching { adapter?.cancelDiscovery() }

        val s = try {
            device.createRfcommSocketToServiceRecord(uuid)
        } catch (e: Exception) {
            throw IOException("无法创建 RFCOMM socket（配对状态或权限问题）", e)
        }
        socket = s
        register()

        gate.begin()
        val watchdog = if (timeoutMs > 0) {
            Thread({
                try {
                    Thread.sleep(timeoutMs.toLong())
                    // 只有「建连还没成功」才关：超时那一刻 connect 恰好成功时，
                    // 关掉的会是刚建好的链路（窄竞态）。
                    if (gate.onDeadline()) runCatching { s.close() }
                } catch (ignored: InterruptedException) {
                    // 正常结束
                }
            }, "spp-connect-watchdog").also {
                it.isDaemon = true
                it.start()
            }
        } else {
            null
        }

        try {
            s.connect()
            // 输了 CAS ⇒ 看门狗已判超时并关了 socket，不能把它当成功返回
            if (!gate.onConnected()) throw IOException("socket 已被看门狗关闭")
            input = s.inputStream
            output = s.outputStream
        } catch (e: IOException) {
            close()
            throw if (gate.timedOut) IOException("建连超时 ${timeoutMs}ms", e) else e
        } catch (e: SecurityException) {
            close()
            throw IOException("缺少 BLUETOOTH_CONNECT 权限", e)
        } finally {
            gate.end()
            watchdog?.interrupt()
        }
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int =
        (input ?: throw IOException("链路未连接")).read(buf, off, len)

    override fun write(bytes: ByteArray) {
        val out = output ?: throw IOException("链路未连接")
        out.write(bytes)
        out.flush() // SPP 是流式，不 flush 可能压在内核缓冲里
    }

    override fun close() {
        unregister()
        // 幂等；socket.close() 同时负责打断阻塞中的 read()
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    /**
     * 收 ACL / 配对广播。
     *
     * `ACL_DISCONNECTED` 是唯一可靠的「对端真的没了」信号：没有它，半开连接上的
     * `read()` 可以永远不返回，监督线程跟着卡死在任何重试之前。
     */
    @SuppressLint("MissingPermission")
    private fun register() {
        val ctx = context ?: return
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                ?.address == device.address
                        ) {
                            closeSocketOnly("ACL 断开")
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val who =
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (who != null && who.address == device.address &&
                            who.bondState == BluetoothDevice.BOND_NONE
                        ) {
                            rebond.reset() // 配对刚丢，下一次 isAvailable() 立刻重试
                            closeSocketOnly("配对丢失")
                        }
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) ==
                            BluetoothAdapter.STATE_TURNING_OFF ||
                            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) ==
                            BluetoothAdapter.STATE_OFF
                        ) {
                            closeSocketOnly("蓝牙关闭")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 蓝牙广播由蓝牙系统进程发出（非本应用），NOT_EXPORTED 会收不到
                ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
        }.isSuccess
        if (ok) receiver = r
    }

    private fun unregister() {
        val r = receiver ?: return
        receiver = null
        runCatching { context?.unregisterReceiver(r) }
    }

    /** 只关 socket，保留广播注册（`close()` 才注销）。 */
    private fun closeSocketOnly(reason: String) {
        externalClose = reason
        runCatching { socket?.close() }
    }

    companion object {
        /** 标准 SPP（Serial Port Profile）UUID。 */
        val SPP_UUID: UUID = UUID.fromString(GlassesProtocol.Bluetooth.SPP_UUID)
    }
}

/**
 * `createBond()` 的节流器。
 *
 * `isAvailable()` 每 500 ms 被问一次，而 `createBond()` 会弹系统配对框、失败还会重试 ——
 * 不节流会把系统 UI 刷爆。
 */
internal class BondRetry(private val intervalMs: Long = GlassesOptions.BOND_RETRY_INTERVAL_MS) {
    private var lastAtMs = Long.MIN_VALUE

    /** 允许则返回 true 并记下本次时间；否则 false。 */
    fun shouldRetry(nowMs: Long): Boolean {
        if (lastAtMs != Long.MIN_VALUE && nowMs - lastAtMs < intervalMs) return false
        lastAtMs = nowMs
        return true
    }

    /** 清掉节流状态（例如刚观测到配对丢失）。 */
    fun reset() {
        lastAtMs = Long.MIN_VALUE
    }
}
