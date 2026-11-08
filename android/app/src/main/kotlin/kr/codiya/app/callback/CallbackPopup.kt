package kr.codiya.app.callback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 통화가 끝나면 화면 가운데 뜨는 창: "콜백문자를 보내시겠습니까?"
 * [보내기] → 내용이 채워진 문자앱 열기 / [닫기] → 아무것도 안 보냄
 * ('다른 앱 위에 표시' 권한이 있을 때만, 없으면 알림줄로)
 */
object CallbackPopup {
    private const val GOLD = 0xFF0AA394.toInt()
    private const val GOLD2 = 0xFF2CC2B0.toInt()
    private const val INK = 0xFF0F181D.toInt()
    private const val MUTE = 0xFF5B6870.toInt()
    private var current: View? = null
    /** 콜백 확인 창이 떠 있는지 (메모창이 그 위로 올라가지 않게) */
    fun isShowing(): Boolean = current != null
    // 아직 못 띄운 창 (통화 중 대기 전화): first = 창으로 띄우기, second = 알림줄로 보내기
    private val waiting = ArrayDeque<Pair<() -> Unit, () -> Unit>>()

    /** '다른 앱 위에 표시' 허용 여부: 일부 휴대폰은 켜 놓아도 canDrawOverlays 가 늦게 바뀌어서 시스템 기록(AppOps)도 같이 봄 */
    fun canShow(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (Settings.canDrawOverlays(c)) return true
        return try {
            val ops = c.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow("android:system_alert_window", android.os.Process.myUid(), c.packageName)
                       else @Suppress("DEPRECATION") ops.checkOpNoThrow("android:system_alert_window", android.os.Process.myUid(), c.packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    /** 백그라운드에서 호출 → 화면에 띄우고 성공 여부 반환 */
    fun show(c: Context, num: String, missed: Boolean, list: List<JSONObject>, hint: Boolean, fallback: () -> Unit = {}): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        Handler(Looper.getMainLooper()).post {
            ok = try { add(c.applicationContext, num, missed, list, hint, fallback); true } catch (e: Exception) { false }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    private fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    private fun round(color: Int, r: Float, c: Context) = GradientDrawable().apply { setColor(color); cornerRadius = dp(c, r).toFloat() }

    private fun add(c: Context, num: String, missed: Boolean, list: List<JSONObject>, hint: Boolean, fallback: () -> Unit) {
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (current != null) { // 이미 창이 떠 있으면 닫힌 뒤에 차례로
            waiting.addLast(Pair({ runCatching { add(c, num, missed, list, hint, fallback) }; Unit }, fallback))
            return
        }
        lateinit var root: View
        root = buildCard(c, num, missed, list, hint, 0,
            onClose = { close(wm, root) }, // 닫기 → 다음 번호 창
            onSend = { t, body ->
                // 창이 떠 있는 동안 문자앱 열기 (안드로이드 백그라운드 실행 규칙)
                val i = Intent(c, CallbackSendActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("num", num); putExtra("tpl_id", t.optInt("id")); putExtra("text", body)
                    putExtra("kind", if (missed) "missed" else "call")
                    if (t.optBoolean("mms")) putExtra("image", t.optString("image"))
                }
                runCatching { c.startActivity(i) }
                close(wm, root, sending = true) // 보내는 중 → 다음 번호는 문자앱을 가리지 않게 알림줄로
            })
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        )
        wm.addView(root, params)
        PermWatch.markOverlayOk(c)
        current = root
        root.requestFocus()
        // 에이닷·T전화처럼 통화 후 자기 화면을 띄우는 전화앱보다 앞에 오게 몇 번 다시 올림
        val h = Handler(Looper.getMainLooper())
        for (ms in longArrayOf(600L, 1500L, 2800L, 4500L)) h.postDelayed({
            // 다른 창(에이닷 통화 후 화면 등)에 가려져 포커스를 잃었을 때만 다시 올림 → 안 가려졌으면 깜빡이지 않음
            if (current === root && root.isAttachedToWindow && !root.hasWindowFocus())
                runCatching { wm.removeViewImmediate(root); wm.addView(root, params); root.requestFocus() }
        }, ms)
    }

    /** "콜백문자를 보내시겠습니까?" 창 화면 (화면 위 창 · 알림을 눌렀을 때 화면 둘 다 사용) */
    @SuppressLint("ClickableViewAccessibility")
    fun buildCard(c: Context, num: String, missed: Boolean, list: List<JSONObject>, hint: Boolean, initial: Int,
                  onClose: () -> Unit, onSend: (JSONObject, String) -> Unit): View {
        var selected = initial.coerceIn(0, list.size - 1)
        val root = object : FrameLayout(c) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { onClose(); return true }
                return super.dispatchKeyEvent(event)
            }
        }
        root.setBackgroundColor(0x99000000.toInt())
        root.isFocusableInTouchMode = true

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.WHITE, 24f, c)
            setPadding(dp(c, 20f), dp(c, 20f), dp(c, 20f), dp(c, 16f))
            elevation = dp(c, 12f).toFloat()
            isClickable = true
        }
        fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val lp = { top: Float -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, top) } }

        card.addView(text("📨 콜백문자", 13f, GOLD, true))
        card.addView(text("콜백문자를 보내시겠습니까?", 19f, INK, true), lp(4f))
        val masked = CallbackStore.pretty(num)
        card.addView(text(if (missed) "📵 부재중 전화 · $masked" else "📞 통화 종료 · $masked", 14.5f, MUTE), lp(4f))
        if (hint) {
            card.addView(text("🎯 ${list[0].optString("title")} 홈페이지에서 온 전화예요", 13.5f, 0xFF1D7D5C.toInt(), true).apply {
                background = round(0xFFE3F6EE.toInt(), 10f, c); setPadding(dp(c, 10f), dp(c, 6f), dp(c, 10f), dp(c, 6f))
            }, lp(10f))
        }

        // 미리보기
        val preview = text("", 14f, 0xFF2B2B2B.toInt()).apply { setLineSpacing(0f, 1.25f) }
        fun body(i: Int) = if (missed) list[i].optString("text_missed") else list[i].optString("text")
        val mmsTag = text("📷 사진도 같이 보내요", 12.5f, 0xFF3A86D8.toInt(), true)

        // 현장 고르기 (2개 이상일 때)
        val chips = ArrayList<TextView>()
        fun paint() {
            chips.forEachIndexed { i, t ->
                t.background = if (i == selected) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(GOLD2, GOLD)).apply { cornerRadius = dp(c, 99f).toFloat() }
                               else round(0xFFEEF2F3.toInt(), 99f, c)
                t.setTextColor(if (i == selected) Color.WHITE else 0xFF3B4850.toInt())
            }
            preview.text = body(selected)
            mmsTag.visibility = if (list[selected].optBoolean("mms")) View.VISIBLE else View.GONE
        }
        if (list.size > 1) {
            card.addView(text("어떤 문자를 보낼까요?", 13.5f, MUTE, true), lp(14f))
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            list.forEachIndexed { i, t ->
                val chip = text(t.optString("title"), 14f, INK, true).apply {
                    setPadding(dp(c, 14f), dp(c, 8f), dp(c, 14f), dp(c, 8f))
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(c, 160f)
                    setOnClickListener { selected = i; paint() }
                }
                chips.add(chip)
                row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(c, 6f) })
            }
            card.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp(6f))
        }
        val scroll = ScrollView(c).apply {
            background = round(0xFFEEF0F3.toInt(), 16f, c)
            setPadding(dp(c, 14f), dp(c, 12f), dp(c, 14f), dp(c, 12f))
            addView(preview)
        }
        val maxH = (c.resources.displayMetrics.heightPixels * 0.36).toInt()
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, 12f) })
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (scroll.height > maxH) scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = maxH }
        }

        card.addView(mmsTag, lp(8f))

        // 버튼
        val btns = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val close = text("닫기", 16f, 0xFF3B4850.toInt(), true).apply {
            gravity = Gravity.CENTER; background = round(0xFFEEF2F3.toInt(), 14f, c)
            setPadding(0, dp(c, 14f), 0, dp(c, 14f))
            setOnClickListener { onClose() }
        }
        val send = text("💬 보내기", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(GOLD2, GOLD)).apply { cornerRadius = dp(c, 14f).toFloat() }
            setPadding(0, dp(c, 14f), 0, dp(c, 14f))
            setOnClickListener { onSend(list[selected], body(selected)) }
        }
        btns.addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(c, 8f) })
        btns.addView(send, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        card.addView(btns, lp(16f))
        card.addView(text("문자앱이 열리면 전송 버튼을 한 번 더 눌러주세요", 12f, MUTE).apply { gravity = Gravity.CENTER }, lp(10f))

        val w = minOf(c.resources.displayMetrics.widthPixels - dp(c, 32f), dp(c, 440f))
        root.addView(card, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.setOnTouchListener { _, _ -> true } // 바깥을 눌러도 닫히지 않게 (실수 방지)
        paint()
        return root
    }

    /** 권한 안내 창: "다른 앱 위에 표시"를 켜면 다음부터 통화 후 바로 창이 떠요 */
    fun buildGuide(c: Context, onLater: () -> Unit, onOk: () -> Unit): View {
        val root = FrameLayout(c).apply { setBackgroundColor(0x99000000.toInt()) }
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.WHITE, 24f, c)
            setPadding(dp(c, 22f), dp(c, 22f), dp(c, 22f), dp(c, 18f))
        }
        fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); setLineSpacing(0f, 1.3f)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val lp = { top: Float -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, top) } }
        card.addView(tv("📨 콜백문자", 13f, GOLD, true))
        card.addView(tv("콜백문자를 사용하시려면\n'다른 앱 위에 표시'를 켜주세요", 18f, INK, true), lp(6f))
        card.addView(tv("다음 화면에서 [코디야]를 찾아 켜고 뒤로 가기를 누르면 바로 보내기 창이 나와요.", 14.5f, MUTE), lp(10f))
        val btns = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val later = tv("나중에", 16f, 0xFF3B4850.toInt(), true).apply {
            gravity = Gravity.CENTER; background = round(0xFFEEF2F3.toInt(), 14f, c)
            setPadding(0, dp(c, 14f), 0, dp(c, 14f)); setOnClickListener { onLater() }
        }
        val ok = tv("확인", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF37C495.toInt(), 0xFF21A67A.toInt())).apply { cornerRadius = dp(c, 14f).toFloat() }
            setPadding(0, dp(c, 14f), 0, dp(c, 14f)); setOnClickListener { onOk() }
        }
        btns.addView(later, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(c, 8f) })
        btns.addView(ok, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        card.addView(btns, lp(18f))
        val w = minOf(c.resources.displayMetrics.widthPixels - dp(c, 32f), dp(c, 440f))
        root.addView(card, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root
    }

    private fun close(wm: WindowManager, v: View, sending: Boolean = false) {
        runCatching { wm.removeView(v) }
        if (current === v) {
            current = null
            if (sending) {
                while (waiting.isNotEmpty()) { val f = waiting.removeFirst().second; Thread { runCatching { f() } }.start() }
            } else {
                waiting.removeFirstOrNull()?.let { next -> Handler(Looper.getMainLooper()).postDelayed({ next.first() }, 350) }
            }
        }
    }
}
