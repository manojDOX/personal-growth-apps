package com.selfapps.guardlock.data

enum class Role { GUARDIAN, GUARDED }

/**
 * One locked app. [locked] = false means the guardian released it indefinitely.
 * [unlockUntil] (epoch millis) is a timed exception on top of a locked app.
 */
data class AppRule(
    val pkg: String,
    val name: String,
    val locked: Boolean,
    val unlockUntil: Long?,
    val addedBy: String,
) {
    fun isTemporarilyOpen(now: Long = System.currentTimeMillis()): Boolean =
        locked && unlockUntil != null && unlockUntil > now
}

data class Health(
    val lastHeartbeat: Long?,
    val accessibilityOn: Boolean,
    val batteryExempt: Boolean,
    val deviceAdmin: Boolean,
    val notificationsOn: Boolean,
)

data class PairInfo(
    val id: String,
    val guardianUid: String,
    val guardedUid: String?,
    val health: Health,
)

data class InstalledApp(val pkg: String, val name: String)
