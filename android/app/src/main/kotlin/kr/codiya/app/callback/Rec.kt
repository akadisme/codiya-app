package kr.codiya.app.callback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kr.codiya.app.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🎙 통화요약: 받은 통화가 끝나면 휴대폰 '통화 자동 녹음' 파일을 찾아 서버로 →
 * 받아쓰기 + 요약이 그 고객 메모에 저장 (녹음 파일은 서버에 남기지 않음) → "📝 통화요약 도착" 알림
 *  · 처음 5통 무료 체험 · 그 뒤는 요금제 한도 안에서 (서버가 셈 · 1건 = 최대 5분)
 *  · 남은 건수가 없으면 녹음 파일을 올리지 않음 (데이터 · 배터리 아낌)
 *  · 녹음 파일 위치는 휴대폰마다 달라서 폴더를 정하지 않고, 끊은 시각 · 파일 이름(번호 · 저장 이름)으로 찾음
 */
object Rec {
    private const val CH = "rec_summary"

    fun on(c: Context) = CallbackStore.prefs(c).getBoolean("rec_on", false)

    fun perm(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun allowed(c: Context) = c.checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED

    /** 받은 통화가 끝났을 때 */
    fun afterCall(c: Context) {
        val app = c.applicationContext
        if (!on(app)) return
        if (!allowed(app)) { CallbackStore.diag(app, "🎙 통화요약: '오디오 파일' 권한이 없어요"); return }
        val p = CallbackStore.prefs(app)
        val num = p.getString("sum_num", "") ?: ""
        if (num.isEmpty() || System.currentTimeMillis() - p.getLong("sum_at", 0L) > 60_000L) return
        val endAt = System.currentTimeMillis()
        Thread {
            // 남은 건수부터 확인 (없으면 올리지 않음)
            val chk = check(app)
            if (chk != null && !chk.optBoolean("ok") && chk.optString("error") != "stt_off") {
                CallbackStore.diag(app, "🎙 통화요약: " + chk.optString("msg", chk.optString("error")))
                return@Thread
            }
            if (chk != null && chk.optBoolean("ok") && chk.optInt("left", 1) <= 0) {
                CallbackStore.diag(app, "🎙 통화요약: 남은 건수가 없어요")
                quotaNote(app, chk.optString("msg"))
                return@Thread
            }
            // 녹음 파일이 저장될 때까지 잠깐 기다리며 찾기
            var found: Found? = null
            for (i in 0 until 6) {
                Thread.sleep(4000)
                found = find(app, endAt, num)
                if (found != null) break
            }
            if (found == null) { CallbackStore.diag(app, "🎙 통화요약: 녹음 파일이 없어요 (전화 앱 설정 → 통화 녹음 → '자동 녹음'을 켜주세요)"); return@Thread }
            if (p.getLong("rec_last", 0L) == found.id) return@Thread
            p.edit().putLong("rec_last", found.id).apply()
            upload(app, num, found)
        }.start()
    }

    private class Found(val id: Long, val mime: String, val durSec: Int)

    /** GET /v1/rec/check → {ok, left, msg} (인터넷이 안 되면 null → 일단 올려 봄) */
    private fun check(c: Context): JSONObject? {
        val token = CallbackStore.prefs(c).getString("token", null) ?: return null
        return try {
            val con = (URL(CallbackStore.api(c) + "/v1/rec/check?token=" + URLEncoder.encode(token, "UTF-8")).openConnection() as HttpURLConnection)
                .apply { connectTimeout = 6000; readTimeout = 9000 }
            val code = con.responseCode
            val body = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            con.disconnect()
            runCatching { JSONObject(body) }.getOrNull()
        } catch (e: Exception) { null }
    }

    /**
     * 끊기 3분 전 이후에 생긴 녹음 파일 중 통화 녹음
     *  1순위: 파일 이름에 그 번호(또는 휴대폰에 저장된 이름)가 있는 것
     *  2순위: 폴더 · 이름에 call · 통화 · record 가 들어간 것 (삼성 Recordings/Call, LG, 샤오미 call_rec 등)
     */
    private fun find(c: Context, endAt: Long, num: String): Found? {
        val since = (endAt - 3 * 60_000L) / 1000
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val digits = num.filter { it.isDigit() }
        val tail = if (digits.length >= 8) digits.takeLast(8) else ""
        val pname = CallbackStore.phoneName(c, num).replace(" ", "")
        val proj = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION)
        if (Build.VERSION.SDK_INT >= 29) proj.add(MediaStore.Audio.Media.RELATIVE_PATH)
        return try {
            c.contentResolver.query(col, proj.toTypedArray(), MediaStore.Audio.Media.DATE_ADDED + " >= ?", arrayOf(since.toString()),
                MediaStore.Audio.Media.DATE_ADDED + " DESC")?.use { cur ->
                var second: Found? = null
                while (cur.moveToNext()) {
                    val name = cur.getString(3) ?: ""
                    val path = (cur.getString(2) ?: "") + "/" + (if (proj.size > 5) cur.getString(5) ?: "" else "") + "/" + name
                    val low = path.lowercase()
                    val f = Found(cur.getLong(0), cur.getString(1) ?: "audio/mp4", ((cur.getLong(4)) / 1000L).toInt())
                    val nameDigits = name.filter { it.isDigit() }
                    if ((tail.isNotEmpty() && nameDigits.contains(tail)) || (pname.isNotEmpty() && name.replace(" ", "").contains(pname))) return@use f
                    if (second == null && (low.contains("call") || path.contains("통화") || low.contains("record") || low.contains("rec"))) second = f
                }
                second
            }
        } catch (e: Exception) { CallbackStore.diag(c, "🎙 녹음 찾기 실패 " + e.javaClass.simpleName); null }
    }

