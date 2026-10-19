package dev.hashsome.kiosk

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.Toast
import dev.hashsome.client.Cancel
import dev.hashsome.client.Store
import dev.hashsome.ui.ChipsView
import dev.hashsome.ui.Align
import dev.hashsome.ui.ColumnLayout
import dev.hashsome.ui.Drawer
import dev.hashsome.ui.FlexParams
import dev.hashsome.ui.FormField
import dev.hashsome.ui.FormHeading
import dev.hashsome.ui.FormRow
import dev.hashsome.ui.FormSelect
import dev.hashsome.ui.FormSlider
import dev.hashsome.ui.Hashsome
import dev.hashsome.ui.Overlay
import dev.hashsome.ui.FlowRow
import dev.hashsome.ui.PillButton
import dev.hashsome.ui.RowLayout
import dev.hashsome.ui.Sheet
import dev.hashsome.ui.ThemeChoice
import dev.hashsome.ui.ToggleView
import dev.hashsome.ui.dp
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The hidden settings: connection (server, device, profile, status, reload), dashboard (choice, switcher), theme, screen
 * (brightness, auto-brightness, minimum, night mode and its idle time, wake/sleep test), MQTT, debug. A `Sheet` on small
 * screens, a `Drawer` on wide ones; every control writes the one `Settings` store. Opened by the [Gesture], or by
 * `am start --es settings true`.
 *
 * Built from the `ui` form views (a heading, label + control rows, toggle, dropdown, slider, text field, pill buttons), so it
 * follows the theme like the dashboards do.
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
        // The theme the sheet was drawn in is baked into its views: a change of theme (its own chips make one) closes it.
        val themed = Hashsome.themeChanged.listen { shell.dismiss() }
        val before = shell.onDismissed
        shell.onDismissed = {
            themed()
            before?.invoke()
        }
        shell.title = "Settings"
        shell.subtitle = (Settings.deviceName.value ?: Settings.deviceId.value)?.takeIf { it.isNotBlank() }
        shell.show(SettingsSheet(activity, Settings, Kiosk.state, Kiosk.actions).view())
    }
}


/** Opens the settings over [activity], as the gesture does (a consumer app without the kiosk's gesture can call it). */
fun showSettingsSheet(activity: Activity) = SettingsHost.open(activity)

/** The width of a control at the right of a row, dp. */
private const val CONTROL_DP = 200

