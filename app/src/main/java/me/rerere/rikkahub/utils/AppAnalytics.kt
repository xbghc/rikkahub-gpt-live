package me.rerere.rikkahub.utils

/** App events use a no-op implementation when Firebase is disabled for this build. */
fun interface AppAnalytics {
    fun logEvent(name: String)
}
