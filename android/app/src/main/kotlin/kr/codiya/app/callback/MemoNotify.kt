package kr.codiya.app.callback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kr.codiya.app.R
import org.json.JSONObject

/**
 * 📒 전화가 울릴 때 상단 알림으로 고객 메모 보여주기
 * (수신 화면이 다른 앱 창을 가려서, 받기 전에는 알림 배너로 · 끊은 뒤엔 메모창)
 */
object MemoNotify {
    private const val CHANNEL = "call_memo"
    private const val ID = 7600

    private fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "전화 메모 (받기 전)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "전화가 울릴 때 그 고객의 지난 메모를 위쪽 알림으로 보여줘요"
                setSound(null, null)          // 벨소리와 겹치지 않게 소리·진동 없음
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun kind(k: String) = when (k) { "ai", "share" -> "🤖"; "voice" -> "🎤"; else -> "✏️" }

    /** 모든 전화 (메모가 없으면 "처음 온 번호예요" / "메모가 없는 번호예요") */
    fun post(c: Context, num: String, d: JSONObject?) {
        if (d == null || muted) return
        curNum = num
        val name = d.optString("name", "")
        val items = d.optJSONArray("items")
        val n = items?.length() ?: 0
        channel(c)
        val calls = d.optInt("calls", 0)
        if (d.has("_loading")) { // 울리는 즉시 (내용은 받아오는 대로 같은 알림에 채움)
            notify(c, NotificationCompat.Builder(c, CHANNEL).setContentTitle("📒 " + MemoPopup.pretty(num)).setContentText("고객 메모 확인 중…"))
            return
        }
        if (name.isEmpty() && n == 0) {
            if (d.has("_err")) {
                notify(c, NotificationCompat.Builder(c, CHANNEL).setContentTitle("📒 " + MemoPopup.pretty(num))
                    .setContentText("메모를 불러오지 못했어요 · 끊은 뒤 메모창에서 다시 확인해요"))
                return
            }
            val t = if (calls <= 1) "📒 처음 온 번호예요" else "📒 메모가 없는 번호예요"
            val msg = MemoPopup.pretty(num) + (if (calls > 1) " · 통화 ${calls}번" else "") + "\n아직 고객 메모가 없어요. 메모를 저장해 두면 다음 전화 때 받기 전에 미리 보여줘요"
            notify(c, NotificationCompat.Builder(c, CHANNEL).setContentTitle(t).setContentText(msg).setStyle(NotificationCompat.BigTextStyle().bigText(msg)))
            return
        }
        val title = "📒 " + (if (name.isNotEmpty()) name else MemoPopup.pretty(num)) +
            (if (calls > 0) "  ·  통화 ${calls}번" else "")
        // 메모를 날짜별로 최대한 (최근 것부터)
        val lines = ArrayList<String>()
        var total = 0
        for (i in 0 until n) {
            val m = items!!.optJSONObject(i) ?: continue
            val at = m.optString("at").let { if (it.length >= 10) it.substring(5, 10).replace('-', '/') else it }
            val body = m.optString("body").replace(Regex("\\s*\\n+\\s*"), " / ").replace("🎤 원문:", "").trim()
            val line = "$at ${kind(m.optString("kind"))} " + (if (body.length > 160) body.take(160) + "…" else body)
            if (total + line.length > 900) break
            lines.add(line); total += line.length
        }
        val first = lines.firstOrNull() ?: "아직 고객 메모가 없어요 · 저장해 두면 다음 전화 때 받기 전에 보여줘요"
        val big = (if (name.isNotEmpty()) MemoPopup.pretty(num) + "\n" else "") + (if (lines.isEmpty()) first else lines.joinToString("\n"))
        val card = Card.bitmap(c, CallbackStore.memoKey(num)) // 📇 명함이 있으면 알림에 크게
        notify(c, NotificationCompat.Builder(c, CHANNEL)
            .setContentTitle(title)
            .setContentText(first)
            .apply { if (card != null) setLargeIcon(card) }
            .setStyle(if (card != null) NotificationCompat.BigPictureStyle().bigPicture(card).setBigContentTitle(title).setSummaryText(first)
                      else NotificationCompat.BigTextStyle().bigText(big).setBigContentTitle(title)))
    }

