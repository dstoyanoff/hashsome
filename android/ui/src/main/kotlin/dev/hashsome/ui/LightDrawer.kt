package dev.hashsome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.LightControl
import dev.hashsome.headless.LightColors
import dev.hashsome.headless.LightPreset
import dev.hashsome.headless.LogbookControl
import dev.hashsome.headless.Scope
import dev.hashsome.ui.icons.Ui

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * The body of a light's drawer (the web's `LightDetailBody` and `LightColorSection`): the power button, the brightness bar with its step
 * buttons, a "Color" card with the temperature and hue bars and the swatches of [LightControl.presets], then the History of what changed
 * it. It shares the tile's [control], so the tile's fill and this bar are one level. The web's "Any color" picker has no counterpart on
 * a display without a keyboard or a color dialog: the swatches stand in its place.
 */
open class LightDrawerBody(context: Context, val control: LightControl, scope: Scope, history: LogbookControl? = null) : BoundView(context) {
    private val column = ColumnLayout(context).also { it.gap = 2 }

    private val powerHint = LabelView(context, Role.Secondary, Tone.Muted)
    private val power = PowerButtonView(context) { if (control.handle.state.value is HandleState.Ready) control.toggle() }
    private val brightness = ColumnLayout(context).also { it.gap = 2 }
    private val brightnessValue = LabelView(context, Role.Secondary, Tone.Muted)
    private val brightnessBar = BarView(context).also { it.raisedTrack = true }
    private val color = ColumnLayout(context).also { it.gap = 2 }
    private val colorDot = DotView(context)
    private val bars = ColumnLayout(context).also { it.gap = 3 }
    private val temperature = ColumnLayout(context).also { it.gap = 2 }
    private val temperatureValue = LabelView(context, Role.Secondary, Tone.Muted)
    private val temperatureBar = GradientBarView(context)
    private val hue = ColumnLayout(context).also { it.gap = 2 }
    private val hueBar = GradientBarView(context)
    private val presets = ColumnLayout(context).also { it.gap = 2 }
    private val swatches = SwatchRowView(context)
    private val chevron = GlyphView(context, Ui.chevronDown, 16)

    private var colorOpen = false
    private var dragKelvin: Int? = null
    private var dragHue: Int? = null
    private var lastBrightness = 0
    private var lastKelvin = 0
    private var lastHue = 0

    init {
        val u = unitPx()
        fun vertical(view: View, units: Float = 3.5f) = view.setPadding(0, Math.round(units * u), 0, Math.round(units * u))

        // Power: the name and what the light is doing, the button at the end.
        val powerRow = RowLayout(context)
        val powerText = ColumnLayout(context)
        powerText.addView(LabelView(context, Role.BodyStrong).also { it.text = "Power" }, FlexParams(WRAP, WRAP))
        powerText.addView(powerHint, FlexParams(WRAP, WRAP))
        powerRow.addView(powerText, FlexParams(0, WRAP).apply { weight = 1f })
        powerRow.addView(power, FlexParams(dp(PowerButtonView.SIZE), dp(PowerButtonView.SIZE)))
        vertical(powerRow)
        column.addView(powerRow, FlexParams(MATCH, WRAP))

        // Brightness: a bar between two fine-adjustment buttons.
        val line = RowLayout(context).also { it.justify = Justify.SpaceBetween }
        line.addView(LabelView(context, Role.BodyStrong).also { it.text = "Brightness" }, FlexParams(WRAP, WRAP))
        line.addView(brightnessValue, FlexParams(WRAP, WRAP))
        brightness.addView(line, FlexParams(MATCH, WRAP))
        val barRow = RowLayout(context).also { it.gap = 2 }
        barRow.addView(StepButtonView(context, Ui.minus, -1, ::step), FlexParams(dp(StepButtonView.SIZE), dp(StepButtonView.SIZE)))
        barRow.addView(brightnessBar, FlexParams(0, dp(BAR_HEIGHT)).apply { weight = 1f })
        barRow.addView(StepButtonView(context, Ui.plus, 1, ::step), FlexParams(dp(StepButtonView.SIZE), dp(StepButtonView.SIZE)))
        brightness.addView(barRow, FlexParams(MATCH, WRAP))
        vertical(brightness)
        column.addView(brightness, FlexParams(MATCH, WRAP))
        brightnessBar.onDrag = { f ->
            lastBrightness = Math.floor(f * 100.0 + 0.5).toInt()
            control.drag(lastBrightness)
        }
        brightnessBar.onCommit = { f -> control.commit(Math.floor(f * 100.0 + 0.5).toInt()) }
        brightnessBar.onCancel = { control.commit(lastBrightness) }

        // Color: a card that folds, with the temperature and hue bars and the swatches.
        val toggle = RowLayout(context).also { it.justify = Justify.SpaceBetween }
        toggle.addView(LabelView(context, Role.BodyStrong).also { it.text = "Color" }, FlexParams(WRAP, WRAP))
        val current = RowLayout(context).also { it.gap = 2 }
        current.addView(colorDot, FlexParams(WRAP, WRAP))
        current.addView(chevron, FlexParams(WRAP, WRAP))
        toggle.addView(current, FlexParams(WRAP, WRAP))
        toggle.isClickable = true
        toggle.setOnClickListener {
            colorOpen = !colorOpen
            render()
        }
        color.addView(toggle, FlexParams(MATCH, WRAP))
        temperature.addView(
            RowLayout(context).also {
                it.justify = Justify.SpaceBetween
                it.addView(LabelView(context, Role.Secondary, Tone.Muted).also { l -> l.text = "Temperature" }, FlexParams(WRAP, WRAP))
                it.addView(temperatureValue, FlexParams(WRAP, WRAP))
            },
            FlexParams(MATCH, WRAP),
        )
        temperature.addView(temperatureBar, FlexParams(MATCH, dp(BAR_HEIGHT)))
        bars.addView(temperature, FlexParams(MATCH, WRAP))
        hue.addView(LabelView(context, Role.Secondary, Tone.Muted).also { it.text = "Hue" }, FlexParams(WRAP, WRAP))
        hue.addView(hueBar, FlexParams(MATCH, dp(BAR_HEIGHT)))
        bars.addView(hue, FlexParams(MATCH, WRAP))
        presets.addView(
            RowLayout(context).also {
                it.justify = Justify.SpaceBetween
                it.addView(LabelView(context, Role.Secondary, Tone.Muted).also { l -> l.text = "Presets" }, FlexParams(WRAP, WRAP))
                it.addView(swatches, FlexParams(WRAP, WRAP))
            },
            FlexParams(MATCH, WRAP),
        )
        bars.addView(presets, FlexParams(MATCH, WRAP))
        bars.setPadding(0, Math.round(u), 0, 0)
        color.addView(bars, FlexParams(MATCH, WRAP))
        vertical(color)
        column.addView(color, FlexParams(MATCH, WRAP))

        temperatureBar.onDrag = { f ->
            lastKelvin = LightBars.kelvinAt(f, kelvinRange())
            dragKelvin = lastKelvin
            render()
        }
        temperatureBar.onCommit = { f ->
            val kelvin = LightBars.kelvinAt(f, kelvinRange())
            dragKelvin = kelvin
            control.setKelvin(kelvin)
            render()
        }
        temperatureBar.onCancel = {
            dragKelvin = lastKelvin
            control.setKelvin(lastKelvin)
            render()
        }
        hueBar.onDrag = { f ->
            lastHue = LightBars.hueAt(f)
            dragHue = lastHue
            render()
        }
        hueBar.onCommit = { f -> commitHue(LightBars.hueAt(f)) }
        hueBar.onCancel = { commitHue(lastHue) }

        if (history != null) column.addView(HistorySectionView(context, history, scope), FlexParams(MATCH, WRAP))
        addView(column, LayoutParams(MATCH, WRAP))
    }

