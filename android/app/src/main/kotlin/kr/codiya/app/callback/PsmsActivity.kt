package kr.codiya.app.callback

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 📤 PC 문자 발송 설정: 안내·동의 → 문자 권한 → 켜기/끄기 (켜면 서버에 "이 휴대폰이 발송 휴대폰"이라고 알림) */
class PsmsActivity : Activity() {
    private val TEAL = 0xFF0AA394.toInt()
    private val INK = 0xFF0F181D.toInt()
    private val MUTE = 0xFF5B6B73.toInt()
    private lateinit var stateTv: TextView
    private lateinit var btn: TextView

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(0f, 1.25f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(24f), dp(20f), dp(24f)); setBackgroundColor(Color.WHITE) }
        root.addView(tv("📤 PC 문자 발송", 22f, INK, true))
        root.addView(tv("코디야 홈페이지(PC)에서 보낸 문자를 이 휴대폰의 문자로 자동으로 보내요. 내 휴대폰 요금제의 문자로 나가서 문자 비용이 따로 들지 않아요.", 15f, MUTE).apply { setPadding(0, dp(8f), 0, dp(14f)) })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = GradientDrawable().apply { setColor(0xFFF2FBF9.toInt()); cornerRadius = dp(12f).toFloat() }
        }
        listOf(
            "• 같은 코디야 아이디로 로그인한 PC에서 보낸 문자만 보내요.",
            "• 몇 초 간격으로 한 통씩 보내요. 보내는 동안 알림줄에 진행 상황과 [잠깐 멈춤] [그만 보내기]가 보여요.",
            "• 보낸 결과(성공·실패·사유)는 PC 발송내역에서 볼 수 있어요.",
            "• 문자 내용·받는 사람 번호는 발송에만 쓰고 다른 곳에 보내거나 이 휴대폰 문자함을 읽지 않아요.",
            "• 사진 문자는 보낼 수 없어요 (휴대폰 정책). 사진 문자는 PC에서 [문자 업체로(유료)]를 골라 주세요.",
            "• 광고 문자는 밤 9시 ~ 아침 8시에 보내지 않아요 (정보통신망법)."
        ).forEach { box.addView(tv(it, 14f, INK).apply { setPadding(0, dp(3f), 0, dp(3f)) }) }
        root.addView(box)
        stateTv = tv("", 15f, INK, true).apply { setPadding(0, dp(18f), 0, dp(10f)) }
        root.addView(stateTv)
        btn = tv("", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14f), 0, dp(14f))
            setOnClickListener { toggle() }
        }
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tv("💡 배터리 '제한 없음'으로 두면 화면이 꺼져 있어도 바로 보내요. (앱 · 콜백문자 설정의 🔋 단계)", 13f, MUTE).apply { setPadding(0, dp(14f), 0, 0) })
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val on = PsmsService.on(this) && PsmsService.allowed(this)
        stateTv.text = if (on) "✅ 켜져 있어요 · PC에서 보낸 문자를 이 휴대폰으로 보내요" else "⏸ 꺼져 있어요"
        btn.text = if (on) "끄기" else "동의하고 켜기"
        btn.background = GradientDrawable().apply { setColor(if (on) 0xFF8A989F.toInt() else TEAL); cornerRadius = dp(12f).toFloat() }
    }

    private fun toggle() {
        val on = PsmsService.on(this) && PsmsService.allowed(this)
        if (on) { save(false); return }
        AlertDialog.Builder(this)
            .setTitle("PC 문자 발송을 켤까요?")
            .setMessage("PC 코디야에서 보낸 문자를 이 휴대폰 문자로 자동으로 보내요. 이 기능을 쓰려면 '문자 보내기' 권한이 필요해요. (문자함을 읽는 권한은 쓰지 않아요)")
            .setPositiveButton("동의하고 켜기") { _, _ ->
                if (PsmsService.allowed(this)) save(true)
                else if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 7821)
            }
            .setNegativeButton("취소", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7821) {
            if (PsmsService.allowed(this)) save(true)
            else Toast.makeText(this, "문자 보내기 권한을 허용해야 켤 수 있어요", Toast.LENGTH_LONG).show()
        }
    }

    private fun save(on: Boolean) {
        val app = applicationContext
        Thread {
            val tok = CallbackStore.prefs(app).getString("token", "") ?: ""
            var ok = false; var why = ""
            try {
                val con = URL(CallbackStore.api(app) + "/v1/psms/enable").openConnection() as HttpURLConnection
                con.connectTimeout = 8000; con.readTimeout = 12000; con.requestMethod = "POST"; con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json")
                con.outputStream.use { it.write(JSONObject().put("token", tok).put("on", if (on) 1 else 0).put("model", Build.MANUFACTURER + " " + Build.MODEL).toString().toByteArray()) }
                val code = con.responseCode
                val txt = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                val j = runCatching { JSONObject(txt) }.getOrNull()
                ok = code in 200..299 && j?.optBoolean("ok") == true
                if (!ok) why = if (j?.optString("error") == "not_linked") "코디야에 로그인한 뒤 다시 해 주세요" else "인터넷 연결을 확인해 주세요"
                if (ok && on && j?.optBoolean("pending") == true) PsmsService.kick(app)
            } catch (e: Exception) { why = "인터넷 연결을 확인해 주세요" }
            if (ok) CallbackStore.prefs(app).edit().putBoolean("psms_on", on).apply()
            runOnUiThread {
                render()
                Toast.makeText(this, if (ok) (if (on) "✅ PC 문자 발송을 켰어요" else "PC 문자 발송을 껐어요") else "저장하지 못했어요 · $why", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
