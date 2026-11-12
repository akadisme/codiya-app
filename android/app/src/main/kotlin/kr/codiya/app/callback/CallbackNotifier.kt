package kr.codiya.app.callback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kr.codiya.app.R
import org.json.JSONObject
import java.util.Calendar

/** 통화가 끝나면 "현장 안내 문자 보낼까요?" 알림 (현장 버튼 최대 3개) */
object CallbackNotifier {
    private const val CHANNEL = "callback"
    const val NOTI_ID = 7700
    /** 번호마다 다른 알림 번호 (두 번호 알림이 서로 덮어쓰지 않게) */
    fun notiId(num: String): Int = NOTI_ID + 1 + (num.hashCode() and 0x7fff)

    private fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "콜백문자", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "통화가 끝나면 고객에게 안내 문자를 보낼 수 있게 알려줘요"
            }
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun inTime(cfg: JSONObject): Boolean {
        val now = Calendar.getInstance().let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
        val from = cfg.optString("time_from", "09:00")
        val to = cfg.optString("time_to", "20:00")
        return now >= from && now < to
    }

    /** 백그라운드에서 호출 */
    fun afterCall(c: Context, num: String, missed: Boolean, test: Boolean = false) {
        val head = (if (test) "테스트" else if (missed) "부재중" else "통화 끝남") + " " + CallbackStore.mask(num)
        val cfg = CallbackStore.fetchConfig(c)
        if (cfg == null) { CallbackStore.diag(c, "$head · 설정을 못 불러옴 (인터넷 확인 후 앱을 한 번 열어주세요)"); return }
        if (!cfg.optBoolean("linked", true)) { CallbackStore.diag(c, "$head · 홈페이지 로그인이 연결 안 됨 (앱에서 로그인해주세요)"); return }
        if (cfg.has("allowed") && !cfg.optBoolean("allowed")) { CallbackStore.diag(c, "$head · 광고계정 이관 해지로 사용 중지"); return }
        val tpls = cfg.optJSONArray("templates")
        if (tpls == null || tpls.length() == 0) { CallbackStore.diag(c, "$head · 템플릿이 없음 (홈페이지에서 만들어주세요)"); return }
        if (!test) {
            if (missed && !cfg.optBoolean("missed")) { CallbackStore.diag(c, "$head · '부재중 전화에도 보내기'가 꺼져 있음"); return }
            if (!missed && !cfg.optBoolean("auto")) { CallbackStore.diag(c, "$head · 콜백문자 '사용 안함'"); return }
            val h = CallbackStore.hash(num)
            if (!inTime(cfg)) { CallbackStore.diag(c, "$head · 알림 시간(${cfg.optString("time_from")}~${cfg.optString("time_to")})이 아님"); CallbackStore.queueLog(c, 0, num, if (missed) "missed" else "call", "skip_time"); return }
            if (cfg.optBoolean("dedupe") && CallbackStore.sentToday(c, h, cfg)) { CallbackStore.diag(c, "$head · 오늘 이미 보낸 번호 (하루 1번)"); CallbackStore.queueLog(c, 0, num, if (missed) "missed" else "call", "skip_dup"); return }
        }

        // 방금 현장 홈페이지에서 전화 버튼을 누른 기록이 있으면 그 현장을 맨 앞에
        val suggested = HashSet<Int>()
        cfg.optJSONArray("suggest")?.let { for (i in 0 until it.length()) suggested.add(it.optJSONObject(i)?.optInt("site_id") ?: 0) }
        val list = (0 until tpls.length()).map { tpls.getJSONObject(it) }
        val ordered = list.filter { it.optInt("site_id") != 0 && suggested.contains(it.optInt("site_id")) } +
            list.filterNot { it.optInt("site_id") != 0 && suggested.contains(it.optInt("site_id")) }
        val top = ordered.first()
        val hasHint = top.optInt("site_id") != 0 && suggested.contains(top.optInt("site_id"))

        // '다른 앱 위에 표시' 권한이 있으면 화면 가운데 창으로, 없으면 알림줄로
        // 같은 번호 메모창이 떠 있으면 그 아래에 붙여서 한 화면으로 (메모 쓰고 바로 보내기)
        if (MemoPopup.attachCallback(c, num, missed, ordered, hasHint)) { CallbackStore.diag(c, "$head · ✅ 메모창 아래에 콜백문자"); return }
        val fallback = { notifyBar(c, num, missed, ordered, hasHint) }
        if (CallbackPopup.canShow(c) && CallbackPopup.show(c, num, missed, ordered, hasHint, fallback)) { CallbackStore.diag(c, "$head · ✅ 확인 창 띄움"); return }
        CallbackStore.diag(c, "$head · ✅ 알림줄에 알림 (확인 창 권한 꺼짐)")
        fallback()
    }

    /** 알림줄 알림 (확인 창을 못 띄울 때 · 다른 번호 창을 보내는 중일 때) */
    fun notifyBar(c: Context, num: String, missed: Boolean, ordered: List<JSONObject>, hasHint: Boolean) {
        val top = ordered.first()
        channel(c)
        val masked = CallbackStore.mask(num)
        val title = if (missed) "📵 부재중 $masked" else "📞 통화 종료 $masked"
        val text = when {
            hasHint -> "🎯 ${top.optString("title")} 홈페이지에서 온 전화예요. 안내 문자를 보낼까요?"
            ordered.size == 1 -> "${top.optString("title")} 안내 문자를 보낼까요?"
            else -> "어느 현장 문의였나요? 눌러서 현장을 고르고 보내세요"
        }
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0AA394.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60 * 60 * 1000L)
            .setContentIntent(confirmIntent(c, 0, num, ordered, missed, hasHint))
        // 알림을 누르거나 현장 버튼을 누르면 "보내시겠습니까?" 창 (권한 없으면 먼저 '확인 창 켜기' 안내)
        ordered.take(3).forEachIndexed { i, t ->
            val label = (if (i == 0 && hasHint) "🎯 " else "💬 ") + t.optString("title").take(12)
            b.addAction(0, label, confirmIntent(c, i, num, ordered, missed, hasHint))
        }
        try {
            NotificationManagerCompat.from(c).notify(notiId(num), b.build())
        } catch (_: SecurityException) {
        }
    }

    private fun confirmIntent(c: Context, sel: Int, num: String, list: List<JSONObject>, missed: Boolean, hint: Boolean): PendingIntent {
        val i = Intent(c, CallbackSendActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("num", num)
            putExtra("missed", missed)
            putExtra("hint", hint)
            putExtra("sel", sel)
            putExtra("list", org.json.JSONArray(list).toString())
        }
        return PendingIntent.getActivity(c, notiId(num) * 8 + sel, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