/** The content: it listens to the stores while it is on screen and lets go when it is not. */
private class SheetContent(
    private val ctx: Context,
    private val settings: Settings,
    private val state: KioskState,
    private val actions: KioskActions,
) : ScrollView(ctx) {
    private val column = ColumnLayout(ctx)
    private val binders = ArrayList<() -> Cancel>()
    private var live = ArrayList<Cancel>()

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        section("Connection")
        info("Link") { state.link.listenNow(it) }
        info("Integrations") { show -> integrations(show) }
        info("App version") { show -> show(appVersion(ctx)); {} }
        info("Device IP") { show -> show(localIp() ?: "unknown"); {} }
        info("Profile in use") { show -> show(DeviceProfile.detect().id); {} }
        textField("Server", settings.server, { it ?: "" }, { it.trim().ifEmpty { null } }, InputType.TYPE_TEXT_VARIATION_URI, clearable = true)
        textField("Device id", settings.deviceId, { it ?: "" }, { it.trim().ifEmpty { null } }, clearable = true)
        textField("Device name", settings.deviceName, { it ?: "" }, { it.trim().ifEmpty { null } }, clearable = true)
        select("Profile", listOf("auto") + listOf(DeviceProfile.ShellyStargate, DeviceProfile.ShellyJenna, DeviceProfile.Generic).map { it.id }, settings.profileOverride, { it ?: "auto" }, { if (it == "auto") null else it })
        val stock = button("Open Shelly app") { actions.openStockApp() }
        binders += { actions.canOpenStockApp.listenNow { stock.visibility = if (it) View.VISIBLE else View.GONE } }
        buttons(
            button("Reload (reconnect)", primary = true) { actions.reload() },
            button("Restart app") { actions.restartApp() },
            stock,
            // Applies the last PROVISION broadcast again.
            button("Re-provision", description = "Re-provision from the last broadcast") {
                val last = Provisioning.decode(settings.lastProvision)
                if (last.isEmpty()) toast("No provisioning broadcast yet") else {
                    Provisioning.apply(settings, last)
                    toast("Applied")
                }
            },
        )

        section("Dashboard")
        val ids = try { Hashsome.app.dashboards.map { it.id } } catch (e: Throwable) { emptyList() }
        select("Dashboard", ids, settings.dashboard, { it ?: ids.firstOrNull() ?: "" }, { it })
        switch("Dashboard switcher", settings.switcherEnabled)

        section("Theme")
        chips("Theme", ThemeChoice.values().map { it.name }, settings.theme, { it.ordinal }, { ThemeChoice.values()[it] })

        section("Screen")
        slider("Brightness", settings.brightness)
        switch("Auto brightness", settings.autoBrightness)
        slider("Minimum brightness", settings.minBrightness)

        section("Night mode")
        switch("Night mode", settings.night)
        textField("Idle time (s)", settings.nightIdleMs, { (it / 1000).toString() }, { it.trim().toLongOrNull()?.let { s -> s.coerceIn(5, 3600) * 1000 } }, InputType.TYPE_CLASS_NUMBER)
        buttons(button("Sleep the screen") { actions.sleep() })

        section("MQTT")
        switch("Enabled", settings.mqtt.enabled)
        textField("Host", settings.mqtt.host, { it }, { it.trim() }, InputType.TYPE_TEXT_VARIATION_URI)
        textField("Port", settings.mqtt.port, { it.toString() }, { it.trim().toIntOrNull()?.takeIf { p -> p in 1..65535 } }, InputType.TYPE_CLASS_NUMBER)
        textField("Base topic", settings.mqtt.base, { it }, { it.trim().trim('/').ifEmpty { null } })

        section("Debug")
        switch("Module grid overlay", settings.debug.grid)
        info("Device admin") { show -> show(if (DeviceAdmin.isActive(ctx)) "active" else "not active"); {} }
        // Restart device only where the device can be rebooted (probed after the service starts, so it may appear a moment after the sheet opens).
        val restart = confirmButton("Restart device", "Tap again to restart the device") { actions.restartDevice() }
        binders += { actions.canRestartDevice.listenNow { restart.visibility = if (it) View.VISIBLE else View.GONE } }
        buttons(
            confirmButton("Remove device admin (before uninstalling)", "Tap again to remove the device admin") {
                toast(if (DeviceAdmin.remove(ctx)) "Device admin removed" else "The device admin is not active")
            },
            restart,
        )
    }

    // ---- rows -----------------------------------------------------------------------------------------------------

    private fun add(view: View) {
        column.addView(view, FlexParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun section(title: String) = add(FormHeading(ctx, title))

    /** A line whose value comes from [source]: it is given the setter and returns how to stop listening. */
    private fun info(name: String, source: ((String) -> Unit) -> Cancel) {
        val row = FormRow(ctx, name)
        add(row)
        binders += { source { row.value = it } }
    }

    private fun integrations(show: (String) -> Unit): Cancel {
        val map = try { Hashsome.client.integrations } catch (e: Throwable) { show("unknown"); return {} }
        return map.listenNow { statuses -> show(if (statuses.isEmpty()) "none" else statuses.entries.joinToString(", ") { "${it.key}: ${it.value.name.lowercase()}" }) }
    }

    private fun switch(name: String, store: Store<Boolean>) {
        val toggle = ToggleView(ctx)
        toggle.onChange = { store.set(it) }
        add(FormRow(ctx, name).control(toggle))
        binders += { store.listenNow { toggle.checked = it } }
    }

    /** A percentage slider with its value beside it. */
    private fun slider(name: String, store: Store<Int>) {
        val slider = FormSlider(ctx, 0, 100, "%")
        slider.onChange = { store.set(it) }
        add(FormRow(ctx, name).control(slider, CONTROL_DP))
        binders += { store.listenNow { slider.value = it } }
    }

    private fun <T> select(name: String, options: List<String>, store: Store<T>, show: (T) -> String, parse: (String) -> T) {
        val select = FormSelect(ctx)
        select.options = options
        select.onSelect = { store.set(parse(it)) }
        add(FormRow(ctx, name).control(select, CONTROL_DP))
        binders += { store.listenNow { select.selected = show(it) } }
    }

    /** A few choices as chips (the web's `ChipRow`): the selected one on the accent pill. */
    private fun <T> chips(name: String, labels: List<String>, store: Store<T>, indexOf: (T) -> Int, of: (Int) -> T) {
        val row = ChipsView(ctx)
        row.labels = labels
        row.onSelect = { store.set(of(it)) }
        add(FormRow(ctx, name).control(row))
        binders += { store.listenNow { row.current = indexOf(it) } }
    }

    /** A text field that writes when the keyboard's Done is pressed or the field loses focus, and shows what the store says otherwise. */
    private fun <T> textField(name: String, store: Store<T>, show: (T) -> String, parse: (String) -> T?, inputType: Int = InputType.TYPE_CLASS_TEXT, clearable: Boolean = false) {
        val field = FormField(ctx)
        field.inputType = inputType
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
        add(FormRow(ctx, name).control(field, CONTROL_DP))
        binders += {
            store.listenNow { if (!field.hasFocus()) field.setText(show(it)) }
        }
    }

    private fun button(name: String, primary: Boolean = false, description: String? = null, onClick: () -> Unit): PillButton =
        PillButton(ctx, null, name).also {
            if (description != null) it.contentDescription = description
            it.heightDp = 40
            it.padUnits = 4.5f
            it.primary = primary
            it.onClick = onClick
        }

    /** A button that needs a second tap within 4 s, for what cannot be undone from the display; in the danger color. */
    private fun confirmButton(name: String, confirm: String, onConfirmed: () -> Unit): PillButton {
        val b = button(name) {}
        b.failed = true
        var armed = false
        val disarm = Runnable {
            armed = false
            b.label = name
        }
        b.onClick = {
            if (armed) {
                b.removeCallbacks(disarm)
                disarm.run()
                onConfirmed()
            } else {
                armed = true
                b.label = confirm
                b.postDelayed(disarm, 4_000)
            }
        }
        return b
    }

    /** Action buttons: each its own width, filling a line and wrapping only when the next does not fit. */
    private fun buttons(vararg actions: PillButton): View {
        val holder = FlowRow(ctx).apply { gap = 2 }
        actions.forEach { holder.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        holder.setPadding(0, dp(8), 0, dp(4))
        add(holder)
        return holder
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
