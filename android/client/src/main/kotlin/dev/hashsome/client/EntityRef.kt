package dev.hashsome.client

/**
 * `<integration>:<id>`, the address of one entity (`ha:light.bathroom_led`). The integration is
 * everything before the first colon, so an id may contain colons itself.
 */
@JvmInline
value class EntityRef(val value: String) {
    val integration: String get() = value.substringBefore(':')
    val id: String get() = value.substringAfter(':', "")

    /** Whether this has the `<integration>:<id>` shape the runtime accepts (non-empty on both sides). */
    val isValid: Boolean get() = value.indexOf(':') > 0 && value.length > value.indexOf(':') + 1

    override fun toString(): String = value

    companion object {
        fun of(integration: String, id: String): EntityRef = EntityRef("$integration:$id")
    }
}
