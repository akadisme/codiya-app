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

    fun mask(d: String): String {
        val m = Regex("^([*#]\\d{3})(\\d{7,})$").find(d)
        if (m != null) return m.groupValues[1] + "-" + mask(m.groupValues[2])
        val n = d.filter { it.isDigit() }
        return if (n.length < 7) "***" else n.substring(0, 3) + "-****-" + n.takeLast(4)
    }

    fun hash(d: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest("grcb|$d".toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    /** 최근 통화 감지 결과 (설정 화면에 표시 → 창이 안 뜬 이유 확인용) */
    fun diag(c: Context, msg: String) {
        val t = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
        val old = prefs(c).getString("diag", "") ?: ""
        val lines = ("$t  $msg\n" + old).lines().filter { it.isNotBlank() }.take(5)
        prefs(c).edit().putString("diag", lines.joinToString("\n")).apply()
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
    fun memoAdd(c: Context, key: String, mask: String, name: String?, body: String, kind: String): JSONObject? {
        val token = prefs(c).getString("token", null) ?: return null
        return try {
            val con = (URL(api(c) + "/v1/memo/add").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 25000
                setRequestProperty("Content-Type", "application/json")
            }
            val o = JSONObject().put("token", token).put("hash", key).put("mask", mask).put("body", body).put("kind", kind).put("phone", numOf(c, key))
            if (name != null) o.put("name", name)
            con.outputStream.use { it.write(o.toString().toByteArray()) }
            val res = (if (con.responseCode in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            con.disconnect()
            val j = JSONObject(res)
            if (j.optBoolean("ok")) { prefs(c).edit().putString("memo_$key", res).apply(); j } else null
        } catch (_: Exception) {
            null
        }
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
