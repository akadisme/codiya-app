package kr.codiya.app.callback

import com.google.firebase.messaging.RemoteMessage
import io.flutter.plugins.firebase.messaging.FlutterFirebaseMessagingService

/** 푸시 받기: "📤 PC 문자 보낼 차례" 조용한 신호면 발송 시작, 나머지는 원래대로(앱 알림) */
class CodiyaFcmService : FlutterFirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (remoteMessage.data["type"] == "psms") {
            PsmsService.kick(applicationContext)
            return
        }
        super.onMessageReceived(remoteMessage)
    }
}
