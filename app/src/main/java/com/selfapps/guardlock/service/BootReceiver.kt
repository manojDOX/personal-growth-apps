package com.selfapps.guardlock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.selfapps.guardlock.data.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.pairId != null) {
            SyncService.start(context)
            HeartbeatWorker.schedule(context)
        }
    }
}
