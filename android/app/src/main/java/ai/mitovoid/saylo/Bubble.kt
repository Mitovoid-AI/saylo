package ai.mitovoid.saylo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin

/** The Sayvra bubble, drawn over other apps by the accessibility service (no "display over other apps"
 *  permission needed). It sits at the right edge above the keyboard. Hold it to talk and let go to send, or tap
 *  it for hands-free (✕ cancels, ✓ sends); drag it up or down to move it. A second, untouchable window above it
 *  shows the mode ("Listening · WhatsApp"), the commands you can say for 2 s, and messages. */
@SuppressLint("ViewConstructor")
class Bubble(private val host: SayloAccessibility) {
    enum class State { HIDDEN, IDLE, HOLD, LOCKED, WORKING }

    private val ctx: Context = host
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val pill = PillView()
    private val info = InfoView()
    private val pillParams = params(touchable = true)
    private val infoParams = params(touchable = false)
    private var pillAdded = false
    private var infoAdded = false
    private var keyboardHeight = 0
    var state = State.HIDDEN
        private set
    private var label = ""

    private fun params(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = (10 * d).toInt()
    }

    /** Shows the idle bubble above a keyboard of this height (px from the bottom of the screen; 0 for none). */
    fun show(keyboardHeight: Int) {
        this.keyboardHeight = keyboardHeight
        if (state == State.HIDDEN) state = State.IDLE
        place()
        pill.sync()
        if (!pillAdded) {
            wm.addView(pill, pillParams)
            pillAdded = true
        } else wm.updateViewLayout(pill, pillParams)
    }

    /** Hides the bubble, unless a dictation is under way. */
    fun hide() {
        if (state != State.IDLE && state != State.HIDDEN) return
        state = State.HIDDEN
        if (pillAdded) wm.removeView(pill)
        pillAdded = false
        hideInfo()
    }

    /** The lowest the bubble goes: just above the keyboard, or clear of the navigation bar and a chat's send
     *  button when there is no on-screen keyboard. */
    private fun lowest() = if (keyboardHeight > 0) keyboardHeight + (12 * d).toInt() else (140 * d).toInt()

    private fun place() {
        val saved = Store.bubbleY
        val lowest = lowest()
        pillParams.y = if (saved >= 0) maxOf(saved, lowest) else lowest
        infoParams.y = pillParams.y + (64 * d).toInt()
    }

    // ---------- the info window ----------
    private var hints = emptyList<String>()
    private var hintsUntil = 0L  // the commands you can say show for HINT_MS after the bubble is pressed

    private fun showInfo(text: String, seconds: Int = 0) {
        val now = SystemClock.uptimeMillis()
        info.set(text, if (now < hintsUntil) hints else emptyList())
        if (!infoAdded) {
            wm.addView(info, infoParams)
            infoAdded = true
        } else wm.updateViewLayout(info, infoParams)
        main.removeCallbacksAndMessages(info)
        if (seconds > 0) main.postAtTime({ hideInfo() }, info, now + seconds * 1000L)
        if (now < hintsUntil) main.postAtTime({ info.set(text, emptyList()) }, info, hintsUntil)
    }

    private fun hideInfo() {
        main.removeCallbacksAndMessages(info)
        if (infoAdded) wm.removeView(info)
        infoAdded = false
    }

    // ---------- dictation ----------
    private fun start(): Boolean {
        val mode = host.startRecording() ?: return false
        label = Commands.LABELS[mode].orEmpty()
        state = State.HOLD
        pill.sync()
        hints = Commands.hints(mode)
        hintsUntil = SystemClock.uptimeMillis() + HINT_MS
        showInfo(listening("Listening"))
        return true
    }

    private fun listening(word: String) = if (label.isEmpty()) word else "$word · $label"

    private fun finish() {
        hintsUntil = 0
        state = State.WORKING
        pill.sync()
        showInfo(listening("Cleaning up"))
        if (!host.finishRecording()) back()
    }

    private fun cancel() {
        host.cancelRecording()
        hintsUntil = 0
        back()
        hideInfo()
    }

    private fun back() {
        state = if (pillAdded) State.IDLE else State.HIDDEN
        pill.sync()
        host.refreshSoon()  // the keyboard may have closed meanwhile
    }

    /** Dictation's status for the clip being sent. */
    fun onStatus(s: Dictation.Status) {
        when (s) {
            is Dictation.Working -> Unit
            is Dictation.Retrying -> showInfo(s.text)
            is Dictation.Message -> { back(); showInfo(s.text, seconds = 3) }
            is Dictation.Done -> {
                back()
                if (s.pasted) hideInfo() else showInfo("Copied. Paste it where you want it.", seconds = 3)
            }
        }
    }

