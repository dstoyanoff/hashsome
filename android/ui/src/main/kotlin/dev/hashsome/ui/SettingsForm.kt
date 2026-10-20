package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.PopupWindow

// The controls of a settings form, drawn with the theme like every other view: a heading, a row of label and control, a toggle, a
// dropdown, a slider with its value, a text field. They sit on a panel's `surface`, so every control is a `surfaceRaised` shape
// with an outline in the `line` color: nothing is the surface's own color on the surface. The accent shows only what is on.

/** A control's outline: the line color, softened, so a raised shape is told from the surface in the light theme as well as the dark one. */
internal fun outlineColor(): Int = withAlpha(theme.palette.line, 0.45f)

/** A section's title in the eyebrow type role and the muted color, with room above it. */
class FormHeading(context: Context, text: CharSequence) : View(context) {
    var text: CharSequence = text
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        paint.style(theme, Role.Eyebrow, 0)
        val line = Math.ceil((paint.textSize * (theme.style(Role.Eyebrow).lineHeight ?: 1.3f)).toDouble()).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), line + dp(HEADING_TOP) + dp(6))
    }

    override fun onDraw(canvas: Canvas) {
        paint.style(theme, Role.Eyebrow, theme.palette.textMuted)
        canvas.drawTextCentered(text.toString().uppercase(), 0f, dp(HEADING_TOP) + (height - dp(HEADING_TOP) - dp(6)) / 2f, paint)
    }

    private companion object {
        const val HEADING_TOP = 22
    }
}

/**
 * A label with its control at the right, [FormMath.rowHeightDp] tall, a hairline under it. A read-only line sets [value]; a control
 * is added with [control].
 */
open class FormRow(context: Context, label: CharSequence) : RowLayout(context) {
    private val labelView = LabelView(context, Role.Body, Tone.Text).also { it.text = label }
    private var valueView: LabelView? = null
    private val paint = Paint()

    init {
        gap = 3
        align = Align.Center
        setWillNotDraw(false)
        addView(labelView, FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            grow = true
            minHeightDp = FormMath.rowHeightDp(theme.density)
        })
    }

    /** The row's label. */
    var label: CharSequence
        get() = labelView.text
        set(value) {
            labelView.text = value
        }

    /** A value shown at the right in the text color (for a line that only reports). */
    var value: CharSequence
        get() = valueView?.text ?: ""
        set(text) {
            val view = valueView ?: LabelView(context, Role.Body, Tone.Muted).also {
                it.align = Paint.Align.RIGHT
                valueView = it
                addView(it, FlexParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1.6f })
            }
            view.text = text
        }

    /** Puts [view] at the right, [widthDp] wide (its own size when 0). */
    fun control(view: View, widthDp: Int = 0): FormRow {
        addView(view, FlexParams(if (widthDp > 0) dp(widthDp) else LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return this
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = theme.palette.border
        canvas.drawRect(0f, height - dp(1f), width.toFloat(), height.toFloat(), paint)
    }
}

/** An on/off switch: a pill track with a knob that slides, the accent track when on and a raised, outlined one when off. */
class ToggleView(context: Context) : View(context) {
    var checked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            knob.to(if (value) 1f else 0f)
        }

    /** Called with the new state when a finger toggles it (not when [checked] is set). */
    var onChange: ((Boolean) -> Unit)? = null

    private val knob = Eased(this, 0f, 160)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(dp(WIDTH), dp(HEIGHT))

    override fun onDraw(canvas: Canvas) {
        val p = theme.palette
        val h = height.toFloat()
        val w = width.toFloat()
        val on = knob.value
        rect.set(0f, 0f, w, h)
        paint.style = Paint.Style.FILL
        paint.color = mix(p.surfaceRaised, p.accent, on)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = mix(outlineColor(), p.accent, on)
        rect.inset(dp(0.75f), dp(0.75f))
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val r = h / 2 - dp(5f)
        val x = h / 2 + (w - h) * on
        paint.style = Paint.Style.FILL
        paint.color = mix(p.line, p.accentText, on)
        canvas.drawCircle(x, h / 2, r, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && contains(event.x, event.y)) {
            checked = !checked
            onChange?.invoke(checked)
        }
        return true
    }

    private companion object {
        const val WIDTH = 48
        const val HEIGHT = 28
    }
}

