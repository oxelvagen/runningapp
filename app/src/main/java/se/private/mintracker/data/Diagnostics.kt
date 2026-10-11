package se.oxelv.mintracker.data

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import androidx.room.*
import androidx.work.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.*
import se.oxelv.mintracker.BuildConfig
import se.oxelv.mintracker.tracking.TrackingState
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** No API accepts free text, coordinates, URLs, credentials or exception messages. */
enum class DiagnosticCode(val category: String, val feature: String, val severity: String = "ERROR") {
    UNCAUGHT_EXCEPTION("CRASH", "APPLICATION", "CRITICAL"), PROCESS_CRASH("CRASH", "APPLICATION", "CRITICAL"),
    PROCESS_ANR("CRASH", "APPLICATION", "CRITICAL"), PROCESS_INTERRUPTED("UNKNOWN", "APPLICATION", "WARNING"),
    AUTH_REFRESH_FAILED("AUTH", "AUTH"), AUTH_UNLOCK_FAILED("AUTH", "AUTH"),
    GPS_START_FAILED("GPS", "TRAINING"), GPS_SIGNAL_LOST("GPS", "TRAINING", "WARNING"), GPS_SERVICE_STOPPED("GPS", "TRAINING"),
    ACTIVITY_SAVE_FAILED("ACTIVITY", "TRAINING"), ACTIVITY_RESTORE_FAILED("ACTIVITY", "TRAINING"),
    SYNC_FAILED("SYNC", "SYNC"), PROFILE_IMAGE_UPLOAD_FAILED("STORAGE", "PROFILE"), IMAGE_LOAD_FAILED("UI", "IMAGE"),
    DATABASE_FAILED("DATABASE", "DATABASE"), CHALLENGE_CREATE_FAILED("CHALLENGE", "CHALLENGE"),
    CHEER_FAILED("SOCIAL", "SOCIAL"), UPDATE_FAILED("UPDATE", "UPDATE"),
    COUNTDOWN_STARTED("GPS", "TRAINING", "INFO"), SERVICE_STARTED("GPS", "TRAINING", "INFO"),
    GPS_STARTED("GPS", "TRAINING", "INFO"), GPS_FIRST_FIX("GPS", "TRAINING", "INFO"),
    SESSION_RESTORED("ACTIVITY", "TRAINING", "INFO"), ACTIVITY_SAVED("ACTIVITY", "TRAINING", "INFO"),
    SERVICE_STOPPED("GPS", "TRAINING", "INFO")
}

object DiagnosticSanitizer {
    private val framePattern = Regex("^se[.]oxelv[.]mintracker[.][A-Za-z0-9_.$]+#[A-Za-z0-9_$<>]+$")
    fun frames(error: Throwable?): List<String> = error?.stackTrace.orEmpty().map { "${it.className}#${it.methodName}" }
        .filter { it.length <= 180 && framePattern.matches(it) }.take(20)
    fun model(value: String) = value.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in " _().-" }.take(80).ifBlank { "Unknown" }
}

@Entity(tableName = "diagnostic_queue", indices = [Index("owner")])
data class DiagnosticRow(@PrimaryKey val id: String, val owner: String, val createdAt: Long,
    val dedup: String, val payload: String, val attempts: Int = 0)
@Dao interface DiagnosticDao {
    @Query("""SELECT * FROM diagnostic_queue WHERE owner=:owner AND attempts<6
        ORDER BY CASE WHEN payload LIKE '%"severity":"INFO"%' THEN 1 ELSE 0 END,createdAt LIMIT 6""") suspend fun batch(owner: String): List<DiagnosticRow>
    @Query("SELECT EXISTS(SELECT 1 FROM diagnostic_queue WHERE owner=:owner AND dedup=:key AND createdAt>:since)") suspend fun duplicate(owner: String, key: String, since: Long): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: DiagnosticRow)
    @Query("DELETE FROM diagnostic_queue WHERE owner=:owner AND id IN (:ids)") suspend fun acknowledge(owner: String, ids: List<String>)
    @Query("UPDATE diagnostic_queue SET attempts=attempts+1 WHERE owner=:owner AND id IN (:ids)") suspend fun attempted(owner: String, ids: List<String>)
    @Query("DELETE FROM diagnostic_queue WHERE createdAt<:before") suspend fun expire(before: Long)
    @Query("""DELETE FROM diagnostic_queue WHERE id NOT IN (SELECT id FROM diagnostic_queue
        ORDER BY CASE WHEN payload LIKE '%"severity":"INFO"%' THEN 1 ELSE 0 END,createdAt DESC,id DESC LIMIT 200)""") suspend fun bound()
    @Query("DELETE FROM diagnostic_queue WHERE owner=:owner") suspend fun clearOwner(owner: String)
    @Query("SELECT COUNT(*) FROM diagnostic_queue") suspend fun count(): Int
}
@Database(entities = [DiagnosticRow::class], version = 1, exportSchema = false)
abstract class DiagnosticDatabase : RoomDatabase() { abstract fun queue(): DiagnosticDao }