    // ---------- views ----------
    private inner class PillView : View(ctx) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#16141B") }
        private val mint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7FE0C2") }
        private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2C2933") }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 2.4f * d
        }
        private val size = (52 * d).toInt()
        private val wide = (200 * d).toInt()
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downY = 0f
        private var downAt = 0L
        private var startY = 0
        private var dragging = false
        private var t0 = System.nanoTime()

        init {
            elevation = 6 * d
        }

        fun sync() {
            val w = if (state == State.HOLD || state == State.LOCKED) wide else size
            if (layoutParams?.width != w) {
                pillParams.width = w
                pillParams.height = size
                if (pillAdded) wm.updateViewLayout(this, pillParams)
            }
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(if (state == State.HOLD || state == State.LOCKED) wide else size, size)

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val r = h / 2
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), r, r, ink)
            val time = (System.nanoTime() - t0) / 1e9
            when (state) {
                State.HOLD, State.LOCKED -> {
                    val level = host.level()
                    val bars = 9
                    val gap = 7 * d
                    val left = if (state == State.LOCKED) h + 4 * d else width / 2f - bars * gap / 2
                    for (i in 0 until bars) {
                        val shape = sin((i + 1) / (bars + 1.0) * Math.PI)
                        val amp = 4 * d + (level / 100.0 * 26 * d * shape * (0.6 + 0.4 * sin(time * 9 + i))).toFloat()
                        val x = left + i * gap + gap / 2
                        c.drawRoundRect(RectF(x - 1.6f * d, r - amp / 2, x + 1.6f * d, r + amp / 2), 2 * d, 2 * d, mint)
                    }
                    if (state == State.LOCKED) {
                        // ✕ on the left, ✓ on the right
                        c.drawCircle(r, r, r - 8 * d, soft)
                        stroke.color = Color.WHITE
                        val k = 5.5f * d
                        c.drawLine(r - k, r - k, r + k, r + k, stroke)
                        c.drawLine(r - k, r + k, r + k, r - k, stroke)
                        val cx = width - r
                        c.drawCircle(cx, r, r - 8 * d, mint)
                        stroke.color = ink.color
                        c.drawLine(cx - 6 * d, r, cx - 1.5f * d, r + 5 * d, stroke)
                        c.drawLine(cx - 1.5f * d, r + 5 * d, cx + 7 * d, r - 5 * d, stroke)
                    }
                    postInvalidateOnAnimation()
                }
                State.WORKING -> {
                    stroke.color = mint.color
                    val a = (time * 360 % 360).toFloat()
                    c.drawArc(RectF(r - 11 * d, r - 11 * d, r + 11 * d, r + 11 * d), a, 250f, false, stroke)
                    postInvalidateOnAnimation()
                }
                else -> {  // the Sayvra mark: four bars
                    val heights = floatArrayOf(10f, 20f, 26f, 14f)
                    for (i in heights.indices) {
                        val x = r + (i - 1.5f) * 6 * d
                        val hh = heights[i] * d / 2
                        c.drawRoundRect(RectF(x - 1.6f * d, r - hh, x + 1.6f * d, r + hh), 2 * d, 2 * d, mint)
                    }
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    downAt = System.currentTimeMillis()
                    startY = pillParams.y
                    dragging = false
                    when (state) {
                        State.IDLE -> { t0 = System.nanoTime(); performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); start() }
                        State.LOCKED -> {
                            if (e.x < height) cancel() else finish()  // ✕ on the left; anything else sends
                        }
                        else -> Unit
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    // Moving before it really started listening is a drag, not a dictation.
                    if (!dragging && state == State.HOLD && abs(dy) > slop * 2 && System.currentTimeMillis() - downAt < 300) {
                        host.cancelRecording()
                        state = State.IDLE
                        hideInfo()
                        dragging = true
                        sync()
                    }
                    if (dragging) {
                        pillParams.y = maxOf(lowest(), (startY - dy).toInt())
                        wm.updateViewLayout(this, pillParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        Store.bubbleY = pillParams.y
                        infoParams.y = pillParams.y + (64 * d).toInt()
                    } else if (state == State.HOLD) {
                        if (System.currentTimeMillis() - downAt < HOLD_MS) {  // a tap: hands-free until ✓
                            state = State.LOCKED
                            sync()
                            showInfo(listening("Hands-free") + "  ·  ✓ to send")
                        } else finish()
                    }
                }
            }
            return true
        }
    }

    private inner class InfoView : LinearLayout(ctx) {
        private val line = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { setColor(Color.parseColor("#16141B")); cornerRadius = 18 * d }
        }
        private val card = TextView(ctx).apply {
            setTextColor(Color.parseColor("#16141B"))
            textSize = 13f
            setLineSpacing(4 * d, 1f)
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = 14 * d; setStroke((1 * d).toInt(), Color.parseColor("#E7E1D8"))
            }
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.END
            addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8 * d).toInt() })
            addView(line)
        }

        fun set(text: String, hints: List<String>) {
            line.text = text
            card.visibility = if (hints.isEmpty()) GONE else VISIBLE
            card.text = "YOU CAN SAY\n" + hints.joinToString("\n")
        }
    }

    companion object {
        private const val HOLD_MS = 350  // shorter is a tap (hands-free); longer is hold-to-talk
        private const val HINT_MS = 2000
    }
}
