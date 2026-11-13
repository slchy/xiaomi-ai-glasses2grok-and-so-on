package io.github.slchy.glasses2grok.sdk

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import io.github.slchy.glasses2grok.core.wifi.WifiP2pIds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** 手机侧建好的 P2P 组。 */
public data class P2pGroup(
    /** 组名，形如 `DIRECT-ab12cd34`（P2P 规范要求这个前缀）。 */
    val ssid: String,
    val passphrase: String,
    /** MHz；参考实现写死 5745（ch149）。 */
    val frequency: Int,
    /** 组的网络句柄（`p2p0`）。拿它去 [AlbumClient.bindTo] 才能走这张网卡。 */
    val network: Network?,
    /** 组主（GO）在本机上的 IPv4，通常是 `192.168.49.1`。 */
    val ownerAddress: String?,
)

/**
 * 手机当 **Wi-Fi Direct Group Owner** 自建组（参考实现「无感」方案，`实测`）。
 *
 * 参考实现就是这么干的：
 *
 * ```java
 * WifiP2pConfig cfg = new WifiP2pConfig.Builder()
 *         .setNetworkName("DIRECT-" + UUID.randomUUID().toString().take(8))
 *         .setPassphrase(<8 位随机，A-Za-z0-9!@#$%^&*()_->)
 *         .setGroupOperatingFrequency(5745)
 *         .build();
 * cfg.wps.setup = 0;
 * wifiP2pManager.createGroup(channel, cfg, listener);   // 静默，无任何系统确认框
 * ```
 *
 * 建好组后把 [P2pGroup.ssid] / [P2pGroup.passphrase] / [P2pGroup.frequency] 通过
 * `GlassesSession.enableWifiP2pGcAndWait(...)`（`System id=99`）发给眼镜；眼镜作为
 * P2P client 加入，并在回包里报出自己的 IP（`192.168.49.x`），之后
 * `AlbumClient.bindTo(group.network, <眼镜 IP>)` 就能取文件 —— 全程**零弹窗**。
 *
 * 与 `WifiNetworkSpecifier` 的区别：那是把手机的 STA 接口临时切到别人的 AP，系统必须
 * 让用户确认；P2P 只是开一个独立接口当 GO，用户不参与，所以没有确认框。
 *
 * ## 权限
 *
 * - `CHANGE_WIFI_STATE`
 * - Android 13+：`NEARBY_WIFI_DEVICES`；Android 12 及以下：`ACCESS_FINE_LOCATION`
 *
 * ## 限制
 *
 * **要求 API 29+，更低版本明确不支持**（2026-11-11 裁定）：`WifiP2pConfig.Builder` 与
 * `setNetworkName` / `setPassphrase` / `setGroupOperatingFrequency` 全是 API 29 起，
 * 低版本没有等价的静默建组手段。`isSupported == false` 时 [create] 直接返回 null 并写
 * [reason]，**不做 AP + `WifiNetworkSpecifier` 降级**（那条同样要求 API 29+，还多一次人工确认）。
 */
public class P2pGroupOwner(private val context: Context) : AutoCloseable {

    private val connectivity: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private val channel: WifiP2pManager.Channel? =
        manager?.let {
            runCatching { it.initialize(context, Looper.getMainLooper(), null) }
                .onFailure { e -> initError = "${e.javaClass.simpleName}: ${e.message}" }
                .getOrNull()
        }

    @Volatile
    private var initError: String = ""

    /** `true` = 当前这套 API 可用（API 29+ 且有 P2P 服务）。 */
    public val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && manager != null && channel != null

    /** 一句诊断：SDK 版本 + P2P 服务/通道状态，排查 `isSupported == false` 用。 */
    public val status: String
        get() = "SDK=${Build.VERSION.SDK_INT} manager=${manager != null} " +
            "channel=${channel != null}" + if (initError.isEmpty()) "" else " init=$initError"

    /** 诊断：当前所有网络的接口名/IP，排查「组起来了但找不到 p2p 网络」用。 */
    public fun describeNetworks(): String {
        val cm = connectivity ?: return "connectivity=null"
        return cm.allNetworks.joinToString("；") { net ->
            val lp = cm.getLinkProperties(net)
            val caps = cm.getNetworkCapabilities(net)
            val transports = buildList {
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cell")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("eth")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
            }
            "${lp?.interfaceName ?: "?"}[${transports.joinToString(",")}]" +
                "{${lp?.linkAddresses?.joinToString(",") { it.address.hostAddress } ?: ""}}"
        }.ifEmpty { "（无网络）" }
    }

