package dev.hashsome.testing

import dev.hashsome.client.Entity
import dev.hashsome.client.EntityRef
import dev.hashsome.client.HashsomeClient
import dev.hashsome.client.ServerAddress
import dev.hashsome.client.Transport

/**
 * A [Transport] a test drives by hand: it records what the client sends and lets the test play the
 * runtime's side (open, deliver a message, drop the connection). Used with [TestScheduler] to check
 * heartbeats, backoff and resubscription without a socket.
 */
class FakeTransport : Transport {
    /** Every text frame the client sent, oldest first. */
    val sent: MutableList<String> = ArrayList()

    override fun connect(listener: Transport.Listener): Unit = TODO("WP1")
    override fun send(text: String): Unit = TODO("WP1")
    override fun close(): Unit = TODO("WP1")

    /** The runtime accepts the connection. */
    fun open(): Unit = TODO("WP1")

    /** The runtime sends [text]. */
    fun receive(text: String): Unit = TODO("WP1")

    /** The connection drops. */
    fun drop(reason: String? = null): Unit = TODO("WP1")

    /** Hands out one [FakeTransport] per connection attempt, remembering them in [created]. */
    class Factory : Transport.Factory {
        val created: MutableList<FakeTransport> = ArrayList()
        override fun create(server: ServerAddress, path: String): Transport = FakeTransport().also { created += it }
    }
}

/**
 * The runtime as a test sees it: a set of entities that a [HashsomeClient] (wired to a [FakeTransport]
 * and a [TestScheduler]) watches and commands. Commands are recorded in [commands], answers are scripted.
 */
class FakeRuntime(val scheduler: TestScheduler = TestScheduler()) {
    /** The client under test, connected to this runtime. */
    val client: HashsomeClient get() = TODO("WP1")

    /** Every command the client sent: the entity, the name, and the args as JSON text. */
    val commands: MutableList<Triple<EntityRef, String, String?>> = ArrayList()

    /** Makes [entity] exist (or change), notifying whoever watches it. */
    fun put(entity: Entity): Unit = TODO("WP1")

    /** Makes the entity at [ref] not exist. */
    fun remove(ref: EntityRef): Unit = TODO("WP1")

    /** Answers the next command with [error], or with success when null. */
    fun answerNext(error: String? = null): Unit = TODO("WP1")
}
