package kr.codiya.app.callback

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 🎙 통화녹음 AI 요약: 통화가 끝나면 휴대폰 '통화 자동 녹음' 파일을 찾아 서버로 →
 * 받아쓰기 + 한 줄 요약이 그 고객 메모에 저장 (녹음 파일은 서버에 남기지 않음)
 */
object Rec {
    fun on(c: Context) = CallbackStore.prefs(c).getBoolean("rec_on", false)

    fun perm(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun allowed(c: Context) = c.checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED

    /** 받은 통화가 끝났을 때 */
    fun afterCall(c: Context) {
        val app = c.applicationContext
        if (!on(app)) return
        if (!allowed(app)) { CallbackStore.diag(app, "🎙 녹음 요약: '오디오 파일' 권한이 없어요"); return }
        val p = CallbackStore.prefs(app)
        val num = p.getString("sum_num", "") ?: ""
        if (num.isEmpty() || System.currentTimeMillis() - p.getLong("sum_at", 0L) > 60_000L) return
        val endAt = System.currentTimeMillis()
        Thread {
            // 녹음 파일이 저장될 때까지 잠깐 기다리며 찾기
            var found: Pair<Long, String>? = null
            for (i in 0 until 6) {
                Thread.sleep(4000)
                found = find(app, endAt)
                if (found != null) break
            }
            if (found == null) { CallbackStore.diag(app, "🎙 녹음 요약: 녹음 파일이 없어요 (전화 앱 설정 → 통화 녹음 → '자동 녹음'을 켜주세요)"); return@Thread }
            if (p.getLong("rec_last", 0L) == found.first) return@Thread
            p.edit().putLong("rec_last", found.first).apply()
            upload(app, num, found.first, found.second)
        }.start()
    }

    /** 끊기 3분 전 이후에 생긴 통화 녹음 파일 (삼성: Recordings/Call) */
    private fun find(c: Context, endAt: Long): Pair<Long, String>? {
        val since = (endAt - 3 * 60_000L) / 1000
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME)
        return try {
            c.contentResolver.query(col, proj, MediaStore.Audio.Media.DATE_ADDED + " >= ?", arrayOf(since.toString()),
                MediaStore.Audio.Media.DATE_ADDED + " DESC")?.use { cur ->
                while (cur.moveToNext()) {
                    val path = (cur.getString(2) ?: "") + "/" + (cur.getString(3) ?: "")
                    val low = path.lowercase()
                    if (low.contains("call") || path.contains("통화") || low.contains("record")) return@use cur.getLong(0) to (cur.getString(1) ?: "audio/mp4")
                }
                null
            }
        } catch (e: Exception) { CallbackStore.diag(c, "🎙 녹음 찾기 실패 " + e.javaClass.simpleName); null }
    }

    private fun upload(c: Context, num: String, id: Long, mime: String) {
        val token = CallbackStore.prefs(c).getString("token", null) ?: return
        val key = CallbackStore.memoKey(num)
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            val url = URL(CallbackStore.api(c) + "/v1/rec?token=" + URLEncoder.encode(token, "UTF-8") + "&hash=" + key +
                "&mask=" + URLEncoder.encode(CallbackStore.mask(num), "UTF-8"))
            val con = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 200000
                setRequestProperty("Content-Type", mime); setChunkedStreamingMode(64 * 1024)
            }
            c.contentResolver.openInputStream(uri)!!.use { inp -> con.outputStream.use { inp.copyTo(it) } }
            val code = con.responseCode
            val res = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            con.disconnect()
            val ok = res.contains("\"ok\":true")
            CallbackStore.diag(c, if (ok) "🎙 통화녹음 요약 저장 " + CallbackStore.mask(num) else "🎙 녹음 요약 실패 $code " + res.take(80))
            if (ok) {
                CallbackStore.prefs(c).edit().putLong("sum_ok_$key", System.currentTimeMillis()).apply()
                CallbackStore.memoGet(c, key, CallbackStore.mask(num), false)
                MemoPopup.refresh(c, key); MemoActivity.refresh(key)
            }
        } catch (e: Exception) {
            CallbackStore.diag(c, "🎙 녹음 올리기 실패 (" + e.javaClass.simpleName + ")")
        }
    }
}
