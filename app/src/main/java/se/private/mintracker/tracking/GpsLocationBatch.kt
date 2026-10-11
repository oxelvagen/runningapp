package se.oxelv.mintracker.tracking

import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/** The service's reception boundary, separate from the serialized Room writer. */
internal object GpsLocationBatch {
    fun receivedLocations(locations: List<Location>, receivedWallMs: Long,
        requestStartedElapsedNanos: Long, requestStartedWallMs: Long,
        receivedElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
        cutoffElapsedNanos: Long = Long.MAX_VALUE, cutoffWallMs: Long = Long.MAX_VALUE): List<Location> =
        locations.filter {
            // Delivery age is not measurement age: delayed fixes from this request are history.
            if (it.elapsedRealtimeNanos > 0) {
                it.elapsedRealtimeNanos >= requestStartedElapsedNanos && it.elapsedRealtimeNanos <= receivedElapsedNanos &&
                    it.elapsedRealtimeNanos <= cutoffElapsedNanos
            } else it.time >= requestStartedWallMs && it.time <= receivedWallMs + 5_000 && it.time <= cutoffWallMs
        }.sortedBy { it.time }.map { Location(it) }

    fun request(): LocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
        .setMinUpdateIntervalMillis(1500)
        .setMinUpdateDistanceMeters(0f)
        .setMaxUpdateDelayMillis(6000)
        .setMaxUpdateAgeMillis(0)
        .build()
}
