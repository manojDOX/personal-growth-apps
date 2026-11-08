package com.selfapps.guardlock.lock

import android.os.SystemClock
import com.selfapps.guardlock.data.AppRule
import com.selfapps.guardlock.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-memory decision point: "should this package be blocked right now?"
 *
 * Timed unlocks are converted to a monotonic (elapsedRealtime) deadline the moment a rule set
 * arrives, so changing the wall clock afterwards cannot extend an open window.
 * Everything defaults to LOCKED: if we know nothing, we block.
 *
 * Listeners are told whenever a new rule set arrives. That lets the accessibility service react
 * at once instead of polling every couple of seconds (a steady battery drain).
 */
object LockEngine {
    private class Entry(val rule: AppRule, val openUntilElapsed: Long)

    @Volatile
    private var entries: Map<String, Entry> = emptyMap()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun load() {
        update(fromJson(Prefs.rulesJson), persist = false, notify = false)
    }

    fun update(rules: List<AppRule>, persist: Boolean = true, notify: Boolean = true) {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        entries = rules.associate { r ->
            val openUntil = r.unlockUntil?.let { elapsed + (it - wall) } ?: 0L
            r.pkg to Entry(r, openUntil)
        }
        if (persist) Prefs.rulesJson = toJson(rules)
        if (notify) listeners.forEach { it() }
    }

    fun isBlocked(pkg: String): Boolean {
        val e = entries[pkg] ?: return false
        if (!e.rule.locked) return false
        return SystemClock.elapsedRealtime() >= e.openUntilElapsed
    }

    /** If [pkg] is open only for a limited time, how many ms until it blocks again. Else null. */
    fun msUntilBlocked(pkg: String): Long? {
        val e = entries[pkg] ?: return null
        if (!e.rule.locked) return null
        val left = e.openUntilElapsed - SystemClock.elapsedRealtime()
        return if (left > 0) left else null
    }

    fun label(pkg: String): String = entries[pkg]?.rule?.name ?: pkg

    private fun toJson(rules: List<AppRule>): String {
        val arr = JSONArray()
        rules.forEach {
            arr.put(
                JSONObject()
                    .put("pkg", it.pkg)
                    .put("name", it.name)
                    .put("locked", it.locked)
                    .put("until", it.unlockUntil ?: JSONObject.NULL)
                    .put("by", it.addedBy)
            )
        }
        return arr.toString()
    }

    private fun fromJson(s: String): List<AppRule> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            AppRule(
                pkg = o.getString("pkg"),
                name = o.getString("name"),
                locked = o.getBoolean("locked"),
                unlockUntil = if (o.isNull("until")) null else o.getLong("until"),
                addedBy = o.optString("by"),
            )
        }
    }.getOrDefault(emptyList())
}
