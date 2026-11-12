package kr.codiya.app.callback

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * 📒 화면 위쪽 절반 메모 (알림을 누르면 열림 · 울릴 때 / 통화 중)
 *  · 지난 메모를 드래그해서 끝까지 보기 · 바로 적어서 저장
 *  · 아래 절반은 비워서 전화 화면(받기 버튼)을 그대로 씀 + [📞 전화 받기] 버튼
 *  · 창이 아니라 앱 화면이라 에이닷 등 전화 화면 위에서도 열림
 */
class MemoActivity : Activity() {
    companion object {
        private var cur: WeakReference<MemoActivity>? = null
        /** 통화가 끝나면 닫음 (적던 메모는 저장) → 끊은 뒤 메모창·콜백문자 화면으로 */
        fun refresh(key: String) { cur?.get()?.let { a -> a.runOnUiThread { if (a.key == key) a.load() } } }
        fun finishOnCallEnd() {
            cur?.get()?.let { a -> a.runOnUiThread { a.saveDraft(); a.finish() } }
        }
    }

    private val GOLD = 0xFF0AA394.toInt()
    private val GOLD2 = 0xFF2CC2B0.toInt()
    private val INK = 0xFF0F181D.toInt()
    private val MUTE = 0xFF5B6870.toInt()
    private val SKY = 0xFF3A86D8.toInt()

    private var num = ""
    var key = ""
    private var data: JSONObject? = null
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var histBox: LinearLayout
    private lateinit var memoEt: EditText
    private lateinit var nameRow: LinearLayout
    private lateinit var nameEt: EditText
    private var answerBtn: TextView? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun grad(a: Int, b: Int, r: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { cornerRadius = dp(r).toFloat() }
    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); setLineSpacing(0f, 1.3f)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cur = WeakReference(this)
        MemoPopup.closeForActivity(this) // 끊은 뒤 메모창이 떠 있으면 닫기 (창이 2개 뜨지 않게)
        num = intent.getStringExtra("num") ?: ""
        key = CallbackStore.memoKey(num)
        if (key.isEmpty()) { finish(); return }

