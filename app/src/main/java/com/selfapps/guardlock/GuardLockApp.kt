package com.selfapps.guardlock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.selfapps.guardlock.data.Prefs
import com.selfapps.guardlock.lock.LockEngine
import com.selfapps.guardlock.service.Hardening

class GuardLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        if (Prefs.schema < SCHEMA) {
            Prefs.clear()
            Prefs.schema = SCHEMA
        }
        LockEngine.load()
        Hardening.apply(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Protection status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_ID = "protection"
        const val SCHEMA = 2
    }
}
