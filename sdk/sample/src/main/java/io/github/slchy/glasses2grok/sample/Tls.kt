package io.github.slchy.glasses2grok.sample

import android.content.Context
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * 客户端 TLS：自签 CA 打进 `assets/local-ca.pem`，与云端 `MIAI_TLS_CERT` 对齐。
 *
 * 自带 CA + 严格校验 = **证书 pinning**，不需要自研应用层加密：
 * 只有持有对应私钥的服务端能完成握手，中间人换上自己的证书会直接失败。
 *
 * 两种形态共用同一份代码：
 * - 调试：`assets/local-ca.pem` 是自签证书 → 只信它
 * - 生产：没有该文件 → 退回系统信任库（换成正式证书即可，不用改代码）
 */
internal object Tls {

    private const val ASSET = "local-ca.pem"

    fun open(
        host: String,
        port: Int,
        useTls: Boolean,
        context: Context,
        log: (String) -> Unit,
    ): Socket {
        if (!useTls) {
            val plain = Socket()
            plain.tcpNoDelay = true // 小帧 + 实时语音，别让 Nagle 攒包
            plain.connect(InetSocketAddress(host, port), 5_000)
            return plain
        }

        val factory = socketFactory(context, log)
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), 5_000)
        val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
        // SSLSocket 默认**不校验主机名**，必须显式打开 —— 否则任何持有该 CA 的
        // 证书都能冒充。IP 连接靠证书里的 SAN=IP 通过校验。
        ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        ssl.startHandshake()
        log("TLS 握手完成 ${ssl.session.protocol} / ${ssl.session.cipherSuite}")
        return ssl
    }

    private fun socketFactory(context: Context, log: (String) -> Unit): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        val ca = readAsset(context)
        if (ca == null) {
            log("TLS：assets/$ASSET 不存在，改用系统信任库")
            sslContext.init(null, null, null)
            return sslContext.socketFactory
        }

        val certificates = ca.use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificates(stream)
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certificates.forEachIndexed { index, certificate ->
            keyStore.setCertificateEntry("ca-$index", certificate)
        }
        val trust = TrustManagerFactory.getInstance("X509").apply { init(keyStore) }
        sslContext.init(null, trust.trustManagers, null)
        log("TLS：只信任 assets/$ASSET 里的 ${certificates.size} 张证书")
        return sslContext.socketFactory
    }

    private fun readAsset(context: Context): InputStream? = try {
        context.assets.open(ASSET)
    } catch (e: Exception) {
        null
    }
}
