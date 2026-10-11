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
import java.util.TreeMap

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
    private var publishedPoints: List<TrackPoint> = points.toList()
    private var publishedSplits: List<KmSplit> = splits.toList()
    // Timestamp and cumulative counted distance; small jitter must not invent speed.
    private val speedSamples = ArrayDeque<Pair<Long, Double>>()
    private var distance = restored?.distanceM ?: 0.0
    private var activeMs = restored?.activeMs ?: 0L
    private var pausedMs = restored?.pausedMs ?: 0L
    private var lastTick = SystemClock.elapsedRealtime()
    private var lastSplitMs = restored?.splits?.sumOf { it.durationMs } ?: 0L
    private var paused = restored?.paused ?: false
    private var resumeAnchor = restored != null
    // Route continuity and distance jitter suppression have different anchors.
    private var distanceAnchor: TrackPoint? = null
    private var distanceAnchorActiveMs = activeMs
    private var requestActiveMs = activeMs
    private var requestElapsedNanos = SystemClock.elapsedRealtimeNanos()
    private var requestWallMs = System.currentTimeMillis()
    private var segment = (restored?.points?.lastOrNull()?.segment ?: 0) + if (restored != null) 1 else 0
    private val sessionId = restored?.sessionId ?: UUID.randomUUID().toString()
    private var altitudeAnchor: Double? = null
    private var ascent = restored?.ascentM ?: 0.0
    private var descent = restored?.descentM ?: 0.0
    private var hasReliableAltitude = restored?.ascentM != null
    private var steps: Int? = restored?.steps
    private val legFixes = TreeMap<Long, Location>()
    private data class LegAnchor(val pointCount: Int, val splitCount: Int, val distance: Double,
        val lastSplitMs: Long, val segment: Int, val resume: Boolean, val distancePoint: TrackPoint?, val distanceActiveMs: Long,
        val altitude: Double?, val ascent: Double, val descent: Double, val reliable: Boolean,
        val speed: List<Pair<Long, Double>>)
    private var legAnchor: LegAnchor? = null
    private var recordingError = restored?.recordingError
    fun setRecordingError(message: String) { recordingError = message; publish() }
    fun beginLocationRequest(elapsedNanos: Long, wallMs: Long) {
        requestActiveMs = activeMs; requestElapsedNanos = elapsedNanos; requestWallMs = wallMs
    }

    fun tick(now: Long = SystemClock.elapsedRealtime()) {
        if (!paused) activeMs += (now - lastTick).coerceAtLeast(0) else pausedMs += (now - lastTick).coerceAtLeast(0)
        lastTick = now
        publish()
    }
    fun pause(now: Long = SystemClock.elapsedRealtime()) { tick(now); paused = true; publish() }
    fun resume() { tick(); lastTick = SystemClock.elapsedRealtime(); paused = false; resumeAnchor = true; segment++; altitudeAnchor = null; speedSamples.clear(); legFixes.clear(); legAnchor = null; publish() }
    fun setStepSensorAvailable(available: Boolean) { steps = if (available) steps ?: 0 else steps; publish() }
    fun addStep() { if (!paused && steps != null) { steps = steps!! + 1; publish() } }
    fun add(location: Location) = addBatch(listOf(location))
    fun addBatch(locations: List<Location>) {
        if (paused || locations.isEmpty()) return
        val anchor = legAnchor ?: LegAnchor(points.size, splits.size, distance, lastSplitMs, segment,
            resumeAnchor, distanceAnchor, distanceAnchorActiveMs, altitudeAnchor, ascent, descent, hasReliableAltitude, speedSamples.toList()).also { legAnchor = it }
        val boundary = points.getOrNull(anchor.pointCount - 1)?.timestamp ?: Long.MIN_VALUE
        val previousLast = legFixes.lastKeyOrNull()
        val added = locations.filter { valid(it) && it.time > boundary && !legFixes.containsKey(it.time) }
            .distinctBy { it.time }.sortedBy { it.time }
        if (added.isEmpty()) return
        added.forEach { legFixes[it.time] = Location(it) }
        if (previousLast != null && added.first().time < previousLast) {
            // Replay only the current uninterrupted recording leg; never cross a pause/restore.
            points.subList(anchor.pointCount, points.size).clear()
            splits.subList(anchor.splitCount, splits.size).clear()
            distance = anchor.distance; lastSplitMs = anchor.lastSplitMs; segment = anchor.segment
            resumeAnchor = anchor.resume; distanceAnchor = anchor.distancePoint; altitudeAnchor = anchor.altitude
            distanceAnchorActiveMs = anchor.distanceActiveMs
            ascent = anchor.ascent; descent = anchor.descent; hasReliableAltitude = anchor.reliable
            speedSamples.clear(); speedSamples.addAll(anchor.speed)
            legFixes.values.forEach { addOrdered(it) }
            publishedPoints = points.toList(); publishedSplits = splits.toList()
        } else added.forEach { addOrdered(it) }
        publish()
    }
    private fun TreeMap<Long, Location>.lastKeyOrNull(): Long? = if (isEmpty()) null else lastKey()
    private fun valid(location: Location) = location.hasAccuracy() && location.accuracy.isFinite() &&
        location.accuracy in 0.000001f..40f && location.latitude.isFinite() && location.longitude.isFinite() &&
        location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
    private fun addOrdered(location: Location) {
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
            // Reject implausible teleports, not ordinary road speeds (the old limit was 43.2 km/h).
            if (meters[0] / elapsed > 100) return
        }
        val measuredActiveMs = (requestActiveMs + if (location.elapsedRealtimeNanos > 0)
            (location.elapsedRealtimeNanos - requestElapsedNanos) / 1_000_000 else location.time - requestWallMs)
            .coerceIn(requestActiveMs, activeMs.coerceAtLeast(requestActiveMs))
        if (resumeAnchor || previous == null) { distanceAnchor = point; distanceAnchorActiveMs = measuredActiveMs }
        val anchor = distanceAnchor ?: previous ?: point
        val meters = FloatArray(1)
        Location.distanceBetween(anchor.latitude, anchor.longitude, point.latitude, point.longitude, meters)
        if (!resumeAnchor && meters[0] >= 2) {
            val before = floor(distance / 1000).toInt()
            distance += meters[0]
            val after = floor(distance / 1000).toInt()
            for (km in before + 1..after) {
                val fraction = ((km * 1000 - (distance - meters[0])) / meters[0]).coerceIn(0.0, 1.0)
                val lat = anchor.latitude + (point.latitude - anchor.latitude) * fraction
                val lon = anchor.longitude + (point.longitude - anchor.longitude) * fraction
                val splitAt = (distanceAnchorActiveMs + (measuredActiveMs - distanceAnchorActiveMs) * fraction).toLong().coerceAtLeast(lastSplitMs)
                val splitTime = splitAt - lastSplitMs
                splits += KmSplit(km, splitTime, lat, lon)
                lastSplitMs = splitAt
            }
            distanceAnchor = point
            distanceAnchorActiveMs = measuredActiveMs
        }
        if (resumeAnchor) speedSamples.clear()
        speedSamples.addLast(point.timestamp to distance)
        if (speedSamples.size > 6) speedSamples.removeFirst()
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
    }
    private fun publish() {
        val recentM = if (speedSamples.size > 1) speedSamples.last().second - speedSamples.first().second else 0.0
        val recentS = if (speedSamples.size > 1) (speedSamples.last().first - speedSamples.first().first) / 1000.0 else 0.0
        val speed = if (recentS > 0 && !paused) recentM / recentS * 3.6 else 0.0
        val stale = points.lastOrNull()?.let { System.currentTimeMillis() - it.timestamp > 30_000 } ?: true
        if (publishedPoints.size != points.size) publishedPoints = points.toList()
        if (publishedSplits.size != splits.size) publishedSplits = splits.toList()
        snapshot = Session(kind, startedAt, activeMs, distance, paused, publishedPoints, publishedSplits, if (stale) 0.0 else speed, true,
            ascent.takeIf { hasReliableAltitude }, descent.takeIf { hasReliableAltitude }, steps, ownerUserId, dogId,
            sessionId = sessionId, status = if (paused) "PAUSED" else "ACTIVE", checkpointAt = System.currentTimeMillis(), pausedMs = pausedMs,
            interruptions = restored?.interruptions ?: 0,
            recordingError = if (stale && !paused) "GPS-signal saknas. Ingen distans beräknas för avbrottet." else recordingError)
        TrackingState.session.value = snapshot
    }
}

