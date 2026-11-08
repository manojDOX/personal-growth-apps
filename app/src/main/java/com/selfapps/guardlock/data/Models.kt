package com.selfapps.guardlock.data

/**
 * One locked app on one phone. [locked] = false means the partner released it indefinitely.
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
    val usageAccess: Boolean,
    val hardened: Boolean,
)

/** A pair is two phones. Each one guards the other. */
data class PairInfo(
    val id: String,
    val creatorUid: String,
    val joinerUid: String?,
) {
    fun peerOf(me: String): String? = when (me) {
        creatorUid -> joinerUid
        joinerUid -> creatorUid
        else -> null
    }
}

data class AppUsage(val pkg: String, val name: String, val ms: Long)

/** Foreground time for one local calendar day. [date] is ISO yyyy-MM-dd. */
data class UsageDay(val date: String, val totalMs: Long, val apps: List<AppUsage>)

data class InstalledApp(val pkg: String, val name: String)
