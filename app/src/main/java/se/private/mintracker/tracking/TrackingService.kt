package se.oxelv.mintracker.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import se.oxelv.mintracker.MainActivity
import se.oxelv.mintracker.R
import se.oxelv.mintracker.data.ActivityKind

class TrackingService : Service() {
    companion object {
        const val START = "start"; const val PAUSE = "pause"; const val RESUME = "resume"; const val STOP = "stop"
        const val EXTRA_KIND = "kind"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_DOG_ID = "dog_id"
        private const val CHANNEL = "tracking"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var fused: FusedLocationProviderClient
    private var accumulator: TrackAccumulator? = null
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) { accumulator?.addStep() }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { accumulator?.add(it) }
            updateNotification()
        }
    }
    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pågående pass", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (accumulator == null) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    stopSelf(); return START_NOT_STICKY
                }
                val ownerUserId = intent.getStringExtra(EXTRA_USER_ID)
                if (ownerUserId.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
                val kind = ActivityKind.fromStored(intent.getStringExtra(EXTRA_KIND) ?: "RUN")
                val dogId = intent.getStringExtra(EXTRA_DOG_ID)
                if (kind == null || kind.requiresDog != (dogId != null)) { stopSelf(); return START_NOT_STICKY }
                accumulator = TrackAccumulator(kind.name, System.currentTimeMillis(), ownerUserId, dogId)
                val canCountSteps = stepSensor != null && (Build.VERSION.SDK_INT < 29 ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
                accumulator?.setStepSensorAvailable(canCountSteps)
                if (canCountSteps) sensorManager.registerListener(stepListener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                } else {
                    startForeground(1, notification())
                }
                requestUpdates()
                scope.launch { while (isActive) { accumulator?.tick(); updateNotification(); delay(1000) } }
            }
            PAUSE -> { accumulator?.pause(); fused.removeLocationUpdates(callback); updateNotification() }
            RESUME -> { accumulator?.resume(); requestUpdates(); updateNotification() }
            STOP -> { fused.removeLocationUpdates(callback); sensorManager.unregisterListener(stepListener); accumulator = null; TrackingState.session.value = Session(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun requestUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).setMinUpdateIntervalMillis(1500).build(), callback, Looper.getMainLooper())
        }
    }
    private fun notification(): Notification {
        val state = TrackingState.session.value
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val status = if (state.paused) "Pausat" else "${"%.2f".format(state.distanceM / 1000)} km • ${state.activeMs / 60000}:${"%02d".format(state.activeMs / 1000 % 60)}"
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("${ActivityKind.label(state.kind)} ${if (state.paused) "pausad" else "pågår"}").setContentText(status)
            .setContentIntent(launch).setOngoing(true).build()
    }
    private fun updateNotification() { if (accumulator != null) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification()) }
    override fun onDestroy() { fused.removeLocationUpdates(callback); sensorManager.unregisterListener(stepListener); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
}

