package com.selfapps.guardlock.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.selfapps.guardlock.GuardLockApp
import com.selfapps.guardlock.data.PairRepository
import com.selfapps.guardlock.data.Prefs
import com.selfapps.guardlock.lock.LockEngine
import com.selfapps.guardlock.lock.Perms
import com.selfapps.guardlock.ui.MainActivity
import com.selfapps.guardlock.usage.UsageSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Runs on EVERY paired phone (each phone is guarded by the other). Keeps a live Firestore listener
 * on the lock rules for THIS phone so a partner's Lock/Unlock lands within a second or two, and
 * keeps the process alive via a foreground notification. Rules are cached in
 * [LockEngine]/[Prefs] so locking still works offline.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        val pairId = Prefs.pairId
        if (pairId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job == null) {
            job = scope.launch {
                launch {
                    val me = awaitUid()
                    PairRepository.appsFlow(pairId, me)
                        .retryWhen { cause, attempt ->
                            Log.w(TAG, "rules listener failed, retrying", cause)
                            delay(minOf(60_000L, 2_000L * (attempt + 1)))
                            true
                        }
                        .collect { LockEngine.update(it) }
                }
                launch {
                    runCatching {
                        PairRepository.sendHeartbeat(pairId, Perms.snapshot(this@SyncService))
                        UsageSync.push(this@SyncService)
                    }.onFailure { Log.w(TAG, "heartbeat/usage upload failed", it) }
                }
            }
        }
        return START_STICKY
    }

    /** The anonymous identity is stored on the phone, but the very first sign-in needs internet. */
    private suspend fun awaitUid(): String {
        while (true) {
            try {
                return PairRepository.uid()
            } catch (e: Exception) {
                Log.w(TAG, "sign-in failed, retrying", e)
                delay(5_000)
            }
        }
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, GuardLockApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("GuardLock is active")
            .setContentText("Your partner's lock rules are being enforced")
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SyncService"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SyncService::class.java))
        }
    }
}
