package com.selfapps.guardlock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.selfapps.guardlock.data.Prefs
import com.selfapps.guardlock.data.Role

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.role == Role.GUARDED && Prefs.pairId != null) {
            SyncService.start(context)
            HeartbeatWorker.schedule(context)
        }
    }
}
