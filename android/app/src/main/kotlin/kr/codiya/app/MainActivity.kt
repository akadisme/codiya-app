package kr.codiya.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kr.codiya.app.callback.CallbackNotifier
import kr.codiya.app.callback.CallbackStore

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "codiya/callback").setMethodCallHandler { call, result ->
            when (call.method) {
                "setDevice" -> {
                    CallbackStore.setDevice(this, call.argument<String>("token") ?: "", call.argument<String>("api") ?: "")
                    val app = applicationContext
                    Thread { CallbackStore.fetchConfig(app); CallbackStore.flushLogs(app); CallbackStore.uploadDiag(app) }.start()
                    result.success(true)
                }
                "status" -> result.success(status())
                "installer" -> result.success(runCatching {
                    if (Build.VERSION.SDK_INT >= 30) packageManager.getInstallSourceInfo(packageName).installingPackageName
                    else @Suppress("DEPRECATION") packageManager.getInstallerPackageName(packageName)
                }.getOrNull() ?: "")
                "requestRole" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val rm = getSystemService(RoleManager::class.java)
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 7701)
                        }
                    }
                    result.success(true)
                }
                "requestPhone" -> {
                    val perms = mutableListOf(Manifest.permission.READ_PHONE_STATE)
                    if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) requestPermissions(perms.toTypedArray(), 7702)
                    result.success(true)
                }
                "requestMemo" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val perms = mutableListOf(Manifest.permission.READ_CALL_LOG)
                        if (Build.VERSION.SDK_INT >= 26) perms.add(Manifest.permission.ANSWER_PHONE_CALLS) // 메모 화면 [📞 전화 받기]
                        requestPermissions(perms.toTypedArray(), 7703)
                    }
                    result.success(true)
                }
                "cardRegister" -> { // 📇 명함만 찍어서 등록
                    startActivity(Intent(this, kr.codiya.app.callback.CardActivity::class.java)); result.success(true)
                }
                "recOn" -> { // 🎙 통화녹음 AI 요약 켜기/끄기
                    val on = call.argument<Boolean>("on") ?: true
                    kr.codiya.app.callback.CallbackStore.prefs(this).edit().putBoolean("rec_on", on).apply()
                    if (on && !kr.codiya.app.callback.Rec.allowed(this)) requestPermissions(arrayOf(kr.codiya.app.callback.Rec.perm()), 7711)
                    result.success(true)
                }
                "requestSummary" -> { // 🤖 통화요약 자동 저장: 알림 읽기 허용
                    try {
                        if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, kr.codiya.app.callback.SummaryListener.component(this).flattenToString()))
                        else startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (e: Exception) {
                        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    }
                    result.success(true)
                }
                "openBattery" -> { // 앱 정보 → 배터리 '제한 없음' · 모바일 데이터 '백그라운드 허용'
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    result.success(true)
                }
                "requestOverlay" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }
                    result.success(true)
                }
                "openAppSettings" -> {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    result.success(true)
                }
                "test" -> {
                    val app = applicationContext
                    val missed = call.argument<Boolean>("missed") ?: false
                    Thread { CallbackNotifier.afterCall(app, "01000000000", missed, test = true) }.start()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun status(): Map<String, Any> {
        val sdk = Build.VERSION.SDK_INT
        var role = false
        if (sdk >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            role = rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        }
        val phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val noti = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val cfg = CallbackStore.cachedConfig(this)
        return mapOf(
            "supported" to (sdk >= Build.VERSION_CODES.Q),
            "role" to role, "phone" to phone, "notify" to noti,
            "calllog" to (checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED),
            "summary" to kr.codiya.app.callback.SummaryListener.enabled(this),
            "rec" to (kr.codiya.app.callback.Rec.on(this) && kr.codiya.app.callback.Rec.allowed(this)),
            "mic" to (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            "memo" to (cfg?.optBoolean("memo", false) ?: false),
            "overlay" to (sdk < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)),
            "linked" to (cfg?.optBoolean("linked", false) ?: false),
            "plan" to (cfg?.optString("plan", "") ?: ""),
            "battery" to (Build.VERSION.SDK_INT < 23 || (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)),
            "saver" to (Build.VERSION.SDK_INT >= 24 && (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED),
            "phone_state" to CallbackStore.phoneState(this),
            "diag" to (CallbackStore.prefs(this).getString("diag", "") ?: ""),
            "templates" to (cfg?.optJSONArray("templates")?.length() ?: 0),
            "auto" to (cfg?.optBoolean("auto", false) ?: false),
        )
    }
}
