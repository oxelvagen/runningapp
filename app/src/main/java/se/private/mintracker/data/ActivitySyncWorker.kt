package se.oxelv.mintracker.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class ActivitySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val userId = inputData.getString(USER_ID) ?: return Result.failure()
        supabase.auth.sessionStatus.first { it !is SessionStatus.Initializing }
        val currentUserId = supabase.auth.currentUserOrNull()?.id ?: return Result.retry()
        if (currentUserId != userId) return Result.failure()
        return try {
            ActivityRepository(TrackerDatabaseProvider.get(applicationContext)).sync(userId)
            Result.success()
        } catch (_: AccountChangedException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val USER_ID = "user_id"

        fun enqueue(context: Context, userId: String) {
            val request = OneTimeWorkRequestBuilder<ActivitySyncWorker>()
                .setInputData(Data.Builder().putString(USER_ID, userId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("activity-sync-$userId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context, userId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork("activity-sync-$userId")
        }
    }
}
