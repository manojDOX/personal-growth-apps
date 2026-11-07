package com.selfapps.guardlock.data

import android.content.Context
import android.content.SharedPreferences

/** Tiny synchronous store, readable from the accessibility service without any async hop. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("guardlock", Context.MODE_PRIVATE)
    }

    var role: Role?
        get() = sp.getString("role", null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        set(value) {
            sp.edit().putString("role", value?.name).apply()
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

    fun clear() {
        sp.edit().clear().apply()
    }
}
