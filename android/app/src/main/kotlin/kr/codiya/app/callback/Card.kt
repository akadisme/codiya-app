package kr.codiya.app.callback

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 📇 명함 사진: 찍기·고르기 → 줄여서 서버로 · 받은 사진은 휴대폰 캐시에 */
object Card {
    const val REQ = 7710

    fun dir(c: Context) = File(c.cacheDir, "cards").apply { mkdirs() }
    fun file(c: Context, key: String) = File(dir(c), "$key.jpg")
    private fun capFile(c: Context) = File(dir(c), "_cap.jpg")

    /** 사진 찍기 + 갤러리 고르기 한 번에 */
    fun pick(a: Activity) {
        val cap = capFile(a); cap.delete()
        val uri = FileProvider.getUriForFile(a, a.packageName + ".cbfiles", cap)
        val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val gallery = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        val chooser = Intent.createChooser(gallery, "📇 명함 사진").putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        try { a.startActivityForResult(chooser, REQ) } catch (e: Exception) { Toast.makeText(a, "사진 앱을 열 수 없어요", Toast.LENGTH_SHORT).show() }
    }

    /** 사진 찍기만 (연속 등록용) */
    fun camera(a: Activity, req: Int) {
        val cap = capFile(a); cap.delete()
        val uri = FileProvider.getUriForFile(a, a.packageName + ".cbfiles", cap)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { a.startActivityForResult(i, req) } catch (e: Exception) { Toast.makeText(a, "카메라를 열 수 없어요", Toast.LENGTH_SHORT).show(); a.finish() }
    }

    /** 갤러리에서 여러 장 고르기 */
    fun gallery(a: Activity, req: Int) {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { a.startActivityForResult(Intent.createChooser(i, "📇 명함 사진 고르기 (여러 장 가능)"), req) } catch (e: Exception) { a.finish() }
    }

    /** 결과의 사진들 (여러 장이면 모두) */
    fun uris(data: Intent?): List<Uri> {
        val out = mutableListOf<Uri>()
        val clip = data?.clipData
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
        else data?.data?.let { out.add(it) }
        return out
    }

    fun fromUri(c: Context, uri: Uri): ByteArray? = try { shrink(c, c.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) } catch (_: Exception) { null }
    fun fromCamera(c: Context): ByteArray? = capFile(c).takeIf { it.exists() && it.length() > 0 }?.readBytes()?.let { shrink(c, it) }.also { capFile(c).delete() }

    /** 결과 → 줄인 jpg (가로 최대 1280) */
    fun result(c: Context, data: Intent?): ByteArray? {
        val u = data?.data
        return if (u != null) fromUri(c, u) else fromCamera(c)
    }

    private fun shrink(c: Context, raw: ByteArray): ByteArray? {
        return try {
            var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply {
                inSampleSize = run { val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(raw, 0, raw.size, o); var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 2600) s *= 2; s }
            }) ?: return null
            // 사진 방향 바로잡기
            val rot = try { when (ExifInterface(raw.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f } } catch (_: Exception) { 0f }
            if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            val max = 1280f
            val r = minOf(1f, max / maxOf(bmp.width, bmp.height))
            if (r < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt(), (bmp.height * r).toInt(), true)
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        } catch (e: Exception) {
            CallbackStore.diag(c, "명함 사진 읽기 실패 " + e.javaClass.simpleName); null
        }
    }

    /** 서버로 (key 없으면 AI가 명함 번호를 읽어서 붙임) */
    fun upload(c: Context, key: String?, num: String?, jpg: ByteArray): JSONObject? {
        val token = CallbackStore.prefs(c).getString("token", null) ?: return null
        return try {
            val con = (URL(CallbackStore.api(c) + "/v1/card").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 60000
                setRequestProperty("Content-Type", "application/json")
            }
            val o = JSONObject().put("token", token).put("img", Base64.encodeToString(jpg, Base64.NO_WRAP))
            if (!key.isNullOrEmpty()) o.put("hash", key)
            if (!num.isNullOrEmpty()) o.put("num", num)
            con.outputStream.use { it.write(o.toString().toByteArray()) }
            val res = (if (con.responseCode in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
            con.disconnect()
            val j = JSONObject(res)
            val k = j.optString("hash")
            if (j.optBoolean("ok") && k.length == 40) {
                file(c, k).writeBytes(jpg)
                CallbackStore.prefs(c).edit().putString("memo_$k", res).putString("card_v_$k", ver(j.optString("card"))).apply()
            }
            j
        } catch (e: Exception) { null }
    }

    fun delete(c: Context, key: String): JSONObject? {
        val token = CallbackStore.prefs(c).getString("token", null) ?: return null
        return try {
            val con = (URL(CallbackStore.api(c) + "/v1/card/del").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 10000
                setRequestProperty("Content-Type", "application/json")
            }
            con.outputStream.use { it.write(JSONObject().put("token", token).put("hash", key).toString().toByteArray()) }
            val res = con.inputStream.bufferedReader().readText(); con.disconnect()
            file(c, key).delete()
            JSONObject(res).also { CallbackStore.prefs(c).edit().putString("memo_$key", res).remove("card_v_$key").apply() }
        } catch (e: Exception) { null }
    }

    private fun ver(url: String) = Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1) ?: ""

    /** 메모를 불러올 때: 명함이 있으면 휴대폰에 받아두기 (바뀌었을 때만) */
    fun sync(c: Context, key: String, j: JSONObject) {
        val url = j.optString("card")
        val f = file(c, key)
        if (url.isEmpty()) { if (f.exists()) f.delete(); return }
        val v = ver(url); val p = CallbackStore.prefs(c)
        if (f.exists() && p.getString("card_v_$key", "") == v) return
        try {
            val con = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 8000 }
            if (con.responseCode == 200) { f.writeBytes(con.inputStream.readBytes()); p.edit().putString("card_v_$key", v).apply() }
            con.disconnect()
        } catch (_: Exception) {}
    }

    fun bitmap(c: Context, key: String): Bitmap? = file(c, key).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}

