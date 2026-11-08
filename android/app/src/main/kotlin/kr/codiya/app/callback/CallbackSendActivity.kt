package kr.codiya.app.callback

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 콜백문자 화면 (투명 배경)
 *  · 알림을 누르면: (권한 없으면 '확인 창 켜기' 안내 →) "콜백문자를 보내시겠습니까?" 창 → [보내기] 문자앱 / [닫기]
 *  · 화면 위 창에서 [보내기]: 바로 문자앱 열고 닫힘
 */
class CallbackSendActivity : Activity() {
    private var waitingOverlay = false
    private var confirmShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallbackStore.installCrashLog(this)
        val listJson = intent.getStringExtra("list")
        if (listJson == null) { // 바로 보내기
            send(intent.getStringExtra("num") ?: "", intent.getIntExtra("tpl_id", 0), intent.getStringExtra("text") ?: "",
                intent.getStringExtra("kind") ?: "call", intent.getStringExtra("image") ?: "")
            return
        }
        NotificationManagerCompat.from(this).cancel(CallbackNotifier.notiId(intent.getStringExtra("num") ?: ""))
        val needOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)
        if (needOverlay) {
            setContentView(CallbackPopup.buildGuide(this,
                onLater = { showConfirm() },
                onOk = {
                    waitingOverlay = true
                    if (!CallbackStore.openOverlaySettings(this)) { waitingOverlay = false; showConfirm() }
                }))
        } else {
            showConfirm()
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingOverlay) { // 권한 화면에서 뒤로 돌아오면 바로 보내기 창
            waitingOverlay = false
            showConfirm()
        }
    }

    private fun showConfirm() {
        if (confirmShown) return
        confirmShown = true
        val num = intent.getStringExtra("num") ?: ""
        val missed = intent.getBooleanExtra("missed", false)
        val arr = runCatching { JSONArray(intent.getStringExtra("list")) }.getOrNull()
        val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
        if (list.isEmpty() || num.isEmpty()) { finish(); return }
        setContentView(CallbackPopup.buildCard(this, num, missed, list, intent.getBooleanExtra("hint", false), intent.getIntExtra("sel", 0),
            onClose = { finish() },
            onSend = { t: JSONObject, body: String ->
                send(num, t.optInt("id"), body, if (missed) "missed" else "call", if (t.optBoolean("mms")) t.optString("image") else "")
            }))
    }

    private fun send(num: String, tplId: Int, text: String, kind: String, image: String = "") {
        NotificationManagerCompat.from(this).cancel(CallbackNotifier.notiId(intent.getStringExtra("num") ?: ""))
        if (num.isEmpty()) { finish(); return }
        if (image.isEmpty()) { openSms(num, tplId, text, kind, null); return }
        // 사진 문자: 사진을 받아서(보통 미리 받아둠) 문자앱에 같이 넘김
        val app = applicationContext
        Thread {
            val f = CallbackStore.downloadImage(app, image)
            runOnUiThread { openSms(num, tplId, text, kind, f) }
        }.start()
    }

    private fun openSms(num: String, tplId: Int, text: String, kind: String, img: java.io.File?) {
        var opened = false
        if (img != null) {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.cbfiles", img)
                val mms = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra("address", num)
                    putExtra("sms_body", text)
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    // 기본 문자앱으로 바로 (앱 고르기 창 없이)
                    android.provider.Telephony.Sms.getDefaultSmsPackage(this@CallbackSendActivity)?.let { setPackage(it) }
                }
                startActivity(mms)
                opened = true
            } catch (_: Exception) {
            }
        }
        if (!opened) { // 사진 없이 글자만
            try {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", num, null)).apply {
                    putExtra("sms_body", text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                opened = true
            } catch (_: Exception) {
                Toast.makeText(this, "문자앱을 열 수 없어요", Toast.LENGTH_SHORT).show()
            }
        }
        if (opened) {
            CallbackStore.markSent(this, CallbackStore.hash(num))
            CallbackStore.queueLog(this, tplId, num, kind, "sent")
            val app = applicationContext
            Thread { CallbackStore.flushLogs(app) }.start()
        }
        finish()
    }
}
