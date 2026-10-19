package dev.hashsome.client

import dev.hashsome.testing.EntityJson
import dev.hashsome.testing.Fixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parsers against the frames the runtime's own mocks produce (`pnpm generate:fixtures`), then against sloppy input. */
class ParsersTest {
    private fun update(name: String): ServerMessage.EntityUpdate =
        ServerMessage.decode(Fixtures.text("entities/$name.json")) as ServerMessage.EntityUpdate

    private inline fun <reified E : Entity> entity(name: String): E = update(name).entity as E

    private fun result(name: String): ServerMessage.Result = ServerMessage.decode(Fixtures.text("results/$name.json")) as ServerMessage.Result

    // --- The whole set ---------------------------------------------------------------------------------------------

    @Test
    fun `every fixture decodes`() {
        assertTrue(Fixtures.paths.size > 60)
        for (path in Fixtures.paths) {
            if (path == "index.json") continue
            val message = ServerMessage.decode(Fixtures.text(path))
            assertNotNull("$path did not decode", message)
            when {
                path.startsWith("entities/") -> assertTrue(path, message is ServerMessage.EntityUpdate && message.entity != null)
                path.startsWith("status/") -> assertTrue(path, message is ServerMessage.Status)
                path.startsWith("results/") -> assertTrue(path, message is ServerMessage.Result)
            }
        }
    }

    @Test
    fun `every fixture entity keeps its ref, name and availability`() {
        for (path in Fixtures.inFolder("entities")) {
            val frame = Fixtures.json(path)
            val entity = (ServerMessage.decode(frame.toString()) as ServerMessage.EntityUpdate).entity!!
            val wire = frame.getJSONObject("entity")
            assertEquals(path, frame.getString("ref"), entity.ref.value)
            assertEquals(path, wire.getString("name"), entity.name)
            assertEquals(path, wire.getString("availability"), entity.availability.wire)
        }
    }

    @Test
    fun `every query result parses with its own parser`() {
        for (path in Fixtures.inFolder("results")) {
            val name = path.substringAfter("results/").removeSuffix(".json")
            val frame = result(name)
            when {
                name == "error" -> assertFalse(frame.ok)
                name == "command-ok" -> assertTrue(frame.ok && frame.data == null)
                name.startsWith("browse") -> Query.Browse().parse(frame.data)
                name.startsWith("queue") -> Query.Queue().parse(frame.data)
                name.startsWith("forecast") -> Query.Forecast(ForecastType.Daily).parse(frame.data)
                name.startsWith("history") -> Query.History(HistoryRange.Day).parse(frame.data)
                name.startsWith("logbook") -> Query.Logbook().parse(frame.data)
                else -> throw AssertionError("no parser for $path")
            }
        }
    }

    @Test
    fun `the test fixtures encode back to what parses to the same entity`() {
        for (path in Fixtures.inFolder("entities")) {
            val parsed = (ServerMessage.decode(Fixtures.text(path)) as ServerMessage.EntityUpdate).entity!!
            val again = Entities.parse(parsed.ref, EntityJson.encode(parsed))
            assertEquals(path, parsed, again)
        }
    }

    // --- One entity of each kind, field by field -----------------------------------------------------------------------

    @Test
    fun `a color light`() {
        val light = entity<LightEntity>("ha-light.fx_color_on")
        assertEquals(EntityRef("ha:light.fx_color_on"), light.ref)
        assertEquals("Color light on", light.name)
        assertEquals(Availability.Ready, light.availability)
        assertTrue(light.on)
        assertEquals(0.5, light.brightness!!, 0.0)
        assertEquals(LightColor.Hs(280.0, 80.0, listOf(170, 51, 255)), light.color)
        assertEquals(LightCapabilities(brightness = true, colorTemperature = false, color = true, kelvinRange = null), light.capabilities)
        assertEquals("2026-03-01T11:59:00.000Z", light.lastChanged)
        assertEquals("2026-03-01T11:59:00.000Z", light.lastUpdated)
        assertNull(light.raw)
    }