/** A dropdown: a raised pill showing the current value and a chevron; a tap opens a list of [options] under (or over) it. */
class FormSelect(context: Context) : View(context) {
    var options: List<String> = emptyList()
        set(value) {
            field = value
            requestLayout()
        }

    /** What the pill shows. */
    var selected: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Called with an option a finger chose. */
    var onSelect: ((String) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var popup: PopupWindow? = null

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        text.style(theme, Role.Body, 0)
        val wide = (options + selected).maxOfOrNull { text.measureText(it) } ?: 0f
        val own = Math.ceil(wide.toDouble()).toInt() + dp(14 + 8 + 14 + 14)
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(own, MeasureSpec.getSize(widthMeasureSpec))
            else -> own
        }
        setMeasuredDimension(w, dp(HEIGHT))
    }

    override fun onDraw(canvas: Canvas) {
        val p = theme.palette
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        paint.style = Paint.Style.FILL
        paint.color = p.surfaceRaised
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = outlineColor()
        rect.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val chevron = dp(14)
        text.style(theme, Role.Body, p.text)
        val room = width - dp(14) - chevron - dp(8) - dp(10)
        val shown = android.text.TextUtils.ellipsize(selected, text, room.toFloat(), android.text.TextUtils.TruncateAt.END)
        canvas.drawTextCentered(shown, dp(14f), h / 2, text)
        canvas.drawIcon(iconBitmap(UiIcons.chevronDown, chevron, p.text), (width - dp(10) - chevron).toFloat(), (h - chevron) / 2)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && contains(event.x, event.y)) open()
        return true
    }

    private fun open() {
        if (popup != null || options.isEmpty()) return
        val list = OptionList(context, options, selected) { choice ->
            popup?.dismiss()
            if (choice != selected) onSelect?.invoke(choice)
        }
        val width = maxOf(this.width, dp(160))
        list.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rootView.height - dp(24), MeasureSpec.AT_MOST))
        val at = IntArray(2)
        getLocationInWindow(at)
        val screen = rootView
        val x = FormMath.listLeft(at[0] + this.width, width, screen.width)
        val y = FormMath.listTop(at[1], at[1] + height, list.measuredHeight, screen.height, dp(6))
        val window = PopupWindow(list, width, list.measuredHeight, true)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.isOutsideTouchable = true
        window.elevation = dp(12f)
        window.setOnDismissListener { popup = null }
        popup = window
        window.showAtLocation(screen, Gravity.TOP or Gravity.START, x, y)
    }

    override fun onDetachedFromWindow() {
        popup?.dismiss()
        popup = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val HEIGHT = 40
    }
}

/** The list a [FormSelect] opens: the surface with an outline, one row per option, the chosen one raised and marked with an accent dot. */
private class OptionList(context: Context, private val options: List<String>, private val selected: String, private val pick: (String) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(theme.radius.row))
            }
        }
    }

    private fun rowPx() = dp(FormMath.rowHeightDp(theme.density))

    private fun contentPx() = rowPx() * options.size + 2 * dp(PAD)

    /** How far a long list is scrolled, px. */
    private var offset = 0
    private var downY = 0f
    private var lastY = 0f
    private var dragged = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), minOf(contentPx(), cap))
    }

    override fun onDraw(canvas: Canvas) {
        val p = theme.palette
        val r = dp(theme.radius.row)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = p.surface
        canvas.drawRoundRect(rect, r, r, paint)
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.translate(0f, -offset.toFloat())
        options.forEachIndexed { i, option ->
            val top = dp(PAD) + i * rowPx().toFloat()
            if (option == selected) {
                rect.set(dp(PAD).toFloat(), top, width - dp(PAD).toFloat(), top + rowPx())
                paint.color = p.surfaceRaised
                canvas.drawRoundRect(rect, dp(theme.radius.small), dp(theme.radius.small), paint)
                paint.color = p.accent
                canvas.drawCircle(dp(PAD) + dp(14f), top + rowPx() / 2f, dp(4f), paint)
            }
            text.style(theme, if (option == selected) Role.BodyStrong else Role.Body, p.text)
            canvas.drawTextCentered(option, dp(PAD) + dp(28f), top + rowPx() / 2f, text)
        }
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = outlineColor()
        rect.set(dp(0.5f), dp(0.5f), width - dp(0.5f), height - dp(0.5f))
        canvas.drawRoundRect(rect, r, r, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                lastY = event.y
                dragged = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(event.y - downY) > dp(8)) dragged = true
                if (dragged) offset = (offset - (event.y - lastY).toInt()).coerceIn(0, maxOf(0, contentPx() - height))
                lastY = event.y
                invalidate()
            }

            MotionEvent.ACTION_UP -> if (!dragged) {
                val i = ((event.y + offset - dp(PAD)) / rowPx()).toInt()
                options.getOrNull(i)?.let(pick)
            }
        }
        return true
    }

    private companion object {
        const val PAD = 6
    }
}

