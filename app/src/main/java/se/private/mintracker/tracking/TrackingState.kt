package se.oxelv.mintracker.tracking

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import se.oxelv.mintracker.data.PointEntity
import se.oxelv.mintracker.data.SplitEntity
import kotlin.math.floor
import kotlin.math.abs
import kotlinx.serialization.Serializable
import java.util.UUID
import android.os.SystemClock

@Serializable data class TrackPoint(val latitude: Double, val longitude: Double, val timestamp: Long, val accuracy: Float, val altitude: Double?, val segment: Int = 0)
@Serializable data class KmSplit(val kilometer: Int, val durationMs: Long, val latitude: Double?, val longitude: Double?)
@Serializable
data class Session(
    val kind: String = "RUN", val startedAt: Long = 0, val activeMs: Long = 0,
    val distanceM: Double = 0.0, val paused: Boolean = false,
    val points: List<TrackPoint> = emptyList(), val splits: List<KmSplit> = emptyList(),
    val speedKmh: Double = 0.0, val running: Boolean = false,
    val ascentM: Double? = null, val descentM: Double? = null, val steps: Int? = null,
    val ownerUserId: String? = null,
    val dogId: String? = null,
    val sessionId: String = UUID.randomUUID().toString(),
    val status: String = "ACTIVE",
    val countdownUntil: Long = 0,
    val checkpointAt: Long = 0,
    val pausedMs: Long = 0,
    val interruptions: Int = 0,
    val endedAt: Long = 0,
    val recordingError: String? = null
) {
    val averagePaceSeconds: Int get() = if (distanceM < 1) 0 else (activeMs / 1000.0 / (distanceM / 1000)).toInt()
    val currentPaceSeconds: Int get() = if (speedKmh < 0.5) 0 else (3600 / speedKmh).toInt()
}

object TrackingState {
    val session = MutableStateFlow(Session())
    val serviceRunning = MutableStateFlow(false)
}

class TrackAccumulator(private val kind: String, private val startedAt: Long, private val ownerUserId: String, private val dogId: String? = null,
    private val restored: Session? = null) {
    var snapshot: Session = restored ?: Session(kind = kind, startedAt = startedAt, ownerUserId = ownerUserId, dogId = dogId)
        private set
    private val points = restored?.points?.toMutableList() ?: mutableListOf()
    private val splits = restored?.splits?.toMutableList() ?: mutableListOf()
    private var distance = restored?.distanceM ?: 0.0
    private var activeMs = restored?.activeMs ?: 0L
    private var pausedMs = restored?.pausedMs ?: 0L
    private var lastTick = SystemClock.elapsedRealtime()
    private var lastSplitMs = restored?.splits?.sumOf { it.durationMs } ?: 0L
    private var paused = restored?.paused ?: false
    private var resumeAnchor = restored != null
    private var segment = (restored?.points?.lastOrNull()?.segment ?: 0) + if (restored != null) 1 else 0
    private val sessionId = restored?.sessionId ?: UUID.randomUUID().toString()
    private var altitudeAnchor: Double? = null
    private var ascent = restored?.ascentM ?: 0.0
    private var descent = restored?.descentM ?: 0.0
    private var hasReliableAltitude = restored?.ascentM != null
    private var steps: Int? = restored?.steps

    fun tick(now: Long = SystemClock.elapsedRealtime()) {
        if (!paused) activeMs += (now - lastTick).coerceAtLeast(0) else pausedMs += (now - lastTick).coerceAtLeast(0)
        lastTick = now
        publish()
    }
    fun pause() { tick(); paused = true; publish() }
    fun resume() { tick(); lastTick = SystemClock.elapsedRealtime(); paused = false; resumeAnchor = true; segment++; altitudeAnchor = null; publish() }
    fun setStepSensorAvailable(available: Boolean) { steps = if (available) steps ?: 0 else steps; publish() }
    fun addStep() { if (!paused && steps != null) { steps = steps!! + 1; publish() } }
    fun add(location: Location) {
        if (paused || !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy > 40f || location.accuracy <= 0f ||
            !location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        val previous = points.lastOrNull()
        if (previous != null && location.time <= previous.timestamp) return
        if (previous != null && location.time - previous.timestamp > 30_000 && !resumeAnchor) {
            resumeAnchor = true; segment++; altitudeAnchor = null
        }
        val point = TrackPoint(location.latitude, location.longitude, location.time, location.accuracy, location.altitude.takeIf { location.hasAltitude() && it.isFinite() }, segment)
        if (previous != null && !resumeAnchor) {
            val meters = FloatArray(1)
            Location.distanceBetween(previous.latitude, previous.longitude, point.latitude, point.longitude, meters)
            val elapsed = ((point.timestamp - previous.timestamp) / 1000.0).coerceAtLeast(1.0)
            if (meters[0] < 2 || meters[0] / elapsed > 12) return
            val before = floor(distance / 1000).toInt()
            distance += meters[0]
            val after = floor(distance / 1000).toInt()
            for (km in before + 1..after) {
                val fraction = ((km * 1000 - (distance - meters[0])) / meters[0]).coerceIn(0.0, 1.0)
                val lat = previous.latitude + (point.latitude - previous.latitude) * fraction
                val lon = previous.longitude + (point.longitude - previous.longitude) * fraction
                val splitTime = activeMs - lastSplitMs
                splits += KmSplit(km, splitTime, lat, lon)
                lastSplitMs = activeMs
            }
        }
        resumeAnchor = false
        if (location.hasAltitude() && location.altitude.isFinite() && (!location.hasVerticalAccuracy() || location.verticalAccuracyMeters <= 15f)) {
            val altitude = location.altitude
            val anchor = altitudeAnchor
            if (anchor != null) {
                val change = altitude - anchor
                if (abs(change) >= 3.0) {
                    if (change > 0) ascent += change else descent -= change
                    altitudeAnchor = altitude
                }
            } else altitudeAnchor = altitude
            hasReliableAltitude = true
        }
        points += point
        publish()
    }
    private fun publish() {
        val recent = points.takeLast(6).filter { it.segment == points.lastOrNull()?.segment }
        var recentM = 0.0
        for (i in 1 until recent.size) {
            val result = FloatArray(1)
            Location.distanceBetween(recent[i-1].latitude, recent[i-1].longitude, recent[i].latitude, recent[i].longitude, result)
            recentM += result[0]
        }
        val recentS = if (recent.size > 1) (recent.last().timestamp - recent.first().timestamp) / 1000.0 else 0.0
        val speed = if (recentS > 0 && !paused) (recentM / recentS * 3.6).coerceAtMost(43.2) else 0.0
        val stale = points.lastOrNull()?.let { System.currentTimeMillis() - it.timestamp > 30_000 } ?: true
        snapshot = Session(kind, startedAt, activeMs, distance, paused, points.toList(), splits.toList(), if (stale) 0.0 else speed, true,
            ascent.takeIf { hasReliableAltitude }, descent.takeIf { hasReliableAltitude }, steps, ownerUserId, dogId,
            sessionId = sessionId, status = if (paused) "PAUSED" else "ACTIVE", checkpointAt = System.currentTimeMillis(), pausedMs = pausedMs,
            interruptions = restored?.interruptions ?: 0,
            recordingError = if (stale && !paused) "GPS-signal saknas. Ingen distans beräknas för avbrottet." else restored?.recordingError)
        TrackingState.session.value = snapshot
    }
}

