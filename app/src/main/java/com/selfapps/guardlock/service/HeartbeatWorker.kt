package com.selfapps.guardlock.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.selfapps.guardlock.data.PairRepository
import com.selfapps.guardlock.data.Prefs
import com.selfapps.guardlock.lock.Perms
import com.selfapps.guardlock.usage.UsageSync
import java.util.concurrent.TimeUnit

/**
 * Check-in every ~15 min (WorkManager's minimum). The partner's app treats a long silence as
 * "phone offline or protection killed". It also uploads screen time, and doubles as a watchdog
 * that restarts SyncService.
 */
class HeartbeatWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val pairId = Prefs.pairId ?: return Result.success()
        return try {
            PairRepository.sendHeartbeat(pairId, Perms.snapshot(applicationContext))
            runCatching { UsageSync.push(applicationContext) } // never fail the heartbeat over stats
            runCatching { SyncService.start(applicationContext) } // watchdog; may be disallowed in background
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "heartbeat", ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork("heartbeat")
        }
    }
}