/** A slider with its value beside it: a [BarView] on a raised, outlined track and the number (with [unit]) in the text color. */
class FormSlider(context: Context, private val min: Int = 0, private val max: Int = 100, private val unit: String = "%") : RowLayout(context) {
    private val bar = BarView(context).apply {
        raisedTrack = true
        outline = outlineColor()
    }
    private val readout = LabelView(context, Role.Body, Tone.Text).apply {
        align = Paint.Align.RIGHT
        minWidthDp = 44
    }

    /** The value shown, between the bounds. */
    var value: Int = min
        set(v) {
            field = v
            if (!bar.dragging) bar.fraction = FormMath.fractionOf(v, min, max)
            readout.text = "$v$unit"
        }

    /** Called with the value under the finger, as it moves. */
    var onChange: ((Int) -> Unit)? = null

    /** Whether the slider can be moved: when it is not, it is dimmed and takes no touch (it still shows [value]). */
    var active: Boolean = true
        set(v) {
            field = v
            alpha = if (v) 1f else 0.4f
        }

    /** A slider that is not [active] swallows the touch before the bar sees it. */
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean = !active

    init {
        gap = 3
        align = Align.Center
        addView(bar, FlexParams(0, dp(BAR_HEIGHT)).apply { weight = 1f })
        addView(readout, FlexParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        bar.onDrag = {
            val at = FormMath.valueAt(it, min, max)
            if (at != value) {
                readout.text = "$at$unit"
                value = at
                onChange?.invoke(at)
            }
        }
        bar.onCommit = { value = value }
        bar.onCancel = { value = value }
        readout.text = "$min$unit"
    }

    private companion object {
        const val BAR_HEIGHT = 22
    }
}

/**
 * A text field styled as the library's search field: a raised, outlined pill with the accent outline while it has focus.
 * It never takes focus on its own (on attach, resume or when the window regains focus the system would pick the first field and
 * raise the keyboard): it is focusable only once touched, and [release] (also on every window-focus gain) takes the focus and the keyboard away.
 */
class FormField(context: Context) : EditText(context) {
    private val shape = GradientDrawable()

    init {
        val t = theme
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        inputType = InputType.TYPE_CLASS_TEXT
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        typeface = t.typefaces.forWeight(t.style(Role.Body).weight)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(t.style(Role.Body).size.toFloat()))
        setTextColor(t.palette.text)
        setHintTextColor(t.palette.textMuted)
        highlightColor = withAlpha(t.palette.accent, 0.35f)
        background = shape
        setPadding(dp(14), 0, dp(14), 0)
        minHeight = dp(40)
        paintOutline(false)
        isFocusable = false
        isFocusableInTouchMode = false
    }

    /** Lets go of the focus and hides the keyboard; the field is focusable again only by a touch. */
    fun release() {
        clearFocus()
        isFocusable = false
        isFocusableInTouchMode = false
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && !isFocusableInTouchMode) {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        return super.onTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) release()
    }

    private fun paintOutline(focused: Boolean) {
        val p = theme.palette
        shape.setColor(p.surfaceRaised)
        shape.cornerRadius = dp(20f)
        shape.setStroke(dp(if (focused) 2 else 1), if (focused) p.accent else outlineColor())
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        paintOutline(focused)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(40), MeasureSpec.EXACTLY))
    }
}
