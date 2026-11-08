package com.selfapps.guardlock.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.selfapps.guardlock.service.GuardDeviceAdmin
import com.selfapps.guardlock.service.Hardening
import com.selfapps.guardlock.service.LockAccessibilityService
import com.selfapps.guardlock.usage.UsageCollector

/** Health checks for everything the lock depends on. Also what the heartbeat reports. */
object Perms {
    data class Snapshot(
        val accessibility: Boolean,
        val batteryExempt: Boolean,
        val deviceAdmin: Boolean,
        val notifications: Boolean,
        val usageAccess: Boolean,
        /** Device-owner mode: new users, profiles, safe mode and uninstall are blocked by Android. */
        val hardened: Boolean,
    ) {
        val allGood get() = accessibility && batteryExempt && deviceAdmin && notifications && usageAccess
    }

    fun snapshot(ctx: Context) = Snapshot(
        accessibility = accessibilityOn(ctx),
        batteryExempt = batteryExempt(ctx),
        deviceAdmin = deviceAdmin(ctx),
        notifications = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
        usageAccess = UsageCollector.hasAccess(ctx),
        hardened = Hardening.isDeviceOwner(ctx),
    )

    fun accessibilityOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(ctx, LockAccessibilityService::class.java)
        return enabled.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
    }

    fun batteryExempt(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    fun deviceAdmin(ctx: Context): Boolean =
        ctx.getSystemService(DevicePolicyManager::class.java)
            ?.isAdminActive(ComponentName(ctx, GuardDeviceAdmin::class.java)) == true
}
