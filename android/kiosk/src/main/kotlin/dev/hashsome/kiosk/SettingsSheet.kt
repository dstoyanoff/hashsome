package dev.hashsome.kiosk

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.AdapterView
import dev.hashsome.client.Cancel
import dev.hashsome.client.Store
import dev.hashsome.ui.Drawer
import dev.hashsome.ui.Hashsome
import dev.hashsome.ui.Overlay
import dev.hashsome.ui.Sheet
import dev.hashsome.ui.ThemeChoice
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The hidden settings: connection (server, device, profile, status, reload), dashboard (choice, switcher), theme, screen
 * (brightness, auto-brightness, minimum, night mode and its idle time, wake/sleep test), MQTT, debug. A `Sheet` on small
 * screens, a `Drawer` on wide ones; every control writes the one `Settings` store. Opened by the [Gesture], or by
 * `am start --es settings true`.
 *
 * The rows are plain framework views (label + switch, slider, dropdown, text field or button); the look is the `Sheet`'s.
 */
class SettingsSheet(
    private val context: Context,
    private val settings: Settings,
    private val state: KioskState,
    private val actions: KioskActions,
) {
    /** The view to show in an overlay. */
    fun view(): View = SheetContent(context, settings, state, actions)
}

/** Shows the sheet over the dashboard that is in front. */
internal object SettingsHost {
    private var overlay: Overlay? = null

    /** Whether the screen is wide enough for a [Drawer] rather than a [Sheet] (dp). */
    private const val WIDE_DP = 600