    /**
     * 建一个自主组（Autonomous GO）。**不需要用户确认**，也不需要眼镜在线。
     *
     * @param ssid 组名；不传就随机 `DIRECT-<8 位十六进制>`（与参考实现一致）
     * @param passphrase 8~63 位 WPA2 口令；不传就随机 8 位
     * @param frequencyMhz 建组频段，默认 [WifiP2pIds.DEFAULT_FREQUENCY_MHZ]（5745）
     * @return 建好的组；失败返回 null（原因已打到 [reason]）
     *
     * 真机（Redmi 10X / Android 12）实测：`createGroup` 的 ActionListener 几毫秒就回 success，
     * wpa_supplicant 真正 `P2P-GROUP-STARTED` 要十几秒，但对调用方无影响 ——
     * **不需要等组播起来**：紧接着发 `id=99`，眼镜 3~4 s 就进组并回 IP。
     *
     * 也不用去 `getAllNetworks()` 里找 `p2p0`：真机上普通 App 看不到这张网络（实测只列出
     * `wlan0`），而 192.168.49.0/24 的目的地址**默认路由就能到**（参考实现 P2P 分支同样不 bind）。
     */
    public fun create(
        ssid: String = randomSsid(),
        passphrase: String = randomPassphrase(),
        frequencyMhz: Int = WifiP2pIds.DEFAULT_FREQUENCY_MHZ,
        timeoutMs: Long = 15_000,
    ): P2pGroup? {
        val m = manager
        val ch = channel
        if (!isSupported || m == null || ch == null) {
            reason = "P2P 不可用（需要 API 29+ 且设备支持 Wi-Fi Direct），当前 SDK=${Build.VERSION.SDK_INT}"
            return null
        }
        removeGroup() // 上一轮的组不复用，组名/密码每轮都新

        val config = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(passphrase)
            .setGroupOperatingFrequency(frequencyMhz)
            .build()
        config.wps.setup = 0

        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        try {
            m.createGroup(
                ch,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        latch.countDown()
                    }

                    override fun onFailure(reasonCode: Int) {
                        failure.set(describeFailure(reasonCode))
                        latch.countDown()
                    }
                },
            )
        } catch (e: SecurityException) {
            reason = "缺少 Wi-Fi 权限（CHANGE_WIFI_STATE / NEARBY_WIFI_DEVICES / ACCESS_FINE_LOCATION）：${e.message}"
            return null
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            reason = "createGroup 超时（${timeoutMs}ms）"
            return null
        }
        failure.get()?.let {
            reason = it
            return null
        }
        // 尽力拿一下 Network（多数机型看不到），拿不到也不算失败：默认路由能直达 192.168.49.x
        val net = awaitP2pNetwork(2_000)
        reason = ""
        return P2pGroup(
            ssid = ssid,
            passphrase = passphrase,
            frequency = frequencyMhz,
            network = net,
            ownerAddress = net?.let { ipv4Of(it) },
        )
    }

    /** 解散组（幂等）：传 null 表示解散当前组。 */
    public fun removeGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        m.removeGroup(ch, null)
    }

    override fun close() {
        removeGroup()
    }

    /** 最近一次失败原因（空串 = 没失败）。 */
    @Volatile
    public var reason: String = ""
        private set

    /**
     * 找 p2p 接口对应的 [Network]：`LinkProperties.interfaceName` 以 `p2p` 开头且已有 IPv4。
     *
     * 为什么不用 `WifiP2pInfo.groupOwnerAddress`：参考实现也没用（全仓零命中），
     * 眼镜会把自己在组里的 IP 通过 `System id=99` 回包告诉我们。
     */
    private fun awaitP2pNetwork(timeoutMs: Long): Network? {
        val cm = connectivity ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (net in cm.allNetworks) {
                val lp = cm.getLinkProperties(net) ?: continue
                if (lp.interfaceName?.startsWith("p2p") == true) return net
            }
            Thread.sleep(200)
        }
        return null
    }

    private fun ipv4Of(network: Network): String? {
        val cm = connectivity ?: return null
        return cm.getLinkProperties(network)?.let { ipv4Of(it) }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun ipv4Of(lp: LinkProperties): String? =
        lp.linkAddresses
            .map { it.address }
            .firstOrNull { it is java.net.Inet4Address }
            ?.hostAddress

    private fun describeFailure(code: Int): String = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持 Wi-Fi Direct（P2P_UNSUPPORTED）"
        WifiP2pManager.BUSY -> "P2P 忙（BUSY）：先关掉投屏/Wi-Fi Direct 应用"
        else -> "createGroup 失败（code=$code）"
    }

    public companion object {
        private const val SSID_CHARS = "0123456789abcdef"
        private const val PASS_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*()_-"

        /** `DIRECT-` + 8 位（参考实现：UUID 前 8 位）。 */
        public fun randomSsid(): String =
            "DIRECT-" + (1..8).map { SSID_CHARS[Random.nextInt(SSID_CHARS.length)] }.joinToString("")

        /** 8 位随机口令（参考实现：8 位，字符集同上）。WPA2 最小长度就是 8。 */
        public fun randomPassphrase(): String =
            (1..8).map { PASS_CHARS[Random.nextInt(PASS_CHARS.length)] }.joinToString("")
    }
}
