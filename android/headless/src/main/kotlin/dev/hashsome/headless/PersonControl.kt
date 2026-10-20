package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.PersonEntity
import dev.hashsome.client.Store

/**
 * What one person's avatar shows (the web's `PresenceAvatar`): their name, picture, and whether they are home. Someone who is out, or
 * whom Home Assistant cannot reach, reads as inactive. Before the person is known the avatar is active, so it does not flash dim.
 */
class PersonControl(val handle: EntityHandle<PersonEntity>) {
    data class State(
        val name: String,
        /** A runtime asset path for their picture, when they have one. */
        val pictureUrl: String?,
        /** Home and reachable; false for someone away (the avatar is dimmed). */
        val active: Boolean,
        /** `home`, `away`, or a zone's name; empty while unknown. */
        val where: String,
    ) {
        /** "Name, where" as a person would say it, for a content description. */
        val label: String get() = if (name.isEmpty()) "" else if (where.isEmpty()) name else "$name, $where"
    }

    val state: Store<State> = Store(State("", null, true, ""))

    init {
        handle.state.listenNow {
            val person = handle.entity
            state.set(
                if (person == null) {
                    State("", null, true, "")
                } else {
                    State(
                        name = person.name,
                        pictureUrl = person.pictureUrl,
                        active = person.home && person.availability == Availability.Ready,
                        where = if (person.home) "home" else person.location,
                    )
                },
            )
        }
    }

    companion object {
        /** The colors an avatar without a picture falls back to (`DEFAULT_PRESENCE_COLORS` of the web), as 0xRRGGBB. */
        val DEFAULT_COLORS: List<Int> = listOf(0x8B5CF6, 0x34D399, 0x60A5FA, 0xF472B6, 0xFBBF24, 0xFB923C)

        /** The first letters of the first two words of [name], upper case: "Dan Smith" is "DS". */
        fun initials(name: String): String =
            name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.substring(0, 1).uppercase() }

        /** Which of [count] colors [id] gets, always the same one: the web's `colorForId`'s hash (`hash * 31 + char`, unsigned 32 bit). */
        fun colorIndex(id: String, count: Int): Int {
            var hash = 0L
            for (c in id) hash = (hash * 31 + c.code) and 0xFFFFFFFFL
            return (hash % count).toInt()
        }
    }
}
