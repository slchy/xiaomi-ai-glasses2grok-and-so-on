package com.miaiglasses.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import com.miaiglasses.core.album.AlbumApi
import com.miaiglasses.core.album.AlbumEntry
import com.miaiglasses.core.album.AlbumFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 眼镜 AP 上的相册 HTTP 客户端。
 *
 * 两道菜：**连 AP**（`WifiNetworkSpecifier`，只对这一个网络生效，不动手机的默认网络）
 * 与**流式下载**（`Network.openConnection` + 8 KB 缓冲，55 MB 的 mp4 也不整块进内存）。
 *
 * 协议与端点见 [AlbumApi]（实测）。
 *
 * ## 权限（宿主 App 必须声明并授予）
 *
 * - `ACCESS_NETWORK_STATE` / `CHANGE_NETWORK_STATE`
 * - Android 13+：`NEARBY_WIFI_DEVICES`（`neverForLocation`）
 * - Android 12 及以下：`ACCESS_FINE_LOCATION`
 * - **明文 HTTP**：眼镜是 `http://192.168.43.1:8080`，宿主必须允许该地址的 cleartext
 *   （`usesCleartextTraffic="true"` 或 `networkSecurityConfig` 放行该 IP），否则 Android 9+
 *   会抛 `Cleartext HTTP traffic … not permitted`（真机 2026-11-11 踩过）。
 *
 * ## 典型用法
 *
 * ```kotlin
 * val ap = (session.enableWifiApAndWait() as QueryResult.Ok).value   // System id=88 的回包
 * AlbumClient(context).use { album ->
 *     album.connect(ap.ssid, ap.password)                            // 12 s 超时
 *     val shots = album.filelists()
 *     val files = album.files(shots.first())
 *     album.download(files.first { it.isHeic }, File(dir, "ev0.heic")) { done, total -> … }
 * }
 * ```
 */
