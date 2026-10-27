package com.yunhu.tv

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.yunhu.tv.api.WsClient
import com.yunhu.tv.util.Session
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class App : Application() {

    companion object {
        lateinit var instance: App
            private set
        val wsClient: WsClient by lazy { WsClient() }

        /** 图片 / 视频共用客户端：带云湖 Referer，并对云湖 CDN 放宽证书校验 */
        lateinit var mediaClient: OkHttpClient
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Session.init(this)
        initImageLoader()
    }

    /** 云湖图床需要 Referer 头，否则返回 403 */
    private fun initImageLoader() {
        mediaClient = OkHttpClient.Builder()
            // 老电视（Android 6）信任库缺少 CDN 新根证书，需放宽 jwznb.com 证书校验
            .apply {
                val tm = JwznbTrustManager()
                val ssl = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(tm), null)
                }
                sslSocketFactory(ssl.socketFactory, tm)
            }
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val newReq = if (host.endsWith("jwznb.com")) {
                    req.newBuilder()
                        .header("Referer", "https://myapp.jwznb.com")
                        .build()
                } else req
                chain.proceed(newReq)
            }
            .build()
        val client = mediaClient
        // 内置 Emoji 字体：老电视系统缺少 Emoji 字形时也能正常显示
        androidx.emoji2.bundled.BundledEmojiCompatConfig(this).let {
            androidx.emoji2.text.EmojiCompat.init(it)
        }
        // allowRgb565: 电视内存有限，图片解码内存减半，防 OOM 闪退
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(client)
                .components {
                    // GIF 动图解码：API28+ 用系统 ImageDecoder，低版本用 GifDecoder；
                    // 配合 allowRgb565 与按视图尺寸采样，控制动图内存占用
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        add(coil.decode.ImageDecoderDecoder.Factory())
                    } else {
                        add(coil.decode.GifDecoder.Factory())
                    }
                }
                .allowRgb565(true)
                .crossfade(false)
                .build()
        )
    }
}

/**
 * 系统信任库校验失败时，仅对云湖 CDN（*.jwznb.com）的证书放行，
 * 其它域名仍走系统默认校验。主机名校验仍由 OkHttp 自行完成。
 */
private class JwznbTrustManager : X509TrustManager {

    private val standard: X509TrustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        standard.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            standard.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            if (!isJwznbCert(chain)) throw e
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = standard.acceptedIssuers

    private fun isJwznbCert(chain: Array<out X509Certificate>?): Boolean {
        val leaf = chain?.firstOrNull() ?: return false
        return try {
            leaf.subjectAlternativeNames?.any { entry ->
                entry.size >= 2 && entry[0] == 2 && run {
                    val dns = entry[1].toString().trimEnd('.').lowercase()
                    dns == "jwznb.com" || dns.endsWith(".jwznb.com")
                }
            } == true
        } catch (_: Exception) {
            // 个别证书解析 SAN 抛异常时按证书主体判断
            leaf.subjectX500Principal.name.contains("jwznb.com", ignoreCase = true)
        }
    }
}
