package kr.codiya.app.callback

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * 🤖 통화요약 자동 저장
 * 에이닷·익시오·갤럭시 AI 등 통화요약 앱이 "요약" 알림을 띄우면,
 * 방금(20분 안) 끊은 통화 번호의 메모에 그 내용을 바로 저장함 (공유 누를 필요 없음)
 * · 알림은 '읽기'만 하고 지우거나 바꾸지 않음 · 통화 직후 요약 알림 말고는 저장하지 않음
 */
class SummaryListener : NotificationListenerService() {
    companion object {
        private const val WINDOW = 20 * 60_000L
        // 통화·요약 앱일 가능성이 높은 패키지 (이름 일부) — 이 앱들은 "요약" 글자가 없어도 진단 기록
        private val CALL_APPS = listOf("skt", "apollo", "adot", "lguplus", "ixi", "callagent", "dialer", "incallui",
            "telecom", "phone", "kt.", "callsummary", "samsung.android.app.notes", "voicenote", "rogcall", "tphone")
        private val KEYS = listOf("요약", "summary", "Summary", "통화 내용", "통화내용", "AI 노트", "핵심")

        fun enabled(c: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

        fun component(c: Context) = ComponentName(c, SummaryListener::class.java)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        val app = applicationContext
        if (s.packageName == app.packageName) return
        val p = CallbackStore.prefs(app)
        val num = p.getString("sum_num", "") ?: ""
        val at = p.getLong("sum_at", 0L)
        if (num.isEmpty() || System.currentTimeMillis() - at > WINDOW) return
        if (!MemoPopup.enabled(app)) return
        if (CallbackStore.cachedConfig(app)?.optBoolean("summary", true) == false) return // 코디야 설정: 통화요약 자동 저장 '사용 안함'

        val ex = s.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() } ?: ""
        val body = listOf(big, lines, text).maxByOrNull { it.length } ?: ""
        val pkg = s.packageName.lowercase()
        val callApp = CALL_APPS.any { pkg.contains(it) }
        val hit = KEYS.any { title.contains(it) || body.take(80).contains(it) }
        if (!hit) {
            if (callApp && (title.isNotEmpty() || body.isNotEmpty()))
                CallbackStore.diag(app, "🔔 통화앱 알림 (요약 아님) " + s.packageName + " · " + title.take(20) + " · ${body.length}자")
            return
        }
        // 같은 알림이 여러 번 갱신돼도 한 번만 (가장 긴 내용이 오면 다시 저장하지 않고, 처음 것만)
        val key = CallbackStore.memoKey(num)
        if (key.isEmpty()) return
        val sig = (s.packageName + "|" + body.take(60)).hashCode().toString()
        if (p.getString("sum_sig_" + key, "") == sig) return

        if (body.length < 25) { // "통화 요약이 완료됐어요" 처럼 내용이 없는 알림
            CallbackStore.diag(app, "🔔 요약 알림 왔지만 내용이 짧아요 " + s.packageName + " · \"" + (title + " " + body).take(40) + "\" → 요약 앱에서 [공유]로 저장해 주세요")
            return
        }
        p.edit().putString("sum_sig_" + key, sig).apply()
        val save = (if (title.isNotEmpty() && !body.startsWith(title)) "$title\n" else "") + body
        Thread {
            val j = CallbackStore.memoAdd(app, key, CallbackStore.mask(num), null, save.take(4000), "ai")
            if (j != null) {
                p.edit().putLong("sum_ok_" + key, System.currentTimeMillis()).apply()
                CallbackStore.diag(app, "🤖 통화요약 자동 저장 " + CallbackStore.mask(num) + " · " + s.packageName + " · ${save.length}자")
                MemoPopup.refresh(app, key)
                MemoActivity.refresh(key)
            } else {
                p.edit().remove("sum_sig_" + key).apply()
                CallbackStore.diag(app, "🤖 통화요약 저장 실패 (인터넷) " + s.packageName)
            }
        }.start()
    }
}
