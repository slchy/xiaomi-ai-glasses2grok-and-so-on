package io.github.slchy.glasses2grok.core.wifi

import io.github.slchy.glasses2grok.core.pb.Pb

/**
 * Wi-Fi Direct（P2P）建组：`System id=99 EnableWiFiP2PGC`。
 *
 * 参考实现：`EnableWiFiP2PGC(ssid, password, frequency, taskId)`
 * - `getPackedId() = 0x63`(99)
 * - `getSystem()` → `设置 P2P(WiFiP2P{ssid, password, frequency, taskId})`
 * - 回包 → `P2P 回执(WiFiP2P.Result{code, ipAddress, taskId})`
 *
 * 语义：**手机自己当 P2P Group Owner**（开启 P2P 组时
 * `WifiP2pConfig.Builder().setNetworkName/setPassphrase/setGroupOperatingFrequency(5745)`
 * 后 `createGroup()`，静默无弹窗），再把这个组的 SSID/密码/信道告诉眼镜，
 * 眼镜作为 **GC**（Group Client）加入，并在回包里报出自己的 P2P IP。
 *
 * 这是参考实现「无感」的唯一实现路径：全程不碰 `WifiNetworkSpecifier`，
 * 所以没有系统「临时 WLAN 网络」确认框（实测）。
 */
public object WifiP2pIds {
    public const val ID_ENABLE_P2P_GC: Int = 99

    /** `System.f65`（`设置 P2P` 把 `载荷类型` 设成 65）。 */
    public const val FIELD_WIFI_P2P: Int = 65

    /** `System.f66`（`P2P 回执` → 66）：`id=99` 的回包。 */
    public const val FIELD_RESULT: Int = 66

    /** 参考实现写死的建组频率：`0x1671` = 5745 MHz（ch149）。 */
    public const val DEFAULT_FREQUENCY_MHZ: Int = 5745
}

/**
 * `WiFiP2P.Result`：`id=99` 的回包内容。
 *
 * `code == 0` 且 [ip] 非空才算建组成功 —— 参考实现
 * 参考实现的建组回调就是先取
 * `P2P 回执里的 ipAddress` 再做非空判断，随后把该 IP 当文件服务地址。
 */
public data class WifiP2pResult(
    val code: Int,
    val ip: String,
    val taskId: Int,
) {
    val isOk: Boolean get() = code == 0 && ip.isNotEmpty()
}

public object WifiP2pCodec {

    /**
     * `EnableWiFiP2PGC` 的载荷：`System{ f65 = WiFiP2P{ f1 ssid, f2 password, f3 frequency, f4 taskId } }`。
     *
     * 字段号取自 `protobuf 消息类.protobuf 编码`：
     * `protobuf 编码` / `(2, password)` / `protobuf 编码` / `(4, taskId)`。
     *
     * [taskId] 不传就不写（与参考实现等价，nano 的 uint32 默认值不上线）。
     */
    public fun enablePayload(
        ssid: String,
        password: String,
        frequency: Int = WifiP2pIds.DEFAULT_FREQUENCY_MHZ,
        taskId: Int = 0,
    ): ByteArray {
        val p2p = java.io.ByteArrayOutputStream()
        Pb.string(p2p, 1, ssid)
        Pb.string(p2p, 2, password)
        Pb.uint(p2p, 3, frequency.toLong())
        if (taskId != 0) Pb.uint(p2p, 4, taskId.toLong())
        val system = java.io.ByteArrayOutputStream()
        Pb.bytes(system, WifiP2pIds.FIELD_WIFI_P2P, p2p.toByteArray())
        return system.toByteArray()
    }

    /** 解析 `id=99` 的回包 `System{ f66 = WiFiP2P.Result{ f1 code, f2 ipAddress, f3 taskId } }`。 */
    public fun parseResult(payload: ByteArray): WifiP2pResult? {
        val result = Pb.fields(payload)[WifiP2pIds.FIELD_RESULT] ?: return null
        val f = Pb.fields(result)
        return WifiP2pResult(
            code = (f[1]?.let { Pb.varintValue(it) } ?: 0L).toInt(),
            ip = f[2]?.toString(Charsets.UTF_8) ?: "",
            taskId = (f[3]?.let { Pb.varintValue(it) } ?: 0L).toInt(),
        )
    }
}
