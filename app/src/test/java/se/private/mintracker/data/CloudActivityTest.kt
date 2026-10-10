package se.oxelv.mintracker.data

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.Json

class CloudActivityTest {
    @Test fun timestampRoundTripPreservesMilliseconds() {
        val millis = 1_760_000_123_456L
        assertEquals(millis, fromTimestamptz(toTimestamptz(millis)))
    }

    @Test fun postgresOffsetTimestampConvertsToUtc() {
        assertEquals(
            fromTimestamptz("2026-10-03T12:30:00Z"),
            fromTimestamptz("2026-10-03T14:30:00+02:00")
        )
    }

    @Test fun splitDurationRoundTripsMilliseconds() {
        val local = SplitEntity(42, 3, 376_345, 59.3, 18.1)
        val cloud = local.toCloudSplit("activity-uuid", "user-uuid")
        assertEquals(3, cloud.splitNumber)
        assertEquals(1000.0, cloud.distanceMeters, 0.0)
        assertEquals(376.345, cloud.durationSeconds, 0.000_001)
        assertEquals(local.durationMs, cloud.toLocalSplit(7).durationMs)
    }

    @Test fun oldCloudActivityWithoutNewMetricsStillDecodes() {
        val oldRecord = """{"id":"a","user_id":"u","activity_type":"Löpning","started_at":"2026-10-03T12:30:00Z","ended_at":"2026-10-03T13:00:00Z","duration_seconds":1800,"distance_meters":5000.0}"""
        val activity = Json.decodeFromString<CloudActivity>(oldRecord)
        assertEquals(null, activity.elevationGainMeters)
        assertEquals(null, activity.elevationLossMeters)
        assertEquals(null, activity.steps)
        assertEquals(null, activity.dogId)
    }

    @Test fun dogActivityKeepsItsDogId() {
        val record = """{"id":"a","user_id":"u","activity_type":"DOG_RUN","dog_id":"dog-uuid","started_at":"2026-10-03T12:30:00Z","ended_at":"2026-10-03T13:00:00Z","duration_seconds":1800,"distance_meters":5000.0}"""
        assertEquals("dog-uuid", Json.decodeFromString<CloudActivity>(record).dogId)
    }
}
