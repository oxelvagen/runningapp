package se.oxelv.mintracker.data

import android.location.Location
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.tracking.*

/** Synthetic coordinates, exercising the real Android Location and accumulator. */
class GpsFlowRegressionTest {
    @Test fun delayedBatchMustUseMeasurementTimesForSplitsRatherThanDeliveryTime() {
        val wall = System.currentTimeMillis()
        val clock = SystemClock.elapsedRealtime()
        val accumulator = TrackAccumulator("RUN", wall, "gps-test-owner")
        accumulator.tick(clock + 60_000)
        val fixes = (0..20).map { index -> point(59.0 + index * .001, wall + index * 3000L) }
        accumulator.addBatch(fixes)
        assertEquals(2, accumulator.snapshot.splits.size)
        assertTrue("First km must use measurement time, not batch delivery time", accumulator.snapshot.splits.first().durationMs in 25_000..30_000)
        assertTrue("Second km must not become a zero-time split", accumulator.snapshot.splits.last().durationMs in 25_000..30_000)
    }
    @Test fun lateFixCanRepairFalseThirtySecondGapWithinSameRecordingLeg() {
        val now = System.currentTimeMillis()
        val accumulator = TrackAccumulator("RUN", now, "gps-test-owner")
        accumulator.add(point(59.0, now))
        accumulator.add(point(59.0002, now + 36_000))
        assertEquals(2, routeSegments(accumulator.snapshot.points).size)
        accumulator.add(point(59.0001, now + 18_000))
        assertEquals(3, accumulator.snapshot.points.size)
        assertEquals(1, routeSegments(accumulator.snapshot.points).size)
        assertTrue(accumulator.snapshot.distanceM > 20)
    }
    @Test fun pauseCutoffKeepsDelayedPrePauseFixAndRejectsMotionDuringFlush() {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val fixes = listOf(-3000L, -1000L, 0L).map { offset ->
            point(59.0, now + offset).apply { elapsedRealtimeNanos = elapsed + offset * 1_000_000 }
        }
        val result = GpsLocationBatch.receivedLocations(fixes, now, elapsed - 10_000_000_000L, now - 10000,
            elapsed, elapsed - 2_000_000_000L, now - 2000)
        assertEquals(listOf(now - 3000), result.map { it.time })
    }
    @Test fun delayedFixesCannotReconnectAcrossPauseOrRestore() {
        val now = System.currentTimeMillis()
        val accumulator = TrackAccumulator("RUN", now, "gps-test-owner")
        accumulator.add(point(59.0, now))
        accumulator.add(point(59.0001, now + 3000))
        val before = accumulator.snapshot.distanceM
        accumulator.pause(); accumulator.resume()
        accumulator.add(point(59.01, now + 6000))
        accumulator.add(point(59.005, now + 2000))
        assertEquals(before, accumulator.snapshot.distanceM, .001)
        assertEquals(listOf(now, now+3000, now+6000), accumulator.snapshot.points.map { it.timestamp })
        assertEquals(2, routeSegments(accumulator.snapshot.points).size)
    }
    @Test fun oneVersusThreeSecondRunningSamplingBenchmark() {
        val now = System.currentTimeMillis()
        val duration = 1800
        val distances = mutableMapOf<Int, Double>()
        for (interval in listOf(1, 3)) {
            val accumulator = TrackAccumulator("RUN", now - duration * 1000, "gps-test-owner")
            val fixes = (0..duration step interval).map { second ->
                val angle = second * .06 // 50 m radius, 3 m/s, synthetic smooth running curve
                point(59.0 + 50 * kotlin.math.sin(angle) / 111400, now - (duration-second) * 1000L).apply {
                    longitude = 18.0 + 50 * kotlin.math.cos(angle) / (111400 * kotlin.math.cos(Math.toRadians(59.0)))
                }
            }
            val cpu = SystemClock.currentThreadTimeMillis()
            fixes.chunked(6 / interval).forEach { accumulator.addBatch(it) }
            distances[interval] = accumulator.snapshot.distanceM
            println("GPS_SAMPLING interval=$interval points=${fixes.size} cpuMs=${SystemClock.currentThreadTimeMillis()-cpu} distanceM=${accumulator.snapshot.distanceM}")
            assertEquals(fixes.size, accumulator.snapshot.points.size)
            assertTrue(accumulator.snapshot.distanceM in 5300.0..5500.0)
        }
        assertTrue("Denser samples retain more of a smooth curve", distances.getValue(1) > distances.getValue(3))
    }
    @Test fun olderBatchMustFillCurveWithoutDuplicatesOrWrongDistance() {
        val now = System.currentTimeMillis()
        fun fix(lat: Double, lon: Double, seconds: Int) = point(lat, now + seconds * 1000L).apply { longitude = lon }
        val a = fix(59.0, 18.0, 0)
        val b = fix(59.0001, 18.0001, 3)
        val c = fix(59.0, 18.0002, 6)
        val expected = TrackAccumulator("RUN", now, "gps-test-owner")
        listOf(a, b, c).forEach { expected.add(it) }
        val actual = TrackAccumulator("RUN", now, "gps-test-owner")
        listOf(a, c, b, c).forEach { actual.add(it) }
        assertEquals(listOf(a.time, b.time, c.time), actual.snapshot.points.map { it.timestamp })
        assertEquals(expected.snapshot.distanceM, actual.snapshot.distanceM, 0.001)
        assertEquals(expected.snapshot.speedKmh, actual.snapshot.speedKmh, 0.001)
    }
    private fun point(latitude: Double, time: Long) = Location("gps-test").apply {
        this.latitude = latitude; longitude = 18.0; this.time = time; accuracy = 3f
    }
    @Test fun continuousCarFixesMustNotBecomeRouteGapsOrCappedRunningSpeed() {
        val now = System.currentTimeMillis()
        val accumulator = TrackAccumulator("RUN", now, "gps-test-owner")
        repeat(20) { index -> accumulator.add(point(59.0 + index * 0.0006, now + index * 3000L)) }
        assertEquals("Every valid car fix must be retained", 20, accumulator.snapshot.points.size)
        assertEquals("Continuous reception must remain one route segment", 1, routeSegments(accumulator.snapshot.points).size)
        assertTrue("Recorded car distance must exceed 1 km", accumulator.snapshot.distanceM > 1000)
        assertTrue("Speed must not be capped at 43.2 km/h", accumulator.snapshot.speedKmh > 60)
    }
    @Test fun continuousSlowFixesMustRemainPresentWithoutInventingReceptionGap() {
        val now = System.currentTimeMillis()
        val accumulator = TrackAccumulator("RUN", now, "gps-test-owner")
        repeat(20) { index -> accumulator.add(point(59.0 + index * 0.000001, now + index * 3000L)) }
        assertEquals("Valid slow fixes must not disappear from recorded route", 20, accumulator.snapshot.points.size)
        assertEquals(1, routeSegments(accumulator.snapshot.points).size)
    }
    @Test fun delayedBatchRecordedDuringRequestMustNotBeDiscardedAsStaleCache() {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val fixes = listOf(point(59.0, wall - 45_000).apply { elapsedRealtimeNanos = elapsed - 45_000_000_000L },
            point(59.0001, wall - 42_000).apply { elapsedRealtimeNanos = elapsed - 42_000_000_000L })
        assertEquals("Real recorded fixes from this request remain useful after delivery delay", 2,
            GpsLocationBatch.receivedLocations(fixes, wall, elapsed - 60_000_000_000L, wall - 60_000).size)
    }
    @Test fun cachedFutureAndDuplicateFixesAreRejectedButStationaryRouteIsRetained() {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val old = point(59.0, wall - 10_000).apply { elapsedRealtimeNanos = elapsed - 10_000_000_000L }
        val valid = point(59.0, wall - 1000).apply { elapsedRealtimeNanos = elapsed - 1_000_000_000L }
        val future = point(59.0, wall + 1000).apply { elapsedRealtimeNanos = elapsed + 1_000_000_000L }
        val filtered = GpsLocationBatch.receivedLocations(listOf(old, valid, future), wall,
            elapsed - 5_000_000_000L, wall - 5000, elapsed)
        assertEquals(listOf(valid.time), filtered.map { it.time })
        valid.latitude = 60.0
        assertEquals("Receipt boundary must copy provider-owned objects", 59.0, filtered.single().latitude, 0.0)
        val accumulator = TrackAccumulator("RUN", wall, "gps-test-owner")
        repeat(20) { accumulator.add(point(59.0, wall + it * 3000L)) }
        accumulator.add(point(59.0, wall + 57_000))
        accumulator.add(point(59.1, wall + 60_000).apply { accuracy = 100f })
        accumulator.add(point(60.0, wall + 60_000)) // impossible teleport
        assertEquals(20, accumulator.snapshot.points.size)
        assertEquals(0.0, accumulator.snapshot.distanceM, 0.0)
        assertEquals(0.0, accumulator.snapshot.speedKmh, 0.0)
        assertEquals(1, routeSegments(accumulator.snapshot.points).size)
    }
    @Test fun requestKeepsPowerSensitiveIntervalsAndDisablesInitialCacheOnly() {
        val request = GpsLocationBatch.request()
        assertEquals(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, request.priority)
        assertEquals(3000L, request.intervalMillis)
        assertEquals(1500L, request.minUpdateIntervalMillis)
        assertEquals(6000L, request.maxUpdateDelayMillis)
        assertEquals(0f, request.minUpdateDistanceMeters, 0f)
        assertEquals(0L, request.maxUpdateAgeMillis)
    }
    @Test fun timerReusesImmutableRouteInsteadOfCopyingWholeLongWorkout() {
        val wall = System.currentTimeMillis()
        val points = List(20_000) { TrackPoint(59.0, 18.0, wall - (20_000 - it) * 3000L, 3f, null) }
        val accumulator = TrackAccumulator("RUN", wall, "gps-test-owner",
            restored = Session(ownerUserId = "gps-test-owner", points = points))
        accumulator.tick()
        val published = accumulator.snapshot.points
        val now = SystemClock.elapsedRealtime()
        val cpu = SystemClock.currentThreadTimeMillis()
        repeat(1000) { accumulator.tick(now + it) }
        println("GPS_REVIEW timer1000/points20000 CPU ms=${SystemClock.currentThreadTimeMillis() - cpu}")
        assertTrue("Timer and steps should reuse the immutable route until GPS changes it", published === accumulator.snapshot.points)
        accumulator.add(point(59.0, wall))
        assertEquals(20_000, published.size)
        assertEquals(20_001, accumulator.snapshot.points.size)
        assertNotSame(published, accumulator.snapshot.points)
    }
    @Test fun subTwoMeterStationaryJitterDoesNotInventDistanceOrSpeed() {
        val now = System.currentTimeMillis()
        val accumulator = TrackAccumulator("RUN", now, "gps-test-owner")
        repeat(20) { accumulator.add(point(59.0 + (it % 2) * 0.00001, now + it * 3000L)) }
        assertEquals(20, accumulator.snapshot.points.size)
        assertEquals(0.0, accumulator.snapshot.distanceM, 0.0)
        assertEquals(0.0, accumulator.snapshot.speedKmh, 0.0)
    }
}
