package se.oxelv.mintracker.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ActivitySummaryTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private fun activity(date: LocalDate, meters: Double, millis: Long) = ActivityEntity(
        kind = "Löpning", startedAt = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        activeMs = millis, distanceM = meters
    )

    @Test fun weeklySummaryUsesOnlyRealActivitiesInCurrentWeek() {
        val today = LocalDate.of(2026, 10, 3)
        val result = ActivitySummary.week(listOf(
            activity(LocalDate.of(2026, 9, 28), 5000.0, 1800000),
            activity(LocalDate.of(2026, 10, 2), 3000.0, 900000),
            activity(LocalDate.of(2026, 9, 27), 9000.0, 3000000)
        ), today, zone)
        assertEquals(2, result.totals.count)
        assertEquals(8000.0, result.totals.distanceMeters, 0.001)
        assertEquals(2700000L, result.totals.durationMillis)
        assertEquals(5000.0, result.dailyDistanceMeters[0], 0.001)
        assertEquals(3000.0, result.dailyDistanceMeters[4], 0.001)
    }
}
