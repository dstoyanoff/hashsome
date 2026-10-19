package dev.hashsome.headless

import dev.hashsome.client.ConnectionStatus
import dev.hashsome.client.HashsomeClient
import dev.hashsome.client.Store

/** What the system status popover shows. */
data class Health(
    /** The socket to the runtime is open. */
    val linkOpen: Boolean,
    /** Each integration and how it is connected, by integration id. */
    val integrations: Map<String, ConnectionStatus>,
) {
    /** Everything is up. */
    val healthy: Boolean get() = linkOpen && integrations.values.all { it == ConnectionStatus.Connected }
}

/** The link and the integrations as one value (mirrors `ConnectionHealth` on the web). */
class ConnectionHealth(client: HashsomeClient, scope: Scope) {
    val state: Store<Health> = Store(Health(false, emptyMap()))
}
