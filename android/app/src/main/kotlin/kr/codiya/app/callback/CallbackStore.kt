package kr.codiya.app.callback

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 콜백문자 저장소: 기기 토큰, 서버 설정(캐시), 통화 상태, 오늘 보낸 번호, 보낼 기록 */
object CallbackStore {
    private const val PREFS = "cd_callback"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setDevice(c: Context, token: String, apiBase: String) {
        prefs(c).edit().putString("token", token).putString("api", apiBase).apply()
    }

    /** 번호 정리: 숫자만 + 투넘버 앞자리(*281 등)는 그대로 (온 번호 그대로 답장) */
    fun digits(n: String?): String {
        val raw = (n ?: "").filter { it.isDigit() || it == '*' || it == '#' }
        if (raw.isEmpty()) return raw
        val head = raw.takeWhile { it == '*' || it == '#' }
        val rest = raw.drop(head.length).filter { it.isDigit() }
        if (head.isNotEmpty()) return head + rest
        return if (rest.startsWith("82") && rest.length >= 11) "0" + rest.substring(2) else rest
    }

    /** (예전엔 가린 번호) 이제는 서버에도 전체 번호로 보냄 → 서버·앱 표시 모양 같게 */
    fun mask(d: String): String = pretty(d)

    /** 화면에 보여줄 전체 번호 (가림 없이): 010-1234-5678 · 투넘버는 *281 010-1234-5678 */
    fun pretty(d: String): String {
        val m = Regex("^([*#]\\d{3})(\\d{7,})$").find(d)
        if (m != null) return m.groupValues[1] + " " + pretty(m.groupValues[2])
        val n = d.filter { it.isDigit() }
        return when {
            n.length == 11 -> n.substring(0, 3) + "-" + n.substring(3, 7) + "-" + n.substring(7)
            n.length == 10 && n.startsWith("02") -> n.substring(0, 2) + "-" + n.substring(2, 6) + "-" + n.substring(6)
            n.length == 10 -> n.substring(0, 3) + "-" + n.substring(3, 6) + "-" + n.substring(6)
            n.length == 9 && n.startsWith("02") -> n.substring(0, 2) + "-" + n.substring(2, 5) + "-" + n.substring(5)
            n.length == 8 -> n.substring(0, 4) + "-" + n.substring(4)
            else -> n
        }
    }

