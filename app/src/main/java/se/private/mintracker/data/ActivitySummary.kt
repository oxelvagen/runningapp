package se.oxelv.mintracker.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class ActivityTotals(val count: Int, val distanceMeters: Double, val durationMillis: Long)
data class WeeklyActivitySummary(val totals: ActivityTotals, val dailyDistanceMeters: List<Double>)

object ActivitySummary {
    fun totals(activities: List<ActivityEntity>): ActivityTotals = ActivityTotals(
        count = activities.size,
        distanceMeters = activities.sumOf { it.distanceM },
        durationMillis = activities.sumOf { it.activeMs }
    )

    fun week(activities: List<ActivityEntity>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): WeeklyActivitySummary {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        val weekly = activities.filter {
            val date = Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
            !date.isBefore(monday) && !date.isAfter(sunday)
        }
        val daily = MutableList(7) { 0.0 }
        weekly.forEach {
            val date = Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
            daily[date.dayOfWeek.value - 1] += it.distanceM
        }
        return WeeklyActivitySummary(totals(weekly), daily)
    }
}
