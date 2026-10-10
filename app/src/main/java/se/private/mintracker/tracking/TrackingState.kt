package se.oxelv.mintracker.tracking

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import se.oxelv.mintracker.data.PointEntity
import se.oxelv.mintracker.data.SplitEntity
import kotlin.math.floor
import kotlin.math.abs

data class TrackPoint(val latitude: Double, val longitude: Double, val timestamp: Long, val accuracy: Float, val altitude: Double?)
data class KmSplit(val kilometer: Int, val durationMs: Long, val latitude: Double?, val longitude: Double?)
data class Session(
    val kind: String = "RUN", val startedAt: Long = 0, val activeMs: Long = 0,
    val distanceM: Double = 0.0, val paused: Boolean = false,
    val points: List<TrackPoint> = emptyList(), val splits: List<KmSplit> = emptyList(),
    val speedKmh: Double = 0.0, val running: Boolean = false,
    val ascentM: Double? = null, val descentM: Double? = null, val steps: Int? = null,
    val ownerUserId: String? = null,
    val dogId: String? = null
) {
    val averagePaceSeconds: Int get() = if (distanceM < 1) 0 else (activeMs / 1000.0 / (distanceM / 1000)).toInt()
    val currentPaceSeconds: Int get() = if (speedKmh < 0.5) 0 else (3600 / speedKmh).toInt()
}

object TrackingState { val session = MutableStateFlow(Session()) }

class TrackAccumulator(private val kind: String, private val startedAt: Long, private val ownerUserId: String, private val dogId: String? = null) {
    private val points = mutableListOf<TrackPoint>()
    private val splits = mutableListOf<KmSplit>()
    private var distance = 0.0
    private var activeMs = 0L
    private var lastTick = System.currentTimeMillis()
    private var lastSplitMs = 0L
    private var paused = false
    private var resumeAnchor = false
    private var altitudeAnchor: Double? = null
    private var ascent = 0.0
    private var descent = 0.0
    private var hasReliableAltitude = false
    private var steps: Int? = null

    fun tick(now: Long = System.currentTimeMillis()) {
        if (!paused) activeMs += (now - lastTick).coerceAtLeast(0)
        lastTick = now
        publish()
    }
    fun pause() { tick(); paused = true; publish() }
    fun resume() { lastTick = System.currentTimeMillis(); paused = false; resumeAnchor = true; altitudeAnchor = null; publish() }
    fun setStepSensorAvailable(available: Boolean) { steps = if (available) 0 else null; publish() }
    fun addStep() { if (!paused && steps != null) { steps = steps!! + 1; publish() } }
    fun add(location: Location) {
        if (paused || location.accuracy > 40f || location.accuracy <= 0f) return
        val point = TrackPoint(location.latitude, location.longitude, location.time, location.accuracy, location.altitude.takeIf { location.hasAltitude() })
        val previous = points.lastOrNull()
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
        if (location.hasAltitude() && (!location.hasVerticalAccuracy() || location.verticalAccuracyMeters <= 15f)) {
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
        val recent = points.takeLast(6)
        var recentM = 0.0
        for (i in 1 until recent.size) {
            val result = FloatArray(1)
            Location.distanceBetween(recent[i-1].latitude, recent[i-1].longitude, recent[i].latitude, recent[i].longitude, result)
            recentM += result[0]
        }
        val recentS = if (recent.size > 1) (recent.last().timestamp - recent.first().timestamp) / 1000.0 else 0.0
        val speed = if (recentS > 0 && !paused) (recentM / recentS * 3.6).coerceAtMost(43.2) else 0.0
        TrackingState.session.value = Session(kind, startedAt, activeMs, distance, paused, points.toList(), splits.toList(), speed, true,
            ascent.takeIf { hasReliableAltitude }, descent.takeIf { hasReliableAltitude }, steps, ownerUserId, dogId)
    }
}

