package se.oxelv.mintracker.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import se.oxelv.mintracker.MainActivity
import se.oxelv.mintracker.data.*

/** One serialized writer owns countdown, GPS and durable state, independently of the UI. */
class TrackingService : Service() {
    companion object {
        const val START = "start"; const val RESTORE = "restore"; const val PAUSE = "pause"; const val RESUME = "resume"; const val STOP = "stop"
        const val EXTRA_KIND = "kind"; const val EXTRA_USER_ID = "user_id"; const val EXTRA_DOG_ID = "dog_id"
        const val EXTRA_COUNTDOWN = "countdown_seconds"
        private const val CHANNEL = "tracking"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val eventMutex = Mutex()
    private var destroying = false
    private val events: RecordingInbox<suspend () -> Unit> = RecordingInbox(onSignalFailure = {
        // A failed wake-up never owns/drops the GPS payload. Drain the retained inbox.
        if (!destroying) scope.launch { drainEvents() }
    })
    private lateinit var checkpoints: RecordingCheckpoints
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var sensors: SensorManager
    private lateinit var store: ActiveTrainingStore
    private var accumulator: TrackAccumulator? = null
    private var countdown: Session? = null
    private var started = false
    private var lastCheckpoint = 0L
    private var lastNotification = 0L
    private var firstFixReported = false
    private var gpsGapReported = false
    private var gpsRequestedAt = 0L
    private var requestStartedElapsedNanos = 0L
    private var requestStartedWallMs = 0L
    private var requestGeneration = 0L
    private var cutoffElapsedNanos = Long.MAX_VALUE
    private var cutoffWallMs = Long.MAX_VALUE
    private var transition: String? = null
    private val deferredControls = ArrayDeque<Intent>()
    private fun enqueue(event: suspend () -> Unit) {
        if (!events.offer(event) && !destroying) {
            reportDiagnostic(DiagnosticCode.GPS_SERVICE_STOPPED)
            scope.launch { eventMutex.withLock { event() } }
        }
    }
    private suspend fun drainEvents() = eventMutex.withLock {
        while (true) {
            val event = events.poll() ?: break
            try { event() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                reportDiagnostic(DiagnosticCode.GPS_SERVICE_STOPPED, error)
                TrackingState.session.value = TrackingState.session.value.copy(recordingError =
                    "Registreringen avbröts. Redan sparade data finns kvar. Öppna passet och försök igen.")
                stopSelf()
            }
        }
    }
    private var countdownWakeLock: PowerManager.WakeLock? = null
    private fun keepCountdownAwake(until: Long) {
        releaseCountdownWakeLock()
        countdownWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MinTracker:countdown").apply {
                acquire((until - System.currentTimeMillis()).coerceIn(0, 60_000) + 10_000)
            }
    }
    private fun releaseCountdownWakeLock() { countdownWakeLock?.let { if (it.isHeld) it.release() }; countdownWakeLock = null }
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) { if (transition == null && !destroying) enqueue { accumulator?.addStep() } }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (destroying) return
            val generation = requestGeneration
            val now = System.currentTimeMillis()
            val locations = GpsLocationBatch.receivedLocations(result.locations, now,
                requestStartedElapsedNanos, requestStartedWallMs,
                cutoffElapsedNanos = cutoffElapsedNanos, cutoffWallMs = cutoffWallMs)
            enqueue {
                if (generation != requestGeneration) return@enqueue
                accumulator?.tick(if (transition != null) cutoffElapsedNanos / 1_000_000 else SystemClock.elapsedRealtime())
                accumulator?.addBatch(locations)
                persist()
                if (accumulator?.snapshot?.points?.lastOrNull()?.let { now - it.timestamp < 30_000 } == true) gpsGapReported = false
                if (!firstFixReported && accumulator?.snapshot?.points?.isNotEmpty() == true) {
                    firstFixReported = true; reportDiagnostic(DiagnosticCode.GPS_FIRST_FIX)
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        store = ActiveTrainingStore(TrackerDatabaseProvider.get(this))
        checkpoints = RecordingCheckpoints(store::checkpoint) { error ->
            if (!destroying) enqueue { throw error }
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pågående pass", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            while (events.awaitSignal()) drainEvents()
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                enqueue {
                    val pending = countdown
                    if (pending != null && System.currentTimeMillis() >= pending.countdownUntil) activate(pending)
                    accumulator?.tick(if (transition != null) cutoffElapsedNanos / 1_000_000 else SystemClock.elapsedRealtime())
                    val recording = accumulator?.snapshot
                    if (recording != null && !recording.paused && gpsRequestedAt != 0L &&
                        SystemClock.elapsedRealtime() - gpsRequestedAt > 60_000 &&
                        recording.points.lastOrNull()?.let { System.currentTimeMillis() - it.timestamp > 30_000 } != false && !gpsGapReported) {
                        gpsGapReported = true; reportDiagnostic(DiagnosticCode.GPS_SIGNAL_LOST)
                    }
                    if (started && SystemClock.elapsedRealtime() - lastCheckpoint >= 5_000) persist()
                    if (started && SystemClock.elapsedRealtime() - lastNotification >= 5_000) updateNotification()
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in setOf(STOP, PAUSE, RESUME)) {
            enqueue { control(checkNotNull(intent)) }
            return START_STICKY
        }
        if (!hasLocation()) { stopSelf(); return START_NOT_STICKY }
        // Synchronous promotion precedes Room I/O and the caller locking the screen.
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, notification())
        } catch (error: RuntimeException) {
            reportDiagnostic(DiagnosticCode.GPS_START_FAILED, error)
            TrackingState.session.value = TrackingState.session.value.copy(recordingError =
                "Android kunde inte starta GPS-tjänsten. Öppna appen och kontrollera platsbehörigheten. Sparade data finns kvar.")
            stopSelf(); return START_NOT_STICKY
        }
        enqueue {
            if (destroying) return@enqueue
            if (started) return@enqueue
            val owner = intent?.getStringExtra(EXTRA_USER_ID) ?: SecureSessions.store.owner
            if (owner.isNullOrBlank()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@enqueue }
            val old = store.load(owner)
            if (old != null) {
                if (old.status == "STOPPING") {
                    TrackingState.session.value = old; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@enqueue
                }
                val restored = old.copy(interruptions = old.interruptions + 1,
                    recordingError = "Passet har återställts efter ett registreringsavbrott. Saknad tid och sträcka räknas inte.")
                reportDiagnostic(DiagnosticCode.SESSION_RESTORED)
                started = true
                if (restored.status == "COUNTDOWN") {
                    keepCountdownAwake(restored.countdownUntil)
                    countdown = restored; TrackingState.session.value = restored
                    if (System.currentTimeMillis() >= restored.countdownUntil) activate(restored)
                } else activate(restored)
            } else if (intent?.action == START) {
                val kind = ActivityKind.fromStored(intent.getStringExtra(EXTRA_KIND) ?: "RUN")
                val dog = intent.getStringExtra(EXTRA_DOG_ID)
                require(kind != null && kind.requiresDog == (dog != null))
                if (dog != null) require(TrackerDatabaseProvider.get(this@TrackingService).dogDao().byId(dog, owner) != null)
                val now = System.currentTimeMillis()
                val until = now + intent.getIntExtra(EXTRA_COUNTDOWN, 0).coerceIn(0, 60) * 1000L
                keepCountdownAwake(until)
                val pending = Session(kind = kind.name, ownerUserId = owner, dogId = dog,
                    startedAt = until, countdownUntil = until, running = true, status = "COUNTDOWN", checkpointAt = now)
                store.checkpoint(pending)
                reportDiagnostic(DiagnosticCode.COUNTDOWN_STARTED)
                reportDiagnostic(DiagnosticCode.SERVICE_STARTED)
                countdown = pending; started = true; TrackingState.serviceRunning.value = true; TrackingState.session.value = pending
                if (until <= now) activate(pending)
            } else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@enqueue }
            persist(); updateNotification()
        }
        return START_STICKY
    }
    private suspend fun control(intent: Intent) {
        val requestedOwner = intent.getStringExtra(EXTRA_USER_ID)
        val owned = countdown ?: accumulator?.snapshot
        if (requestedOwner != null && owned != null && owned.ownerUserId != requestedOwner) return
        if (transition != null) { deferredControls.addLast(Intent(intent)); return }
        if (owned == null && intent.action != STOP) { stopSelf(); return }
        if (intent.action == RESUME) {
            if (accumulator?.snapshot?.paused == true) {
                accumulator?.resume(); requestUpdates()
                accumulator?.snapshot?.let { checkpoints.flush(it) }
                updateNotification()
            }
            return
        }
        if (intent.action == PAUSE && (countdown != null || owned?.paused == true)) return
        transition = intent.action
        cutoffElapsedNanos = SystemClock.elapsedRealtimeNanos()
        cutoffWallMs = System.currentTimeMillis()
        accumulator?.tick(cutoffElapsedNanos / 1_000_000)
        // Return to the actor now, so callbacks produced by flush can be consumed.
        scope.launch {
            val failure = GpsDrain.finish(
                flush = { if (accumulator?.snapshot?.paused == false) fused.flushLocations().awaitCompletion() },
                remove = { fused.removeLocationUpdates(callback).awaitCompletion() },
                callbackBarrier = { suspendCancellableCoroutine<Unit> { continuation ->
                    Handler(Looper.getMainLooper()).post { if (continuation.isActive) continuation.resume(Unit) }
                } })
            enqueue { finishControl(intent, failure) }
        }
    }
    private suspend fun finishControl(intent: Intent, failure: Throwable?) {
        requestGeneration++
        accumulator?.pause(cutoffElapsedNanos / 1_000_000)
        if (failure != null) accumulator?.setRecordingError("GPS-bufferten kunde inte tömmas helt. Redan mottagna punkter har sparats.")
        val state = countdown ?: accumulator?.snapshot ?: (intent.getStringExtra(EXTRA_USER_ID) ?: SecureSessions.store.owner)?.let { store.load(it) }
        if (failure != null) reportDiagnostic(DiagnosticCode.GPS_SERVICE_STOPPED, failure)
        val checked = state?.let { if (failure == null) it else it.copy(recordingError =
            "GPS-bufferten kunde inte tömmas helt. Redan mottagna punkter har sparats.") }
        if (intent.action == STOP) {
            checked?.let {
                val stopped = it.copy(status = "STOPPING", running = false, paused = true,
                    endedAt = cutoffWallMs, checkpointAt = System.currentTimeMillis())
                checkpoints.flush(stopped)
                TrackingState.session.value = stopped
                Diagnostics.report(DiagnosticCode.SERVICE_STOPPED, expectedOwner = it.ownerUserId)
            }
            started = false; countdown = null; accumulator = null; deferredControls.clear()
            releaseCountdownWakeLock(); sensors.unregisterListener(stepListener)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        } else {
            checked?.let { checkpoints.flush(it); TrackingState.session.value = it }
            updateNotification()
        }
        transition = null
        while (deferredControls.isNotEmpty()) {
            val next = deferredControls.removeFirst()
            enqueue { control(next) }
        }
    }
    private suspend fun com.google.android.gms.tasks.Task<Void>.awaitCompletion(): Unit = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (continuation.isActive) {
                if (task.isSuccessful) continuation.resume(Unit)
                else continuation.resumeWithException(task.exception ?: IllegalStateException("Location task cancelled"))
            }
        }
    }
    private suspend fun activate(state: Session) {
        if (destroying) return
        countdown = null
        val seed = if (state.status == "COUNTDOWN") state.copy(status = "ACTIVE", paused = false, activeMs = 0) else state
        accumulator = TrackAccumulator(seed.kind, seed.startedAt, checkNotNull(seed.ownerUserId), seed.dogId, seed)
        val stepSensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val stepsAllowed = stepSensor != null && (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this,
            Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
        accumulator?.setStepSensorAvailable(stepsAllowed)
        if (stepsAllowed) sensors.registerListener(stepListener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        accumulator?.tick(); persist()
        TrackingState.serviceRunning.value = true
        if (!seed.paused) requestUpdates()
        reportDiagnostic(DiagnosticCode.GPS_STARTED)
        releaseCountdownWakeLock()
    }
    private fun persist() {
        val state = countdown ?: accumulator?.snapshot ?: return
        if (state.ownerUserId != null && started) checkpoints.schedule(state.copy(checkpointAt = System.currentTimeMillis()))
        lastCheckpoint = SystemClock.elapsedRealtime()
    }
    private fun reportDiagnostic(code: DiagnosticCode, error: Throwable? = null) { Diagnostics.report(code, error, (countdown ?: accumulator?.snapshot)?.ownerUserId) }
    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Location permission unavailable")
        gpsRequestedAt = SystemClock.elapsedRealtime()
        requestGeneration++
        cutoffElapsedNanos = Long.MAX_VALUE; cutoffWallMs = Long.MAX_VALUE
        requestStartedElapsedNanos = SystemClock.elapsedRealtimeNanos()
        requestStartedWallMs = System.currentTimeMillis()
        accumulator?.beginLocationRequest(requestStartedElapsedNanos, requestStartedWallMs)
        fused.requestLocationUpdates(GpsLocationBatch.request(), callback, Looper.getMainLooper())
            .addOnFailureListener { reportDiagnostic(DiagnosticCode.GPS_START_FAILED, it); enqueue { throw it } }
    }
    private fun notification(): Notification {
        val state = countdown ?: accumulator?.snapshot ?: Session()
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("open_training", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val status = when {
            state.status == "COUNTDOWN" -> "Startar om ${((state.countdownUntil - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)} s"
            state.paused -> "Pausat"
            else -> "Distans: ${"%.2f".format(state.distanceM / 1000)} km • Tid: ${state.activeMs / 60000}:${"%02d".format(state.activeMs / 1000 % 60)}"
        }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Min Tracker – ${ActivityKind.label(state.kind)} pågår").setContentText(status)
            .setContentIntent(launch).setOnlyAlertOnce(true).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }
    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification())
        lastNotification = SystemClock.elapsedRealtime()
    }
    override fun onDestroy() {
        destroying = true
        TrackingState.serviceRunning.value = false
        releaseCountdownWakeLock()
        fused.removeLocationUpdates(callback); sensors.unregisterListener(stepListener)
        scope.cancel(); events.close()
        // Service destruction does not cancel already received positions or their final write.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                withTimeout(10_000) {
                    drainEvents()
                    (countdown ?: accumulator?.snapshot)?.takeIf { started }?.let { checkpoints.flush(it) }
                    checkpoints.close()
                }
            } catch (error: Exception) { reportDiagnostic(DiagnosticCode.GPS_SERVICE_STOPPED, error) }
            finally { checkpoints.cancel(); cancel() }
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
