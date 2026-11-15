package com.shay.waypoint.sync

import android.content.Context
import androidx.work.*
import com.shay.waypoint.repo
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = applicationContext.repo()
        if (repo.sheetId == null) return Result.success()
        repo.sync.sync(inputData.getString("reason") ?: "background")
        return Result.success()
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(net)
                .setInputData(workDataOf("reason" to "background"))
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("periodic-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Push local edits shortly after they happen. */
        fun pushSoon(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(20, TimeUnit.SECONDS)
                .setConstraints(net)
                .setInputData(workDataOf("reason" to "push"))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("push-sync", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