/**
 * 📇 명함 등록 (앱 메뉴 · 콜백문자 설정) — 여러 장 연속으로
 *  · 📷 찍기: 한 장 찍으면 AI가 읽어서 등록 → 바로 다음 장 카메라 (취소하면 끝)
 *  · 🖼 고르기: 갤러리에서 여러 장 한꺼번에
 *  · 번호를 못 읽으면 직접 입력 / 휴대폰·사무실 번호가 둘 다 있으면 둘 다에 붙임 (서버)
 */
class CardActivity : Activity() {
    private val REQ_CAM = 7721
    private val REQ_GAL = 7722
    private val queue = ArrayDeque<Uri>()
    private var camMode = false
    private var done = 0
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        android.app.AlertDialog.Builder(this).setTitle("📇 명함 등록")
            .setMessage("AI가 명함의 이름·회사·전화번호를 읽어서 등록해요. 그 고객이 전화하면 받기 전에 명함이 떠요.")
            .setPositiveButton("📷 찍기 (여러 장 연속)") { _, _ -> camMode = true; Card.camera(this, REQ_CAM) }
            .setNegativeButton("🖼 사진 고르기") { _, _ -> Card.gallery(this, REQ_GAL) }
            .setOnCancelListener { finish() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { finishAll(); return }
        if (requestCode == REQ_CAM) process(null)
        else if (requestCode == REQ_GAL) { queue.addAll(Card.uris(data)); next() }
    }

    private fun next() {
        if (busy) return
        val u = queue.removeFirstOrNull()
        if (u == null) { if (camMode) Card.camera(this, REQ_CAM) else finishAll(); return }
        process(u)
    }

    /** 사진 한 장 처리 (uri 없으면 방금 찍은 사진) */
    private fun process(uri: Uri?, num: String? = null, jpgIn: ByteArray? = null) {
        busy = true
        val app = applicationContext
        Toast.makeText(app, "📇 AI가 명함을 읽는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val jpg = jpgIn ?: (if (uri != null) Card.fromUri(app, uri) else Card.fromCamera(app))
            val j = if (jpg != null) Card.upload(app, null, num, jpg) else null
            runOnUiThread {
                busy = false
                if (isFinishing) return@runOnUiThread
                when {
                    jpg == null -> { Toast.makeText(app, "사진을 읽지 못했어요", Toast.LENGTH_SHORT).show(); next() }
                    j == null -> { Toast.makeText(app, "등록하지 못했어요. 인터넷을 확인해주세요", Toast.LENGTH_LONG).show(); next() }
                    j.optString("error") == "no_phone" -> askNumber(jpg, j.optJSONObject("read")?.optString("name") ?: "")
                    !j.optBoolean("ok") -> { Toast.makeText(app, "등록하지 못했어요 (" + j.optString("error") + ")", Toast.LENGTH_LONG).show(); next() }
                    else -> {
                        done++
                        val nm = j.optJSONObject("read")?.optString("name")?.takeIf { it.isNotEmpty() } ?: "명함"
                        Toast.makeText(app, "✅ $nm · " + MemoPopup.pretty(j.optString("num")) + " 등록 ($done)", Toast.LENGTH_SHORT).show()
                        next()
                    }
                }
            }
        }.start()
    }

    /** 명함에서 번호를 못 읽었을 때: 직접 입력 */
    private fun askNumber(jpg: ByteArray, name: String) {
        val et = android.widget.EditText(this).apply {
            hint = "010-0000-0000"; inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("📇 번호를 못 읽었어요" + (if (name.isNotEmpty()) " ($name)" else ""))
            .setMessage("명함의 전화번호를 적어주세요")
            .setView(et)
            .setPositiveButton("등록") { _, _ ->
                val n = et.text.toString().filter { it.isDigit() }
                if (n.length >= 9) process(null, n, jpg) else { Toast.makeText(this, "번호를 확인해주세요", Toast.LENGTH_SHORT).show(); next() }
            }
            .setNegativeButton("건너뛰기") { _, _ -> next() }
            .setCancelable(false)
            .show()
    }

    private fun finishAll() {
        if (done > 0) Toast.makeText(applicationContext, "📇 명함 ${done}장 등록했어요", Toast.LENGTH_LONG).show()
        finish()
    }
}
