package kr.codiya.app.callback

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi

/**
 * 걸려온 전화 번호 알아내기 (전화를 막지 않고 그대로 통과)
 * 안드로이드는 '주소록에 없는 번호'일 때만 이 서비스를 불러줌 → 저장 안 된 고객 번호만 잡힘
 */
@RequiresApi(Build.VERSION_CODES.N)
class CallScreen : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        try {
            CallbackStore.installCrashLog(this)
            val incoming = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                details.callDirection == Call.Details.DIRECTION_INCOMING
            val num = CallbackStore.digits(details.handle?.schemeSpecificPart)
            if (incoming && num.length >= 9) {
                CallbackStore.addCallNum(this, num) // 통화 중 대기 전화도 차례로 쌓음
                MemoPopup.onRinging(this, num)      // 📒 받기 전에 메모창
            }
        } catch (_: Exception) {
        }
        respondToCall(details, CallResponse.Builder().build())
    }
}
