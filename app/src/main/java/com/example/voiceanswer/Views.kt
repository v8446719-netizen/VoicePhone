package com.example.voiceanswer

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Базовая активность: подхватывает тему и «оживляет» радужный цвет. */
abstract class BaseActivity : Activity() {
    private val tickHandler = Handler(Looper.getMainLooper())
    private val updaters = ArrayList<(Int) -> Unit>()
    private var builtSignature = ""

    private val ticker = object : Runnable {
        override fun run() {
            val a = Theme.accent
            for (u in updaters) u(a)
            if (Theme.rainbow) tickHandler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        builtSignature = Theme.signature()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Theme.bg))
        applyBars(Theme.bg, Theme.bg)
    }

    /** Подписывает действие на смену акцентного цвета (для радужной темы вызывается постоянно). */
    protected fun onAccent(update: (Int) -> Unit) {
        updaters.add(update)
        update(Theme.accent)
    }

    protected fun clearAccentUpdaters() {
        updaters.clear()
    }

    protected fun applyBars(top: Int, bottom: Int) {
        window.statusBarColor = top
        window.navigationBarColor = bottom
        var flags = window.decorView.systemUiVisibility
        flags = if (Theme.light) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        window.decorView.systemUiVisibility = flags
    }

    override fun onResume() {
        super.onResume()
        Theme.load(this)
        if (Theme.signature() != builtSignature && recreateOnThemeChange()) {
            recreate()
            return
        }
        tickHandler.removeCallbacks(ticker)
        if (Theme.rainbow) tickHandler.post(ticker)
    }

    /** Активности с собственной перерисовкой (настройки) возвращают false. */
    protected open fun recreateOnThemeChange(): Boolean = true

    override fun onPause() {
        tickHandler.removeCallbacks(ticker)
        super.onPause()
    }

    protected fun markThemeBuilt() {
        builtSignature = Theme.signature()
    }
}

/** Контейнер, который переносит дочерние элементы на новую строку (для «чипов» со словами). */
class FlowLayout(c: Context) : ViewGroup(c) {
    var hGap = 0
    var vGap = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        var used = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == View.GONE) continue
            measureChild(v, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            val w = v.measuredWidth
            val h = v.measuredHeight
            if (x > 0 && x + w > maxW) {
                x = 0
                y += rowH + vGap
                rowH = 0
            }
            x += w + hGap
            rowH = max(rowH, h)
            used = max(used, x - hGap)
        }
        setMeasuredDimension(
            resolveSize(used + paddingLeft + paddingRight, widthSpec),
            resolveSize(y + rowH + paddingTop + paddingBottom, heightSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == View.GONE) continue
            val w = v.measuredWidth
            val h = v.measuredHeight
            if (x > 0 && x + w > maxW) {
                x = 0
                y += rowH + vGap
                rowH = 0
            }
            v.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + w, paddingTop + y + h)
            x += w + hGap
            rowH = max(rowH, h)
        }
    }
}

/** Расходящиеся круги вокруг аватарки входящего звонка. */
class PulseView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var running = true

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dpf(c, 3f)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val t = (SystemClock.uptimeMillis() % 2600L) / 2600f
        for (i in 0 until 3) {
            val p = (t + i / 3f) % 1f
            paint.color = Theme.accent
            paint.alpha = ((1f - p) * 120f).toInt()
            canvas.drawCircle(cx, cy, maxR * (0.42f + 0.58f * p), paint)
        }
        if (running) postInvalidateOnAnimation()
    }
}

/** «эквалайзер»: бегает по уровню звука с микрофона — видно, что телефон вас слушает. */
class EqualizerView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bars = 11
    private val smooth = FloatArray(bars)

    @Volatile
    var level = 0f
    var running = true

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w / bars * 0.38f
        val bw = (w - gap * (bars - 1)) / bars
        val t = SystemClock.uptimeMillis() * 0.006f
        paint.color = Theme.accent
        for (i in 0 until bars) {
            val wave = 0.35f + 0.65f * abs(sin(t + i * 0.85f))
            val target = 0.12f + 0.88f * level * wave
            smooth[i] += (target - smooth[i]) * 0.3f
            val bh = max(bw, smooth[i] * h)
            val left = i * (bw + gap)
            rect.set(left, (h - bh) / 2f, left + bw, (h + bh) / 2f)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
        if (running) postInvalidateOnAnimation()
    }
}

