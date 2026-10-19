package dev.hashsome.headless

import dev.hashsome.client.EntityRef
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.Store

/** One speaker offered for grouping, and whether it already plays with this player. */
data class Speaker(val ref: EntityRef, val name: String, val joined: Boolean)

/** Which speakers can join this player's stream and which have (mirrors `offered-speakers.ts`). */
class SpeakersControl(val handle: EntityHandle<MediaPlayerEntity>, val scope: Scope, val allow: List<EntityRef>) {
    /** The speakers to show: those in [allow] this player can be grouped with, plus the ones in its group. */
    val speakers: Store<List<Speaker>> = Store(emptyList())

    fun add(speaker: EntityRef): Unit = TODO("WP2")
    fun remove(speaker: EntityRef): Unit = TODO("WP2")

    /** This player leaves the group it plays in. */
    fun leave(): Unit = TODO("WP2")

    /** For a player that follows another: takes the stream over. */
    fun takeOver(): Unit = TODO("WP2")
}
