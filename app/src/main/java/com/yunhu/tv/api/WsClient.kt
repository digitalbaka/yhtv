package com.yunhu.tv.api

import android.os.Handler
import android.os.Looper
import com.yunhu.tv.util.Session
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 云湖 WebSocket 实时通道。
 * 登录/心跳为 JSON 文本帧，服务端推送为 Protobuf 二进制帧（WsWrapper + Any）。
 */
class WsClient {

    interface Listener {
        /** 新消息推送 */
        fun onPush(msg: ChatMsg) {}
        /** 编辑消息推送 */
        fun onEdit(msg: ChatMsg) {}
        /** 流式消息追加 */
        fun onStream(stream: StreamMsg) {}
        /** 连接状态变化 */
        fun onStateChanged(connected: Boolean) {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var running = false
    private var loggedIn = false
    private var retryCount = 0
    private var seq = 0L

    private val listeners = mutableListOf<Listener>()

    private val wsHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WS 长连接
            .pingInterval(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun addListener(l: Listener) {
        synchronized(listeners) { if (!listeners.contains(l)) listeners.add(l) }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        retryCount = 0
        connect()
    }

    @Synchronized
    fun stop() {
        running = false
        loggedIn = false
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye")
        ws = null
    }

    private fun connect() {
        if (!running || Session.token.isEmpty()) return
        val req = Request.Builder().url(Api.WS_URL).build()
        ws = wsHttpClient.newWebSocket(req, socketListener)
    }

    private fun nextSeq(): String = "${System.currentTimeMillis()}${(seq++ % 1000)}"

    private fun sendJson(cmd: String, data: JSONObject) {
        val json = JSONObject()
            .put("seq", nextSeq())
            .put("cmd", cmd)
            .put("data", data)
        ws?.send(json.toString())
    }

    private fun sendLogin() {
        sendJson(
            "login",
            JSONObject()
                .put("userId", Session.userId)
                .put("token", Session.token)
                .put("platform", "android")
                .put("deviceId", Session.deviceId)
        )
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (loggedIn) sendJson("heartbeat", JSONObject())
            handler.postDelayed(this, 25_000)
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            retryCount = 0
            sendLogin()
            // 发送登录帧后即视为已登录；登录失败时后续数据会被服务端忽略，不影响心跳
            loggedIn = true
            notifyState(true)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val env = WsEnvelope.decode(bytes.toByteArray())
                dispatch(env)
            } catch (_: Exception) {
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDisconnected()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onDisconnected()
        }
    }

    private fun onDisconnected() {
        val wasRunning = running
        loggedIn = false
        notifyState(false)
        handler.removeCallbacks(heartbeatRunnable)
        if (wasRunning) {
            // 指数退避重连，最长 30 秒
            val delay = minOf(30_000L, 2_000L shl minOf(retryCount, 4))
            retryCount++
            handler.postDelayed({ connect() }, delay)
        }
    }

    private fun notifyState(connected: Boolean) {
        val copy = synchronized(listeners) { listeners.toList() }
        handler.post { copy.forEach { it.onStateChanged(connected) } }
    }

    private fun dispatch(env: WsEnvelope) {
        val copy = synchronized(listeners) { listeners.toList() }
        when (env.cmd) {
            "heartbeat_ack" -> {}
            "push_message", "blocked_message" -> {
                val msg = ChatMsg.decodePush(env.payload)
                handler.post { copy.forEach { it.onPush(msg) } }
            }
            "edit_message" -> {
                val msg = ChatMsg.decodePush(env.payload)
                handler.post { copy.forEach { it.onEdit(msg) } }
            }
            "stream_message" -> {
                val s = StreamMsg.decode(env.payload)
                handler.post { copy.forEach { it.onStream(s) } }
            }
        }
    }

    init {
        // 登录成功后开始心跳
        handler.postDelayed(heartbeatRunnable, 25_000)
    }
}
