package app.gamenative.analytics

/**
 * Stand-in for the PostHog analytics SDK, which this build does not include: usage analytics are
 * off for good and nothing is sent anywhere. Keeps the SDK's call shape so call sites compile
 * unchanged.
 */
object PostHog {
    @Suppress("UNUSED_PARAMETER")
    fun capture(
        event: String,
        distinctId: String? = null,
        properties: Map<String, Any?>? = null,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun register(key: String, value: Any?) = Unit
}
