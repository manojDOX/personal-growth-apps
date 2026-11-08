package com.selfapps.guardlock.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Active device admins can't be uninstalled until the admin is deactivated first - a speed bump
 * against casual removal. As device owner this also switches on [Hardening].
 */
class GuardDeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Hardening.apply(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off weakens the lock your friend set up."
}
