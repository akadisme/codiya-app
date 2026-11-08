package kr.codiya.app.callback

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 에이닷·갤럭시 AI 통화 요약 → [공유] → 코디야
 * 최근 걸려온 번호를 골라서 그 고객 메모에 'AI 요약'으로 날짜별 저장
 */
class MemoShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = (intent.getStringExtra(Intent.EXTRA_TEXT) ?: "").trim()
        if (intent.action != Intent.ACTION_SEND || text.isEmpty()) { finish(); return }
        if (CallbackStore.prefs(this).getString("token", null) == null) {
            Toast.makeText(this, "앱을 한 번 열고 로그인해주세요", Toast.LENGTH_LONG).show(); finish(); return
        }
        // 요약 글 안에 번호가 있으면 그걸 먼저
        val inText = Regex("(\\*\\d{3})?0\\d{1,2}[- .]?\\d{3,4}[- .]?\\d{4}").find(text)?.value?.let { CallbackStore.digits(it) }
        val recent = CallbackStore.recent(this)
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        val nums = ArrayList<String>(); val labels = ArrayList<String>()
        if (inText != null && inText.length >= 9) { nums.add(inText); labels.add("📄 요약 속 번호 " + CallbackStore.pretty(inText)) }
        for (r in recent) {
            val n = r.optString("n"); if (n.isEmpty() || nums.contains(n)) continue
            val nm = CallbackStore.memoCached(this, CallbackStore.memoKey(n))?.optString("name", "") ?: ""
            nums.add(n); labels.add("📞 " + fmt.format(Date(r.optLong("t"))) + "  " + (if (nm.isNotEmpty()) "$nm · " else "") + CallbackStore.pretty(n))
            if (nums.size >= 12) break
        }
        nums.add(""); labels.add("⌨️ 번호 직접 입력")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("📒 어느 고객의 통화 요약인가요?")
            .setItems(labels.toTypedArray()) { _, i -> if (nums[i].isEmpty()) askNumber(text) else save(nums[i], text) }
            .setNegativeButton("닫기") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun askNumber(text: String) {
        val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_PHONE; hint = "010-0000-0000" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("고객 전화번호")
            .setView(et)
            .setPositiveButton("저장") { _, _ ->
                val n = CallbackStore.digits(et.text.toString())
                if (n.length >= 9) save(n, text) else { Toast.makeText(this, "번호를 확인해주세요", Toast.LENGTH_SHORT).show(); finish() }
            }
            .setNegativeButton("닫기") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun save(num: String, text: String) {
        val app = applicationContext
        Toast.makeText(this, "📒 저장하는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val j = CallbackStore.memoAdd(app, CallbackStore.memoKey(num), CallbackStore.mask(num), null, text, "share")
            runOnUiThread {
                Toast.makeText(app, if (j != null) "📒 ${CallbackStore.pretty(num)} 메모에 AI 요약을 저장했어요" else "저장하지 못했어요. 인터넷을 확인해주세요", Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
}
