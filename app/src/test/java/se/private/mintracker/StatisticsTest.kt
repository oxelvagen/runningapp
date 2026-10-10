package se.oxelv.mintracker

import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.data.*
import java.time.LocalDate
import java.time.ZoneId

class StatisticsTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private val today = LocalDate.of(2026, 10, 4)
    private fun pass(day: LocalDate, kind: String = "RUN", distance: Double = 1000.0, minutes: Long = 6, dog: String? = null, climb: Double? = null) =
        ActivityEntity(
            kind = kind, startedAt = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            activeMs = minutes * 60_000, distanceM = distance, dogId = dog, ascentM = climb,
            userId = "owner", syncStatus = SyncStatus.SYNCED
        )
    private val dog = DogEntity("dog", "owner", "Frasse", createdAt = 0, updatedAt = 0, archivedAt = 1)

    @Test fun periodAndZeroBuckets() {
        val source = listOf(pass(today), pass(today.minusDays(8)))
        val seven = Statistics.calculate(source, emptyList(), StatisticsPeriod.SEVEN_DAYS, today = today, zone = zone)
        assertEquals(1, seven.totals.count)
        assertEquals(7, seven.buckets.size)
        assertEquals(6, seven.buckets.count { it.distanceMeters == 0.0 })
        assertEquals(2, Statistics.calculate(source, emptyList(), StatisticsPeriod.THIRTY_DAYS, today = today, zone = zone).totals.count)
        assertEquals(2, Statistics.calculate(source, emptyList(), StatisticsPeriod.YEAR, today = today, zone = zone).totals.count)
    }

    @Test fun typesDogsLegacyAndArchivedDog() {
        val source = listOf(pass(today, "Löpning"), pass(today, "DOG_RUN", dog = "dog"), pass(today, "DOG_WALK", dog = "dog"), pass(today, "WALK"))
        val run = Statistics.calculate(source, listOf(dog), StatisticsPeriod.ALL, StatisticsFilter(ActivityKind.RUN), today, zone)
        assertEquals(1, run.totals.count)
        val dogRun = Statistics.calculate(source, listOf(dog), StatisticsPeriod.ALL, StatisticsFilter(ActivityKind.DOG_RUN, "dog"), today, zone)
        assertEquals(1, dogRun.totals.count)
        assertEquals(1, dogRun.dogs.single().runCount)
        assertEquals("Frasse", dogRun.dogs.single().dog.name)
        assertEquals(2, Statistics.calculate(source, listOf(dog), StatisticsPeriod.ALL, StatisticsFilter(dogId = "dog"), today, zone).totals.count)
    }

    @Test fun totalsWeightedPaceAndRecords() {
        val source = listOf(pass(today, distance = 1000.0, minutes = 4, climb = 10.0), pass(today, distance = 9000.0, minutes = 60, climb = 25.0))
        val result = Statistics.calculate(source, emptyList(), StatisticsPeriod.ALL, today = today, zone = zone)
        assertEquals(10_000.0, result.totals.distanceMeters, 0.01)
        assertEquals(64 * 60_000L, result.totals.durationMillis)
        assertEquals(384.0, result.paceSecondsPerKm!!, 0.01)
        assertEquals(5000.0, result.averageDistanceMeters!!, 0.01)
        assertEquals(9000.0, result.longest!!.distanceM, 0.01)
        assertEquals(35.0, result.elevationGainMeters!!, 0.01)
        assertEquals(25.0, result.highestClimb!!.ascentM!!, 0.01)
    }

    @Test fun weekComparisonAndEmptyData() {
        val result = Statistics.calculate(listOf(pass(today), pass(today.minusWeeks(1))), emptyList(), StatisticsPeriod.ALL, today = today, zone = zone)
        assertEquals(0, Statistics.weekChangePercent(result.thisWeek.distanceMeters, result.lastWeek.distanceMeters))
        assertNull(Statistics.weekChangePercent(1000.0, 0.0))
        val empty = Statistics.calculate(emptyList(), emptyList(), StatisticsPeriod.SEVEN_DAYS, today = today, zone = zone)
        assertEquals(0, empty.totals.count)
        assertNull(empty.paceSecondsPerKm)
        assertNull(empty.longest)
    }

    @Test fun tinyDistancesDoNotDistortPace() {
        val result = Statistics.calculate(listOf(pass(today, distance = 10.0, minutes = 30), pass(today, distance = 1000.0, minutes = 5)), emptyList(), StatisticsPeriod.ALL, today = today, zone = zone)
        assertEquals(300.0, result.paceSecondsPerKm!!, 0.01)
    }

    @Test fun personalRecordsRemainAllTimeWhenPeriodChanges() {
        val source = listOf(pass(today, distance = 1000.0), pass(today.minusDays(40), distance = 12_000.0))
        val result = Statistics.calculate(source, emptyList(), StatisticsPeriod.SEVEN_DAYS, today = today, zone = zone)
        assertEquals(1000.0, result.longest!!.distanceM, 0.01)
        assertEquals(12_000.0, result.recordLongest!!.distanceM, 0.01)
    }

    @Test fun shareImageLabels() {
        val data = ShareImage.text(pass(today, "Löpning", 5240.0, 29), zone)
        assertEquals("LÖPNING", data.title)
        assertEquals("5,24 KM", data.distance)
        assertEquals("29:00", data.duration)
        assertEquals("5:32 /KM", data.pace)
        assertTrue(data.date.contains("2026"))
    }
}