    fun open(activity: Activity) {
        if (overlay != null) return
        val wide = activity.resources.configuration.screenWidthDp >= WIDE_DP
        val shell: Overlay = if (wide) Drawer(activity) else Sheet(activity)
        overlay = shell
        shell.onDismissed = {
            (shell.parent as? ViewGroup)?.removeView(shell)
            if (overlay === shell) overlay = null
        }
        activity.addContentView(shell, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shell.show(SettingsSheet(activity, Settings, Kiosk.state, Kiosk.actions).view())
    }
}

/** The content: it listens to the stores while it is on screen and lets go when it is not. */
private class SheetContent(
    private val ctx: Context,
    private val settings: Settings,
    private val state: KioskState,
    private val actions: KioskActions,
) : ScrollView(ctx) {
    private val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val binders = ArrayList<() -> Cancel>()
    private var live = ArrayList<Cancel>()
    private val density = ctx.resources.displayMetrics.density

    // Text colors from the theme when it is there (it is once the host runs); the fallback is for a bare device.
    private val fg: Int = try { Hashsome.theme.palette.text } catch (e: Throwable) { Color.WHITE }
    private val dim: Int = try { Hashsome.theme.palette.textMuted } catch (e: Throwable) { Color.LTGRAY }

    private fun dp(v: Int) = (v * density).toInt()

    init {
        isFillViewport = true
        val pad = dp(20)
        column.setPadding(pad, dp(8), pad, pad)
        addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        build()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        live = ArrayList(binders.map { it() })
    }

    override fun onDetachedFromWindow() {
        live.forEach { it() }
        live.clear()
        super.onDetachedFromWindow()
    }

    private fun build() {
        val gesture = if (Kiosk.config.settingsGesture == Gesture.FiveTapsOnStatusDot) "five taps on the status dot, or two fingers held for 2 s" else "two fingers held for 2 s, or five taps on the status dot"
        label("Settings: opened by $gesture.", dim, 13f)

        section("Connection")
        info("Link") { state.link.listenNow(it) }
        info("Integrations") { show -> integrations(show) }
        info("App version") { show -> show(appVersion(ctx)); {} }
        info("Device IP") { show -> show(localIp() ?: "unknown"); {} }
        info("Profile in use") { show -> show(DeviceProfile.detect().id); {} }
        textField("Server", settings.server, { it ?: "" }, { it.trim().ifEmpty { null } }, InputType.TYPE_TEXT_VARIATION_URI, clearable = true)
        textField("Device id", settings.deviceId, { it ?: "" }, { it.trim().ifEmpty { null } }, clearable = true)
        textField("Device name", settings.deviceName, { it ?: "" }, { it.trim().ifEmpty { null } }, clearable = true)
        choice("Profile", listOf("auto") + listOf(DeviceProfile.ShellyStargate, DeviceProfile.ShellyJenna, DeviceProfile.Generic).map { it.id }, settings.profileOverride, { it ?: "auto" }, { if (it == "auto") null else it })
        button("Reload (reconnect)") { actions.reload() }
        button("Re-provision from the last broadcast") {
            val last = Provisioning.decode(settings.lastProvision)
            if (last.isEmpty()) toast("No provisioning broadcast yet") else {
                Provisioning.apply(settings, last)
                toast("Applied")
            }
        }

        section("Dashboard")
        val ids = try { Hashsome.app.dashboards.map { it.id } } catch (e: Throwable) { emptyList() }
        choice("Dashboard", ids, settings.dashboard, { it ?: ids.firstOrNull() ?: "" }, { it })
        switch("Dashboard switcher", settings.switcherEnabled)

        section("Theme")
        choice("Theme", ThemeChoice.values().map { it.name.lowercase() }, settings.theme, { it.name.lowercase() }, { name -> ThemeChoice.values().first { it.name.equals(name, ignoreCase = true) } })

        section("Screen")
        slider("Brightness", settings.brightness, 0, 255)
        switch("Auto brightness", settings.autoBrightness)
        slider("Minimum brightness", settings.minBrightness, 0, 255)
        switch("Night mode", settings.night)
        textField("Night idle time (s)", settings.nightIdleMs, { (it / 1000).toString() }, { it.trim().toLongOrNull()?.let { s -> s.coerceIn(5, 3600) * 1000 } }, InputType.TYPE_CLASS_NUMBER)
        button("Wake the screen") { actions.wake() }
        button("Sleep the screen") { actions.sleep() }

        section("MQTT")
        switch("Enabled", settings.mqtt.enabled)
        textField("Host", settings.mqtt.host, { it }, { it.trim() }, InputType.TYPE_TEXT_VARIATION_URI)
        textField("Port", settings.mqtt.port, { it.toString() }, { it.trim().toIntOrNull()?.takeIf { p -> p in 1..65535 } }, InputType.TYPE_CLASS_NUMBER)
        textField("Base topic", settings.mqtt.base, { it }, { it.trim().trim('/').ifEmpty { null } })

        section("Debug")
        switch("Module grid overlay", settings.debug.grid)
        info("Device admin") { show -> show(if (DeviceAdmin.isActive(ctx)) "active" else "not active"); {} }
        confirmButton("Remove device admin (before uninstalling)", "Tap again to remove the device admin") {
            toast(if (DeviceAdmin.remove(ctx)) "Device admin removed" else "The device admin is not active")
        }
    }

    // ---- rows -----------------------------------------------------------------------------------------------------

    private fun label(value: String, color: Int = fg, size: Float = 16f): TextView = TextView(ctx).apply {
        this.text = value
        setTextColor(color)
        textSize = size
        column.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun section(title: String) {
        label(title, fg, 20f).setPadding(0, dp(24), 0, dp(4))
    }

    private fun row(name: String, control: View, controlWidth: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        line.addView(TextView(ctx).apply { this.text = name; setTextColor(fg); textSize = 16f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(control, LinearLayout.LayoutParams(controlWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(line, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A line whose value comes from [source]: it is given the setter and returns how to stop listening. */
    private fun info(name: String, source: ((String) -> Unit) -> Cancel) {
        val value = TextView(ctx).apply { setTextColor(dim); textSize = 16f; gravity = Gravity.END }
        row(name, value, dp(260))
        binders += { source { value.text = it } }
    }

    private fun integrations(show: (String) -> Unit): Cancel {
        val map = try { Hashsome.client.integrations } catch (e: Throwable) { show("unknown"); return {} }
        return map.listenNow { statuses -> show(if (statuses.isEmpty()) "none" else statuses.entries.joinToString(", ") { "${it.key}: ${it.value.name.lowercase()}" }) }
    }

    private fun switch(name: String, store: Store<Boolean>) {
        val control = Switch(ctx)
        var updating = false
        control.setOnCheckedChangeListener { _, checked -> if (!updating) store.set(checked) }
        row(name, control)
        binders += {
            store.listenNow {
                updating = true
                control.isChecked = it
                updating = false
            }
        }
    }

    private fun slider(name: String, store: Store<Int>, min: Int, max: Int) {
        val bar = SeekBar(ctx).apply { this.max = max - min }
        val value = TextView(ctx).apply { setTextColor(dim); minWidth = dp(40); gravity = Gravity.END }
        val both = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bar, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(value)
        }
        var updating = false
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !updating) store.set(progress + min)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        row(name, both)
        binders += {
            store.listenNow {
                updating = true
                bar.progress = it - min
                value.text = it.toString()
                updating = false
            }
        }
    }

    private fun <T> choice(name: String, options: List<String>, store: Store<T>, show: (T) -> String, parse: (String) -> T) {
        val spinner = Spinner(ctx)
        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, options)
        var updating = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                // The spinner reports its first selection when it is laid out: that is not a choice, and must not write a setting.
                if (!updating && options[position] != show(store.value)) store.set(parse(options[position]))
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        row(name, spinner, dp(220))
        binders += {
            store.listenNow {
                updating = true
                val at = options.indexOf(show(it))
                if (at >= 0) spinner.setSelection(at)
                updating = false
            }
        }
    }

    /** A text field that writes when the keyboard's Done is pressed or the field loses focus, and shows what the store says otherwise. */
    private fun <T> textField(name: String, store: Store<T>, show: (T) -> String, parse: (String) -> T?, inputType: Int = InputType.TYPE_CLASS_TEXT, clearable: Boolean = false) {
        val field = EditText(ctx).apply {
            setTextColor(fg)
            setSingleLine()
            this.inputType = inputType
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.END
        }
        fun commit() {
            val parsed = parse(field.text.toString())
            @Suppress("UNCHECKED_CAST")
            if (parsed != null) store.set(parsed) else if (clearable && field.text.isNullOrBlank()) store.set(null as T)
            field.setText(show(store.value))
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                commit()
                field.clearFocus()
            }
            false
        }
        field.setOnFocusChangeListener { _, focused -> if (!focused) commit() }
        row(name, field, dp(260))
        binders += {
            store.listenNow { if (!field.hasFocus()) field.setText(show(it)) }
        }
    }

    private fun button(name: String, onClick: () -> Unit) {
        val b = Button(ctx).apply { this.text = name; isAllCaps = false; setOnClickListener { onClick() } }
        column.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A button that needs a second tap within 4 s, for what cannot be undone from the display. */
    private fun confirmButton(name: String, confirm: String, onConfirmed: () -> Unit) {
        val b = Button(ctx).apply { this.text = name; isAllCaps = false }
        var armed = false
        val disarm = Runnable {
            armed = false
            b.text = name
        }
        b.setOnClickListener {
            if (armed) {
                b.removeCallbacks(disarm)
                disarm.run()
                onConfirmed()
            } else {
                armed = true
                b.text = confirm
                b.postDelayed(disarm, 4_000)
            }
        }
        column.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toast(message: String) = Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
