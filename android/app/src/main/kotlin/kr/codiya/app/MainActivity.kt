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
    /** 알림을 눌러 열 때 보여줄 주소 (예: 콜백문자 설정) — 플러터가 takeOpen 으로 가져감 */
    private var pendingOpen: String? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        CallbackStore.installCrashLog(this)
        pendingOpen = intent?.getStringExtra("open_url")
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("open_url")?.let { pendingOpen = it }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "codiya/callback").setMethodCallHandler { call, result ->
            when (call.method) {
                "setDevice" -> {
                    CallbackStore.setDevice(this, call.argument<String>("token") ?: "", call.argument<String>("api") ?: "")
                    val app = applicationContext
                    Thread { CallbackStore.fetchConfig(app); CallbackStore.flushLogs(app); CallbackStore.reportDiag(app) }.start()
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
                        requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.RECORD_AUDIO), 7703)
                    }
                    result.success(true)
                }
                "requestOverlay" -> {
                    CallbackStore.openOverlaySettings(this)
                    result.success(true)
                }
                "takeOpen" -> { result.success(pendingOpen ?: ""); pendingOpen = null }
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
            "mic" to (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            "memo" to (cfg?.optBoolean("memo", false) ?: false),
            "overlay" to kr.codiya.app.callback.CallbackPopup.canShow(this),
            "crash" to (CallbackStore.prefs(this).getString("crash", "") ?: ""),
            "linked" to (cfg?.optBoolean("linked", false) ?: false),
            "plan" to (cfg?.optString("plan", "") ?: ""),
            "diag" to (CallbackStore.prefs(this).getString("diag", "") ?: ""),
            "templates" to (cfg?.optJSONArray("templates")?.length() ?: 0),
            "auto" to (cfg?.optBoolean("auto", false) ?: false),
        )
    }
}
