package com.selfapps.guardlock.lock

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telecom.TelecomManager
import com.selfapps.guardlock.data.InstalledApp

object Apps {
    /** Apps that must never be lockable, so the phone can't be bricked. */
    private val NEVER_LOCK = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.emergency",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )

    fun launchable(ctx: Context): List<InstalledApp> {
        val pm = ctx.packageManager
        val protectedPkgs = buildSet {
            addAll(NEVER_LOCK)
            add(ctx.packageName)
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName?.let { add(it) }
            ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { add(it) }
            Telephony.Sms.getDefaultSmsPackage(ctx)?.let { add(it) }
        }
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .filter { it.pkg !in protectedPkgs }
            .sortedBy { it.name.lowercase() }
    }
}
