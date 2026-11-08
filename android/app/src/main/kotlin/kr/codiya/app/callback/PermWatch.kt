package kr.codiya.app.callback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.codiya.app.R

/**
 * 콜백문자·메모창에 필요한 권한이 (앱 업데이트 등으로) 꺼졌는지 살펴서 알림으로 알려줌.
 * 알림을 누르면 앱의 콜백문자(통화 감지) 화면이 열려 바로 다시 켤 수 있어요.
 * 한 번이라도 켜져 있던 권한만 봐요 (처음부터 안 쓰는 분께는 알림 안 함).
 */
class PermWatch : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) check(context, force = true)
    }

    companion object {
        private const val CH = "perm_watch"
        private const val NOTI_ID = 7900

        /** 지금 권한 상태: 이름 → 켜짐 여부 */
        fun states(c: Context): Map<String, Boolean> {
            fun ok(p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
            var role = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = c.getSystemService(android.app.role.RoleManager::class.java)
                role = rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
            }
            // 확인 창: 일부 휴대폰은 켜 놓아도 꺼짐으로 알려줘서, 최근에 창을 실제로 띄웠으면 켜진 걸로 봄
            val p = CallbackStore.prefs(c)
            val overlayWorked = false // canShow 가 실제로 창을 띄워 보며 확인함
            return linkedMapOf(
                "전화" to ok(Manifest.permission.READ_PHONE_STATE),
                "통화 기록" to ok(Manifest.permission.READ_CALL_LOG),
                "번호 확인" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || role),
                "확인 창(다른 앱 위에 표시)" to (CallbackPopup.canShow(c) || overlayWorked),
            )
        }

        fun verCode(c: Context): Long = runCatching {
            val pi = c.packageManager.getPackageInfo(c.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        }.getOrDefault(0L)

        /** 창을 실제로 띄우는 데 성공했을 때 기록 */
        fun markOverlayOk(c: Context) {
            CallbackStore.prefs(c).edit().putLong("ov_ok_at", System.currentTimeMillis()).putLong("ov_ok_ver", verCode(c)).apply()
        }

        /**
         * 켜져 있던 권한이 꺼졌으면 알림. force=false 면 같은 내용은 12시간에 한 번만.
         * 앱을 열 때·전화 올 때·업데이트 직후에 불러요.
         */
        fun check(c: Context, force: Boolean = false) {
            try {
                val app = c.applicationContext
                val p = CallbackStore.prefs(app)
                val st = states(app)
                val wasOn = (p.getString("perm_on", "") ?: "").split('|').filter { it.isNotEmpty() }.toMutableSet()
                st.forEach { (k, v) -> if (v) wasOn.add(k) }
                p.edit().putString("perm_on", wasOn.joinToString("|")).apply()
                val off = st.filter { (k, v) -> !v && wasOn.contains(k) }.keys.toList()
                if (off.isEmpty()) {
                    NotificationManagerCompat.from(app).cancel(NOTI_ID)
                    p.edit().remove("perm_noti_key").apply()
                    return
                }
                val key = off.joinToString(",")
                val last = p.getLong("perm_noti_at", 0L)
                if (!force && key == p.getString("perm_noti_key", "") && System.currentTimeMillis() - last < 12L * 3600_000L) return
                p.edit().putString("perm_noti_key", key).putLong("perm_noti_at", System.currentTimeMillis()).apply()
                CallbackStore.diag(app, "⚠️ 권한이 꺼졌어요: " + off.joinToString(", ") + " → 알림으로 안내")
                notify(app, off)
            } catch (_: Exception) {}
        }

        private fun notify(c: Context, off: List<String>) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CH, "권한 안내", NotificationManager.IMPORTANCE_HIGH).apply { description = "콜백문자·전화 메모창 권한이 꺼지면 알려줘요" }
                c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            }
            val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.apply {
                putExtra("open_url", "codiya://callback")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pi = open?.let { PendingIntent.getActivity(c, 7901, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
            val text = off.joinToString(", ") + " 권한이 꺼져서 콜백문자·전화 메모창이 안 떠요.\n눌러서 다시 켜 주세요. (앱 업데이트 뒤에 꺼지는 경우가 있어요)"
            val b = NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(0xFFE5604D.toInt())
                .setContentTitle("⚠️ 코디야 권한이 꺼졌어요")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            if (pi != null) b.setContentIntent(pi)
            try { NotificationManagerCompat.from(c).notify(NOTI_ID, b.build()) } catch (_: SecurityException) {}
        }
    }
}
