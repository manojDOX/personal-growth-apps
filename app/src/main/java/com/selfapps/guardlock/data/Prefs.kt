package com.selfapps.guardlock.data

import android.content.Context
import android.content.SharedPreferences

/** Tiny synchronous store, readable from the accessibility service without any async hop. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("guardlock", Context.MODE_PRIVATE)
    }

    /** Data layout version. Older layouts (one-way guardian/guarded) are wiped on upgrade. */
    var schema: Int
        get() = sp.getInt("schema", 0)
        set(value) {
            sp.edit().putInt("schema", value).apply()
        }

    var pairId: String?
        get() = sp.getString("pairId", null)
        set(value) {
            sp.edit().putString("pairId", value).apply()
        }

    /** Last known rule set, so locks keep working offline and right after a reboot. */
    var rulesJson: String
        get() = sp.getString("rules", "[]") ?: "[]"
        set(value) {
            sp.edit().putString("rules", value).apply()
        }

    /** Total (ms) last uploaded for a day, or -1 if that day was never uploaded. */
    fun usagePushed(date: String): Long = sp.getLong("u_$date", -1L)

    fun setUsagePushed(date: String, totalMs: Long) {
        sp.edit().putLong("u_$date", totalMs).apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }
}