    @Test
    fun `a tunable light keeps the raw payload and its kelvin range`() {
        val light = entity<LightEntity>("ha-light.tunable")
        assertEquals(LightColor.Temperature(2700), light.color)
        assertEquals(1.0, light.brightness!!, 0.0)
        assertEquals(2000..6500, light.capabilities.kelvinRange)
        assertEquals("on", light.raw!!.json.getString("state"))
        assertEquals(2700, light.raw!!.json.getJSONObject("attributes").getInt("color_temp_kelvin"))
    }

    @Test
    fun `a light that is not dimmable has no brightness`() {
        val light = entity<LightEntity>("ha-light.plain")
        assertNull(light.brightness)
        assertNull(light.color)
        assertFalse(light.capabilities.brightness)
    }

    @Test
    fun `an unavailable light`() {
        val light = entity<LightEntity>("ha-light.fx_unavailable")
        assertEquals(Availability.Unavailable, light.availability)
        assertFalse(light.on)
    }

    @Test
    fun `a switch`() {
        val on = entity<SwitchEntity>("ha-switch.fx_on")
        assertTrue(on.on)
        assertEquals("2026-03-01T11:59:00.000Z", on.lastChanged)
        assertNull(on.lastUpdated)
        assertEquals(Availability.Unavailable, entity<SwitchEntity>("ha-switch.fx_unavailable").availability)
    }

    @Test
    fun `a heater`() {
        val climate = entity<ClimateEntity>("ha-climate.fx_heating")
        assertEquals(ClimateMode.Heat, climate.mode)
        assertEquals(ClimateAction.Heating, climate.action)
        assertEquals(23.0, climate.targetTemperature!!, 0.0)
        assertEquals(21.5, climate.currentTemperature!!, 0.0)
        assertEquals(48.0, climate.humidity!!, 0.0)
        assertEquals("°C", climate.unit)
        assertEquals("comfort", climate.preset)
        assertEquals(listOf(ClimateMode.Off, ClimateMode.Heat, ClimateMode.Auto), climate.capabilities.modes)
        assertEquals(listOf("comfort", "eco"), climate.capabilities.presets)
        assertTrue(climate.capabilities.targetTemperature)
        assertEquals(0.5, climate.capabilities.step, 0.0)
        assertEquals(10.0..28.0, climate.capabilities.range)
    }

    @Test
    fun `a heater that reports little`() {
        val climate = entity<ClimateEntity>("ha-climate.fx_unavailable")
        assertEquals(Availability.Unavailable, climate.availability)
        assertNull(climate.action)
        assertNull(climate.targetTemperature)
        assertNull(climate.preset)
    }

    @Test
    fun `an action`() {
        val action = entity<ActionEntity>("ha-action.fx_ready")
        assertEquals("Scene", action.name)
        assertEquals("2026-03-01T11:59:00.000Z", action.lastTriggered)
        assertNull(entity<ActionEntity>("ha-action.fx_unavailable").lastTriggered)
    }

    @Test
    fun `sensors with a number, with text and with nothing`() {
        val temperature = entity<SensorEntity>("ha-sensor.temperature")
        assertEquals("18.4", temperature.value)
        assertEquals(18.4, temperature.numeric!!, 0.0)
        assertEquals("°C", temperature.unit)
        assertEquals("temperature", temperature.measurement)
        val daylight = entity<SensorEntity>("ha-sensor.fx_text")
        assertEquals("on", daylight.value)
        assertNull(daylight.numeric)
        assertEquals("daylight", daylight.measurement)
        val silent = entity<SensorEntity>("ha-sensor.fx_unknown")
        assertEquals(Availability.Unknown, silent.availability)
        assertEquals("", silent.value)
    }

    @Test
    fun `people`() {
        val dan = entity<PersonEntity>("ha-person.dan")
        assertTrue(dan.home)
        assertEquals("home", dan.location)
        assertNull(dan.pictureUrl)
        val sam = entity<PersonEntity>("ha-person.fx_zone")
        assertFalse(sam.home)
        assertEquals("Work", sam.location)
        assertEquals("/_hashsome/asset/ha?path=%2Fapi%2Fimage%2Fsam.png", sam.pictureUrl)
    }

