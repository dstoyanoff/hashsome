package dev.hashsome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.ActorKind
import dev.hashsome.client.Iso8601
import dev.hashsome.client.LogbookChange
import dev.hashsome.client.LogbookEntry
import dev.hashsome.headless.LogbookControl
import dev.hashsome.headless.LogbookState
import dev.hashsome.headless.Scope
import dev.hashsome.ui.icons.Ui

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * A short activity list for an entity's drawer, folded away until asked for (the web's `HistorySection` with `LogbookHistory`): the
 * first opening asks the runtime through [control], with placeholder rows until the answer comes. A backend that keeps no activity,
 * or an error, shows "No recent activity".
 */
open class HistorySectionView(context: Context, val control: LogbookControl, scope: Scope) : ColumnLayout(context) {
    private val chevron = GlyphView(context, Ui.chevronDown, 16)
    private val body = ColumnLayout(context)
    private var open = false

    init {
        gap = 2
        val toggle = object : RowLayout(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP && contains(event.x, event.y)) toggle()
                return true
            }
        }.also {
            it.isClickable = true
            val pad = Math.round(unitPx())
            it.setPadding(0, pad, 0, pad)
        }
        toggle.addView(AlignedText(context, "HISTORY", Role.Eyebrow, theme.palette.textMuted), FlexParams(WRAP, WRAP).apply { grow = true })
        toggle.addView(chevron, FlexParams(WRAP, WRAP))
        chevron.color = theme.palette.textMuted
        addView(toggle, FlexParams(MATCH, WRAP))
        body.visibility = GONE
        addView(body, FlexParams(MATCH, WRAP))
        scope.bind(control.state) { render() }
    }

    private fun toggle() {
        open = !open
        if (open) control.open()
        if (Animation.full) chevron.animate().rotation(if (open) 180f else 0f).setDuration(200).setInterpolator(EASE_OUT).start() else chevron.rotation = if (open) 180f else 0f
        render()
    }

    private fun render() {
        body.visibility = if (open) VISIBLE else GONE
        if (!open) return
        body.removeAllViews()
        when (val state = control.state.value) {
            LogbookState.Idle, LogbookState.Loading -> repeat(3) { row ->
                val line = RowLayout(context).also { it.gap = 3 }
                val pad = Math.round(2 * unitPx())
                line.setPadding(0, pad, 0, pad)
                line.addView(Skeleton(context).also { it.radius = 16f }, FlexParams(dp(32), dp(32)))
                val text = ColumnLayout(context).also { it.gap = 1; it.align = Align.Start }
                text.addView(Skeleton(context), FlexParams(dp(if (row == 1) 150 else 190), dp(14)))
                text.addView(Skeleton(context), FlexParams(dp(56), dp(12)))
                line.addView(text, FlexParams(WRAP, WRAP))
                body.addView(line, FlexParams(MATCH, WRAP))
            }

            is LogbookState.Failed -> none()
            is LogbookState.Ready -> if (state.entries.isEmpty()) none() else {
                val now = Hashsome.scheduler.wallMs()
                state.entries.forEach { body.addView(ActivityRowView(context, it, now), FlexParams(MATCH, WRAP)) }
            }
        }
    }

    private fun none() {
        body.addView(AlignedText(context, "No recent activity", Role.Secondary, theme.palette.textMuted), FlexParams(MATCH, WRAP))
    }
}

/** One entry of the activity list: a round picture (initials for a person, a workflow glyph for an automation, a switch or a chip for nobody), what happened, and when. */
internal class ActivityRowView(context: Context, private val entry: LogbookEntry, now: Long) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strong = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val plain = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val person = entry.actor != null && entry.actorKind == ActorKind.Person
    private val initials = entry.actor?.let { ActivityText.initials(it) } ?: ""
    private val whenText = Iso8601.parse(entry.timestamp)?.let { ActivityText.relativeTime(it, now) } ?: ""
    private val message = if (entry.actor != null) entry.message else ActivityText.capitalize(entry.message)
    private val glyph: Icon? = when {
        person -> null
        entry.actor != null -> Ui.workflow
        else -> when (entry.change) {
            LogbookChange.On -> Ui.toggleRight
            LogbookChange.Off -> Ui.toggleLeft
            else -> Ui.cpu
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(32) + 2 * Math.round(2 * unitPx()))
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        val p = t.palette
        val size = dp(32f)
        val cy = height / 2f
        paint.color = if (person) p.accent else p.surfaceRaised
        canvas.drawCircle(size / 2, cy, size / 2, paint)
        if (person) {
            strong.style(t, Role.Eyebrow, p.accentText)
            canvas.drawTextCentered(initials, size / 2, cy, strong, Paint.Align.CENTER)
        } else if (glyph != null) {
            val s = dp(16)
            canvas.drawBitmap(iconBitmap(glyph, s, if (entry.actor == null) p.textMuted else p.text), size / 2 - s / 2f, cy - s / 2f, null)
        }

        val x = size + 3 * unitPx()
        val room = width - x
        plain.style(t, Role.Body, p.text)
        strong.style(t, Role.BodyStrong, p.text)
        val actor = entry.actor
        val lead = if (actor != null) "$actor " else ""
        val leadWidth = if (lead.isEmpty()) 0f else strong.measureText(lead)
        val first = cy - plain.fontSpacing * 0.5f
        if (lead.isNotEmpty()) {
            canvas.drawTextCentered(TextUtils.ellipsize(lead, strong, room, TextUtils.TruncateAt.END), x, first, strong)
        }
        canvas.drawTextCentered(TextUtils.ellipsize(message, plain, maxOf(0f, room - leadWidth), TextUtils.TruncateAt.END), x + leadWidth, first, plain)
        plain.style(t, Role.Secondary, p.textMuted)
        canvas.drawTextCentered(whenText, x, cy + plain.fontSpacing * 0.6f, plain)
    }
}
