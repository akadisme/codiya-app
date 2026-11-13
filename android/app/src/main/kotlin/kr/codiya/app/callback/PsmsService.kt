package kr.codiya.app.callback

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kr.codiya.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 📤 PC 문자 발송: PC(코디야 홈페이지)에서 보낸 문자를 이 휴대폰 문자로 한 통씩 자동 발송
 *  · 서버가 조용한 푸시로 깨우면(또는 앱을 열면) 시작 → 보낼 묶음을 받아 몇 초 간격으로 보내고 결과를 알림
 *  · 보내는 동안 알림줄에 진행 상황 + [잠깐 멈춤] [그만 보내기]
 *  · 같은 아이디의 PC에서 보낸 문자만 보냄 · 문자 내용·연락처를 다른 곳으로 보내지 않음
 */
class PsmsService : Service() {
    companion object {
        private const val CH = "psms_send"
        private const val CH_DONE = "psms_done"
        private const val NID = 7801
        private const val NID_DONE = 7802
        private const val ACT_SENT = "kr.codiya.app.PSMS_SENT"
        @Volatile private var running = false
        @Volatile private var paused = false
        @Volatile private var stopReq = false

        fun on(c: Context): Boolean = CallbackStore.prefs(c).getBoolean("psms_on", false)
        fun allowed(c: Context): Boolean = c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        /** 보낼 게 있는지 확인 시작 (푸시 · 앱 열 때) */
        fun kick(c: Context) {
            if (!on(c) || !allowed(c) || running) return
            val i = Intent(c, PsmsService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) }
                .onFailure { CallbackStore.diag(c, "📤 PC 문자 발송 시작 못 함 (" + it.javaClass.simpleName + ")") }
        }
    }

    private val results = ConcurrentHashMap<String, Int>()
    private var latch: CountDownLatch? = null
    private val sentRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val k = i.getStringExtra("k") ?: return
            results[k] = resultCode
            latch?.countDown()
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("act")) {
            "pause" -> { paused = !paused; return START_NOT_STICKY }
            "stop" -> { stopReq = true; paused = false; return START_NOT_STICKY }
        }
        channels()
        val n = progress("📤 PC에서 보낸 문자 확인 중…", "", 0, 0)
        runCatching {
            ServiceCompat.startForeground(this, NID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.onFailure { CallbackStore.diag(this, "📤 PC 문자 발송 알림 못 띄움 (" + it.javaClass.simpleName + ")"); stopSelf(); return START_NOT_STICKY }
        if (!running) {
            running = true; paused = false; stopReq = false
            ContextCompat.registerReceiver(this, sentRx, IntentFilter(ACT_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            Thread { runCatching { loop() }.onFailure { CallbackStore.diag(this, "📤 PC 문자 발송 오류 " + it.javaClass.simpleName) }; finishAll() }.start()
        }
        return START_NOT_STICKY
    }

    private var sentAll = 0
    private var failAll = 0

    private fun loop() {
        var idle = 0
        while (!stopReq) {
            val r = api("GET", "/v1/psms/next", null) ?: break
            if (r.optBoolean("off")) { CallbackStore.prefs(this).edit().putBoolean("psms_on", false).apply(); break }
            val job = r.optJSONObject("job")
            if (job == null) {
                if (r.optBoolean("busy") && idle++ < 3) { Thread.sleep(8000); continue }
                break
            }
            idle = 0
            val jobId = job.optInt("id")
            val total = job.optInt("total")
            var done = job.optInt("done")
            val gap = job.optInt("gap", 5).coerceIn(2, 30)
            val items = job.optJSONArray("items") ?: JSONArray()
            val buf = JSONArray()
            var cancel = false
            for (k in 0 until items.length()) {
                val it = items.optJSONObject(k) ?: continue
                while (paused && !stopReq) { notifyProgress("⏸ 잠깐 멈춤", total, done); Thread.sleep(1000) }
                if (stopReq) break
                notifyProgress("📤 문자 보내는 중", total, done)
                val res = sendOne(it.optInt("id"), it.optString("hp"), it.optString("msg"))
                buf.put(res)
                done++
                if (res.optBoolean("ok")) sentAll++ else failAll++
                notifyProgress("📤 문자 보내는 중", total, done)
                if (buf.length() >= 5) { val rr = report(jobId, buf, false); clear(buf); if (rr?.optBoolean("cancel") == true) { cancel = true; break } }
                if (k < items.length() - 1) Thread.sleep(gap * 1000L)
            }
            // 남은 결과 보내기 (그만 보내기면 stop 표시)
            report(jobId, buf, stopReq)
            if (stopReq || cancel) break
            Thread.sleep(gap * 1000L)
        }
    }

    private fun clear(a: JSONArray) { while (a.length() > 0) a.remove(0) }

    /** 한 통 보내기 → {id, ok, err} */
    private fun sendOne(id: Int, hp0: String, msg: String): JSONObject {
        val out = JSONObject().put("id", id)
        val hp = hp0.filter { it.isDigit() }
        if (!allowed(this)) return out.put("ok", false).put("err", "휴대폰에서 문자 권한이 꺼져 있음")
        if (hp.length < 8) return out.put("ok", false).put("err", "번호 오류")
        return try {
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sm.divideMessage(msg)
            val l = CountDownLatch(parts.size)
            latch = l
            val keys = ArrayList<String>()
            val pis = ArrayList<PendingIntent>()
            for (p in 0 until parts.size) {
                val key = "$id.$p"
                keys.add(key); results.remove(key)
                val i = Intent(ACT_SENT).setPackage(packageName).putExtra("k", key)
                pis.add(PendingIntent.getBroadcast(this, (id * 8 + p) and 0x7fffffff, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            if (parts.size > 1) sm.sendMultipartTextMessage(hp, null, parts, pis, null)
            else sm.sendTextMessage(hp, null, msg, pis[0], null)
            if (!l.await(60, TimeUnit.SECONDS)) return out.put("ok", false).put("err", "보낸 결과 확인 안 됨 (보냈을 수 있음)")
            val bad = keys.mapNotNull { results[it] }.firstOrNull { it != Activity.RESULT_OK }
            if (bad == null) out.put("ok", true) else out.put("ok", false).put("err", errText(bad))
        } catch (e: Exception) {
            out.put("ok", false).put("err", "보내기 실패 (" + e.javaClass.simpleName + ")")
        }
    }

    private fun errText(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "통신사 거절 (번호·스팸 차단·요금제 확인)"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "비행기 모드 또는 전파 꺼짐"
        SmsManager.RESULT_ERROR_NULL_PDU -> "문자 만들기 실패"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "통화권 이탈 (신호 없음)"
        6 -> "통신사 발송 한도 초과" // RESULT_ERROR_LIMIT_EXCEEDED
        7 -> "문자 발송이 막혀 있음 (FDN)"
        8 -> "단문 발송 거부됨" // RESULT_ERROR_SHORT_CODE_NOT_ALLOWED
        else -> "실패 (코드 $code)"
    }

    private fun report(job: Int, res: JSONArray, stop: Boolean): JSONObject? {
        if (res.length() == 0 && !stop) return null
        val b = JSONObject().put("job", job).put("results", JSONArray(res.toString())).put("stop", if (stop) 1 else 0)
        return api("POST", "/v1/psms/report", b)
    }

    private fun api(method: String, path: String, body: JSONObject?): JSONObject? {
        val tok = CallbackStore.prefs(this).getString("token", "") ?: ""
        if (tok.isEmpty()) return null
        for (tryN in 0 until 3) {
            try {
                val url = CallbackStore.api(this) + path + (if (method == "GET") "?token=" + URLEncoder.encode(tok, "UTF-8") else "")
                val con = URL(url).openConnection() as HttpURLConnection
                con.connectTimeout = 8000; con.readTimeout = 15000; con.requestMethod = method
                if (method == "POST") {
                    con.doOutput = true
                    con.setRequestProperty("Content-Type", "application/json")
                    con.outputStream.use { it.write((body ?: JSONObject()).put("token", tok).toString().toByteArray()) }
                }
                val code = con.responseCode
                val txt = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                con.disconnect()
                if (code in 200..299) return JSONObject(txt)
                if (code == 403 || code == 404) return null
            } catch (_: Exception) { }
            Thread.sleep(3000L * (tryN + 1))
        }
        return null
    }

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "PC 문자 발송 (보내는 중)", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "PC 문자 발송 (끝남)", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun act(a: String, rq: Int): PendingIntent =
        PendingIntent.getService(this, rq, Intent(this, PsmsService::class.java).putExtra("act", a), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun progress(title: String, text: String, total: Int, done: Int) =
        NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_notify).setColor(0xFF0AA394.toInt())
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .apply { if (total > 0) setProgress(total, done, false) }
            .addAction(0, if (paused) "▶ 다시 보내기" else "⏸ 잠깐 멈춤", act("pause", 7811))
            .addAction(0, "■ 그만 보내기", act("stop", 7812))
            .build()

    private fun notifyProgress(title: String, total: Int, done: Int) {
        val n = progress(title, "$done / $total · PC에서 보낸 문자를 내 휴대폰으로 보내고 있어요", total, done)
        runCatching { NotificationManagerCompat.from(this).notify(NID, n) }
    }

    private fun finishAll() {
        running = false
        runCatching { unregisterReceiver(sentRx) }
        if (sentAll + failAll > 0) {
            val n = NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_notify).setColor(0xFF0AA394.toInt())
                .setContentTitle(if (stopReq) "■ PC 문자 보내기를 멈췄어요" else "✅ PC 문자 보내기 끝")
                .setContentText("$sentAll 건 보냄" + (if (failAll > 0) " · 실패 $failAll 건 (PC 발송내역에서 사유 확인)" else ""))
                .setAutoCancel(true).build()
            runCatching { NotificationManagerCompat.from(this).notify(NID_DONE, n) }
        }
        sentAll = 0; failAll = 0; stopReq = false; paused = false
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }
}
