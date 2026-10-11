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
import kotlinx.coroutines.channels.Channel
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
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
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
        override fun onSensorChanged(event: SensorEvent) { events.trySend { accumulator?.addStep() } }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            events.trySend {
                val now = System.currentTimeMillis()
                result.locations.filter { it.time <= now + 5_000 && now - it.time < 30_000 }
                    .sortedBy { it.time }.forEach { accumulator?.add(it) }
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
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pågående pass", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            for (event in events) try { event() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                reportDiagnostic(DiagnosticCode.GPS_SERVICE_STOPPED, error)
                // Preserve the last good checkpoint; never discard on a write/start failure.
                TrackingState.session.value = TrackingState.session.value.copy(recordingError =
                    "Registreringen avbröts. Redan sparade data finns kvar. Öppna passet och försök igen.")
                stopSelf()
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                events.send {
                    val pending = countdown
                    if (pending != null && System.currentTimeMillis() >= pending.countdownUntil) activate(pending)
                    accumulator?.tick()
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
            events.trySend {
                val requestedOwner = intent?.getStringExtra(EXTRA_USER_ID)
                val owned = countdown ?: accumulator?.snapshot
                if (requestedOwner != null && owned != null && owned.ownerUserId != requestedOwner) return@trySend
                if (owned == null && intent?.action != STOP) { stopSelf(); return@trySend }
                when (intent?.action) {
                    PAUSE -> { accumulator?.pause(); fused.removeLocationUpdates(callback); persist(); updateNotification() }
                    RESUME -> { accumulator?.resume(); requestUpdates(); persist(); updateNotification() }
                    STOP -> {
                        accumulator?.tick()
                        val state = countdown ?: accumulator?.snapshot ?: owned ?: (requestedOwner ?: SecureSessions.store.owner)?.let { store.load(it) }
                        if (state == null) { stopSelf(); return@trySend }
                        if (state.ownerUserId != null) {
                            val stopped = state.copy(status = "STOPPING", running = false, paused = true,
                                endedAt = System.currentTimeMillis(), checkpointAt = System.currentTimeMillis())
                            store.checkpoint(stopped)
                            TrackingState.session.value = stopped
                        }
                        Diagnostics.report(DiagnosticCode.SERVICE_STOPPED, expectedOwner = state.ownerUserId)
                        started = false; countdown = null; accumulator = null
                        releaseCountdownWakeLock()
                        fused.removeLocationUpdates(callback); sensors.unregisterListener(stepListener)
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    }
                }
            }
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
        events.trySend {
            if (started) return@trySend
            val owner = intent?.getStringExtra(EXTRA_USER_ID) ?: SecureSessions.store.owner
            if (owner.isNullOrBlank()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@trySend }
            val old = store.load(owner)
            if (old != null) {
                if (old.status == "STOPPING") {
                    TrackingState.session.value = old; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@trySend
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
            } else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@trySend }
            persist(); updateNotification()
        }
        return START_STICKY
    }
    private suspend fun activate(state: Session) {
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
    private suspend fun persist() {
        val state = countdown ?: accumulator?.snapshot ?: return
        if (state.ownerUserId != null && started) store.checkpoint(state.copy(checkpointAt = System.currentTimeMillis()))
        lastCheckpoint = SystemClock.elapsedRealtime()
    }
    private fun reportDiagnostic(code: DiagnosticCode, error: Throwable? = null) { Diagnostics.report(code, error, (countdown ?: accumulator?.snapshot)?.ownerUserId) }
    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Location permission unavailable")
        gpsRequestedAt = SystemClock.elapsedRealtime()
        fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1500).setMaxUpdateDelayMillis(6000).build(), callback, Looper.getMainLooper())
            .addOnFailureListener { reportDiagnostic(DiagnosticCode.GPS_START_FAILED, it); events.trySend { throw it } }
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
        TrackingState.serviceRunning.value = false
        releaseCountdownWakeLock()
        fused.removeLocationUpdates(callback); sensors.unregisterListener(stepListener)
        scope.cancel(); events.close(); super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