    private fun kelvinRange() = LightBars.kelvinRange(control.handle.entity)

    private fun commitHue(hue: Int) {
        dragHue = hue
        control.setHs(hue.toDouble(), LightBars.saturationOf(control.handle.entity))
        render()
    }

    private fun step(delta: Int) {
        control.commit(LightBars.stepped(control.shown.value, delta))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(control.handle.state) { render() }
        bind(control.on) { render() }
        bind(control.shown) { render() }
    }

    private fun render() {
        val state = control.handle.state.value
        val ready = state is HandleState.Ready
        val light = control.handle.entity
        val on = control.on.value
        val level = control.shown.value
        val dimmable = light?.capabilities?.brightness == true
        val temp = light?.capabilities?.colorTemperature == true
        val hs = light?.capabilities?.color == true

        power.on = on
        power.alpha = if (ready) 1f else 0.5f
        powerHint.text = StatusLabels.of(state) ?: LightBars.powerHint(on, if (dimmable) level else null)

        brightness.visibility = if (ready && dimmable) VISIBLE else GONE
        brightnessValue.text = "$level%"
        brightnessBar.fraction = level / 100f

        val showColor = ready && (temp || hs)
        color.visibility = if (showColor) VISIBLE else GONE
        colorDot.color = LightBars.colorOf(light)?.let(LightBars::argb)
        chevron.icon = if (colorOpen) Ui.chevronUp else Ui.chevronDown
        bars.visibility = if (colorOpen) VISIBLE else GONE

        val range = LightBars.kelvinRange(light)
        val kelvin = LightBars.kelvinOf(light, range)
        // What a finger set stays until the light reports it, as the web's `dragK` and `dragH`.
        if (dragKelvin == kelvin) dragKelvin = null
        val hueNow = LightBars.hueOf(light)
        if (dragHue == hueNow) dragHue = null
        temperature.visibility = if (temp) VISIBLE else GONE
        temperatureBar.stops = LightBars.kelvinStops(range)
        temperatureBar.fraction = LightBars.fractionOfKelvin(dragKelvin ?: kelvin, range)
        temperatureValue.text = "${dragKelvin ?: kelvin} K"
        hue.visibility = if (hs) VISIBLE else GONE
        hueBar.stops = LightBars.hueStops
        hueBar.fraction = LightBars.fractionOfHue(dragHue ?: hueNow)

        val offered = control.presets
        presets.visibility = if (offered.isEmpty()) GONE else VISIBLE
        swatches.swatches = offered.map { preset ->
            Swatch(preset.label ?: "", LightBars.argb(swatchColor(preset)), control.isActive(preset)) { pick(preset) }
        }
    }

    private fun pick(preset: LightPreset) {
        val kelvin = preset.kelvin
        if (kelvin != null) control.setKelvin(kelvin) else control.setHs(preset.hue ?: 0.0, preset.saturation ?: 100.0)
    }

    private fun swatchColor(preset: LightPreset) = preset.kelvin?.let { LightColors.kelvinToRgb(it) }
        ?: LightColors.hsToRgb(preset.hue ?: 0.0, preset.saturation ?: 100.0)

    private companion object {
        const val BAR_HEIGHT = 28
    }
}