public class AlbumClient(
    private val context: Context,
    host: String = AlbumApi.HOST,
    private val port: Int = AlbumApi.PORT,
) : AutoCloseable {

    /** 设备地址：AP 模式下固定 `192.168.43.1`；P2P 模式下是眼镜回包里的 `ipAddress`。 */
    @Volatile
    private var host: String = host

    private val connectivity: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkRef = AtomicReference<Network?>(null)
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** true = 不绑 `Network`，走系统默认路由（手机已经手动连上眼镜 AP 时用）。 */
    private var useDefaultNetwork = false

    /** 已连上的眼镜 AP 网络；null = 还没连。 */
    public val network: Network? get() = networkRef.get()

    public val isConnected: Boolean get() = useDefaultNetwork || networkRef.get() != null

    /**
     * 加入眼镜 AP。**必须在发完 `System id=88` 之后立刻调** ——
     * 眼镜 hostapd 空闲约 30 s 就关热点（实测）。
     *
     * ⚠️ **仅排障/对照用**：会弹系统「临时 WLAN 网络」确认，且 `WifiNetworkSpecifier` 本身
     * 也是 API 29+。产品路径一律走 [P2pGroupOwner] + [connectP2pDefaultRoute]（零弹窗，
     * `实测`）。
     *
     * @return true 表示拿到 [Network]；false = 超时（热点没起 / 密码错 / 信号太差）
     */
    public fun connect(ssid: String, password: String, timeoutMs: Long = 12_000): Boolean {
        val cm = connectivity ?: return false
        close() // 重复 connect 先清掉旧的

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val latch = CountDownLatch(1)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkRef.set(network)
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }

            override fun onLost(network: Network) {
                networkRef.compareAndSet(network, null)
            }
        }
        callback = cb
        return try {
            cm.requestNetwork(request, cb)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) && networkRef.get() != null
        } catch (e: SecurityException) {
            // 少 NEARBY_WIFI_DEVICES / FINE_LOCATION 时这里会炸 —— 直接告诉宿主，不要静默
            throw IllegalStateException("缺少 Wi-Fi 权限（NEARBY_WIFI_DEVICES / ACCESS_FINE_LOCATION）", e)
        }
    }

    /** 退出 AP（注销回调，手机的网络恢复原状）。幂等。 */
    override fun close() {
        val cm = connectivity
        val cb = callback
        callback = null
        useDefaultNetwork = false
        networkRef.set(null)
        if (cm != null && cb != null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    /**
     * 绑定到指定 [Network]（P2P 场景）：手机是 GO，p2p0 上的路由不是默认网络，
     * 必须显式绑上去，HTTP 才会走这张网卡。
     *
     * @param host 眼镜的 P2P IP（`System id=99` 回包里的 `ipAddress`，通常 `192.168.49.x`）
     */
    public fun bindTo(network: Network, host: String = this.host) {
        close()
        this.host = host
        networkRef.set(network)
    }

    /**
     * **P2P 无弹窗**用法：手机当 GO（[P2pGroupOwner.create]）→ 发 `System id=99` →
     * 拿回包里的眼镜 IP → 本方法绑定 p2p 网络即可直接取文件。
     *
     * @return true 表示这张网络上确实能读到 `/v1/filelists`
     */
    public fun connectP2p(network: Network, host: String): Boolean {
        bindTo(network, host)
        return try {
            getText(AlbumApi.PATH_FILELISTS).isNotEmpty()
        } catch (e: Throwable) {
            close()
            false
        }
    }

    /**
     * P2P 下**不绑定**网络的用法：直接换个 host 走默认路由试一次。
     *
     * 参考实现 P2P 分支就是没 bind（`MediaTransManagerViewModel$onP2PConnected` 只在 AP 分支调
     * `P2P 配置处理.p()`），说明目的地址 `192.168.49.x` 在多数机型上
     * 能被系统路由到 `p2p0`；不行再用 [bindTo]。
     *
     * @return true 表示默认路由上能读到 `/v1/filelists`
     */
    public fun connectP2pDefaultRoute(host: String): Boolean {
        close()
        this.host = host
        useDefaultNetwork = true
        return try {
            getText(AlbumApi.PATH_FILELISTS).isNotEmpty()
        } catch (e: Throwable) {
            useDefaultNetwork = false
            false
        }
    }

    /**
     * **不弹窗**的用法：手机已经（由用户或系统）连上眼镜 AP 时，直接走默认路由。
     *
     * 适用于：① 用户手动在系统设置里连过一次；② 自动化测试用
     * `adb shell cmd wifi connect-network '<ssid>' wpa2 '<pwd>'` 先连上。
     *
     * 为什么需要它：`WifiNetworkSpecifier` 的「临时 WLAN 网络」确认框是 Android 设计如此，
     * **每次都要用户点**；而 `WifiNetworkSuggestion` 的「批准一次」要求 (SSID, 密码) 固定 ——
     * 眼镜热点密码每次 `id=88` 都换，所以那条路走不通（实测）。
     *
     * @return true 表示默认路由上确实能读到 `/v1/filelists`
     */
    public fun connectExisting(): Boolean {
        close()
        useDefaultNetwork = true
        return try {
            getText(AlbumApi.PATH_FILELISTS).isNotEmpty()
        } catch (e: Throwable) {
            useDefaultNetwork = false
            false
        }
    }

    // ------------------------------------------------------------ HTTP

    /** `GET /v1/filelists` —— 全部媒体条目（照片 = 一条 `image/folder`）。 */
    public fun filelists(): List<AlbumEntry> = AlbumEntry.parseList(getText(AlbumApi.PATH_FILELISTS))

    /**
     * 只取「上次同步之后」的条目 —— 增量同步用。
     *
     * @param sinceMillis 上次同步时间（毫秒）；用 `fileModified` 比较，`0` 等于全量
     */
    public fun filelists(sinceMillis: Long): List<AlbumEntry> =
        filelists().filter { it.fileModifiedMillis >= sinceMillis }

    /**
     * 删除设备上的一个副本：`DELETE /v1/files?id=<identifier>`。
     *
     * ⚠️ **不可恢复**，而且参考实现拉完就删是因为它已经存到手机相册了。
     * SDK 不替你决定，调用前请确认已经落盘成功。
     *
     * @return true 表示设备回了 2xx
     */
    public fun delete(identifier: String): Boolean {
        val path = AlbumApi.deletePath(identifier)
        val url = URL(AlbumApi.baseUrl(host, port) + path)
        val conn = if (useDefaultNetwork) {
            url.openConnection() as HttpURLConnection
        } else {
            val net = networkRef.get()
                ?: throw IllegalStateException("还没连上眼镜 AP：先 connect(ssid, password) 或 connectExisting()")
            net.openConnection(url) as HttpURLConnection
        }
        return try {
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            (code in 200..299).also { ok ->
                if (!ok) throw IOException("DELETE $code @ $path")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 某个条目内部的文件清单。
     *
     * **folder 条目** → `GET /v1/filelists/<stem>`；
     * **非 folder 条目**（录音 `audio/ogg`、单个文件）→ 它自己就是文件，直接回一条
     * （列表里那条的 `size` 是 0，真长度由下载时的 `Content-Length` 决定）。
     */
    public fun files(entry: AlbumEntry): List<AlbumFile> =
        entry.asFile?.let { listOf(it) }
            ?: AlbumFile.parseList(getText(AlbumApi.filelistPath(entry)))

    /**
     * 下载一个文件到 [target]（流式，8 KB 缓冲）。
     *
     * @param onProgress 已写字节 / 总字节（总字节来自 `Content-Length`，拿不到时传 -1）
     * @return 实际写入的字节数
     */
    public fun download(
        file: AlbumFile,
        target: File,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
    ): Long {
        target.parentFile?.mkdirs()
        val path = AlbumApi.filePath(file)
        val conn = openConnection(path)
        try {
            requireResponseOk(conn, path)
            // 列表里的 size 可能报 0（录音条目真机如此），这时用 Content-Length 当总长
            val total = if (file.size > 0) file.size else conn.contentLengthLong
            return conn.inputStream.use { input ->
                target.outputStream().use { out -> copy(input, out, total, onProgress) }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载一个条目的「主文件」（规则见 [AlbumApi.pickPrimary]：mp4 优先，其次最大 heic）。
     *
     * @return 落盘的文件；条目的子清单为空时返回 null
     */
    public fun downloadPrimary(
        entry: AlbumEntry,
        dir: File,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
    ): File? {
        val primary = AlbumApi.pickPrimary(files(entry)) ?: return null
        val target = File(dir, primary.url.substringAfterLast('/').ifEmpty { entry.identifier })
        download(primary, target, onProgress)
        return target
    }

    /** 只读文本接口（列表用）。 */
    public fun getText(path: String): String {        val conn = openConnection(path)
        try {
            requireResponseOk(conn, path)
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(path: String): HttpURLConnection {
        val url = URL(AlbumApi.baseUrl(host, port) + path)
        val conn: HttpURLConnection
        if (useDefaultNetwork) {
            conn = url.openConnection() as HttpURLConnection
        } else {
            val net = networkRef.get()
                ?: throw IllegalStateException("还没连上眼镜 AP：先 connect(ssid, password) 或 connectExisting()")
            conn = net.openConnection(url) as HttpURLConnection
        }
        conn.connectTimeout = 8_000
        conn.readTimeout = 30_000
        conn.requestMethod = "GET"
        return conn
    }

    private fun requireResponseOk(conn: HttpURLConnection, path: String) {
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            val body = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
            throw IOException("HTTP $code @ $path${if (body.isNullOrBlank()) "" else "：${body.take(200)}"}")
        }
    }

    private fun copy(
        input: InputStream,
        out: java.io.OutputStream,
        declaredTotal: Long,
        onProgress: ((Long, Long) -> Unit)?,
    ): Long {
        val buf = ByteArray(8 * 1024)
        var done = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            done += n
            if (onProgress != null && done - lastReport >= 256 * 1024) {
                lastReport = done
                onProgress(done, declaredTotal)
            }
        }
        out.flush()
        onProgress?.invoke(done, declaredTotal)
        return done
    }

    /** 只在测试/诊断里用：宿主可能想打印当前网关。 */
    override fun toString(): String = "AlbumClient(host=$host, port=$port, connected=$isConnected)"
}
