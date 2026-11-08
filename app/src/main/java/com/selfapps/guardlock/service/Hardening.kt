package com.selfapps.guardlock.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager

/**
 * Strong protection, only available when GuardLock is the phone's "device owner".
 * Android gives that role to an app only through a one-time USB command (see README).
 * Plain device admin (what the setup card enables) cannot block new users or profiles.
 *
 * When device owner, Android itself refuses:
 *  - creating or switching to other users (the "new user hides the app" bypass)
 *  - work / clone profiles (apps like Island or Shelter, Secure Folder style spaces)
 *  - safe mode (which switches all third party apps off)
 *  - uninstalling GuardLock
 * Without device owner, the accessibility service still blocks the Settings screens for adding
 * users. That is best effort only and differs between phone brands.
 */
object Hardening {
    fun isDeviceOwner(ctx: Context): Boolean =
        ctx.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(ctx.packageName) == true

    fun apply(ctx: Context) {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) return
        val admin = ComponentName(ctx, GuardDeviceAdmin::class.java)

        val restrictions = buildList {
            add(UserManager.DISALLOW_ADD_USER)
            add(UserManager.DISALLOW_ADD_MANAGED_PROFILE)
            add(UserManager.DISALLOW_SAFE_BOOT)
            if (Build.VERSION.SDK_INT >= 28) add(UserManager.DISALLOW_USER_SWITCH)
            if (Build.VERSION.SDK_INT >= 34) add("no_add_clone_profile")
        }
        restrictions.forEach { runCatching { dpm.addUserRestriction(admin, it) } }
        runCatching { dpm.setUninstallBlocked(admin, ctx.packageName, true) }
    }
}
