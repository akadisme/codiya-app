package kr.codiya.app.callback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
 *  · 이름 + 날짜별 기록(최근 것 위, 길면 드래그로 스크롤) + 새 메모 + 🎤 음성 메모
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
    private var voiceUsed = false
    private var rec: SpeechRecognizer? = null
    @Volatile private var lastKey = ""
    @Volatile private var lastAt = 0L

    // 화면 조각
    private var nameEt: EditText? = null
    private var memoEt: EditText? = null
    private var histBox: LinearLayout? = null
    private var titleTv: TextView? = null
    private var subTv: TextView? = null
    private var bodyBox: LinearLayout? = null
    private var smallTv: TextView? = null
    private var micTv: TextView? = null
    private var editBox: LinearLayout? = null

    // ---------- 통화가 끝난 뒤 3분 동안 아무것도 안 쓰면 자동으로 닫기 ----------
    private var autoCloseKey = ""
    private var lastTyped = 0L
    private const val AUTO_CLOSE_MS = 3 * 60 * 1000L

    /** 통화가 끝났을 때 (부재중 포함) */
    fun onCallEnded(c: Context) {
        val app = c.applicationContext
        main.post {
            val key = curKey
            if (root == null || key.isEmpty()) return@post
            autoCloseKey = key
            main.postDelayed({ tryAutoClose(app, key) }, AUTO_CLOSE_MS)
        }
    }

    private fun tryAutoClose(c: Context, key: String) {
        if (root == null || curKey != key || autoCloseKey != key) return
        val typing = (memoEt?.text?.isNotBlank() == true) || memoEt?.hasFocus() == true || nameEt?.hasFocus() == true
        val recent = System.currentTimeMillis() - lastTyped < AUTO_CLOSE_MS
        if (typing || recent) { main.postDelayed({ tryAutoClose(c, key) }, 60_000L); return } // 쓰는 중이면 1분 뒤 다시 확인
        close(c)
    }

    /** 메모 쓰기 칸 열기/닫기 (받기 전엔 닫혀 있어서 창이 작아요) */
    private fun showEdit(c: Context, on: Boolean) {
        editBox?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            setFocusable(c, true)
            memoEt?.requestFocus()
            main.postDelayed({ memoEt?.let { (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) } }, 150)
        } else setFocusable(c, false)
    }

    private fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()
    private fun round(c: Context, color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(c, r).toFloat() }
    private fun grad(c: Context, a: Int, b: Int, r: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { cornerRadius = dp(c, r).toFloat() }

    /** 메모창 쓸 수 있는 상태인지 (권한·설정) */
    fun enabled(c: Context): Boolean {
        val cfg = CallbackStore.cachedConfig(c) ?: return false
        if (cfg.has("allowed") && !cfg.optBoolean("allowed")) return false
        return cfg.optBoolean("memo", false)
    }

    /** 전화가 울릴 때 (어느 스레드에서 불러도 됨) */
    fun onRinging(c: Context, num: String) {
        val app = c.applicationContext
        if (num.length < 8) return
        if (!enabled(app)) {
            // 설정을 아직 못 받았거나(로그인 직후 등) 오래된 경우: 바로 새로 받아서 메모창 켜짐이면 띄움
            Thread {
                CallbackStore.fetchConfig(app, 2500)
                if (enabled(app)) main.post { onRingingNow(app, num) }
                else CallbackStore.diag(app, "📒 전화 옴 " + CallbackStore.mask(num) + " · 메모창 꺼짐 (로그인 연결 또는 '전화 올 때 메모창 띄우기' 설정 확인)")
            }.start()
            return
        }
        onRingingNow(app, num)
    }

    private const val MEMO_CH = "call_memo"
    /** 확인 창 권한이 없을 때: 지난 메모를 맨 위 알림으로 (백그라운드에서) */
    private fun notifyMemo(app: Context, num: String) {
        val key = CallbackStore.memoKey(num)
        if (key.isEmpty()) return
        val cfg = CallbackStore.cachedConfig(app) ?: CallbackStore.fetchConfig(app, 2500)
        if (cfg != null && !cfg.optBoolean("memo", true)) return
        val d = CallbackStore.memoGet(app, key, CallbackStore.mask(num), call = true) ?: CallbackStore.memoCached(app, key)
        if (d != null && d.optBoolean("off")) return
        val name = d?.optString("name", "") ?: ""
        val items = d?.optJSONArray("items")
        val sb = StringBuilder()
        if (items != null) for (i in 0 until minOf(items.length(), 5)) {
            val m = items.optJSONObject(i) ?: continue
            sb.append(m.optString("at")).append("  ").append(m.optString("body").replace("\n", " ").take(80)).append('\n')
        }
        val text = (if (sb.isEmpty()) "아직 남긴 메모가 없어요." else sb.toString().trim()) +
            (if (!CallbackPopup.canShow(app)) "\n💡 눌러서 [확인 창 권한]을 켜면 전화 받기 전에 메모창이 화면 맨 앞에 떠요" else "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(MEMO_CH, "전화 메모", android.app.NotificationManager.IMPORTANCE_HIGH).apply { description = "전화가 올 때 지난 메모를 보여줘요" }
            app.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(ch)
        }
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            putExtra("open_url", "codiya://callback") // 앱의 콜백문자·통화 감지 화면
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = open?.let { android.app.PendingIntent.getActivity(app, 7801, it, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE) }
        val b = androidx.core.app.NotificationCompat.Builder(app, MEMO_CH)
            .setSmallIcon(kr.codiya.app.R.drawable.ic_stat_notify)
            .setColor(0xFF0AA394.toInt())
            .setContentTitle("📒 " + (if (name.isNotEmpty()) "$name · " else "") + CallbackStore.mask(num))
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
        if (pi != null) b.setContentIntent(pi)
        try { androidx.core.app.NotificationManagerCompat.from(app).notify(7800, b.build()) } catch (_: SecurityException) {}
    }

    private fun onRingingNow(app: Context, num: String) {
        val key = CallbackStore.memoKey(num)
        if (key.isEmpty()) return
        synchronized(this) { // 같은 전화로 두 번 불려도 한 번만 (전화 상태 · 번호 확인)
            val now = System.currentTimeMillis()
            if (key == lastKey && now - lastAt < 8_000L) return // 같은 울림이 두 번 알려 온 것만 거름
            lastKey = key; lastAt = now
        }
        CallbackStore.addRecent(app, num)
        CallbackStore.diag(app, "📒 전화 옴 " + CallbackStore.mask(num) + " · 메모창 띄우는 중")
        main.post {
            if (curKey == key && root != null) { if (small) setSmall(app, false); return@post } // 이미 떠 있으면 크게만
            runCatching { show(app, num, key) }.onFailure { e ->
                // 창을 못 띄움(확인 창 권한 꺼짐 등) → 지난 메모를 알림으로라도
                root = null; curKey = ""
                CallbackStore.diag(app, "📒 전화 옴 " + CallbackStore.mask(num) + " · 메모창을 못 띄워 알림으로 보여줌 (" +
                    e.javaClass.simpleName + (if (!CallbackPopup.canShow(app)) " · 확인 창 권한 꺼짐" else "") + ")")
                Thread { runCatching { notifyMemo(app, num) } }.start()
            }
        }
        Thread {
            val j = CallbackStore.memoGet(app, key, CallbackStore.mask(num), call = true)
            if (j != null && !j.optBoolean("off")) main.post { if (curKey == key) { data = j; runCatching { render(app) } } }
            else if (j != null && j.optBoolean("off")) main.post { if (curKey == key) close(app) }
        }.start()
        // 설정 새로고침 (다음 전화 대비)
        Thread { CallbackStore.fetchConfig(app) }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(c: Context, num: String, key: String) {
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (root != null) { // 다른 번호(통화 중 대기 전화): 적던 메모는 먼저 저장하고 바꿈
            saveDraft(c)
            runCatching { wm.removeView(root) }
            root = null
        }
        curNum = num; curKey = key; small = false; voiceUsed = false
        data = CallbackStore.memoCached(c, key)

        val r = FrameLayout(c)
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = round(c, Color.WHITE, 20f)
            elevation = dp(c, 10f).toFloat()
            setPadding(dp(c, 14f), dp(c, 12f), dp(c, 14f), dp(c, 12f))
        }
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
        subTv = tv(CallbackStore.mask(num), 12.5f, MUTE)
        titles.addView(titleTv); titles.addView(subTv)
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val editBtn = chip("접기", 0xFFDDF4F1.toInt(), 0xFF077A6F.toInt())
        editBtn.setOnClickListener {
            val open = editBox?.visibility != View.VISIBLE
            editBox?.visibility = if (open) View.VISIBLE else View.GONE
            editBtn.text = if (open) "접기" else "✏️ 메모"
            if (!open) setFocusable(c, false)
        }
        head.addView(editBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(c, 6f) })
        val minBtn = chip("작게", 0xFFEEF2F3.toInt(), 0xFF3B4850.toInt()).apply { setOnClickListener { setSmall(c, true) } }
        val xBtn = chip("✕", 0xFFEEF2F3.toInt(), 0xFF3B4850.toInt()).apply { setOnClickListener { saveDraft(c); close(c) } }
        head.addView(minBtn); head.addView(xBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(c, 6f) })
        card.addView(head)

        // 몸통
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        bodyBox = body
        nameEt = EditText(c).apply {
            hint = "이름 (예: 김OO 34평 관심)"; setSingleLine(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            background = round(c, 0xFFF5F7F8.toInt(), 12f); setPadding(dp(c, 12f), dp(c, 9f), dp(c, 12f), dp(c, 9f))
        }
        val edit = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL } // 처음부터 펼친 상태
        editBox = edit
        edit.addView(nameEt, lp(10f))
        histBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(c).apply {
            addView(histBox); isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false            // 오른쪽 스크롤 막대가 늘 보여서 어디쯤인지 알 수 있게
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        }
        val maxH = (c.resources.displayMetrics.heightPixels * 0.36).toInt()
        body.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, 8f) })
        sc.viewTreeObserver.addOnGlobalLayoutListener {
            if (sc.height > maxH) sc.layoutParams = (sc.layoutParams as LinearLayout.LayoutParams).apply { height = maxH }
        }
        memoEt = EditText(c).apply {
            hint = "새 메모 (키보드의 🎤 마이크로 말해도 돼요)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setLines(6); gravity = Gravity.TOP or Gravity.START        // 예전보다 3배 큰 칸 · 길면 손가락으로 올려서 보기
            // (코드로 만든 입력칸에 스크롤 막대를 켜면 안드로이드 16에서 앱이 멈춰서 막대는 안 씀)
            setOnTouchListener { v, e -> // 칸 안에서 위아래로 끌 때는 창이 아니라 글이 움직이게
                v.parent?.requestDisallowInterceptTouchEvent(true)
                if (e.actionMasked == MotionEvent.ACTION_DOWN) setFocusable(c, true)
                false
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            background = round(c, 0xFFF5F7F8.toInt(), 12f); setPadding(dp(c, 12f), dp(c, 9f), dp(c, 12f), dp(c, 9f))
        }
        memoEt?.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { lastTyped = System.currentTimeMillis() }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c2: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c2: Int, d: Int) {}
        })
        edit.addView(memoEt, lp(8f))
        val btns = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        micTv = tv("🎤 말로 메모", 14.5f, SKY, true).apply {
            gravity = Gravity.CENTER; background = round(c, 0xFFE6F0FB.toInt(), 12f); setPadding(0, dp(c, 11f), 0, dp(c, 11f))
            setOnClickListener { voice(c) }
        }
        val save = tv("💾 저장", 14.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = grad(c, GOLD2, GOLD, 12f); setPadding(0, dp(c, 11f), 0, dp(c, 11f))
            setOnClickListener { save(c) }
        }
        btns.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        edit.addView(btns, lp(8f))
        body.addView(edit)
        card.addView(body)
        // 손잡이: 잡고 위아래로 끌면 창이 움직여요 (전화 받기 버튼이 가려질 때 위로 밀어 올리기)
        val handle = tv("━━━   위·아래로 끌어서 옮기기   ━━━", 11.5f, 0xFF9AA8AE.toInt(), true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(c, 10f), 0, dp(c, 2f))
        }
        card.addView(handle, lp(4f))

        // 작게 보기 (한 줄 띠)
        smallTv = tv("", 14f, Color.WHITE, true).apply {
            background = grad(c, GOLD2, GOLD, 99f); setPadding(dp(c, 16f), dp(c, 10f), dp(c, 16f), dp(c, 10f))
            visibility = View.GONE; maxLines = 1
        }

        r.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(smallTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val w = minOf(c.resources.displayMetrics.widthPixels - dp(c, 20f), dp(c, 460f))
        val p = WindowManager.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(c, 36f)
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
                        // 위로는 머리 부분만 남을 때까지, 아래로는 화면 끝 근처까지
                        val minY = -((r.height - dp(c, 64f)).coerceAtLeast(0))
                        p.y = (startY + dy).coerceIn(minY, c.resources.displayMetrics.heightPixels - dp(c, 80f))
                        runCatching { wm.updateViewLayout(r, p) }
                    }
                    moved
                }
                MotionEvent.ACTION_UP -> { if (!moved && v === smallTv) setSmall(c, false); true }
                else -> false
            }
        }
        head.setOnTouchListener(drag)
        handle.setOnTouchListener(drag)
        smallTv?.setOnTouchListener(drag)

        // 글 쓸 때만 키보드 받기 (평소엔 전화 화면 조작을 막지 않음)
        val focusOn = View.OnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) setFocusable(c, true)
            false
        }
        nameEt?.setOnTouchListener(focusOn)

        wm.addView(r, p)
        root = r
        render(c)
        // 에이닷·T전화 같은 전화앱이 자기 화면을 나중에 띄우면 메모창이 뒤로 숨어서, 맨 앞으로 몇 번 다시 올려요
        for (ms in longArrayOf(700L, 1600L, 3000L, 5000L)) main.postDelayed({ bringFront(c, r) }, ms)
    }

    private fun bringFront(c: Context, r: FrameLayout) {
        if (root !== r) return
        if (editBox?.visibility == View.VISIBLE && memoEt?.hasFocus() == true) return // 글 쓰는 중엔 그대로
        val p = params ?: return
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeViewImmediate(r); wm.addView(r, p) }
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
            name.isNotEmpty() -> "📒 $name"
            n == 0 && calls <= 1 -> "📒 처음 온 번호"
            else -> "📒 이름 없음"
        }
        subTv?.text = CallbackStore.mask(curNum) + (if (calls > 0) " · 통화 ${calls}번" else "") +
            (d?.optString("last_at")?.takeIf { it.isNotEmpty() && calls > 1 }?.let { " · 지난 통화 " + it.substring(5) } ?: "")
        if (nameEt?.hasFocus() != true && name.isNotEmpty() && nameEt?.text.isNullOrEmpty()) nameEt?.setText(name)
        smallTv?.text = "📒 " + (if (name.isNotEmpty()) name else CallbackStore.mask(curNum)) + (if (n > 0) " · 메모 $n" else "") + "  ▾"

        val box = histBox ?: return
        box.removeAllViews()
        if (d != null && n == 0) {
            box.addView(TextView(c).apply { text = "아직 기록이 없어요. [✏️ 메모]를 눌러 적거나 🎤로 말해 두면 다음 전화 때 여기 떠요."; setTextColor(MUTE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
        }
        for (i in 0 until n) {
            val m = items!!.optJSONObject(i) ?: continue
            val row = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(c, 6f), 0, dp(c, 8f)) }
            row.addView(TextView(c).apply {
                text = m.optString("at") + "  " + kindLabel(m.optString("kind"))
                setTextColor(if (m.optString("kind") == "ai" || m.optString("kind") == "share") SKY else GOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); typeface = Typeface.DEFAULT_BOLD
            })
            row.addView(TextView(c).apply {
                text = m.optString("body"); setTextColor(0xFF1B262C.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setLineSpacing(0f, 1.3f)
            })
            if (i < n - 1) row.addView(View(c).apply { setBackgroundColor(0xFFDDE3E6.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(c, 8f) })
            box.addView(row)
        }
    }

    /** 저장: 이름이 바뀌었으면 이름도, 새 메모가 있으면 기록 추가 */
    private fun save(c: Context, closeAfter: Boolean = false) {
        if (voiceOn) stopVoice()
        val key = curKey; val num = curNum
        if (key.isEmpty()) return
        val name = nameEt?.text?.toString()?.trim() ?: ""
        val oldName = data?.optString("name", "") ?: ""
        val memo = memoEt?.text?.toString()?.trim() ?: ""
        val kind = if (voiceUsed) "voice" else "note"
        if (memo.isEmpty() && name == oldName) { if (closeAfter) close(c) else setFocusable(c, false); return }
        memoEt?.setText(""); voiceUsed = false
        setFocusable(c, false)
        if (memo.isNotEmpty() && kind == "voice") Toast.makeText(c, "🤖 AI로 정리해서 저장하는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val j = CallbackStore.memoAdd(c, key, CallbackStore.mask(num), if (name != oldName) name else null, memo, kind)
            main.post {
                if (j == null) {
                    val why = CallbackStore.lastErr
                    Toast.makeText(c, "저장하지 못했어요" + (if (why.isNotEmpty()) " · $why" else ""), Toast.LENGTH_LONG).show()
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
        if (voiceOn) stopVoice()
        val memo = memoEt?.text?.toString()?.trim() ?: ""
        val name = nameEt?.text?.toString()?.trim() ?: ""
        if (curKey.isEmpty() || (memo.isEmpty() && name == (data?.optString("name", "") ?: ""))) return
        val key = curKey; val mask = CallbackStore.mask(curNum); val kind = if (voiceUsed) "voice" else "note"
        val nm = if (name != (data?.optString("name", "") ?: "")) name else null
        Thread { CallbackStore.memoAdd(c, key, mask, nm, memo, kind) }.start()
    }

    // ---------- 🎤 말로 메모 (휴대폰 받아쓰기 · 무료) ----------
    // 🎤 말로 메모: 말을 멈춰도 [끝]을 누를 때까지 계속 들어요 (말하는 중간 글자도 바로 보여요)
    private var voiceOn = false
    private var voiceBase = ""
    private var voiceStarted = 0L

    private fun voice(c: Context) {
        if (voiceOn) { stopVoice(); return }
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(c, "앱 콜백문자 설정에서 '마이크' 권한을 켜주세요", Toast.LENGTH_LONG).show(); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(c)) {
            Toast.makeText(c, "이 휴대폰은 받아쓰기를 지원하지 않아요. 메모 칸을 누르고 키보드의 🎤를 써 주세요", Toast.LENGTH_LONG).show(); return
        }
        voiceOn = true; voiceStarted = System.currentTimeMillis()
        voiceBase = memoEt?.text?.toString()?.trim() ?: ""
        listenOnce(c)
    }

    private fun stopVoice() {
        voiceOn = false
        runCatching { rec?.stopListening() }
        main.postDelayed({ runCatching { rec?.destroy() }; rec = null }, 400)
        micTv?.text = "🎤 말로 메모"
    }

    private fun listenOnce(c: Context) {
        if (!voiceOn) return
        if (System.currentTimeMillis() - voiceStarted > 3 * 60 * 1000L) { stopVoice(); return } // 최대 3분
        runCatching { rec?.destroy() }
        val sr = SpeechRecognizer.createSpeechRecognizer(c)
        rec = sr
        micTv?.text = "⏺ 듣는 중… 다 말하면 눌러서 끝"
        fun put(t: String) {
            val all = listOf(voiceBase, t).filter { it.isNotBlank() }.joinToString(" ")
            memoEt?.setText(all); memoEt?.setSelection(all.length)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (t.isNotBlank()) put(t)
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (t.isNotBlank()) { put(t); voiceBase = memoEt?.text?.toString()?.trim() ?: voiceBase; voiceUsed = true }
                main.postDelayed({ listenOnce(c) }, 150) // 이어서 계속 듣기
            }
            override fun onError(error: Int) {
                when (error) {
                    // 말이 잠깐 없었을 뿐 → 조용히 다시 듣기
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> main.postDelayed({ listenOnce(c) }, 150)
                    SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        Toast.makeText(c, "지금은 마이크를 쓸 수 없어요 (통화 중이면 끝난 뒤에). 메모 칸을 누르고 키보드의 🎤도 쓸 수 있어요", Toast.LENGTH_LONG).show()
                        stopVoice()
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        Toast.makeText(c, "인터넷 연결이 약해요. 메모 칸을 누르고 키보드의 🎤로 말해 보세요", Toast.LENGTH_LONG).show()
                        stopVoice()
                    }
                    else -> main.postDelayed({ listenOnce(c) }, 300)
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
        })
    }

    fun close(c: Context) {
        val r = root ?: return
        voiceOn = false
        runCatching { rec?.destroy() }; rec = null
        runCatching { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(r) }
        root = null; curKey = ""; curNum = ""; data = null
    }
}
