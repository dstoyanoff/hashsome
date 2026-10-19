package dev.hashsome.headless

import dev.hashsome.client.Client
import dev.hashsome.client.ConnectionStatus
import dev.hashsome.client.LinkStatus
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

/** How well one part of the system is doing, worst first (`Health` of `top-bar.tsx`). */
enum class HealthLevel { Down, Pending, Ok }

/** One line of the status popover: [name] (`Runtime`, or the integration's id in capitals), its level and what to say about it. */
data class HealthRow(val name: String, val level: HealthLevel, val label: String)

/** The status dot and its popover, worked out as `SystemStatus` does: every row, the worst level among them, and a sentence for it. */
data class HealthSummary(val rows: List<HealthRow>, val overall: HealthLevel, val summary: String)

/** The link and the integrations as one value (mirrors `ConnectionHealth` on the web). */
class ConnectionHealth(client: Client, scope: Scope) {
    val state: Store<Health> = Store(Health(false, emptyMap()))

    /** The rows, the overall level and the summary sentence, for the status dot and its popover. */
    val summary: Store<HealthSummary> = Store(summaryOf(LinkStatus.Closed, emptyMap()))

    init {
        scope.bind(client.link) { update(client) }
        scope.bind(client.integrations) { update(client) }
    }

    private fun update(client: Client) {
        val link = client.link.value
        val integrations = client.integrations.value
        state.set(Health(link == LinkStatus.Open, integrations))
        summary.set(summaryOf(link, integrations))
    }

    companion object {
        /** The rows and sentence for a link and integration statuses, exactly as `SystemStatus` words them. */
        fun summaryOf(link: LinkStatus, integrations: Map<String, ConnectionStatus>): HealthSummary {
            val rows = ArrayList<HealthRow>()
            rows += HealthRow(
                "Runtime",
                when (link) {
                    LinkStatus.Open -> HealthLevel.Ok
                    LinkStatus.Connecting -> HealthLevel.Pending
                    LinkStatus.Closed -> HealthLevel.Down
                },
                when (link) {
                    LinkStatus.Open -> "Connected"
                    LinkStatus.Connecting -> "Connecting…"
                    LinkStatus.Closed -> "Unreachable"
                },
            )

            for ((id, status) in integrations) {
                rows += HealthRow(
                    id.uppercase(),
                    when (status) {
                        ConnectionStatus.Connected -> HealthLevel.Ok
                        ConnectionStatus.Connecting -> HealthLevel.Pending
                        else -> HealthLevel.Down
                    },
                    when (status) {
                        ConnectionStatus.Connected -> "Connected"
                        ConnectionStatus.Connecting -> "Connecting…"
                        ConnectionStatus.Error -> "Error"
                        ConnectionStatus.Disconnected -> "Disconnected"
                    },
                )
            }

            val overall = HealthLevel.entries.firstOrNull { level -> rows.any { it.level == level } } ?: HealthLevel.Ok
            val summary = when (overall) {
                HealthLevel.Ok -> "All systems operational"
                HealthLevel.Pending -> "Connecting…"
                HealthLevel.Down -> "${rows.count { it.level == HealthLevel.Down }} system(s) down"
            }

            return HealthSummary(rows, overall, summary)
        }
    }
}
