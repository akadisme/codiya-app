package kr.codiya.app.callback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * 통화 상태 변화: 울림 → 받음 → 끊김 = 통화 종료 / 울림 → 끊김 = 부재중
 * 번호는 CallScreen(번호 확인)이 따로 저장 — 휴대폰마다 '울림'과 '번호 확인' 중 무엇이 먼저 올지 달라서
 * 둘을 따로 기록하고, 통화가 끝날 때 최근 10분 안에 확인된 번호를 씀
 */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val p = CallbackStore.prefs(context)
        val ph = p.getString("ph", "idle") ?: "idle"
        val now = System.currentTimeMillis()
        // 📒 전화 메모창: 저장된 연락처 포함 모든 전화 (번호는 '통화 기록' 권한이 있을 때 같이 옴)
        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            @Suppress("DEPRECATION")
            val inc = CallbackStore.digits(intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
            if (inc.length >= 8) MemoPopup.onRinging(context, inc)
        }
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING ->
                if (ph == "talking") p.edit().putBoolean("ph_wait", true).apply() // 통화 중 대기 전화
                else p.edit().putString("ph", "ringing").putLong("ph_at", now).apply()
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                MemoNotify.stopRinging() // 받으면 알림 다시 띄우기 멈춤
                MemoPopup.onTalking(context) // 통화 중 "📒 메모 보기·쓰기" 알림
                if (ph == "ringing") p.edit().putString("ph", "talking").apply()
                else if (ph != "talking") p.edit().putString("ph", "outgoing").apply() // 내가 건 전화
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                MemoNotify.stopRinging()
                MemoNotify.hideTalking(context)
                MemoActivity.finishOnCallEnd() // 위쪽 반 화면 메모는 닫고 (적던 건 저장) → 끊은 뒤 메모창
                MemoPopup.onCallEnd(context) // 끊으면 그 번호 메모창 (띄우면 알림은 지움)
                if (ph == "talking") Rec.afterCall(context) // 🎙 통화녹음 AI 요약 (켜둔 경우만)
                // 모든 통화가 끝났을 때 한 번만 옴 (같은 알림이 두 번 와도 한 번만 처리)
                p.edit().putString("ph", "idle").putBoolean("ph_wait", false).apply()
                if (ph != "ringing" && ph != "talking") {
                    if (ph == "outgoing") { CallbackStore.takeCallNums(context); MemoPopup.onOutgoingEnd(context) } // 📞 발신 후 메모창 (설정에서 켠 경우만)
                    return
                }
                val missed = ph == "ringing"
                val nums = CallbackStore.takeCallNums(context)
                if (nums.isEmpty()) {
                    CallbackStore.diag(context, (if (missed) "부재중" else "통화 끝남") +
                        " · 번호 확인 안 됨 (저장된 연락처이거나 1단계 '번호 확인'이 꺼져 있어요)")
                    return
                }
                val pending = goAsync()
                Thread {
                    try {
                        // 통화 중에 걸려온 번호까지 모두 차례로 (창은 하나 닫으면 다음 것이 떠요)
                        for (n in nums) CallbackNotifier.afterCall(context.applicationContext, n, missed)
                        CallbackStore.flushLogs(context.applicationContext)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
