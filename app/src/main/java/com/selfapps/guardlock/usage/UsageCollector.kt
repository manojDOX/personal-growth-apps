package com.selfapps.guardlock.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.selfapps.guardlock.data.AppUsage
import com.selfapps.guardlock.data.PairRepository
import com.selfapps.guardlock.data.Prefs
import com.selfapps.guardlock.data.UsageDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Screen time per app, read from Android's own usage log (UsageStatsManager). Needs the
 * "Usage access" special permission. Nothing runs in the background to collect it: Android keeps
 * the log, and we read it only when we upload (every ~15 min) or when the app is opened.
 */
object UsageCollector {
    private const val TOP_APPS = 25
    private const val MIN_APP_MS = 15_000L

    // Event codes (same values as the deprecated MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND).
    private const val RESUMED = 1
    private const val PAUSED = 2
    private const val STOPPED = 23

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        return checkMode(ops, ctx) == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun checkMode(ops: AppOpsManager, ctx: Context): Int =
        if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }

    /** Foreground time for one local calendar day. */
    fun day(ctx: Context, date: LocalDate): UsageDay {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        if (end <= start) return UsageDay(date.toString(), 0L, emptyList())

        val skip = setOf(ctx.packageName, "com.android.systemui")
        val totals = sessions(ctx, start, end).filterKeys { it !in skip }
        val pm = ctx.packageManager
        val apps = totals.entries
            .filter { it.value >= MIN_APP_MS }
            .sortedByDescending { it.value }
            .take(TOP_APPS)
            .map { (pkg, ms) ->
                val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                    .getOrDefault(pkg)
                AppUsage(pkg, name, ms)
            }
        return UsageDay(date.toString(), totals.values.sum(), apps)
    }

    /** Sums time between "activity resumed" and "activity paused/stopped" per package. */
    private fun sessions(ctx: Context, start: Long, end: Long): Map<String, Long> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        val resumedAt = HashMap<String, Long>() // key: package/class
        val totals = HashMap<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val key = pkg + "/" + (e.className ?: "")
            when (e.eventType) {
                RESUMED -> resumedAt[key] = e.timeStamp
                PAUSED, STOPPED -> resumedAt.remove(key)?.let { from ->
                    totals.merge(pkg, (e.timeStamp - from).coerceAtLeast(0L), Long::plus)
                }
            }
        }
        // Still in the foreground right now.
        resumedAt.forEach { (key, from) ->
            totals.merge(key.substringBefore('/'), (end - from).coerceAtLeast(0L), Long::plus)
        }
        return totals
    }
}

/** Uploads today's and recent days' totals so the partner's phone can chart them. */
object UsageSync {
    suspend fun push(ctx: Context) {
        val pairId = Prefs.pairId ?: return
        if (!UsageCollector.hasAccess(ctx)) return
        val today = LocalDate.now()
        for (i in 0..6) {
            val date = today.minusDays(i.toLong())
            val key = date.toString()
            val last = Prefs.usagePushed(key)
            // Older days are final: upload once. Today and yesterday can still change.
            if (i > 1 && last >= 0) continue
            val day = withContext(Dispatchers.Default) { UsageCollector.day(ctx, date) }
            if (last >= 0 && abs(day.totalMs - last) < 60_000) continue
            PairRepository.pushUsage(pairId, day)
            Prefs.setUsagePushed(key, day.totalMs)
        }
    }
}