    fun hash(d: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest("grcb|$d".toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    /** 최근 통화 감지 결과 (설정 화면에 표시 → 창이 안 뜬 이유 확인용) */
    /** 앱이 멈추면(오류) 그 이유를 '최근 통화 감지 기록'에 남김 → 화면 캡처로 원인 확인 */
    @Volatile private var crashOn = false
    fun installCrashLog(c: Context) {
        if (crashOn) return
        crashOn = true
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val at = e.stackTrace.firstOrNull { it.className.startsWith("kr.codiya") } ?: e.stackTrace.firstOrNull()
                val msg = "⚠️ 앱 오류: " + e.javaClass.simpleName + " " + (e.message ?: "").take(70) + (if (at != null) " @" + at.fileName + ":" + at.lineNumber else "")
                val tm = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
                val old = prefs(app).getString("diag", "") ?: ""
                val lines = ("$tm  $msg\n" + old).lines().filter { it.isNotBlank() }.take(8)
                prefs(app).edit().putString("diag", lines.joinToString("\n"))
                    .putString("crash", tm + " " + android.util.Log.getStackTraceString(e).take(3000)).commit()
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun diag(c: Context, msg: String) {
        val t = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
        val old = prefs(c).getString("diag", "") ?: ""
        val lines = ("$t  $msg\n" + old).lines().filter { it.isNotBlank() }.take(8)
        prefs(c).edit().putString("diag", lines.joinToString("\n")).apply()
        reportDiagSoon(c)
    }

    /** 감지 기록을 서버로 → PC 콜백문자 화면(휴대폰 설정 방법 탭)에서 보고 복사 */
    @Volatile private var reportAt = 0L
    fun reportDiagSoon(c: Context) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        if (now - reportAt < 4000L) return // 잠깐 사이 여러 줄이면 한 번만
        reportAt = now
        Thread {
            try {
                Thread.sleep(3000L)
                reportDiag(app)
            } catch (_: Exception) {}
        }.start()
    }

    fun diagText(c: Context): String {
        val p = prefs(c)
        fun ok(perm: String) = androidx.core.content.ContextCompat.checkSelfPermission(c, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var role = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val rm = c.getSystemService(android.app.role.RoleManager::class.java)
            role = rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
        }
        val ver = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: ""
        val head = "코디야 앱 v" + ver + " · 안드로이드 " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MODEL +
            "\n확인 창 " + (if (CallbackPopup.canShow(c)) "켜짐" else "꺼짐") +
            " · 통화 기록 " + (if (ok(android.Manifest.permission.READ_CALL_LOG)) "허용" else "안 됨") +
            " · 전화 " + (if (ok(android.Manifest.permission.READ_PHONE_STATE)) "허용" else "안 됨") +
            " · 번호 확인 " + (if (role) "켜짐" else "꺼짐")
        // 기본 전화앱과 깔린 전화앱들 (삼성 '간단한 팝업' 설정은 기본 전화앱이 삼성 '전화'일 때만 먹혀요)
        val pm = c.packageManager
        fun label(pkg: String?): String = if (pkg.isNullOrEmpty()) "?" else runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() + " (" + pkg + ")" }.getOrDefault(pkg)
        val defDialer = runCatching { (c.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager).defaultDialerPackage }.getOrNull()
        val dialers = runCatching {
            pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_DIAL), 0).map { it.activityInfo.packageName }.distinct().map { label(it) }
        }.getOrDefault(emptyList())
        val dial = "\n기본 전화앱: " + label(defDialer) + (if (dialers.isNotEmpty()) "\n깔린 전화앱: " + dialers.joinToString(", ") else "")
        val crash = p.getString("crash", "") ?: ""
        return head + dial + "\n\n" + (p.getString("diag", "") ?: "") + (if (crash.isNotEmpty()) "\n\n[마지막 오류]\n" + crash.take(1500) else "")
    }

    fun reportDiag(c: Context) {
        val token = prefs(c).getString("token", null) ?: return
        try {
            val con = (URL(api(c) + "/v1/diag").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 10000
                setRequestProperty("Content-Type", "application/json")
            }
            val o = JSONObject().put("token", token).put("text", diagText(c))
            con.outputStream.use { it.write(o.toString().toByteArray()) }
            con.responseCode
            con.disconnect()
        } catch (_: Exception) {}
    }

    // ---------- 전화 메모 ----------
    /** 메모용 고객 키: 투넘버 앞자리(*281)는 빼고 실제 번호로 (서버 gr_cb_memo_key 와 같은 방식) */
    fun memoKey(num: String): String {
        val d = num.replace(Regex("^[*#]\\d{3}"), "").filter { it.isDigit() }
        if (d.length < 8) return ""
        val md = MessageDigest.getInstance("SHA-1").digest("grmemo|$d".toByteArray())
        val k = md.joinToString("") { "%02x".format(it) }
        keyNum[k] = d
        return k
    }

    /** 코디야: 메모 키 → 실제 번호 (코디야 연락처·고객 스토리와 연결하려고 서버에 번호를 함께 보냄) */
    private val keyNum = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun numOf(c: Context, key: String): String {
        val n = keyNum[key]
        if (n != null) { prefs(c).edit().putString("num_$key", n).apply(); return n }
        return prefs(c).getString("num_$key", "") ?: ""
    }

    /** '다른 앱 위에 표시' 설정 열기: 코디야 전용 화면 → 전체 목록 → 앱 정보 순서로 시도 */
    fun openOverlaySettings(a: android.app.Activity): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return false
        val tries = listOf(
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + a.packageName)),
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.packageName)),
        )
        for (i in tries) {
            try { a.startActivity(i); return true } catch (_: Exception) {}
        }
        return false
    }

    /** 사이트 주소 (https://codiya.kr) */
    fun siteUrl(c: Context): String = api(c).substringBefore("/cdapi/").ifEmpty { "https://codiya.kr" }

    private fun api(c: Context): String = prefs(c).getString("api", "https://codiya.kr/cdapi/app/index.php") ?: "https://codiya.kr/cdapi/app/index.php"

    /** 메모 불러오기 (실패하면 저장해둔 것) — 백그라운드에서 */
    fun memoGet(c: Context, key: String, mask: String, call: Boolean): JSONObject? {
        val token = prefs(c).getString("token", null) ?: return memoCached(c, key)
        return try {
            val url = URL(api(c) + "/v1/memo?token=" + URLEncoder.encode(token, "UTF-8") + "&hash=" + key +
                "&mask=" + URLEncoder.encode(mask, "UTF-8") + "&call=" + (if (call) 1 else 0) +
                "&phone=" + URLEncoder.encode(numOf(c, key), "UTF-8"))
            val con = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 3500; readTimeout = 4500 }
            val body = (if (con.responseCode in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            con.disconnect()
            val j = JSONObject(body)
            if (j.optBoolean("ok") && !j.optBoolean("off")) prefs(c).edit().putString("memo_$key", body).apply()
            j
        } catch (_: Exception) {
            memoCached(c, key)
        }
    }

    fun memoCached(c: Context, key: String): JSONObject? =
        prefs(c).getString("memo_$key", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** 메모 저장 (name 이 null 이면 이름은 그대로) → 새 메모 묶음 또는 null — 백그라운드에서 */
    /** 마지막 저장 실패 이유 (화면 안내용) */
    @Volatile var lastErr: String = ""

    fun memoAdd(c: Context, key: String, mask: String, name: String?, body: String, kind: String, queueOnFail: Boolean = true): JSONObject? {
        val token = prefs(c).getString("token", null)
        if (token == null) { lastErr = "코디야 앱에 로그인이 안 되어 있어요"; return null }
        val phone = numOf(c, key)
        val j = memoAddOnce(c, token, key, mask, name, body, kind, phone)
        if (j == null && queueOnFail && (body.isNotEmpty() || name != null)) queueMemo(c, key, mask, name, body, kind, phone)
        if (j != null) Thread { flushMemoQueue(c) }.start()
        return j
    }

    private fun memoAddOnce(c: Context, token: String, key: String, mask: String, name: String?, body: String, kind: String, phone: String): JSONObject? {
        return try {
            val con = (URL(api(c) + "/v1/memo/add").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 25000
                setRequestProperty("Content-Type", "application/json")
            }
            val o = JSONObject().put("token", token).put("hash", key).put("mask", mask).put("body", body).put("kind", kind).put("phone", phone)
            if (name != null) o.put("name", name)
            con.outputStream.use { it.write(o.toString().toByteArray()) }
            val res = (if (con.responseCode in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            con.disconnect()
            val j = JSONObject(res)
            if (j.optBoolean("ok")) { lastErr = ""; prefs(c).edit().putString("memo_$key", res).apply(); j }
            else { lastErr = j.optString("msg", "서버가 저장을 거절했어요"); null }
        } catch (e: Exception) {
            lastErr = "인터넷 연결이 약해요 (연결되면 자동으로 다시 저장해요)"
            null
        }
    }

    /** 저장 못 한 메모는 휴대폰에 잠시 두었다가 다음에 자동으로 다시 저장 */
    @Synchronized
    private fun queueMemo(c: Context, key: String, mask: String, name: String?, body: String, kind: String, phone: String) {
        val p = prefs(c)
        val arr = runCatching { org.json.JSONArray(p.getString("memo_q", "[]")) }.getOrDefault(org.json.JSONArray())
        if (arr.length() >= 50) return
        arr.put(JSONObject().put("key", key).put("mask", mask).put("name", name ?: JSONObject.NULL).put("body", body).put("kind", kind).put("phone", phone))
        p.edit().putString("memo_q", arr.toString()).apply()
    }

    @Synchronized
    fun flushMemoQueue(c: Context) {
        val p = prefs(c)
        val token = p.getString("token", null) ?: return
        val arr = runCatching { org.json.JSONArray(p.getString("memo_q", "[]")) }.getOrDefault(org.json.JSONArray())
        if (arr.length() == 0) return
        val left = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nm = if (o.isNull("name")) null else o.optString("name")
            val ok = memoAddOnce(c, token, o.optString("key"), o.optString("mask"), nm, o.optString("body"), o.optString("kind"), o.optString("phone")) != null
            if (!ok) left.put(o)
        }
        p.edit().putString("memo_q", left.toString()).apply()
    }

    /** 최근 걸려온 번호 (에이닷 요약 공유할 때 고르기용, 휴대폰 안에만 저장) */
    @Synchronized
    fun addRecent(c: Context, num: String, name: String = "") {
        val p = prefs(c)
        val arr = runCatching { org.json.JSONArray(p.getString("recent", "[]")) }.getOrDefault(org.json.JSONArray())
        val out = org.json.JSONArray().put(JSONObject().put("n", num).put("t", System.currentTimeMillis()).put("name", name))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("n") != num && out.length() < 30) out.put(o)
        }
        p.edit().putString("recent", out.toString()).apply()
    }

    fun recent(c: Context): List<JSONObject> {
        val arr = runCatching { org.json.JSONArray(prefs(c).getString("recent", "[]")) }.getOrDefault(org.json.JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    // ---------- 이번 통화 동안 걸려온 번호들 (통화 중 대기 전화 포함) ----------
    @Synchronized
    fun addCallNum(c: Context, num: String) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val arr = runCatching { org.json.JSONArray(p.getString("call_nums", "[]")) }.getOrDefault(org.json.JSONArray())
        val out = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("n") != num && now - o.optLong("t") < 3 * 60 * 60 * 1000L) out.put(o)
        }
        out.put(JSONObject().put("n", num).put("t", now))
        p.edit().putString("call_nums", out.toString()).apply()
    }

    /** 이번 통화 묶음의 번호들을 꺼내고 비움 (최근 3시간 안, 걸려온 순서대로) */
    @Synchronized
    fun takeCallNums(c: Context): List<String> {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val arr = runCatching { org.json.JSONArray(p.getString("call_nums", "[]")) }.getOrDefault(org.json.JSONArray())
        p.edit().putString("call_nums", "[]").apply()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { now - it.optLong("t") < 3 * 60 * 60 * 1000L }
            .sortedBy { it.optLong("t") }.map { it.optString("n") }.filter { it.isNotEmpty() }
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())

    // ---------- 서버 설정 ----------
    fun cachedConfig(c: Context): JSONObject? =
        prefs(c).getString("config", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** 서버에서 새로 받기 (실패하면 저장된 것) — 반드시 백그라운드에서 호출 */
    fun fetchConfig(c: Context, timeoutMs: Int = 3500): JSONObject? {
        val p = prefs(c)
        if (p.getString("memo_q", "[]") != "[]") Thread { runCatching { flushMemoQueue(c) } }.start()
        val token = p.getString("token", null) ?: return cachedConfig(c)
        val api = p.getString("api", "https://codiya.kr/cdapi/app/index.php")!!
        return try {
            val url = URL("$api/v1/callback/config?token=" + URLEncoder.encode(token, "UTF-8"))
            val con = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs
            }
            val code = con.responseCode
            val body = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            con.disconnect()
            val j = JSONObject(body)
            if (j.optBoolean("ok")) {
                p.edit().putString("config", body).putLong("config_at", System.currentTimeMillis()).apply()
                cacheImages(c, j)
                j
            } else cachedConfig(c)
        } catch (e: Exception) {
            cachedConfig(c)
        }
    }

    // ---------- 사진 문자용 사진 (미리 받아두기) ----------
    fun imageFile(c: Context, url: String): java.io.File {
        val dir = java.io.File(c.cacheDir, "cb_img").apply { mkdirs() }
        val name = MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$name.jpg")
    }

    /** 사진을 받아둠 (이미 있으면 그대로) → 파일 또는 null. 백그라운드에서 */
    fun downloadImage(c: Context, url: String, timeoutMs: Int = 6000): java.io.File? {
        if (!url.startsWith("https://")) return null
        val f = imageFile(c, url)
        if (f.exists() && f.length() > 0) return f
        return try {
            val con = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = timeoutMs; readTimeout = timeoutMs }
            if (con.responseCode !in 200..299) { con.disconnect(); return null }
            val tmp = java.io.File(f.path + ".part")
            con.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            con.disconnect()
            if (tmp.length() > 0 && tmp.renameTo(f)) f else null
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheImages(c: Context, cfg: JSONObject) {
        val arr = cfg.optJSONArray("templates") ?: return
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optBoolean("mms")) downloadImage(c, t.optString("image"))
        }
    }

    // ---------- 하루 1번 (중복 방지) ----------
    fun sentToday(c: Context, hash: String, cfg: JSONObject?): Boolean {
        val p = prefs(c)
        if (p.getString("sent_day", "") == today() && (p.getStringSet("sent_hashes", emptySet()) ?: emptySet()).contains(hash)) return true
        val arr = cfg?.optJSONArray("sent_today") ?: return false
        if (cfg.optString("today") != today()) return false
        for (i in 0 until arr.length()) if (arr.optString(i) == hash) return true
        return false
    }

    fun markSent(c: Context, hash: String) {
        val p = prefs(c)
        val set = if (p.getString("sent_day", "") == today()) HashSet(p.getStringSet("sent_hashes", emptySet()) ?: emptySet()) else HashSet()
        set.add(hash)
        p.edit().putString("sent_day", today()).putStringSet("sent_hashes", set).apply()
    }

    // ---------- 발송 기록 → 서버 ----------
    fun queueLog(c: Context, tplId: Int, digits: String, kind: String, result: String) {
        val p = prefs(c)
        val arr = runCatching { JSONArray(p.getString("logq", "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("tpl_id", tplId).put("mask", mask(digits)).put("hash", hash(digits)).put("kind", kind).put("result", result).put("phone", digits))
        p.edit().putString("logq", arr.toString()).apply()
    }

    /** 쌓인 기록 보내기 — 백그라운드에서 */
    @Synchronized
    fun flushLogs(c: Context) {
        val p = prefs(c)
        val token = p.getString("token", null) ?: return
        val api = p.getString("api", "https://codiya.kr/cdapi/app/index.php")!!
        val arr = runCatching { JSONArray(p.getString("logq", "[]")) }.getOrDefault(JSONArray())
        if (arr.length() == 0) return
        try {
            val con = (URL("$api/v1/callback/log").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 8000
                setRequestProperty("Content-Type", "application/json")
            }
            con.outputStream.use { it.write(JSONObject().put("token", token).put("items", arr).toString().toByteArray()) }
            val code = con.responseCode
            con.disconnect()
            // 성공했거나 다시 보내도 소용없는 경우(로그인 연결 안 됨 등) 비우기
            if (code in 200..299 || code == 403 || code == 404) {
                val now = runCatching { JSONArray(p.getString("logq", "[]")) }.getOrDefault(JSONArray())
                val rest = JSONArray()
                for (i in arr.length() until now.length()) rest.put(now.get(i))
                p.edit().putString("logq", rest.toString()).apply()
            }
        } catch (_: Exception) {
        }
    }
}
