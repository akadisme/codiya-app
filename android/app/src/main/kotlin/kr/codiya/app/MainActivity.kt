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
    override fun onResume() {
        super.onResume()
        kr.codiya.app.callback.PsmsService.kick(this) // 📤 PC에서 보낸 문자가 기다리고 있으면 보내기 (푸시를 놓쳤을 때)
        setupResume()
    }

    // ===== ✅ 한 번에 켜기: [동의하고 한 번에 켜기] 한 번 → 창이 차례로 떠요 (허용만 누르면 끝) =====
    //  ① 걸려온 번호 확인(기본 발신번호 표시 앱) → ② 권한 묶음(전화·통화기록·연락처·오디오·문자·알림) → ③ 다른 앱 위에 표시 → ④ 배터리 '제한 없음'
    private var setupStep = 0
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    private fun setupStart() {
        // v1.2.4: 통화녹음 AI 요약은 쓰지 않아요 (에이닷 · 익시오 등 통화요약 자동 저장으로)
        CallbackStore.prefs(this).edit().putBoolean("rec_on", false).putBoolean("setup_done", false).apply()
        setupStep = 1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                runCatching { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 7731); return }
            }
        }
        setupPerms()
    }

    private fun setupPerms() {
        setupStep = 2
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { setupOverlay(); return }
        val want = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 26) want.add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) { afterPerms(); return }
        requestPermissions(need.toTypedArray(), 7732)
    }

    private fun afterPerms() {
        // 📤 PC 문자 발송은 기본으로 켜 둠 (문자 권한을 허용한 경우)
        if (kr.codiya.app.callback.PsmsService.allowed(this) && !kr.codiya.app.callback.PsmsService.on(this)) {
            val app = applicationContext
            Thread { kr.codiya.app.callback.PsmsService.enableServer(app, true) }.start()
        }
        setupOverlay()
    }

    private fun setupOverlay() {
        setupStep = 3
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("목록에서 '코디야'를 켜고 ◀ 뒤로 가기를 눌러 주세요")
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
        }
        setupSummary()
    }

    /** 🤖 통화요약 자동 저장: 알림 읽기 허용 (에이닷 · 익시오 · 갤럭시 AI 요약을 고객 메모에) */
    private fun setupSummary() {
        setupStep = 4
        if (!kr.codiya.app.callback.SummaryListener.enabled(this)) {
            toast("목록에서 '코디야'를 켜고 ◀ 뒤로 가기를 눌러 주세요 (통화요약 자동 저장)")
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, kr.codiya.app.callback.SummaryListener.component(this).flattenToString()))
                else startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }.isSuccess || runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.isSuccess
            if (ok) return
        }
        setupBattery()
    }

    private fun setupBattery() {
        setupStep = 5
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("[배터리] → '제한 없음'을 고르고 ◀ 뒤로 가기를 눌러 주세요")
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))); return }
        }
        setupFinish()
    }

    private fun setupFinish() {
        setupStep = 0
        CallbackStore.prefs(this).edit().putBoolean("setup_done", true).apply()
        toast("✅ 다 켜졌어요! 이제 전화가 오면 전화팝업이 떠요")
    }

    /** 설정 화면에서 돌아왔을 때 다음 단계로 */
    private fun setupResume() {
        when (setupStep) {
            3 -> setupSummary()
            4 -> setupBattery()
            5 -> setupFinish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7731 && setupStep == 1) setupPerms()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7732 && setupStep == 2) afterPerms()
    }

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
                "setupAll" -> { setupStart(); result.success(true) } // ✅ 한 번에 켜기
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
                "psmsOpen" -> { // 📤 PC 문자 발송 설정
                    startActivity(Intent(this, kr.codiya.app.callback.PsmsActivity::class.java)); result.success(true)
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
            "psms" to (kr.codiya.app.callback.PsmsService.on(this) && kr.codiya.app.callback.PsmsService.allowed(this)),
            "auto" to (cfg?.optBoolean("auto", false) ?: false),
            "contacts" to (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED),
            "sms" to kr.codiya.app.callback.PsmsService.allowed(this),
            "setup_done" to CallbackStore.prefs(this).getBoolean("setup_done", false),
        )
    }
}