    @Test
    fun `the weather`() {
        val weather = entity<WeatherEntity>("ha-weather.fx_ready")
        assertEquals(WeatherCondition.PartlyCloudy, weather.condition)
        assertEquals(20.0, weather.temperature!!, 0.0)
        assertEquals("°C", weather.unit)
        assertEquals(71.0, weather.humidity!!, 0.0)
        assertEquals(14.0, weather.windSpeed!!, 0.0)
        assertEquals(225.0, weather.windBearing!!, 0.0)
        assertEquals("km/h", weather.windUnit)
        assertEquals(3.0, weather.uvIndex!!, 0.0)
        assertEquals(40.0, weather.cloudCoverage!!, 0.0)
        assertEquals(18.0, weather.apparentTemperature!!, 0.0)
        assertEquals(1015.0, weather.pressure!!, 0.0)
        assertEquals("hPa", weather.pressureUnit)
        assertEquals("mm", weather.precipitationUnit)
        assertEquals(listOf(ForecastType.Daily, ForecastType.Hourly), weather.forecasts)
    }

    @Test
    fun `a player that is playing`() {
        val player = entity<MediaPlayerEntity>("ha-media_player.fx_playing")
        assertEquals(PlaybackState.Playing, player.playback)
        assertEquals(MediaInfo("Blank Space", "Taylor Swift", "1989", "/_hashsome/asset/ma?path=%2Fimageproxy%2Fcover.jpg"), player.media)
        assertEquals(MediaSource("al-1989", "1989", SourceKind.Album), player.source)
        assertEquals(64.0, player.position!!, 0.0)
        assertEquals(231.0, player.duration!!, 0.0)
        assertEquals("2026-03-01T11:59:00.000Z", player.positionUpdatedAt)
        assertEquals(true, player.shuffle)
        assertEquals(0.4, player.volume!!, 0.0)
        assertFalse(player.muted)
        assertEquals(MediaGroup(EntityRef("ha:media_player.fx_playing"), listOf(EntityRef("ha:media_player.fx_paused"))), player.group)
        assertEquals(listOf(EntityRef("ha:media_player.fx_paused"), EntityRef("ha:media_player.fx_idle")), player.groupable)
        assertEquals(MediaPlayerCapabilities(true, true, true, true, true, true, true, true, true, true, true), player.capabilities)
    }

    @Test
    fun `players that are paused, idle and unavailable`() {
        val paused = entity<MediaPlayerEntity>("ha-media_player.fx_paused")
        assertEquals(PlaybackState.Paused, paused.playback)
        assertTrue(paused.muted)
        assertNull(paused.source)
        assertNull(paused.group)
        assertEquals(emptyList<EntityRef>(), paused.groupable)
        assertNull(paused.shuffle)
        assertEquals(MediaInfo(title = "Dreams", artist = "Fleetwood Mac"), paused.media)
        val idle = entity<MediaPlayerEntity>("ha-media_player.fx_idle")
        assertEquals(PlaybackState.Idle, idle.playback)
        assertNull(idle.media)
        assertNull(idle.position)
        assertEquals(0.15, idle.volume!!, 0.0)
        assertEquals(Availability.Unavailable, entity<MediaPlayerEntity>("ha-media_player.fx_unavailable").availability)
    }

    @Test
    fun `a music assistant player`() {
        val player = entity<MediaPlayerEntity>("ma-living_room")
        assertEquals(EntityRef("ma:living_room"), player.ref)
        assertEquals("More More", player.media!!.artist)
        assertEquals(listOf(EntityRef("ma:kitchen"), EntityRef("ma:office")), player.groupable)
        assertTrue(player.capabilities.group)
    }

    @Test
    fun `a generic entity, and a kind this app has never heard of`() {
        val generic = entity<GenericEntity>("ha-generic.fx_value")
        assertEquals("42", generic.value)
        assertEquals("units", generic.unit)
        val future = entity<GenericEntity>("ha-vacuum.future")
        assertEquals("Robot from the future", future.name)
        assertEquals("docked", future.value)
        assertEquals("state", future.unit)
        assertNull(future.raw)
    }