    // 전화가 울리는 동안 알림이 몇 초 뒤 사라지지 않게 3초마다 다시 띄움 (받거나 끊으면 멈춤)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var ringing = false
    @Volatile private var last: android.app.Notification? = null
    private var ringStart = 0L
    private val keep = object : Runnable {
        override fun run() {
            val n = last
            if (!ringing || muted || n == null || System.currentTimeMillis() - ringStart > 90_000L) { ringing = false; return }
            try { NotificationManagerCompat.from(appCtx ?: return).notify(ID, n) } catch (_: SecurityException) {}
            main.postDelayed(this, 3000)
        }
    }
    @Volatile private var appCtx: Context? = null

    // 메모 화면을 열었으면 이번 통화 동안 위쪽 메모 알림을 다시 띄우지 않음 (새 전화가 오면 풀림)
    @Volatile private var muted = false
    fun mute(c: Context) {
        muted = true
        stopRinging()
        NotificationManagerCompat.from(c).cancel(ID)
    }

    fun startRinging(c: Context) {
        muted = false
        appCtx = c.applicationContext
        ringing = true; ringStart = System.currentTimeMillis()
        main.removeCallbacks(keep); main.postDelayed(keep, 3000)
    }

    fun stopRinging() {
        ringing = false
        main.removeCallbacks(keep)
    }

    @Volatile private var curNum = ""

    /** 알림을 누르면 위쪽 반 화면 메모 (아래는 전화 화면 그대로) */
    private fun openIntent(c: Context, num: String): android.app.PendingIntent {
        val i = android.content.Intent(c, MemoActivity::class.java).putExtra("num", num)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return android.app.PendingIntent.getActivity(c, 7601, i,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
    }

    // ---------- 통화 중: "📒 메모 보기·쓰기" 고정 알림 (끊으면 사라짐) ----------
    private const val TALK_CH = "call_memo_talk"
    private const val TALK_ID = 7602
    fun showTalking(c: Context, num: String) {
        if (num.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(TALK_CH, "전화 메모 (통화 중)", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "통화 중에 눌러서 고객 메모를 보고 적을 수 있어요"; setShowBadge(false)
                })
        }
        val b = NotificationCompat.Builder(c, TALK_CH)
            .setSmallIcon(R.drawable.ic_stat_notify).setColor(0xFF0AA394.toInt())
            .setContentTitle("📒 메모 보기·쓰기")
            .setContentText(MemoPopup.pretty(num) + " · 눌러서 통화하면서 메모")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent(c, num))
        try { NotificationManagerCompat.from(c).notify(TALK_ID, b.build()) } catch (_: SecurityException) {}
    }
    fun hideTalking(c: Context) { NotificationManagerCompat.from(c).cancel(TALK_ID) }

    private fun notify(c: Context, b0: NotificationCompat.Builder) {
        val b = b0
            .setContentIntent(openIntent(c, curNum))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0AA394.toInt())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 잠금화면에서도 내용 보이게
            .setOnlyAlertOnce(!ringing) // 울리는 동안은 다시 띄울 때마다 화면 위에 보이게
            .setAutoCancel(true)
            .setTimeoutAfter(60 * 60 * 1000L) // 긴 통화 중에도 쓸어내리면 보이게 (끊으면 메모창으로 바뀜)
        val n = b.build()
        if (ringing) last = n
        try {
            NotificationManagerCompat.from(c).notify(ID, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(c: Context) {
        stopRinging()
        NotificationManagerCompat.from(c).cancel(ID)
    }
}
