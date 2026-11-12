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
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * 📒 전화 메모창: 전화가 울리면 받기 전에 화면 위쪽에 떠요
 *  · 날짜별 기록(최근 것 위, 길면 드래그로 스크롤) + 새 메모 (키보드 🎤 마이크로 말해서 적기)
 *  · [작게] → 한 줄 띠로 줄어듦 · 위아래로 끌어서 옮김 · [✕] 닫기
 *  · 전화 받기 버튼을 가리지 않게 창 밖 터치는 그대로 통과
 */
object MemoPopup {
    private const val GOLD = 0xFF0AA394.toInt()
    private const val GOLD2 = 0xFF2CC2B0.toInt()
    private const val INK = 0xFF0F181D.toInt()
    private const val MUTE = 0xFF5B6870.toInt()
    private const val SKY = 0xFF3A86D8.toInt()

    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var curNum = ""
    private var curKey = ""
    private var data: JSONObject? = null
    private var small = false
    @Volatile private var lastKey = ""
    @Volatile private var lastAt = 0L

    // 화면 조각
    private var memoEt: EditText? = null
    private var nameRow: LinearLayout? = null
    private var nameEt: EditText? = null
    private var histBox: LinearLayout? = null
    private var titleTv: TextView? = null
    private var subTv: TextView? = null
    private var bodyBox: LinearLayout? = null
    private var smallTv: TextView? = null
    private var micTv: TextView? = null
    private var cardBox: LinearLayout? = null   // 한 화면: 위 메모 + 아래 콜백문자
    private var memoPart: LinearLayout? = null
    private var cbPart: LinearLayout? = null
    private var histScroll: ScrollView? = null