/** Круглая кнопка с подписью для экрана разговора. Включённое состояние — цветное. */
class ControlButton(c: Context, private val iconRes: Int, label: String) : LinearLayout(c) {
    private val circle = FrameLayout(c)
    private val img = ImageView(c)
    private val caption = TextView(c)
    private var on = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = Ui.dp(c, 66)
        circle.addView(img, FrameLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), Gravity.CENTER))
        addView(circle, LayoutParams(size, size))
        caption.text = label
        caption.textSize = 13f
        caption.gravity = Gravity.CENTER
        addView(caption, LayoutParams(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(c, 6) })
        isClickable = true
        setActive(false)
    }

    fun setActive(active: Boolean) {
        on = active
        val c = context
        val fill = if (active) Ui.accentFill(0f, true) else Ui.oval(0x33FFFFFF)
        circle.background = Ui.ripple(fill, 0f, true)
        img.setImageResource(iconRes)
        img.setColorFilter(Color.WHITE)
        caption.setTextColor(if (active) Color.WHITE else 0xCCFFFFFF.toInt())
        alpha = if (isEnabled) 1f else 0.4f
    }

    fun setIcon(res: Int) {
        img.setImageResource(res)
        img.setColorFilter(Color.WHITE)
    }

    fun setLabel(s: String) {
        caption.text = s
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}

/** Цифровая клавиатура 3×4. Используется и для набора номера, и для тонового набора в разговоре. */
class KeypadView(
    c: Context,
    private val keyDp: Int,
    private val textColor: Int,
    private val keyFill: Int,
    private val onDown: (Char) -> Unit,
    private val onUp: () -> Unit,
    private val onLong: ((Char) -> Unit)? = null
) : LinearLayout(c) {

    private val letters = mapOf(
        '1' to "", '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO",
        '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '*' to "", '0' to "+", '#' to ""
    )

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val rows = listOf("123", "456", "789", "*0#")
        for (row in rows) {
            val r = LinearLayout(c)
            r.orientation = HORIZONTAL
            r.gravity = Gravity.CENTER
            for (ch in row) {
                r.addView(key(c, ch), Ui.margins(Ui.lp(Ui.dp(c, keyDp), Ui.dp(c, keyDp)),
                    Ui.dp(c, 8), Ui.dp(c, 5), Ui.dp(c, 8), Ui.dp(c, 5)))
            }
            addView(r, Ui.lp(Ui.WRAP, Ui.WRAP))
        }
    }

    private fun key(c: Context, ch: Char): View {
        val box = LinearLayout(c)
        box.orientation = VERTICAL
        box.gravity = Gravity.CENTER
        box.background = Ui.ripple(Ui.oval(keyFill), 0f, true)
        box.isClickable = true
        val digit = Ui.light(c, ch.toString(), if (ch == '*' || ch == '#') 30f else 32f, textColor)
        digit.gravity = Gravity.CENTER
        box.addView(digit, Ui.lp(Ui.WRAP, Ui.WRAP))
        val sub = letters[ch].orEmpty()
        if (sub.isNotEmpty()) {
            val s = Ui.text(c, sub, 9f, textColor and 0x00FFFFFF or 0x99000000.toInt())
            s.gravity = Gravity.CENTER
            box.addView(s, Ui.lp(Ui.WRAP, Ui.WRAP))
        }
        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onDown(ch)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUp()
            }
            false
        }
        if (ch == '0' && onLong != null) {
            box.setOnLongClickListener {
                onLong.invoke('+')
                true
            }
        }
        return box
    }
}
