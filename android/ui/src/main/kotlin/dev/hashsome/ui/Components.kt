package dev.hashsome.ui

import android.content.Context
import dev.hashsome.headless.ActionControl
import dev.hashsome.headless.ClimateControl
import dev.hashsome.headless.ConnectionHealth
import dev.hashsome.headless.LightControl
import dev.hashsome.headless.SensorReading
import dev.hashsome.headless.WeatherControl

// The M1 components other than media. Each is a thin view over a headless control: it draws the control's stores and
// forwards touches to its methods, so a consumer can subclass it or write its own against the same control.

/** Today's date as a chip. */
open class DateChip(context: Context) : BoundView(context)

/** The weather now; a tap opens the forecast. */
open class WeatherChip(context: Context, val control: WeatherControl) : BoundView(context)

/** The time of day, large enough to read from across a room. */
open class Clock(context: Context) : BoundView(context)

/** A status dot that opens a popover with the link and each integration's connection. */
open class SystemStatus(context: Context, val health: ConnectionHealth) : BoundView(context)

/** A room's name and icon, with the readouts of its sensors beside it. */
open class RoomHeaderView(context: Context, val title: String, val icon: Icon) : BoundView(context)

/** A temperature, humidity or other reading, with its battery when it has one. */
open class SensorReadoutView(context: Context, val reading: SensorReading) : BoundView(context)

/** A light: tap toggles, drag dims, hold opens the color panel. */
open class LightTileView(context: Context, val control: LightControl, val label: String) : BoundView(context)

/** A thermostat tile. M1 draws only its disabled state (a fixed `Unavailable` handle). */
open class ClimateTileView(context: Context, val control: ClimateControl, val label: String) : BoundView(context)

/** A task that is marked done by holding, with a confirmation dialog and a date field. */
open class TaskTileView(context: Context, val control: ActionControl, val label: String) : BoundView(context)

/** A modal question with a confirm and a cancel, optionally with a date field. */
open class ConfirmDialogView(context: Context) : BoundView(context)

/** A row of tabs that switches between lists (the music page's library and queue). */
open class ListTabsView(context: Context, val tabs: List<String>) : BoundView(context)

/** The page navigation down the side, with an attention dot on a page that needs a look. */
open class NavRailView(context: Context, val pages: List<PageSpec>) : BoundView(context)