    /** 보기 좋은 번호 (가리지 않고 전체): 010-8678-3059 · *281 010-2068-2684 */
    fun pretty(num: String): String {
        val m = Regex("^([*#]\\d{3})(\\d+)$").find(num)
        if (m != null) return m.groupValues[1] + " " + pretty(m.groupValues[2])
        val d = num.filter { it.isDigit() }
        return when {
            d.length == 11 -> d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7)
            d.length == 10 && d.startsWith("02") -> "02-" + d.substring(2, 6) + "-" + d.substring(6)
            d.length == 10 -> d.substring(0, 3) + "-" + d.substring(3, 6) + "-" + d.substring(6)
            d.length == 9 && d.startsWith("02") -> "02-" + d.substring(2, 5) + "-" + d.substring(5)
            else -> num
        }
    }

    private fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()
    private fun round(c: Context, color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(c, r).toFloat() }
    private fun grad(c: Context, a: Int, b: Int, r: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { cornerRadius = dp(c, r).toFloat() }

    /** 메모창 쓸 수 있는 상태인지 (권한·설정) */
    fun enabled(c: Context): Boolean {
        // (확인 창 권한은 여기서 보지 않음: 일부 휴대폰은 켜 놓아도 꺼짐으로 알려줘서 알림까지 막혔음 → 창은 띄워 보고 실패하면 알림으로)
        val cfg = CallbackStore.cachedConfig(c) ?: return false
        if (cfg.has("allowed") && !cfg.optBoolean("allowed")) return false
        return cfg.optBoolean("memo", false)
    }

    // 통화 후 메모 방식: 울릴 때는 알림만, 끊으면 그 번호 메모창
    @Volatile private var pendNum = ""
    @Volatile private var pendKey = ""

    /** 전화가 울릴 때 (어느 스레드에서 불러도 됨): 위쪽 알림으로 지난 메모 → 통화 중에도 화면 위를 쓸어내리면 보임 */
    fun onRinging(c: Context, num: String) {
        val app = c.applicationContext
        if (num.length < 8 || !enabled(app)) return
        val key = CallbackStore.memoKey(num)
        if (key.isEmpty()) return
        synchronized(this) { // 같은 전화로 두 번 불려도 한 번만 (전화 상태 · 번호 확인) — 끊고 바로 다시 와도 뜨게 15초
            val now = System.currentTimeMillis()
            if (key == lastKey && now - lastAt < 15_000L) {
                pendNum = num; pendKey = key
                CallbackStore.prefs(app).edit().putString("memo_pend", num).putLong("memo_pend_at", now).apply()
                return
            }
            lastKey = key; lastAt = now
        }
        pendNum = num; pendKey = key
        CallbackStore.prefs(app).edit().putString("memo_pend", num).putLong("memo_pend_at", System.currentTimeMillis()).apply()
        CallbackStore.addRecent(app, num)
        MemoNotify.startRinging(app)
        MemoNotify.post(app, num, CallbackStore.memoCached(app, key) ?: JSONObject().put("_loading", true))
        CallbackStore.diag(app, "☎ 전화 옴 " + CallbackStore.mask(num) + " · 위쪽 알림에 메모 표시 (끊으면 메모창)")
        load(app, num, key, call = true)
        Thread { CallbackStore.fetchConfig(app) }.start() // 다음 전화 대비 설정 새로고침
    }

    private fun load(app: Context, num: String, key: String, call: Boolean) {
        Thread {
            val j = CallbackStore.memoGet(app, key, CallbackStore.mask(num), call, if (call) num else "")
            if (j.optBoolean("off")) { main.post { if (curKey == key) close(app) }; return@Thread }
            MemoNotify.post(app, num, j)
            main.post { if (curKey == key) { data = j; render(app) } }
        }.start()
    }

    /** 전화를 받았을 때: 통화 중 "📒 메모 보기·쓰기" 알림 */
    fun onTalking(c: Context) {
        val app = c.applicationContext
        var num = pendNum
        if (num.isEmpty()) {
            val pp = CallbackStore.prefs(app)
            val n = pp.getString("memo_pend", "") ?: ""
            if (n.isNotEmpty() && System.currentTimeMillis() - pp.getLong("memo_pend_at", 0L) < 10 * 60_000L) num = n
        }
        if (num.isEmpty() || !enabled(app)) return
        MemoNotify.showTalking(app, num)
    }

    /** 다른 곳(통화요약 자동 저장 등)에서 메모가 바뀌었을 때: 열려 있는 메모창 새로고침 */
    fun refresh(c: Context, key: String) {
        val app = c.applicationContext
        main.post { if (curKey == key && root != null) load(app, curNum, key, call = false) }
    }

    /** 이번 통화 요약이 자동 저장됐는지 (메모창에 "🤖 통화요약 저장 완료") */
    fun summaryNote(c: Context, key: String): String {
        val t = CallbackStore.prefs(c).getLong("sum_ok_" + key, 0L)
        return if (t > 0 && System.currentTimeMillis() - t < 60 * 60_000L) "🤖 통화요약 저장 완료" else ""
    }

    /**
     * 📞 내가 건 전화가 끝났을 때 (설정: 0 사용 안함 · 1 저장 안 된 번호만 · 2 모든 발신)
     * 건 번호는 통화기록에서 가져옴 (안드로이드는 거는 중에 다른 앱이 번호를 알기 어려워서 끊은 뒤에)
     */
    fun onOutgoingEnd(c: Context) {
        val app = c.applicationContext
        val cfg = CallbackStore.cachedConfig(app) ?: return
        val mode = cfg.optInt("memo_out", 0)
        if (mode <= 0 || (cfg.has("allowed") && !cfg.optBoolean("allowed"))) return
        if (app.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            CallbackStore.diag(app, "📞 발신 메모: '통화 기록' 권한이 없어요"); return
        }
        Thread {
            Thread.sleep(1500) // 통화기록에 저장될 때까지 잠깐
            var num = ""; var name = ""
            try {
                app.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI,
                    arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.CACHED_NAME, android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.DURATION),
                    android.provider.CallLog.Calls.TYPE + "=?", arrayOf(android.provider.CallLog.Calls.OUTGOING_TYPE.toString()),
                    android.provider.CallLog.Calls.DATE + " DESC")?.use { cur ->
                    if (cur.moveToFirst()) {
                        val end = cur.getLong(2) + cur.getLong(3) * 1000L
                        if (System.currentTimeMillis() - end < 3 * 60_000L) { num = CallbackStore.digits(cur.getString(0)); name = cur.getString(1) ?: "" }
                    }
                }
            } catch (e: Exception) { CallbackStore.diag(app, "📞 발신 메모: 통화기록 읽기 실패 " + e.javaClass.simpleName) }
            if (num.length < 8) return@Thread
            if (mode == 1 && name.isNotEmpty()) return@Thread // 저장된 연락처는 건너뜀
            pendNum = num; pendKey = CallbackStore.memoKey(num)
            CallbackStore.diag(app, "📞 발신 끝 " + CallbackStore.mask(num) + " → 메모창")
            onCallEnd(app)
        }.start()
    }

    /** 통화가 끝났을 때: 그 번호 메모창을 띄움 (지난 메모 + 새로 쓰기). 못 띄우면 알림을 그대로 둠 */
    fun onCallEnd(c: Context) {
        val app = c.applicationContext
        var num = pendNum; var key = pendKey
        val pp = CallbackStore.prefs(app)
        if (key.isEmpty()) { // 앱이 잠깐 꺼졌다 켜진 경우: 저장해 둔 번호로 (30분 안)
            val n = pp.getString("memo_pend", "") ?: ""
            if (n.isNotEmpty() && System.currentTimeMillis() - pp.getLong("memo_pend_at", 0L) < 30 * 60_000L) { num = n; key = CallbackStore.memoKey(n) }
        }
        pendNum = ""; pendKey = ""
        pp.edit().remove("memo_pend").apply()
        if (num.isNotEmpty()) pp.edit().putString("sum_num", num).putLong("sum_at", System.currentTimeMillis()).apply() // 통화요약 자동 저장용
        if (key.isEmpty()) { MemoNotify.cancel(app); return }
        main.post {
            if (curKey == key && root != null) { MemoNotify.cancel(app); load(app, num, key, call = false); return@post }
            val ok = runCatching { show(app, num, key) }
            if (ok.isSuccess) {
                MemoNotify.cancel(app)
                CallbackStore.diag(app, "☎ 통화 끝 " + CallbackStore.mask(num) + " · ✅ 메모창 띄움")
                load(app, num, key, call = false)
            } else {
                root = null; curKey = ""
                val e = ok.exceptionOrNull()
                CallbackStore.diag(app, "☎ 통화 끝 " + CallbackStore.mask(num) + " · 메모창을 못 띄움 (" + (e?.javaClass?.simpleName ?: "") +
                    (if (!CallbackPopup.canShow(app)) " · 다른 앱 위에 표시 꺼짐" else "") + ") → 알림으로 메모 확인")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(c: Context, num: String, key: String) {
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (root != null) saveDraft(c) // 다른 번호(통화 중 대기 전화): 적던 메모는 먼저 저장하고 바꿈
        // 메모창은 항상 1개만: 전에 띄운 창이 남아 있으면 모두 닫고 새로
        attached.forEach { v -> runCatching { wm.removeView(v) } }
        attached.clear()
        root = null
        curNum = num; curKey = key; small = false
        data = CallbackStore.memoCached(c, key)

        val r = FrameLayout(c).apply { setPadding(dp(c, 8f), 0, dp(c, 8f), 0) }
        val outer = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = round(c, Color.WHITE, 20f)
            elevation = dp(c, 10f).toFloat()
            setPadding(dp(c, 14f), dp(c, 12f), dp(c, 14f), dp(c, 12f))
        }
        cardBox = outer
        val card = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL } // 메모 부분
        memoPart = card
        outer.addView(card)
        cbPart = null
        fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); setLineSpacing(0f, 1.25f)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun chip(s: String, bg: Int, fg: Int) = tv(s, 13f, fg, true).apply {
            gravity = Gravity.CENTER; background = round(c, bg, 10f); setPadding(dp(c, 10f), dp(c, 6f), dp(c, 10f), dp(c, 6f))
        }
        val lp = { top: Float -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, top) } }

        // 머리 (끌어서 옮기기)
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        titleTv = tv("📒 불러오는 중…", 16f, INK, true)
        subTv = tv(pretty(num), 12.5f, MUTE)
        titles.addView(titleTv); titles.addView(subTv)
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val minBtn = chip("작게", 0xFFEEF2F3.toInt(), 0xFF3B4850.toInt()).apply { setOnClickListener { setSmall(c, true) } }
        head.addView(minBtn)
        card.addView(head)

        // 이름 고치기 (제목을 누르면 열림)
        val nr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        nameRow = nr
        nameEt = EditText(c).apply {
            hint = "이름 (예: 김민수 34평)"; setSingleLine(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = round(c, 0xFFF5F7F8.toInt(), 12f); setPadding(dp(c, 12f), dp(c, 9f), dp(c, 12f), dp(c, 9f))
        }
        nr.addView(nameEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nr.addView(chip("이름 저장", GOLD, Color.WHITE).apply {
            background = grad(c, GOLD2, GOLD, 10f); setPadding(dp(c, 14f), dp(c, 10f), dp(c, 14f), dp(c, 10f))
            setOnClickListener { saveName(c) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(c, 8f) })
        card.addView(nr, lp(10f))

        // 몸통
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        bodyBox = body
        histBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(c).apply { addView(histBox); isVerticalScrollBarEnabled = true }
        histScroll = sc
        val maxH = (c.resources.displayMetrics.heightPixels * 0.22).toInt()
        body.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, 10f) })
        sc.viewTreeObserver.addOnGlobalLayoutListener {
            if (sc.height > maxH) sc.layoutParams = (sc.layoutParams as LinearLayout.LayoutParams).apply { height = maxH }
        }
        memoEt = EditText(c).apply {
            hint = "새 메모 (통화 내용·약속 등)\n키보드의 🎤 마이크를 누르면 말로 적을 수 있어요"; minLines = 5; maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            background = round(c, 0xFFF5F7F8.toInt(), 12f); setPadding(dp(c, 12f), dp(c, 9f), dp(c, 12f), dp(c, 9f))
        }
        body.addView(memoEt, lp(8f))
        val btns = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        micTv = tv("닫기", 14.5f, 0xFF3B4850.toInt(), true).apply {
            gravity = Gravity.CENTER; background = round(c, 0xFFEEF2F3.toInt(), 12f); setPadding(0, dp(c, 11f), 0, dp(c, 11f))
            setOnClickListener { closeMemo(c) }
        }
        val save = tv("💾 저장", 14.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = grad(c, GOLD2, GOLD, 12f); setPadding(0, dp(c, 11f), 0, dp(c, 11f))
            setOnClickListener { save(c) }
        }
        btns.addView(micTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(c, 8f) })
        btns.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(btns, lp(8f))
        card.addView(body)

        // 작게 보기 (한 줄 띠)
        smallTv = tv("", 14f, Color.WHITE, true).apply {
            background = grad(c, GOLD2, GOLD, 99f); setPadding(dp(c, 16f), dp(c, 10f), dp(c, 16f), dp(c, 10f))
            visibility = View.GONE; maxLines = 1
        }

        r.addView(outer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(smallTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // 가로 100% (휴대폰 기종마다 화면 폭에 자동으로 맞춤)
        val p = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(c, 28f)
        }
        params = p

        // 끌어서 위아래로 옮기기 (머리 · 작은 띠)
        var downY = 0f; var startY = 0; var moved = false
        val drag = View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = p.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (Math.abs(dy) > dp(c, 6f)) moved = true
                    if (moved) {
                        p.y = (startY + dy).coerceIn(0, c.resources.displayMetrics.heightPixels - dp(c, 80f))
                        runCatching { wm.updateViewLayout(r, p) }
                    }
                    moved
                }
                MotionEvent.ACTION_UP -> { if (!moved && v === smallTv) setSmall(c, false); true }
                else -> false
            }
        }
        head.setOnTouchListener(drag)
        titles.setOnClickListener {
            if (data == null || data!!.has("_err")) { titleTv?.text = "📒 불러오는 중…"; load(c, curNum, curKey, call = false); return@setOnClickListener }
            // 이름 고치기 칸 열기/닫기
            val open = nameRow?.visibility != View.VISIBLE
            nameRow?.visibility = if (open) View.VISIBLE else View.GONE
            if (open) {
                nameEt?.setText(data?.optString("name", "") ?: "")
                setFocusable(c, true)
                main.postDelayed({
                    nameEt?.requestFocus(); nameEt?.setSelection(nameEt?.text?.length ?: 0)
                    (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(nameEt, InputMethodManager.SHOW_IMPLICIT)
                }, 150)
            } else setFocusable(c, false)
        }
        smallTv?.setOnTouchListener(drag)

        // 글 쓸 때만 키보드 받기 (평소엔 전화 화면 조작을 막지 않음)
        val focusOn = View.OnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) setFocusable(c, true)
            false
        }
        memoEt?.setOnTouchListener(focusOn)
        nameEt?.setOnTouchListener(focusOn)

        wm.addView(r, p)
        attached.add(r)
        root = r
        render(c)
    }

    private fun setFocusable(c: Context, on: Boolean) {
        val r = root ?: return
        val p = params ?: return
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                  else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        runCatching { wm.updateViewLayout(r, p) }
        if (!on) (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(r.windowToken, 0)
    }

    private fun setSmall(c: Context, on: Boolean) {
        small = on
        val r = root ?: return
        (r.getChildAt(0)).visibility = if (on) View.GONE else View.VISIBLE
        smallTv?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) setFocusable(c, false)
        render(c)
    }

    private fun kindLabel(k: String) = when (k) {
        "ai", "share" -> "🤖 AI 요약"
        "voice" -> "🎤 음성 메모"
        else -> "✏️ 메모"
    }

    private fun render(c: Context) {
        val d = data
        val name = d?.optString("name", "") ?: ""
        val items = d?.optJSONArray("items")
        val n = items?.length() ?: 0
        val calls = d?.optInt("calls", 0) ?: 0
        titleTv?.text = when {
            d == null -> "📒 불러오는 중…"
            d.has("_err") && name.isEmpty() && n == 0 -> "📒 메모를 못 불러왔어요 (눌러서 다시)"
            name.isNotEmpty() -> "📒 $name ✏️"
            n == 0 && calls <= 1 -> "📒 처음 온 번호 ✏️"
            else -> "📒 이름 없음 ✏️"
        }
        subTv?.text = pretty(curNum) + (if (calls > 0) " · 통화 ${calls}번" else "") +
            (d?.optString("last_at")?.takeIf { it.isNotEmpty() && calls > 1 }?.let { " · 지난 통화 " + it.substring(5) } ?: "") +
            summaryNote(c, curKey).let { if (it.isNotEmpty()) "\n$it" else "" }
        smallTv?.text = "📒 " + (if (name.isNotEmpty()) name else CallbackStore.mask(curNum)) + (if (n > 0) " · 메모 $n" else "") + "  ▾"

        val box = histBox ?: return
        box.removeAllViews()
        histScroll?.visibility = if (n == 0) View.GONE else View.VISIBLE // 메모가 없으면 기록 칸 없이 바로 입력칸
        for (i in 0 until n) {
            val m = items!!.optJSONObject(i) ?: continue
            val row = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(c, 6f), 0, dp(c, 8f)) }
            row.addView(TextView(c).apply {
                text = m.optString("at") + "  " + kindLabel(m.optString("kind"))
                setTextColor(if (m.optString("kind") == "ai" || m.optString("kind") == "share") SKY else GOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); typeface = Typeface.DEFAULT_BOLD
            })
            row.addView(TextView(c).apply {
                text = m.optString("body"); setTextColor(0xFF2F2D29.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setLineSpacing(0f, 1.3f)
            })
            if (i < n - 1) row.addView(View(c).apply { setBackgroundColor(0xFFDDE3E6.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(c, 8f) })
            box.addView(row)
        }
    }

    /** 이름만 저장 */
    private fun saveName(c: Context) {
        val key = curKey; val num = curNum
        if (key.isEmpty()) return
        val name = nameEt?.text?.toString()?.trim() ?: ""
        nameRow?.visibility = View.GONE
        setFocusable(c, false)
        titleTv?.text = "📒 " + (if (name.isNotEmpty()) name else "이름 없음")
        Thread {
            val j = CallbackStore.memoAdd(c, key, CallbackStore.mask(num), name, "", "note")
            main.post {
                if (j == null) Toast.makeText(c, "이름을 저장하지 못했어요. 인터넷을 확인해주세요", Toast.LENGTH_SHORT).show()
                else { if (curKey == key) { data = j; render(c) }; Toast.makeText(c, "📒 이름을 저장했어요", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    /** 저장: 이름이 바뀌었으면 이름도, 새 메모가 있으면 기록 추가 */
    private fun save(c: Context, closeAfter: Boolean = false) {
        val key = curKey; val num = curNum
        if (key.isEmpty()) return
        val memo = memoEt?.text?.toString()?.trim() ?: ""
        if (memo.isEmpty()) { if (closeAfter) close(c) else setFocusable(c, false); return }
        memoEt?.setText("")
        setFocusable(c, false)
        Thread {
            val j = CallbackStore.memoAdd(c, key, CallbackStore.mask(num), null, memo, "note")
            main.post {
                if (j == null) {
                    Toast.makeText(c, "저장하지 못했어요. 인터넷을 확인해주세요", Toast.LENGTH_SHORT).show()
                    if (curKey == key && memo.isNotEmpty()) memoEt?.setText(memo)
                } else {
                    if (curKey == key) { data = j; render(c) }
                    Toast.makeText(c, "📒 저장했어요", Toast.LENGTH_SHORT).show()
                }
                if (closeAfter) close(c)
            }
        }.start()
    }

    /** 닫거나 다른 번호로 바뀔 때 적던 메모 저장 */
    private fun saveDraft(c: Context) {
        val memo = memoEt?.text?.toString()?.trim() ?: ""
        if (curKey.isEmpty() || memo.isEmpty()) return
        val key = curKey; val mask = CallbackStore.mask(curNum)
        Thread { CallbackStore.memoAdd(c, key, mask, null, memo, "note") }.start()
    }

    /** 메모 칸에 키보드 열기 (🎤 버튼 → 키보드의 마이크로 말해서 적기) */
    private fun openKeyboard(c: Context, mic: Boolean) {
        val et = memoEt ?: return
        setFocusable(c, true)
        main.postDelayed({
            et.requestFocus()
            et.setSelection(et.text?.length ?: 0)
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            if (mic) Toast.makeText(c, "키보드 위쪽(또는 스페이스 옆)의 🎤 마이크를 누르고 말하세요", Toast.LENGTH_LONG).show()
        }, 150)
    }

    private val attached = mutableListOf<View>()

    /** 위쪽 반 메모 화면(MemoActivity)이 열리면: 이 창은 닫아서 두 개가 겹치지 않게 (적던 메모는 저장) */
    fun closeForActivity(c: Context) {
        main.post { if (root != null) { saveDraft(c.applicationContext); close(c.applicationContext) } }
    }

    fun close(c: Context) {
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        attached.forEach { v -> runCatching { wm.removeView(v) } }
        attached.clear()
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null; curKey = ""; curNum = ""; data = null; cbPart = null; memoPart = null; cardBox = null
    }

    /** 메모 [닫기]: 적던 메모는 저장하고 메모 부분만 닫음 (아래 콜백문자가 있으면 그건 남김) */
    private fun closeMemo(c: Context) {
        saveDraft(c)
        memoEt?.setText("")
        setFocusable(c, false)
        if (cbPart != null) memoPart?.visibility = View.GONE else close(c)
    }

    /**
     * 통화가 끝나 콜백문자를 보낼 때: 같은 번호 메모창이 떠 있으면 그 아래에 붙여서 한 화면으로
     * (어느 스레드에서 불러도 됨) → 붙였으면 true
     */
    fun attachCallback(c: Context, num: String, missed: Boolean, list: List<JSONObject>, hint: Boolean): Boolean {
        if (list.isEmpty()) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        main.post {
            ok = runCatching {
                if (root == null || cardBox == null || cbPart != null || CallbackStore.memoKey(num) != curKey) return@runCatching false
                buildCallback(c.applicationContext, num, missed, list, hint)
                if (small) setSmall(c.applicationContext, false)
                true
            }.getOrDefault(false)
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    private fun buildCallback(c: Context, num: String, missed: Boolean, list: List<JSONObject>, hint: Boolean) {
        val outer = cardBox ?: return
        var selected = 0
        fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); setLineSpacing(0f, 1.25f)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val lp = { top: Float -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, top) } }
        val part = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        // 구분선
        part.addView(View(c).apply { setBackgroundColor(0xFFDDE3E6.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1f)).apply { topMargin = dp(c, 14f) })
        part.addView(tv("📨 콜백문자를 보내시겠습니까?", 16f, INK, true), lp(12f))
        part.addView(tv((if (missed) "📵 부재중 전화 · " else "📞 통화 종료 · ") + pretty(num), 13.5f, MUTE), lp(2f))
        if (hint) part.addView(tv("🎯 ${list[0].optString("title")} 홈페이지에서 온 전화예요", 13f, 0xFF1D7D5C.toInt(), true).apply {
            background = round(c, 0xFFE3F6EE.toInt(), 10f); setPadding(dp(c, 10f), dp(c, 5f), dp(c, 10f), dp(c, 5f))
        }, lp(8f))
        fun body(i: Int) = if (missed) list[i].optString("text_missed") else list[i].optString("text")
        val preview = tv("", 13.5f, 0xFF2B2B2B.toInt())
        val mmsTag = tv("📷 사진도 같이 보내요", 12f, SKY, true)
        val chips = ArrayList<TextView>()
        fun paint() {
            chips.forEachIndexed { i, t ->
                t.background = if (i == selected) grad(c, GOLD2, GOLD, 99f) else round(c, 0xFFEEF2F3.toInt(), 99f)
                t.setTextColor(if (i == selected) Color.WHITE else 0xFF3B4850.toInt())
            }
            preview.text = body(selected)
            mmsTag.visibility = if (list[selected].optBoolean("mms")) View.VISIBLE else View.GONE
        }
        if (list.size > 1) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            list.forEachIndexed { i, t ->
                val chip = tv(t.optString("title"), 13.5f, INK, true).apply {
                    setPadding(dp(c, 13f), dp(c, 7f), dp(c, 13f), dp(c, 7f)); maxLines = 1
                    setOnClickListener { selected = i; paint() }
                }
                chips.add(chip)
                row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(c, 6f) })
            }
            part.addView(android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp(8f))
        }
        // 내용 미리보기: 세로를 줄여서 (길면 드래그)
        val ps = ScrollView(c).apply {
            background = round(c, 0xFFEEF0F3.toInt(), 14f)
            setPadding(dp(c, 12f), dp(c, 9f), dp(c, 12f), dp(c, 9f))
            addView(preview)
        }
        part.addView(ps, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 92f)).apply { topMargin = dp(c, 8f) })
        part.addView(mmsTag, lp(4f))
        val btns = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val no = tv("닫기", 14.5f, 0xFF3B4850.toInt(), true).apply {
            gravity = Gravity.CENTER; background = round(c, 0xFFEEF2F3.toInt(), 12f); setPadding(0, dp(c, 11f), 0, dp(c, 11f))
            setOnClickListener {
                outer.removeView(part); cbPart = null
                if (memoPart?.visibility != View.VISIBLE) close(c)
            }
        }
        val send = tv("💬 보내기", 14.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = grad(c, 0xFF37C495.toInt(), 0xFF21A67A.toInt(), 12f); setPadding(0, dp(c, 11f), 0, dp(c, 11f))
            setOnClickListener {
                val t = list[selected]
                saveDraft(c) // 적어 둔 메모도 같이 저장
                val i = Intent(c, CallbackSendActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("num", num); putExtra("tpl_id", t.optInt("id")); putExtra("text", body(selected))
                    putExtra("kind", if (missed) "missed" else "call")
                    if (t.optBoolean("mms")) putExtra("image", t.optString("image"))
                }
                runCatching { c.startActivity(i) }
                close(c) // 문자앱을 가리지 않게 닫음
            }
        }
        btns.addView(no, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(c, 8f) })
        btns.addView(send, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        part.addView(btns, lp(10f))
        outer.addView(part)
        cbPart = part
        paint()
    }
}
