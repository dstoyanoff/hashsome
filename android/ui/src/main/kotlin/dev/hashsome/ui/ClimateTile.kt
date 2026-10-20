package dev.hashsome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import dev.hashsome.client.ClimateMode
import dev.hashsome.headless.ClimateControl
import dev.hashsome.headless.HandleState
import dev.hashsome.headless.LogbookControl
import dev.hashsome.headless.Scope
import dev.hashsome.ui.icons.Ui

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** How a climate mode looks: its glyph and its name (the web's `MODE_META`). */
internal object ClimateLook {
    fun icon(mode: ClimateMode): Icon = when (mode) {
        ClimateMode.Off -> Ui.power
        ClimateMode.Heat -> Ui.flame
        ClimateMode.Cool -> Ui.snowflake
        ClimateMode.Auto -> Ui.refreshCw
        ClimateMode.HeatCool -> Ui.thermometerSun
        ClimateMode.Dry -> Ui.droplets
        ClimateMode.FanOnly -> Ui.wind
    }

    fun label(mode: ClimateMode): String = when (mode) {
        ClimateMode.Off -> "Off"
        ClimateMode.Heat -> "Heat"
        ClimateMode.Cool -> "Cool"
        ClimateMode.Auto -> "Auto"
        ClimateMode.HeatCool -> "Heat/Cool"
        ClimateMode.Dry -> "Dry"
        ClimateMode.FanOnly -> "Fan"
    }

    /** A target as the stepper writes it: "17.5 °C". */
    fun target(value: Double, unit: String): String = "${ClimateControl.number(value)} $unit"
}

/**
 * A thermostat as a tile (the web's `ClimateTile`): the room's temperature as the second line, a button for the mode at the end that opens a
 * short list of the modes, and the target with a button each side to step it, a press by the device's step and a hold faster. Hold the tile for
 * the drawer. A room with no heater is a fixed `Unavailable` handle: the tile stays, dimmed, with no controls.
 */
open class ClimateTileView(context: Context, val control: ClimateControl, val label: String) : BoundView(context) {
    val tile: TileView = hostTile()

    var icon: Icon = Ui.thermometer
        set(value) {
            field = value
            render()
        }

    private val modeButton = RoundButton(context, Ui.power)
    private val value = LabelView(context, Role.Label)
    private val minus = StepButtonView(context, Ui.minus, -1, "Decrease target", ::step, holdFactor = 4)
    private val plus = StepButtonView(context, Ui.plus, 1, "Increase target", ::step, holdFactor = 4)
    private val stepper = RowLayout(context)

    init {
        tile.title = label
        stepper.gap = 1
        stepper.align = Align.Center
        stepper.addView(minus, FlexParams(dp(StepButtonView.SIZE), dp(StepButtonView.SIZE)))
        stepper.addView(value, FlexParams(WRAP, WRAP))
        stepper.addView(plus, FlexParams(dp(StepButtonView.SIZE), dp(StepButtonView.SIZE)))
        // The tile lays its children out from its right edge: the mode, then the stepper at the very end.
        modeButton.onClick = { openModes() }
        modeButton.contentDescription = "Mode"
        tile.addView(modeButton)
        tile.addView(stepper)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(control.handle.state) { render() }
        bind(control.state) { render() }
    }

    private fun step(steps: Int) = control.nudge(steps)

    private fun openModes() {
        val modes = control.state.value.modes
        val items = modes.map { MenuItem(ClimateLook.label(it), ClimateLook.icon(it)) }
        OptionMenu(context, items, modes.indexOf(control.state.value.mode).takeIf { it >= 0 }, alignEnd = true) { chosen ->
            control.setMode(modes[chosen])
        }.showFrom(modeButton)
    }

    private fun render() {
        val handleState = control.handle.state.value
        val ready = handleState is HandleState.Ready
        val s = control.state.value
        tile.disabled = !ready
        tile.icon = icon
        tile.secondary = StatusLabels.of(handleState) ?: s.current?.let { climateReading(it, s.unit) }
        modeButton.visibility = if (ready) VISIBLE else GONE
        modeButton.icon = ClimateLook.icon(s.mode)
        modeButton.active = s.mode != ClimateMode.Off
        val shown = s.shown
        stepper.visibility = if (ready && shown != null) VISIBLE else GONE
        if (shown != null) {
            value.text = ClimateLook.target(shown, s.unit)
            minus.isEnabled = shown > s.min
            plus.isEnabled = shown < s.max
        }
    }
}

/**
 * The body of a thermostat's drawer (the web's `ClimateDetailBody`): the mode to pick, with what the heater is doing under its name; the
 * target, to step by the device's step and faster while a button is held; the presets it has; then what it draws over time ([energy]) and
 * its recent changes ([history]).
 */
open class ClimateDrawerBody(
    context: Context,
    val control: ClimateControl,
    scope: Scope,
    history: LogbookControl? = null,
    energy: View? = null,
) : BoundView(context) {
    private val column = ColumnLayout(context).also { it.gap = 2 }
    private val modeHint = LabelView(context, Role.Secondary, Tone.Muted)
    private val modes = ChipRowView(context)
    private val targetRow = RowLayout(context).also { it.justify = Justify.SpaceBetween }
    private val targetValue = LabelView(context, Role.BodyStrong)
    private val minus = StepButtonView(context, Ui.minus, -1, "Decrease target", ::step, holdFactor = 4, sizeDp = 28)
    private val plus = StepButtonView(context, Ui.plus, 1, "Increase target", ::step, holdFactor = 4, sizeDp = 28)
    private val presetRow = ColumnLayout(context).also { it.gap = 2 }
    private val presets = ChipRowView(context)
    private var shownModes: List<ClimateMode> = emptyList()
    private var shownPresets: List<String> = emptyList()

    init {
        val u = unitPx()
        fun vertical(view: View) = view.setPadding(0, Math.round(3.5f * u), 0, Math.round(3.5f * u))

        val modeColumn = ColumnLayout(context).also { it.gap = 2 }
        modeColumn.addView(LabelView(context, Role.BodyStrong).also { it.text = "Mode" }, FlexParams(WRAP, WRAP))
        modeColumn.addView(modeHint, FlexParams(MATCH, WRAP))
        modeColumn.addView(modes, FlexParams(MATCH, dp(ChipRowView.CHIP_HEIGHT)))
        modes.onSelect = { index -> shownModes.getOrNull(index)?.let(control::setMode) }
        vertical(modeColumn)
        column.addView(modeColumn, FlexParams(MATCH, WRAP))

        val targetText = ColumnLayout(context)
        targetText.addView(LabelView(context, Role.BodyStrong).also { it.text = "Target" }, FlexParams(WRAP, WRAP))
        targetText.addView(LabelView(context, Role.Secondary, Tone.Muted).also { it.text = "Hold a button to change faster" }, FlexParams(WRAP, WRAP))
        targetRow.addView(targetText, FlexParams(0, WRAP).apply { weight = 1f })
        val stepper = RowLayout(context).also { it.gap = 2; it.align = Align.Center }
        stepper.addView(minus, FlexParams(dp(28), dp(28)))
        stepper.addView(targetValue, FlexParams(WRAP, WRAP))
        stepper.addView(plus, FlexParams(dp(28), dp(28)))
        targetRow.addView(stepper, FlexParams(WRAP, WRAP))
        vertical(targetRow)
        column.addView(targetRow, FlexParams(MATCH, WRAP))

        presetRow.addView(LabelView(context, Role.BodyStrong).also { it.text = "Preset" }, FlexParams(WRAP, WRAP))
        presetRow.addView(presets, FlexParams(MATCH, dp(ChipRowView.CHIP_HEIGHT)))
        presets.onSelect = { index -> shownPresets.getOrNull(index)?.let(control::setPreset) }
        vertical(presetRow)
        column.addView(presetRow, FlexParams(MATCH, WRAP))

        if (energy != null) column.addView(energy, FlexParams(MATCH, WRAP))
        if (history != null) column.addView(HistorySectionView(context, history, scope), FlexParams(MATCH, WRAP))
        addView(column, LayoutParams(MATCH, WRAP))
    }

    private fun step(steps: Int) = control.nudge(steps)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(control.handle.state) { render() }
        bind(control.state) { render() }
    }

    private fun render() {
        val s = control.state.value
        val ready = control.handle.state.value is HandleState.Ready
        modeHint.text = StatusLabels.of(control.handle.state.value) ?: s.summary
        if (shownModes != s.modes) {
            shownModes = s.modes
            modes.chips = s.modes.map { ChipRowView.Chip(ClimateLook.label(it), ClimateLook.icon(it)) }
        }
        modes.current = s.modes.indexOf(s.mode).coerceAtLeast(0)
        val shown = s.shown
        targetRow.visibility = if (ready && shown != null) VISIBLE else GONE
        if (shown != null) {
            targetValue.text = ClimateLook.target(shown, s.unit)
            minus.isEnabled = shown > s.min
            plus.isEnabled = shown < s.max
        }
        presetRow.visibility = if (ready && s.presets.isNotEmpty()) VISIBLE else GONE
        if (shownPresets != s.presets) {
            shownPresets = s.presets
            presets.chips = s.presets.map { ChipRowView.Chip(ClimateControl.title(it)) }
        }
        presets.current = s.presets.indexOf(s.preset).coerceAtLeast(0)
    }
}
