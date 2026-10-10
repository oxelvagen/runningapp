package se.oxelv.mintracker.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToLong

enum class StatisticsPeriod(val label: String) {
    SEVEN_DAYS("7 dagar"), THIRTY_DAYS("30 dagar"), THREE_MONTHS("3 månader"),
    YEAR("År"), ALL("All tid")
}

data class StatisticsFilter(val kind: ActivityKind? = null, val dogId: String? = null)
data class ChartBucket(val label: String, val distanceMeters: Double, val durationMs: Long, val count: Int = 0)
data class DogStatistics(val dog: DogEntity, val distanceMeters: Double, val count: Int, val runCount: Int, val walkCount: Int)
data class StatisticsResult(
    val activities: List<ActivityEntity>,
    val totals: ActivityTotals,
    val paceSecondsPerKm: Double?,
    val averageDistanceMeters: Double?,
    val longest: ActivityEntity?,
    val elevationGainMeters: Double?,
    val buckets: List<ChartBucket>,
    val thisWeek: ActivityTotals,
    val lastWeek: ActivityTotals,
    val longestDuration: ActivityEntity?,
    val highestClimb: ActivityEntity?,
    val recordLongest: ActivityEntity?,
    val recordDuration: ActivityEntity?,
    val recordClimb: ActivityEntity?,
    val dogs: List<DogStatistics>
)

object Statistics {
    private const val MIN_PACE_DISTANCE_M = 100.0

    fun calculate(
        source: List<ActivityEntity>, dogs: List<DogEntity>, period: StatisticsPeriod,
        filter: StatisticsFilter = StatisticsFilter(), today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): StatisticsResult {
        val owned = source.filter { ActivityKind.fromStored(it.kind) != null }
        val matching = owned.filter {
            (filter.kind == null || ActivityKind.fromStored(it.kind) == filter.kind) &&
                (filter.dogId == null || it.dogId == filter.dogId)
        }
        val start = startOf(period, today)
        val selected = matching.filter {
            val date = Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
            start == null || !date.isBefore(start)
        }
        val paceEligible = selected.filter { it.distanceM >= MIN_PACE_DISTANCE_M && it.activeMs > 0 }
        val paceDistance = paceEligible.sumOf { it.distanceM }
        val pace = if (paceDistance > 0) paceEligible.sumOf { it.activeMs }.toDouble() / paceDistance else null
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun weekTotals(from: LocalDate) = ActivitySummary.totals(matching.filter {
            val date = Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
            !date.isBefore(from) && date.isBefore(from.plusDays(7))
        })
        val climbs = selected.filter { it.ascentM != null }
        val dogStats = dogs.map { dog ->
            val passes = selected.filter { it.dogId == dog.id }
            DogStatistics(
                dog, passes.sumOf { it.distanceM }, passes.size,
                passes.count { ActivityKind.fromStored(it.kind) == ActivityKind.DOG_RUN },
                passes.count { ActivityKind.fromStored(it.kind) == ActivityKind.DOG_WALK }
            )
        }.filter { it.count > 0 }
        return StatisticsResult(
            selected, ActivitySummary.totals(selected), pace,
            selected.takeIf { it.isNotEmpty() }?.sumOf { it.distanceM }?.div(selected.size),
            selected.maxByOrNull { it.distanceM },
            climbs.takeIf { it.isNotEmpty() }?.sumOf { it.ascentM ?: 0.0 },
            buckets(selected, period, today, zone),
            weekTotals(monday), weekTotals(monday.minusWeeks(1)),
            selected.maxByOrNull { it.activeMs },
            climbs.maxByOrNull { it.ascentM ?: 0.0 },
            matching.maxByOrNull { it.distanceM },
            matching.maxByOrNull { it.activeMs },
            matching.filter { it.ascentM != null }.maxByOrNull { it.ascentM ?: 0.0 },
            dogStats
        )
    }

    fun weekChangePercent(currentMeters: Double, previousMeters: Double): Int? =
        if (previousMeters <= 0) null else (((currentMeters - previousMeters) / previousMeters) * 100).toInt()

    private fun startOf(period: StatisticsPeriod, today: LocalDate): LocalDate? = when (period) {
        StatisticsPeriod.SEVEN_DAYS -> today.minusDays(6)
        StatisticsPeriod.THIRTY_DAYS -> today.minusDays(29)
        StatisticsPeriod.THREE_MONTHS -> today.minusMonths(3).plusDays(1)
        StatisticsPeriod.YEAR -> today.withDayOfYear(1)
        StatisticsPeriod.ALL -> null
    }

    private fun buckets(items: List<ActivityEntity>, period: StatisticsPeriod, today: LocalDate, zone: ZoneId): List<ChartBucket> {
        val dates = items.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        val first = dates.minOrNull() ?: today
        val start = startOf(period, today) ?: first
        val mode = when (period) {
            StatisticsPeriod.SEVEN_DAYS -> "day"
            StatisticsPeriod.THIRTY_DAYS, StatisticsPeriod.THREE_MONTHS -> "week"
            StatisticsPeriod.YEAR -> "month"
            StatisticsPeriod.ALL -> if (ChronoUnit.DAYS.between(first, today) <= 31) "week" else if (ChronoUnit.MONTHS.between(first, today) <= 24) "month" else "year"
        }
        fun key(date: LocalDate): LocalDate = when (mode) {
            "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            "month" -> date.withDayOfMonth(1)
            "year" -> date.withDayOfYear(1)
            else -> date
        }
        val byKey = items.groupBy { key(Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()) }
        val result = mutableListOf<ChartBucket>()
        var cursor = key(start)
        val end = key(today)
        while (!cursor.isAfter(end)) {
            val group = byKey[cursor].orEmpty()
            val label = when (mode) {
                "day" -> "${cursor.dayOfMonth}/${cursor.monthValue}"
                "week" -> "${cursor.dayOfMonth}/${cursor.monthValue}"
                "month" -> YearMonth.from(cursor).month.getDisplayName(java.time.format.TextStyle.SHORT, Locale("sv", "SE"))
                else -> cursor.year.toString()
            }
            result += ChartBucket(label, group.sumOf { it.distanceM }, group.sumOf { it.activeMs }, group.size)
            cursor = when (mode) { "week" -> cursor.plusWeeks(1); "month" -> cursor.plusMonths(1); "year" -> cursor.plusYears(1); else -> cursor.plusDays(1) }
        }
        return result
    }

    fun paceText(secondsPerKm: Double?): String {
        if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm <= 0) return "–"
        val seconds = secondsPerKm.roundToLong()
        return "%d:%02d min/km".format(seconds / 60, seconds % 60)
    }
}
