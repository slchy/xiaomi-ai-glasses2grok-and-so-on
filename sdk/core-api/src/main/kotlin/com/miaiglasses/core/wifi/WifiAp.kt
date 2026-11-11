package com.miaiglasses.core.wifi

import com.miaiglasses.core.config.GlassesProtocol
import com.miaiglasses.core.pb.Pb

/**
 * 眼镜热点开关（`System` type=2）。
 *
 * 参考实现两个命令类（`静态分析`）：
 * - `EnableWiFiAP` → `getPackedId() = 88`，`getSystem()` 返回**空** `System`
 * - `DisableWiFiAP` → `getPackedId() = 89`，`getSystem()` 里 `设置热点(WiFiAP{ssid,password,gateway})`
 *
 * `System.f57 = WiFiAP`（oneof；`f56 = WiFiAP.Result`、`f73 = WiFiAP.Request` 是另外两条）。
 * `WiFiAP` 字段号取自 `protobuf 消息类.writeTo`：
 * `protobuf 编码` / `(2, password_)` / `(3, gateway_)`。
 *
 * 背景：抓 AP 里的 HTTP 8080 之前**必须先把热点打开**（`` ）；
 * 真机 2026-11-11 扫描确认热点默认是关的（附近没有任何眼镜 MAC 前缀的 BSSID）。
 */
public object WifiApIds {
    public const val ID_ENABLE: Int = GlassesProtocol.Wifi.ID_ENABLE_AP
    public const val ID_DISABLE: Int = GlassesProtocol.Wifi.ID_DISABLE_AP

    /** `System.f57`（`设置热点` 把 `载荷类型` 设成 57）。 */
    public const val FIELD_WIFI_AP: Int = GlassesProtocol.Wifi.FIELD_WIFI_AP

    /** `System.f56`（`热点回执` → 56）：`id=88/89` 的回包。 */
    public const val FIELD_RESULT: Int = GlassesProtocol.Wifi.FIELD_RESULT

    /** `System.f32`（`setWifiConfig` → 32）：`id=56 SetWiFIConfig` 的载荷。 */
    public const val FIELD_WIFI_CONFIG: Int = 32
}

/**
 * `WiFiAP.Result`：`id=88/89` 的回包内容。
 *
 * 真机 `id=88` 回包（2026-11-11）：
 * `c2 03 38 08 00 12 34 0a 16 "Xiaomi AI Glasses 0000" 12 0c "<12 位随机密码>" 1a 0c "192.168.43.1"`
 * ⇒ `f56{ f1 code=0, f2 WiFiAP{ f1 ssid, f2 password, f3 gateway } }`。
 *
 * **热点 SSID 是设备名**（不是 `wifiConfig()` 里的 `GLASSCAP`），**密码每次随机** ——
 * 想连就得先发 `id=88` 再读这条回包。
 */
public data class WifiApInfo(
    val code: Int,
    val ssid: String,
    val password: String,
    val gateway: String,
) {
    val isOk: Boolean get() = code == 0
}

public object WifiApCodec {

    /** `EnableWiFiAP` 的载荷就是 0 字节 —— 不要画蛇添足塞 SSID。 */
    public fun enablePayload(): ByteArray = ByteArray(0)

    /**
     * `System id=56 SetWiFIConfig` 的载荷 —— 用来**钉死热点凭据**（试过才知道设备听不听）。
     *
     * 结构（`protobuf 消息类`，`setWifiConfig` 把 `载荷类型` 设成 **32**）：
     *
     * ```text
     * System   { f32 = WiFiConfig }
     * WiFiConfig{ f1 = BasicInfo{ f1 ssid, f2 authMode, f3 frequency }
     *             f2 = PSKInfo  { f1 identity, f2 password } }   ← password 在 f2
     * ```
     *
     * 常量：`WPA_NONE=1 / WPA_PSK=2 / WPA_EAP=4`；`F_24G=1 / F_5G=2`。
     */
    public fun setConfigPayload(
        ssid: String,
        password: String,
        authMode: Int = AUTH_WPA_PSK,
        frequency: Int = F_5G,
    ): ByteArray {
        val basic = java.io.ByteArrayOutputStream()
        Pb.string(basic, 1, ssid)
        Pb.uint(basic, 2, authMode.toLong())
        Pb.uint(basic, 3, frequency.toLong())
        val psk = java.io.ByteArrayOutputStream()
        Pb.string(psk, 2, password)
        val cfg = java.io.ByteArrayOutputStream()
        Pb.bytes(cfg, 1, basic.toByteArray())
        Pb.bytes(cfg, 2, psk.toByteArray())
        val system = java.io.ByteArrayOutputStream()
        Pb.bytes(system, WifiApIds.FIELD_WIFI_CONFIG, cfg.toByteArray())
        return system.toByteArray()
    }

    public const val ID_SET_WIFI_CONFIG: Int = 56
    public const val AUTH_WPA_NONE: Int = 1
    public const val AUTH_WPA_PSK: Int = 2
    public const val AUTH_WPA_EAP: Int = 4
    public const val F_24G: Int = 1
    public const val F_5G: Int = 2

    /** `DisableWiFiAP` 的载荷：`System{ f57{ f1=ssid f2=password f3=gateway } }`。 */
    public fun disablePayload(ssid: String, password: String, gateway: String): ByteArray {
        val ap = java.io.ByteArrayOutputStream()
        Pb.string(ap, 1, ssid)
        Pb.string(ap, 2, password)
        Pb.string(ap, 3, gateway)
        val system = java.io.ByteArrayOutputStream()
        Pb.bytes(system, WifiApIds.FIELD_WIFI_AP, ap.toByteArray())
        return system.toByteArray()
    }

    /** 解析 `id=88/89` 的回包 `System{ f56 = WiFiAP.Result{ f1 code, f2 WiFiAP{…} } }`。 */
    public fun parseResult(payload: ByteArray): WifiApInfo? {
        val result = Pb.fields(payload)[WifiApIds.FIELD_RESULT] ?: return null
        val f = Pb.fields(result)
        val code = Pb.varintValue(f[1] ?: return null).toInt()
        val ap = f[2]?.let { Pb.fields(it) } ?: return null
        return WifiApInfo(
            code = code,
            ssid = ap[1]?.toString(Charsets.UTF_8) ?: return null,
            password = ap[2]?.toString(Charsets.UTF_8) ?: "",
            gateway = ap[3]?.toString(Charsets.UTF_8) ?: "",
        )
    }
}