    private fun upload(c: Context, num: String, f: Found) {
        val token = CallbackStore.prefs(c).getString("token", null) ?: return
        val key = CallbackStore.memoKey(num)
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, f.id)
            val url = URL(CallbackStore.api(c) + "/v1/rec?token=" + URLEncoder.encode(token, "UTF-8") + "&hash=" + key +
                "&mask=" + URLEncoder.encode(CallbackStore.mask(num), "UTF-8") + "&dur=" + f.durSec)
            val con = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 200000
                setRequestProperty("Content-Type", f.mime); setChunkedStreamingMode(64 * 1024)
            }
            c.contentResolver.openInputStream(uri)!!.use { inp -> con.outputStream.use { inp.copyTo(it) } }
            val code = con.responseCode
            val res = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            con.disconnect()
            val j = runCatching { JSONObject(res) }.getOrNull()
            val ok = j?.optBoolean("ok") == true
            CallbackStore.diag(c, if (ok) "🎙 통화요약 저장 " + CallbackStore.mask(num) else "🎙 통화요약 실패 $code " + res.take(80))
            if (!ok && j?.optString("error") == "quota") { quotaNote(c, j.optString("msg")); return }
            if (ok) {
                CallbackStore.prefs(c).edit().putLong("sum_ok_$key", System.currentTimeMillis()).apply()
                CallbackStore.memoGet(c, key, CallbackStore.mask(num), false)
                MemoPopup.refresh(c, key); MemoActivity.refresh(key)
                arrived(c, num, j!!.optString("summary"), j.optBoolean("trial"), j.optInt("left", -1), j.optString("msg"))
            }
        } catch (e: Exception) {
            CallbackStore.diag(c, "🎙 녹음 올리기 실패 (" + e.javaClass.simpleName + ")")
        }
    }

    private fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "📝 통화요약 도착", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    /** "📝 통화요약 도착" 알림 → 누르면 그 고객 메모 */
    private fun arrived(c: Context, num: String, summary: String, trial: Boolean, left: Int, msg: String) {
        channel(c)
        val name = CallbackStore.dispName(c, num, CallbackStore.memoCached(c, CallbackStore.memoKey(num)))
        val who = if (name.isNotEmpty()) name else MemoPopup.pretty(num)
        val first = summary.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "통화 내용을 고객 메모에 저장했어요"
        var text = summary.ifBlank { first }
        if (trial) text += "\n\n🎁 무료 체험" + (if (left in 0..999) " · 남은 건수 ${left}건" else "")
        if (msg.isNotEmpty()) text += "\n" + msg
        val i = Intent(c, MemoActivity::class.java).putExtra("num", num)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(c, 7650 + (num.hashCode() and 0xff), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            NotificationManagerCompat.from(c).notify(7650 + (num.hashCode() and 0xff), NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat_notify).setColor(0xFF0AA394.toInt())
                .setContentTitle("📝 통화요약 도착 · $who")
                .setContentText(first)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }

    /** 남은 건수가 없을 때 알림 (한 달에 한 번만) */
    private fun quotaNote(c: Context, msg: String) {
        val ym = SimpleDateFormat("yyyyMM", Locale.KOREA).format(Date())
        val p = CallbackStore.prefs(c)
        if (p.getString("rec_quota_note", "") == ym) return
        p.edit().putString("rec_quota_note", ym).apply()
        channel(c)
        val t = msg.ifEmpty { "통화요약 건수를 다 썼어요. 전화팝업 · 메모는 그대로 써요." }
        runCatching {
            NotificationManagerCompat.from(c).notify(7649, NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat_notify).setColor(0xFF0AA394.toInt())
                .setContentTitle("🎙 통화요약을 다 썼어요")
                .setContentText(t).setStyle(NotificationCompat.BigTextStyle().bigText(t)).setAutoCancel(true).build())
        }
    }
}
