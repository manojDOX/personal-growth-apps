package com.selfapps.guardlock.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Active device admins can't be uninstalled until the admin is deactivated first - a speed bump
 * against casual removal. (Phase 4: also alert the guardian when this is disabled.)
 */
class GuardDeviceAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off weakens the lock your guardian set up."
}
