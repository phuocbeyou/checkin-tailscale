package com.mwg.tailctl

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

const val TOPIC = "tailscale"
const val TAG = "TailCtl"

// Mở app 1 lần sau khi cài: app thoát "stopped state" (mới nhận được FCM) + subscribe topic.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
        val view = TextView(this).apply { setPadding(48, 48, 48, 48) }
        setContentView(view)
        thread { // status() gọi mạng (IP public), không được chạy trên main thread
            val s = status(this)
            runOnUiThread { view.text = "Listening FCM topic '$TOPIC'\n\n$s" }
        }
    }
}

class CmdService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
    }

    // Khoá màn hình -> MIUI kill process ~2s sau khi nhận FCM, nên phần chậm (bật/tắt -> chờ -> reply) chạy trong
    // foreground service. FCM high-priority được phép start foreground service từ background.
    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        runCatching {
            startForegroundService(Intent(this, ActionService::class.java)
                .putExtra("type", d["type"]).putExtra("chat_id", d["chat_id"]))
        }.onFailure {
            Log.w(TAG, "foreground service not allowed, running inline", it)
            execute(this, d["type"], d["chat_id"])
        }
    }
}

class ActionService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("cmd", "Lệnh Tailscale", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "cmd")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("TailCtl: ${intent?.getStringExtra("type")}")
            .build())
        thread {
            execute(this, intent?.getStringExtra("type"), intent?.getStringExtra("chat_id"))
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}

// ponytail: chỉ nhận type cố định, không exec lệnh tuỳ ý từ payload -> lộ Firebase key cũng chỉ bật/tắt được Tailscale.
// Exit node (/nodeon /nodeoff) không qua máy: worker duyệt route exit node bằng Tailscale API.
fun execute(ctx: Context, type: String?, chat: String?) {
    if (chat == null) return
    when (type) {
        "on" -> tailscale(ctx, true)
        "off" -> tailscale(ctx, false)
        "status" -> {}
        else -> return
    }
    reply(chat, status(ctx))
}

private fun ipn(action: String) = Intent("com.tailscale.ipn.$action")
    .setComponent(ComponentName("com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver"))
    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

// = adb shell am broadcast -a com.tailscale.ipn.CONNECT_VPN|DISCONNECT_VPN -n com.tailscale.ipn/.IPNReceiver
private fun tailscale(ctx: Context, on: Boolean) {
    ctx.sendBroadcast(ipn(if (on) "CONNECT_VPN" else "DISCONNECT_VPN"))
    for (i in 1..10) {
        if ((vpnIp(ctx) != null) == on) break
        Thread.sleep(1000)
    }
}

// Mạng chập chờn ngay lúc VPN vừa bật/tắt -> retry vài lần.
private fun reply(chat: String, text: String) {
    repeat(3) {
        val ok = runCatching {
            val conn = URL("https://api.telegram.org/bot${BuildConfig.BOT_TOKEN}/sendMessage").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.outputStream.use { it.write("chat_id=${enc(chat)}&text=${enc(text)}".toByteArray()) }
                check(conn.responseCode == 200) { "telegram ${conn.responseCode}" }
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "reply failed", it) }.isSuccess
        if (ok) return
        Thread.sleep(2000)
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

// IP mà Internet nhìn thấy = IP máy khác nhận được khi đi qua exit node này.
private fun publicIp() = runCatching {
    val conn = URL("https://api.ipify.org").openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.inputStream.bufferedReader().use { it.readText().trim() }
    } finally {
        conn.disconnect()
    }
}.getOrDefault("?")

// = adb shell dumpsys connectivity | grep VPN. Trả về IP tailnet (100.x), null nếu VPN đang tắt.
// ponytail: VPN nào đang chạy cũng coi là Tailscale; nếu máy còn VPN khác thì lọc theo IP 100.64.0.0/10.
@Suppress("DEPRECATION")
fun vpnIp(ctx: Context): String? {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val vpn = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } ?: return null
    return cm.getLinkProperties(vpn)?.linkAddresses
        ?.mapNotNull { it.address.hostAddress }
        ?.firstOrNull { it.startsWith("100.") } ?: "?"
}

fun status(ctx: Context) = "${Build.MODEL}: " +
    (vpnIp(ctx)?.let { "🟢 Tailscale ON ($it)" } ?: "🔴 Tailscale OFF") +
    "\n🌐 IP public: ${publicIp()}"