        // 잠금 화면에서도, 위쪽 절반만 · 바깥(아래 전화 화면)은 그대로 누를 수 있게
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(10f).toFloat()
        }
        cardView = card
        // 항상 위쪽 반 (아래쪽 반은 전화 화면 그대로 → 받기 버튼 그대로 사용)
        place(atTop = true)
        val lp = { top: Float -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) } }

        // 머리: 이름(누르면 고치기) · 전체 번호
        titleTv = tv("📒 불러오는 중…", 17f, INK, true)
        subTv = tv(MemoPopup.pretty(num), 13f, MUTE)
        titleTv.setSingleLine(true); titleTv.ellipsize = android.text.TextUtils.TruncateAt.END
        titleTv.setOnClickListener { toggleName() }

        // 📇 명함 (화면 위쪽 약 1/3 · 아래 손잡이를 끌어서 크기 조절)
        cardImg = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12f).toFloat() }
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f)); visibility = View.GONE
        }
        card.addView(cardImg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH()))
        cardHandle = tv("━━━  끌어서 명함 크기 조절  ━━━", 11f, 0xFFB09A6A.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(4f), 0, dp(6f)); visibility = View.GONE
            var startY = 0f; var startH = 0
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { startY = e.rawY; startH = cardImg!!.layoutParams.height }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val sh = resources.displayMetrics.heightPixels
                        val nh = (startH + (e.rawY - startY)).toInt().coerceIn((sh * 0.12).toInt(), (sh * 0.42).toInt())
                        cardImg!!.layoutParams = cardImg!!.layoutParams.apply { height = nh }
                    }
                    android.view.MotionEvent.ACTION_UP -> CallbackStore.prefs(this@MemoActivity).edit()
                        .putFloat("memo_card_h", cardImg!!.layoutParams.height.toFloat() / resources.displayMetrics.heightPixels).apply()
                }
                true
            }
        }
        card.addView(cardHandle)

        // 머리: 이름 + [✏️ 이름 수정] [📇 명함] / 그 아래 줄에 번호·통화 횟수 (가로로 한 줄)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tv("✏️ 이름 수정", 13.5f, 0xFF077A6F.toInt(), true).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10f).toFloat(); setStroke(dp(1.5f), GOLD2) }
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            setOnClickListener { toggleName() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8f) })
        head.addView(chip("📇 명함") { cardMenu() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6f) })
        card.addView(head)
        card.addView(subTv, lp(2f))

        nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        nameEt = EditText(this).apply {
            hint = "이름 (예: 김민수 34평)"; setSingleLine(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = round(Color.WHITE, 12f); setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        }
        nameRow.addView(nameEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nameRow.addView(tv("이름 저장", 13.5f, Color.WHITE, true).apply {
            background = grad(GOLD2, GOLD, 10f); setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            setOnClickListener { saveName() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8f) })
        card.addView(nameRow, lp(8f))

        // 지난 메모 (드래그해서 끝까지)
        histBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(ScrollView(this).apply { addView(histBox); isVerticalScrollBarEnabled = true },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8f) })

        // 새 메모
        memoEt = EditText(this).apply {
            hint = "새 메모 · 닫거나 끊으면 자동 저장 (키보드 🎤로 말해서 적어도 돼요)"; minLines = 4; maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            background = round(Color.WHITE, 12f); setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        }
        card.addView(memoEt, lp(8f))

        // 버튼: 울릴 때 [닫기][📞 전화 받기] / 통화 중 [닫기] — 적은 메모는 닫기·끊기·나가기 때 자동 저장
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(s: String, bg: android.graphics.drawable.Drawable, fg: Int, w: Float, on: () -> Unit) = tv(s, 14.5f, fg, true).apply {
            gravity = Gravity.CENTER; background = bg; setPadding(0, dp(11f), 0, dp(11f)); setOnClickListener { on() }
            btns.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { rightMargin = dp(6f) })
        }
        btn("닫기", round(Color.WHITE, 12f), 0xFF3B4850.toInt(), 1f) { saveDraft(); finish() }
        if (ringing()) answerBtn = btn("📞 전화 받기", grad(0xFF37C495.toInt(), 0xFF21A67A.toInt(), 12f), Color.WHITE, 1.3f) { answer() }
        card.addView(btns, lp(10f))

        // 상태표시줄 아래부터 · 양옆·아래 여백을 둬서 전화 화면과 경계가 보이게
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(dp(8f), statusBar() + dp(6f), dp(8f), dp(8f))
            clipToPadding = false
            addView(card, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(frame)
        data = CallbackStore.memoCached(this, key)
        render()
        load()
    }

    override fun onPause() {
        super.onPause()
        saveDraft() // 홈 버튼·다른 화면으로 나가도 적던 메모 저장
    }

    override fun onDestroy() {
        if (cur?.get() === this) cur = null
        super.onDestroy()
    }

    private var cardView: LinearLayout? = null

    /** 메모 화면 위치: 위쪽 반 / 아래쪽 반 */
    private fun statusBar(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28f)
    }

    private var cardImg: android.widget.ImageView? = null
    private var cardHandle: TextView? = null
    private var hasCard = false
    private fun cardH(): Int = (resources.displayMetrics.heightPixels * CallbackStore.prefs(this).getFloat("memo_card_h", 0.30f)).toInt()

    private fun place(atTop: Boolean) {
        val r = dp(22f).toFloat()
        // 명함이 있으면 명함 자리만큼 조금 길게 (아래 전화 받기 버튼은 보이게 화면의 2/3까지)
        val h = (resources.displayMetrics.heightPixels * (if (hasCard) 0.66 else if (atTop) 0.52 else 0.58)).toInt() + statusBar()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
        window.setGravity(if (atTop) Gravity.TOP else Gravity.BOTTOM)
        cardView?.apply {
            background = GradientDrawable().apply {
                setColor(0xFFFFF4DC.toInt()) // 연한 금빛 바탕 → 전화 화면과 구분
                setStroke(dp(2.5f), 0xFF0AA394.toInt()) // 금색 테두리 (네 면 모두)
                cornerRadius = r
            }
            elevation = dp(14f).toFloat() // 그림자로 아래 전화 화면과 구분
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
    }

    override fun onNewIntent(i: android.content.Intent?) {
        super.onNewIntent(i)
        val n = i?.getStringExtra("num") ?: return
        if (n != num) { saveDraft(); setIntent(i); recreate() } // 다른 번호 알림을 누르면 그 번호로
    }

    override fun onResume() {
        super.onResume()
        MemoNotify.mute(this) // 메모 화면을 보고 있으면 위쪽 메모 알림은 그만 (이번 통화 동안)
    }

    private fun ringing(): Boolean = (CallbackStore.prefs(this).getString("ph", "") ?: "") == "ringing"

    fun load() {
        val app = applicationContext
        Thread {
            val j = CallbackStore.memoGet(app, key, CallbackStore.mask(num), false)
            runOnUiThread { if (!isFinishing) { data = j; render() } }
        }.start()
    }

    private fun kind(k: String) = when (k) { "ai", "share" -> "🤖 AI 요약"; "voice" -> "🎤 음성 메모"; else -> "✏️ 메모" }


    /** 📇 명함: 없으면 바로 찍기·고르기 / 있으면 크게 보기·다시 등록·삭제 */
    private fun cardMenu() {
        if (Card.bitmap(this, key) == null) { Card.pick(this); return }
        android.app.AlertDialog.Builder(this).setTitle("📇 명함")
            .setItems(arrayOf("크게 보기", "다시 찍기·고르기", "명함 삭제")) { _, w ->
                when (w) {
                    0 -> { CallbackStore.prefs(this).edit().putFloat("memo_card_h", 0.40f).apply(); cardImg?.layoutParams = cardImg?.layoutParams?.apply { height = cardH() }; render() }
                    1 -> Card.pick(this)
                    else -> {
                        val app = applicationContext
                        Thread { val j = Card.delete(app, key); runOnUiThread { if (j != null && !isFinishing) { data = j; render() } } }.start()
                    }
                }
            }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data2: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data2)
        if (requestCode != Card.REQ || resultCode != RESULT_OK) return
        val app = applicationContext
        Toast.makeText(app, "📇 명함 저장 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val jpg = Card.result(app, data2)
            val j = if (jpg != null) Card.upload(app, key, num, jpg) else null
            runOnUiThread {
                if (j == null || !j.optBoolean("ok")) { Toast.makeText(app, "명함을 저장하지 못했어요", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                if (!isFinishing) { data = j; render() }
                val nm = j.optJSONObject("read")?.optString("name") ?: ""
                Toast.makeText(app, "📇 명함 저장했어요" + (if (nm.isNotEmpty()) " ($nm)" else ""), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun render() {
        val d = data
        val name = d?.optString("name", "") ?: ""
        val items = d?.optJSONArray("items")
        val n = items?.length() ?: 0
        val calls = d?.optInt("calls", 0) ?: 0
        titleTv.text = when {
            d == null -> "📒 불러오는 중…"
            d.has("_err") && name.isEmpty() && n == 0 -> "📒 메모를 못 불러왔어요"
            name.isNotEmpty() -> "📒 $name"
            n == 0 && calls <= 1 -> "📒 처음 온 번호"
            else -> "📒 이름 없음"
        }
        subTv.text = MemoPopup.pretty(num) + (if (calls > 0) " · 통화 ${calls}번" else "") +
            MemoPopup.summaryNote(this, key).let { if (it.isNotEmpty()) "\n$it" else "" }
        val company = d?.optString("company", "") ?: ""
        if (company.isNotEmpty()) subTv.text = company + " · " + subTv.text
        histBox.removeAllViews()
        val bmp = Card.bitmap(this, key)
        cardImg?.apply { if (bmp != null) { setImageBitmap(bmp); visibility = View.VISIBLE } else visibility = View.GONE }
        cardHandle?.visibility = if (bmp != null) View.VISIBLE else View.GONE
        if ((bmp != null) != hasCard) { hasCard = bmp != null; place(atTop = true) }
        if (d != null && n == 0) histBox.addView(tv("아직 고객 메모가 없어요. 메모를 저장해 두면 다음 전화 때 전화 받기 전에 미리 보여줘요.", 13.5f, MUTE))
        for (i in 0 until n) {
            val m = items!!.optJSONObject(i) ?: continue
            val k = m.optString("kind")
            val id = m.optInt("id")
            // 머리줄: 날짜·종류 (왼쪽) + [✏️ 수정] [🗑 삭제] (오른쪽)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(tv(m.optString("at") + "  " + kind(k), 12.5f, if (k == "ai" || k == "share") SKY else GOLD, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val body = tv(m.optString("body"), 14.5f, 0xFF2F2D29.toInt())
            row.addView(chip("✏️ 수정") { editItem(id, body) })
            row.addView(chip("🗑 삭제") { delItem(id) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6f) })
            histBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (i == 0) 2f else 12f) })
            histBox.addView(body)
        }
    }

    /** 이름 수정 버튼과 같은 모양의 작은 버튼 */
    private fun chip(label: String, on: () -> Unit) = tv(label, 12.5f, 0xFF077A6F.toInt(), true).apply {
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(9f).toFloat(); setStroke(dp(1.2f), GOLD2) }
        setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
        setOnClickListener { on() }
    }

    /** 지난 메모 고치기: 그 자리에서 입력칸으로 바뀜 → [취소] [저장] */
    private fun editItem(id: Int, bodyTv: TextView) {
        val idx = histBox.indexOfChild(bodyTv)
        if (idx < 0) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val et = EditText(this).apply {
            setText(bodyTv.text); minLines = 3; maxLines = 10; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12f).toFloat(); setStroke(dp(1.5f), GOLD2) }
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        }
        box.addView(et, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        val bt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        bt.addView(chip("취소") { render() })
        bt.addView(chip("💾 저장") {
            val v = et.text.toString().trim()
            if (v.isEmpty()) { Toast.makeText(this, "내용을 적어주세요 (지우려면 🗑 삭제)", Toast.LENGTH_SHORT).show(); return@chip }
            change(id, v)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6f) })
        box.addView(bt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        histBox.removeViewAt(idx)
        histBox.addView(box, idx)
        et.requestFocus(); et.setSelection(et.text.length)
    }

    private fun delItem(id: Int) {
        android.app.AlertDialog.Builder(this)
            .setMessage("이 메모를 지울까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ -> change(id, null) }
            .show()
    }

    /** body = null 이면 삭제 */
    private fun change(id: Int, body: String?) {
        val app = applicationContext
        Thread {
            val j = CallbackStore.memoChange(app, key, id, body)
            runOnUiThread {
                if (j == null) { Toast.makeText(app, "저장하지 못했어요. 인터넷을 확인해주세요", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                if (!isFinishing) { data = j; render() }
                Toast.makeText(app, if (body == null) "🗑 지웠어요" else "📒 고쳤어요", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun toggleName() {
        if (data == null || data!!.has("_err")) { titleTv.text = "📒 불러오는 중…"; load(); return }
        val open = nameRow.visibility != View.VISIBLE
        nameRow.visibility = if (open) View.VISIBLE else View.GONE
        if (open) { nameEt.setText(data?.optString("name", "") ?: ""); nameEt.requestFocus() }
    }

    private fun saveName() {
        val name = nameEt.text.toString().trim()
        nameRow.visibility = View.GONE
        titleTv.text = "📒 " + (if (name.isNotEmpty()) name else "이름 없음")
        send(name, "")
    }

    private fun save() {
        val memo = memoEt.text.toString().trim()
        if (memo.isEmpty()) { Toast.makeText(this, "메모를 적어주세요", Toast.LENGTH_SHORT).show(); return }
        memoEt.setText("")
        send(null, memo)
    }

    fun saveDraft() {
        val memo = if (::memoEt.isInitialized) memoEt.text.toString().trim() else ""
        if (memo.isEmpty() || key.isEmpty()) return
        val app = applicationContext; val k = key; val mask = CallbackStore.mask(num)
        Thread {
            val j = CallbackStore.memoAdd(app, k, mask, null, memo, "note")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(app, if (j != null) "📒 메모 저장됨" else "메모를 저장하지 못했어요. 인터넷을 확인해주세요", Toast.LENGTH_SHORT).show()
            }
        }.start()
        if (::memoEt.isInitialized) memoEt.setText("")
    }

    private fun send(name: String?, memo: String) {
        val app = applicationContext
        Thread {
            val j = CallbackStore.memoAdd(app, key, CallbackStore.mask(num), name, memo, "note")
            runOnUiThread {
                if (j == null) {
                    Toast.makeText(app, "저장하지 못했어요. 인터넷을 확인해주세요", Toast.LENGTH_SHORT).show()
                    if (memo.isNotEmpty() && !isFinishing) memoEt.setText(memo)
                } else {
                    if (!isFinishing) { data = j; render() }
                    Toast.makeText(app, "📒 저장했어요", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 📞 전화 받기 ('전화 받기' 권한이 없으면 먼저 물어봄) */
    private fun answer() {
        if (Build.VERSION.SDK_INT < 26) { finish(); return }
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS), 7705); return
        }
        try {
            @Suppress("DEPRECATION")
            (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
            answerBtn?.visibility = View.GONE // 통화 중에는 메모 화면 그대로 (닫으면 통화 화면)
            place(atTop = true) // 받았으면 위쪽으로 (아래 통화 종료 버튼이 보이게)
        } catch (e: Exception) {
            Toast.makeText(this, "아래 전화 화면에서 받아주세요", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7705 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) answer()
    }
}