    @Test
    fun `an entity that does not exist`() {
        val message = ServerMessage.decode(Fixtures.text("entity-null.json")) as ServerMessage.EntityUpdate
        assertEquals(EntityRef("ha:light.gone"), message.ref)
        assertNull(message.entity)
    }

    @Test
    fun `entities with equal payloads are equal, so a store skips them`() {
        val a = entity<LightEntity>("ha-light.tunable")
        val b = entity<LightEntity>("ha-light.tunable")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == b.copy(on = false))
    }

    // --- Other messages -----------------------------------------------------------------------------------------

    @Test
    fun `status messages`() {
        for (status in ConnectionStatus.entries) {
            val message = ServerMessage.decode(Fixtures.text("status/${status.wire}.json"))
            assertEquals(ServerMessage.Status("ha", status), message)
        }
    }

    @Test
    fun `pong, and results`() {
        assertEquals(ServerMessage.Pong, ServerMessage.decode(Fixtures.text("pong.json")))
        val ok = result("command-ok")
        assertTrue(ok.ok)
        assertEquals(17, ok.id)
        assertNull(ok.data)
        assertNull(ok.error)
        val failed = result("error")
        assertFalse(failed.ok)
        assertEquals("Unknown integration \"nope\"", failed.error)
    }

    @Test
    fun `malformed and unknown messages decode to nothing`() {
        for (text in listOf(
            "",
            "not json",
            "[]",
            "{}",
            """{"type":"mystery"}""",
            """{"type":"entity","ref":"nocolon","entity":null}""",
            """{"type":"entity","ref":"ha:x"}""",
            """{"type":"entity","ref":"ha:x","entity":"text"}""",
            """{"type":"status","integration":"ha"}""",
            """{"type":"result","ok":true}""",
            """{"type":"result","id":1}""",
        )) {
            assertNull(text, ServerMessage.decode(text))
        }
    }

    @Test
    fun `an unknown connection status is disconnected`() {
        assertEquals(ServerMessage.Status("ha", ConnectionStatus.Disconnected), ServerMessage.decode("""{"type":"status","integration":"ha","status":"asleep"}"""))
    }

    // --- Tolerance --------------------------------------------------------------------------------------------------

    @Test
    fun `nulls, wrong types and missing fields are absent, not errors`() {
        val ref = EntityRef("ha:light.sloppy")
        val light = Entities.parse(
            ref,
            JSONObject(
                """{"kind":"light","name":null,"availability":"sleepy","on":"yes","brightness":null,"color":{"mode":"rainbow"},
                    "capabilities":{"brightness":1,"kelvinRange":{"min":1}},"lastChanged":5,"raw":"nope"}""",
            ),
        ) as LightEntity
        assertEquals("", light.name)
        assertEquals(Availability.Unknown, light.availability)
        assertFalse(light.on)
        assertNull(light.brightness)
        assertNull(light.color)
        assertEquals(LightCapabilities(brightness = false, colorTemperature = false, color = false), light.capabilities)
        assertNull(light.lastChanged)
        assertNull(light.raw)
    }

    @Test
    fun `the text null is not a value and a JSON null is no string`() {
        val sensor = Entities.parse(EntityRef("ha:s"), JSONObject("""{"kind":"sensor","name":"S","availability":"ready","value":"null","unit":null,"numeric":null}""")) as SensorEntity
        assertEquals("null", sensor.value)
        assertNull(sensor.unit)
        assertNull(sensor.numeric)
    }

    @Test
    fun `an entity with no kind is generic, and unknown enum values fall back`() {
        val entity = Entities.parse(EntityRef("x:y"), JSONObject("""{"name":"Y","availability":"ready"}"""))
        assertTrue(entity is GenericEntity)
        val player = Entities.parse(EntityRef("x:p"), JSONObject("""{"kind":"mediaPlayer","name":"P","availability":"ready","playback":"dancing","source":{"id":"i","title":"T","kind":"podcast"}}""")) as MediaPlayerEntity
        assertEquals(PlaybackState.Idle, player.playback)
        assertEquals(MediaSource("i", "T", null), player.source)
        val weather = Entities.parse(EntityRef("x:w"), JSONObject("""{"kind":"weather","name":"W","availability":"ready","condition":"tornado","forecasts":["daily","monthly"]}""")) as WeatherEntity
        assertEquals(WeatherCondition.Unknown, weather.condition)
        assertEquals(listOf(ForecastType.Daily), weather.forecasts)
    }

    @Test
    fun `numbers are doubles whether the wire wrote an integer or not`() {
        val light = Entities.parse(EntityRef("x:l"), JSONObject("""{"kind":"light","name":"L","availability":"ready","on":true,"brightness":1,"capabilities":{}}""")) as LightEntity
        assertEquals(1.0, light.brightness!!, 0.0)
    }

    // --- Query results ---------------------------------------------------------------------------------------------

    @Test
    fun `the top of a library, a shelf, and a search`() {
        val top = Query.Browse().parse(result("browse-root").data)
        assertNull(top.title)
        assertEquals(listOf("recent", "playlists", "albums", "artists", "radio"), top.items.map { it.id })
        val playlists = top.items[1]
        assertEquals(BrowseItem("playlists", "Playlists", kind = BrowseKind.Folder, playable = false, expandable = true, holds = BrowseKind.Playlist), playlists)

        val shelf = Query.Browse("playlists").parse(result("browse-shelf").data)
        assertEquals("Playlists", shelf.title)
        val morning = shelf.items.first()
        assertEquals("pl-morning", morning.id)
        assertEquals("24 tracks", morning.subtitle)
        assertEquals("https://picsum.photos/seed/pl-morning/400", morning.artworkUrl)
        assertEquals(BrowseKind.Playlist, morning.kind)
        assertTrue(morning.playable && morning.expandable)

        val tracks = Query.Browse("al-1989").parse(result("browse-tracks").data)
        val track = tracks.items.first()
        assertEquals(BrowseKind.Track, track.kind)
        assertEquals("Taylor Swift", track.subtitle)
        assertTrue(track.duration!! > 100.0)
        assertFalse(track.expandable)

        val search = Query.Browse(search = "mgmt").parse(result("browse-search").data)
        assertTrue(search.title!!.startsWith("Results for"))
        assertTrue(search.items.isNotEmpty() && search.items.all { "MGMT" in (it.title + it.subtitle) })
        assertTrue(Query.Browse(search = "a", kind = BrowseKind.Album).parse(result("browse-search-kind").data).items.all { it.kind == BrowseKind.Album })
    }

    @Test
    fun `a queue`() {
        val queue = Query.Queue().parse(result("queue").data)
        assertEquals(24, queue.total)
        assertEquals(0, queue.offset)
        assertEquals(5112.0, queue.duration!!, 0.0)
        assertEquals(24, queue.items.size)
        assertEquals(listOf(2), queue.items.mapIndexedNotNull { i, item -> if (item.current) i else null })
        val current = queue.items[2]
        assertEquals("q2-t-blank-space", current.id)
        assertEquals("Blank Space", current.title)
        assertEquals("Taylor Swift", current.artist)
        assertNull(current.album)
        assertEquals(244.0, current.duration!!, 0.0)

        val limited = Query.Queue(5, true).parse(result("queue-limited").data)
        assertEquals(5, limited.items.size)
        assertEquals(24, limited.total)
        assertEquals(5112.0, limited.duration!!, 0.0)

        val empty = Query.Queue().parse(result("queue-empty").data)
        assertEquals(QueueResult(emptyList(), 0, 0), empty)
    }

    @Test
    fun `forecasts`() {
        val daily = Query.Forecast(ForecastType.Daily).parse(result("forecast-daily").data)
        assertEquals(ForecastType.Daily, daily.type)
        assertEquals("°C", daily.unit)
        assertEquals("km/h", daily.windUnit)
        assertEquals("mm", daily.precipitationUnit)
        assertEquals("hPa", daily.pressureUnit)
        val first = daily.points.first()
        assertEquals("2026-02-28T22:00:00.000Z", first.timestamp)
        assertEquals(WeatherCondition.PartlyCloudy, first.condition)
        assertEquals(23.0, first.temperature!!, 0.0)
        assertEquals(15.0, first.low!!, 0.0)
        assertEquals(0.0, first.precipitationProbability!!, 0.0)
        assertNull(first.precipitation)
        assertEquals(15.7, first.windGustSpeed!!, 0.0)
        assertNull(first.daytime)
        assertEquals(WeatherCondition.Cloudy, daily.points[1].condition)
        assertEquals(1.3, daily.points[1].precipitation!!, 0.0)

        val twice = Query.Forecast(ForecastType.TwiceDaily).parse(result("forecast-twice-daily").data)
        assertEquals(ForecastType.TwiceDaily, twice.type)
        assertEquals(listOf(true, false), twice.points.take(2).map { it.daytime })
        assertEquals(ForecastType.Hourly, Query.Forecast(ForecastType.Hourly).parse(result("forecast-hourly").data).type)
    }

    @Test
    fun `a history`() {
        val measurement = Query.History(HistoryRange.Day).parse(result("history-measurement").data)
        assertEquals(HistoryKind.Measurement, measurement.kind)
        assertEquals("°C", measurement.unit)
        assertEquals(HistoryPoint("2026-02-28T12:05:00.000Z", 25.4), measurement.points.first())
        val total = Query.History(HistoryRange.Week, HistoryBucket.Day).parse(result("history-total").data)
        assertEquals(HistoryKind.Total, total.kind)
        assertEquals("kWh", total.unit)
        assertEquals(HistoryPoint("2026-02-23T00:00:00.000Z", 0.735), total.points.first())
        assertEquals(HistoryResult(emptyList(), HistoryKind.Measurement), Query.History(HistoryRange.Hour).parse(result("history-empty").data))
    }

    @Test
    fun `a logbook`() {
        val log = Query.Logbook(12).parse(result("logbook").data)
        assertEquals(12, log.entries.size)
        val first = log.entries.first()
        assertEquals(LogbookEntry("light.plain@1772355600000", "turned on", "2026-03-01T09:00:00.000Z", "Bedtime", ActorKind.Automation, LogbookChange.On), first)
        val second = log.entries[1]
        assertEquals("Alex", second.actor)
        assertEquals(ActorKind.Person, second.actorKind)
        assertEquals(LogbookChange.Off, second.change)
        val system = log.entries.first { it.actor == null }
        assertEquals(ActorKind.Person, system.actorKind)
        assertTrue(log.entries.any { it.change == LogbookChange.Availability })
        assertEquals(LogbookResult(emptyList()), Query.Logbook().parse(result("logbook-empty").data))
    }

    @Test
    fun `items without an id are skipped and a missing answer is an error`() {
        val browse = Query.Browse().parse(JSONObject("""{"items":[{"title":"No id"},{"id":"ok","title":"Ok","kind":"strange","playable":true,"expandable":false},"junk",null]}"""))
        assertEquals(listOf("ok"), browse.items.map { it.id })
        assertEquals(BrowseKind.Other, browse.items[0].kind)
        val failure = runCatching { Query.Queue().parse(null) }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a forecast answer without a type is the type asked for`() {
        assertEquals(ForecastType.Hourly, Query.Forecast(ForecastType.Hourly).parse(JSONObject("""{"points":[]}""")).type)
    }

    // --- What goes out -----------------------------------------------------------------------------------------------

    @Test
    fun `query arguments`() {
        assertNull(Query.Browse().args())
        assertEquals("""{"path":"p"}""", Query.Browse("p").args().toString())
        val search = Query.Browse(search = "abba", kind = BrowseKind.Artist).args()!!
        assertEquals("abba", search.getString("search"))
        assertEquals("artist", search.getString("kind"))
        assertFalse(search.has("path"))
        assertNull(Query.Queue().args())
        val queue = Query.Queue(limit = 50, duration = true).args()!!
        assertEquals(50, queue.getInt("limit"))
        assertTrue(queue.getBoolean("duration"))
        assertEquals("twice_daily", Query.Forecast(ForecastType.TwiceDaily).args()!!.getString("type"))
        val history = Query.History(HistoryRange.Month, HistoryBucket.FiveMinutes).args()!!
        assertEquals("1m", history.getString("range"))
        assertEquals("5m", history.getString("bucket"))
        assertFalse(Query.History(HistoryRange.Hour).args()!!.has("bucket"))
        assertNull(Query.Logbook().args())
        assertEquals(7, Query.Logbook(7).args()!!.getInt("limit"))
        assertEquals(listOf("browse", "queue", "forecast", "history", "logbook"), listOf(Query.Browse(), Query.Queue(), Query.Forecast(ForecastType.Daily), Query.History(HistoryRange.Hour), Query.Logbook()).map { it.name })
    }

    @Test
    fun `client messages encode as the runtime reads them`() {
        fun json(m: ClientMessage) = JSONObject(m.encode())
        assertEquals("ping", json(ClientMessage.Ping).getString("type"))
        val sub = json(ClientMessage.Subscribe(EntityRef("ha:light.x")))
        assertEquals("subscribe", sub.getString("type"))
        assertEquals("ha:light.x", sub.getString("ref"))
        assertEquals("unsubscribe", json(ClientMessage.Unsubscribe(EntityRef("ha:light.x"))).getString("type"))
        val command = json(ClientMessage.Command(7, EntityRef("ha:light.x"), "setBrightness", JSONObject().put("brightness", 0.4)))
        assertEquals("command", command.getString("type"))
        assertEquals(7, command.getInt("id"))
        assertEquals("setBrightness", command.getString("command"))
        assertEquals(0.4, command.getJSONObject("args").getDouble("brightness"), 0.0)
        assertFalse(json(ClientMessage.Command(8, EntityRef("ha:light.x"), "toggle", null)).has("args"))
        val query = json(ClientMessage.Query(9, EntityRef("ma:p"), "browse", JSONObject().put("path", "x")))
        assertEquals("query", query.getString("type"))
        assertEquals("browse", query.getString("query"))
        assertEquals("x", query.getJSONObject("args").getString("path"))
    }

    // --- Time ---------------------------------------------------------------------------------------------------------

    @Test
    fun `timestamps`() {
        assertEquals(0L, Iso8601.parse("1970-01-01T00:00:00Z"))
        assertEquals(1_772_366_400_000L, Iso8601.parse("2026-03-01T12:00:00.000Z"))
        assertEquals(1_772_366_400_000L, Iso8601.parse("2026-03-01T14:00:00+02:00"))
        assertEquals(1_772_366_400_000L, Iso8601.parse("2026-03-01T06:30:00-05:30"))
        assertEquals(1_772_366_400_123L, Iso8601.parse("2026-03-01T12:00:00.123456Z"))
        assertEquals(1_772_366_400_500L, Iso8601.parse("2026-03-01T12:00:00.5Z"))
        assertEquals(1_772_366_400_000L, Iso8601.parse("2026-03-01T12:00Z"))
        assertEquals(951_782_400_000L, Iso8601.parse("2000-02-29"))
        assertEquals(-86_400_000L, Iso8601.parse("1969-12-31T00:00:00Z"))
        assertEquals(1_772_366_400_000L, Iso8601.parse("2026-03-01T12:00:00.000+00:00"))
    }

    @Test
    fun `things that are not timestamps`() {
        for (text in listOf(null, "", "yesterday", "2026-13-01T00:00:00Z", "2026-02-30T00:00:00Z", "2026-03-01T25:00:00Z", "2026-03-01T12:00:00+99:00", "2026-03-01T12:00:00Zjunk", "2026-03-01X12:00:00Z", "1999-02-29")) {
            assertNull(text, Iso8601.parse(text))
        }
    }
}