object Diagnostics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Application
    private val db by lazy { Room.databaseBuilder(app, DiagnosticDatabase::class.java, "diagnostics.db").build() }
    internal val queue: DiagnosticDao get() = db.queue()
    private val preferences by lazy { app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE) }
    private val spool by lazy { AtomicFile(File(app.filesDir, "diagnostic-crash.json")) }
    @Volatile private var foreground = false
    @Volatile private var owner: String? = null
    private var installed = false
    private val installation: String by lazy {
        preferences.getString("installation", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("installation", it).commit())
        }
    }
    val enabled: Boolean get() = installed && preferences.getBoolean("enabled", true)
    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        if (!value) { spool.delete(); owner?.let { id -> scope.launch { runCatching { queue.clearOwner(id) } } } }
    }
    fun initialize(application: Application) {
        if (installed) return
        app = application; installed = true
        // Prepare metadata outside a crash; never open Room or contact the network in the handler.
        installation
        owner = SecureSessions.store.owner
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var visible = 0
            override fun onActivityStarted(activity: Activity) { visible++; foreground = true }
            override fun onActivityStopped(activity: Activity) { visible = (visible - 1).coerceAtLeast(0); foreground = visible > 0 }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val actor = owner
                if (enabled && actor != null) {
                    val report = payload(DiagnosticCode.UNCAUGHT_EXCEPTION, error)
                    val stream = spool.startWrite()
                    try {
                        stream.write(buildJsonObject { put("owner", actor); put("report", report) }.toString().toByteArray())
                        spool.finishWrite(stream)
                    } catch (failure: Throwable) { spool.failWrite(stream) }
                }
            } catch (_: Throwable) { /* reporting must never replace Android's crash handler */ }
            finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
            }
        }
        scope.launch {
            supabase.auth.sessionStatus.collectLatest {
                owner = SecureSessions.store.owner
                if (it is io.github.jan.supabase.auth.status.SessionStatus.RefreshFailure) report(DiagnosticCode.AUTH_REFRESH_FAILED)
                if (enabled && supabase.auth.currentUserOrNull()?.id == owner && owner != null) {
                    runCatching { importStartup(checkNotNull(owner)); DiagnosticUploadWorker.enqueue(app, checkNotNull(owner)) }
                }
            }
        }
    }
    internal fun payload(code: DiagnosticCode, error: Throwable? = null, time: Long = System.currentTimeMillis()): JsonObject = buildJsonObject {
        put("id", UUID.randomUUID().toString()); put("installation_id", installation)
        put("occurred_at", Instant.ofEpochMilli(time).toString())
        put("category", code.category); put("code", code.name); put("feature", code.feature); put("severity", code.severity)
        put("version_name", BuildConfig.VERSION_NAME); put("version_code", BuildConfig.VERSION_CODE)
        put("android_api", Build.VERSION.SDK_INT); put("device_model", DiagnosticSanitizer.model(Build.MODEL))
        put("foreground", foreground); put("training", TrackingState.serviceRunning.value)
        putJsonArray("frames") { DiagnosticSanitizer.frames(error).forEach { add(it) } }; put("occurrences", 1)
    }
    fun report(code: DiagnosticCode, error: Throwable? = null, expectedOwner: String? = null) {
        if (error is CancellationException) return
        if (!enabled) return
        val actor = SecureSessions.store.owner ?: return
        if (expectedOwner != null && actor != expectedOwner) return
        owner = actor
        // A bounded producer prevents reporting storms from competing with the GPS writer.
        if (!pending.tryAcquire()) return
        scope.launch {
            try { runCatching { if (enabled) { retain(actor, payload(code, error)); DiagnosticUploadWorker.enqueue(app, actor) } } }
            finally { pending.release() }
        }
    }
    private val pending = java.util.concurrent.Semaphore(8)
    internal suspend fun retain(actor: String, report: JsonObject) {
        val now = System.currentTimeMillis()
        val time = Instant.parse(report.text("occurred_at")).toEpochMilli()
        if (time < now - TimeUnit.DAYS.toMillis(30)) return
        val key = report.text("code") + report["frames"].toString()
        db.withTransaction {
            // Startup import may already be in flight when opt-out clears the queue.
            // Check inside the serialized write transaction so it cannot reinsert afterwards.
            if (!enabled) return@withTransaction
            queue.expire(now - TimeUnit.DAYS.toMillis(30))
            if (!queue.duplicate(actor, key, now - TimeUnit.MINUTES.toMillis(10)))
                queue.insert(DiagnosticRow(report.text("id"), actor, time, key, report.toString()))
            queue.bound()
        }
    }
    private suspend fun importStartup(actor: String) {
        queue.expire(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
        var crashAt = preferences.getLong("crash_at", 0)
        if (spool.baseFile.exists()) {
            val saved = runCatching { Json.parseToJsonElement(spool.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject }.getOrNull()
            if (saved != null && saved.text("owner") == actor) {
                val report = saved.getValue("report").jsonObject
                retain(actor, report)
                crashAt = Instant.parse(report.text("occurred_at")).toEpochMilli()
                preferences.edit().putLong("crash_at", crashAt).commit(); spool.delete()
            } else if (saved == null) spool.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val last = preferences.getLong("exit_at", 0)
            val exits = (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getHistoricalProcessExitReasons(app.packageName, 0, 10).filter { it.timestamp > last }.sortedBy { it.timestamp }
            for (exit in exits) {
                // ExitInfo has no account identity. Attribute only exits from this owner's recorded process lifetime.
                val sameOwner = preferences.getString("process_owner", null) == actor
                val since = preferences.getLong("process_since", Long.MAX_VALUE)
                if (sameOwner && exit.timestamp >= since && kotlin.math.abs(exit.timestamp - crashAt) > 15_000) {
                    val code = when (exit.reason) {
                        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> DiagnosticCode.PROCESS_CRASH
                        ApplicationExitInfo.REASON_ANR -> DiagnosticCode.PROCESS_ANR
                        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_USER_REQUESTED,
                        ApplicationExitInfo.REASON_USER_STOPPED, ApplicationExitInfo.REASON_SIGNALED -> DiagnosticCode.PROCESS_INTERRUPTED
                        else -> null
                    }
                    if (code != null) retain(actor, payload(code, time = exit.timestamp))
                }
            }
            preferences.edit().putLong("exit_at", exits.lastOrNull()?.timestamp ?: last).commit()
        }
        preferences.edit().putString("process_owner", actor).putLong("process_since", System.currentTimeMillis()).commit()
    }
}

class DiagnosticUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val actor = inputData.getString("owner") ?: return Result.failure()
        if (!Diagnostics.enabled || runAttemptCount >= 6) return Result.success()
        return try {
            supabase.auth.awaitInitialization()
            if (supabase.auth.currentUserOrNull()?.id != actor || SecureSessions.store.owner != actor) return Result.success()
            Diagnostics.queue.expire(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
            val rows = Diagnostics.queue.batch(actor)
            if (rows.isEmpty()) return Result.success()
            Diagnostics.queue.attempted(actor, rows.map { it.id })
            val token = supabase.auth.currentAccessTokenOrNull() ?: return Result.retry()
            if (!Diagnostics.enabled || supabase.auth.currentUserOrNull()?.id != actor) return Result.success()
            // Freeze this owner's JWT; a concurrent account switch cannot change the HTTP actor.
            val response = http.post("$SUPABASE_PROJECT_URL/rest/v1/rpc/submit_app_error_reports") {
                bearerAuth(token); header("apikey", SUPABASE_PUBLISHABLE_KEY); contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                put("p_reports", JsonArray(rows.map { Json.parseToJsonElement(it.payload) }))
                }.toString())
            }
            check(response.status.isSuccess())
            if (supabase.auth.currentUserOrNull()?.id != actor) return Result.success()
            val accepted = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("accepted_ids").jsonArray.map { it.jsonPrimitive.content }
            Diagnostics.queue.acknowledge(actor, accepted.filter { id -> rows.any { it.id == id } })
            if (Diagnostics.queue.batch(actor).isEmpty()) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount >= 5) Result.success() else Result.retry() }
    }
    companion object {
        private val http = HttpClient(Android)
        fun enqueue(context: Context, owner: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("diagnostics-$owner", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DiagnosticUploadWorker>()
                    .setInputData(Data.Builder().putString("owner", owner).build())
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
