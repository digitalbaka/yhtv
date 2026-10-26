package com.yunhu.tv

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.yunhu.tv.api.WsClient
import com.yunhu.tv.util.Session
import okhttp3.OkHttpClient

class App : Application() {

    companion object {
        lateinit var instance: App
            private set
        val wsClient: WsClient by lazy { WsClient() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Session.init(this)
        initImageLoader()
    }

    /** 云湖图床需要 Referer 头，否则返回 403 */
    private fun initImageLoader() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val newReq = if (host.endsWith("jwznb.com")) {
                    req.newBuilder()
                        .header("Referer", "http://myapp.jwznb.com")
                        .build()
                } else req
                chain.proceed(newReq)
            }
            .build()
        Coil.setImageLoader(ImageLoader.Builder(this).okHttpClient(client).build())
    }
}
