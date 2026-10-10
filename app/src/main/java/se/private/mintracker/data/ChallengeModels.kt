package se.oxelv.mintracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Serializable
data class ChallengeSummary(
    val id: String,
    val title: String,
    @SerialName("metric_type") val metricType: String,
    @SerialName("target_value") val targetValue: Double,
    val scope: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("participant_count") val participantCount: Int,
    val joined: Boolean,
    @SerialName("challenge_origin") val origin: String = "USER",
    val status: String = "OPEN"
)

@Serializable
data class ChallengeDetail(
    val id: String,
    @SerialName("creator_id") val creatorId: String,
    @SerialName("group_id") val groupId: String? = null,
    val title: String,
    val description: String? = null,
    @SerialName("metric_type") val metricType: String,
    @SerialName("target_value") val targetValue: Double,
    val scope: String,
    @SerialName("time_zone") val timeZone: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("activity_types") val activityTypes: List<String>,
    val joined: Boolean,
    @SerialName("challenge_origin") val origin: String = "USER",
    val status: String = "OPEN"
)

@Serializable
data class ChallengeRank(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val username: String? = null,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("progress_value") val progressValue: Double,
    @SerialName("target_value") val targetValue: Double,
    @SerialName("progress_percent") val progressPercent: Double,
    val completed: Boolean,
    val rank: Int
)

@Serializable
data class ChallengeInvitation(
    val id: String,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("challenge_title") val challengeTitle: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("receiver_id") val receiverId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("display_name") val displayName: String? = null,
    val username: String? = null,
    @SerialName("avatar_path") val avatarPath: String? = null
)

data class NewChallenge(
    val title: String,
    val description: String,
    val metricType: String,
    val targetValue: Double,
    val activityTypes: Set<String>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val timeZone: String,
    val scope: String,
    val groupId: String?
)

object ChallengeRules {
    val metrics = listOf("DISTANCE", "ACTIVITY_COUNT", "DURATION", "ELEVATION_GAIN")
    val activityTypes = listOf("RUN", "WALK", "DOG_RUN", "DOG_WALK")

    fun state(startAt: String, endAt: String, now: Instant = Instant.now(), status: String = "OPEN"): String = when {
        status != "OPEN" -> status
        now.isBefore(java.time.OffsetDateTime.parse(startAt).toInstant()) -> "UPCOMING"
        !now.isBefore(java.time.OffsetDateTime.parse(endAt).toInstant()) -> "COMPLETED"
        else -> "ACTIVE"
    }

    fun unit(metric: String) = when (metric) {
        "DISTANCE", "LONGEST_ACTIVITY" -> "km"; "FASTEST_5K" -> "min/km"; "DURATION" -> "h"; "ACTIVITY_COUNT" -> "pass"; else -> "höjdmeter"
    }

    fun toBase(metric: String, displayed: Double): Double = when (metric) {
        "DISTANCE" -> displayed * 1000.0
        "DURATION" -> displayed * 3600.0
        else -> displayed
    }

    fun format(metric: String, base: Double): String {
        if (metric == "FASTEST_5K") {
            if (base <= 0) return "— min/km"
            val seconds = kotlin.math.round(1000 / base).toLong()
            return "%d:%02d min/km".format(seconds / 60, seconds % 60)
        }
        val shown = when (metric) { "DISTANCE", "LONGEST_ACTIVITY" -> base / 1000.0; "DURATION" -> base / 3600.0; else -> base }
        val decimals = if (metric == "ACTIVITY_COUNT") 0 else 1
        return String.format(Locale.forLanguageTag("sv-SE"), "%.${decimals}f %s", shown, unit(metric))
    }

    fun percent(value: Double, target: Double): Double = if (target > 0) 100.0 * value / target else 0.0

    fun validate(value: NewChallenge): String? = when {
        value.title.trim().length !in 1..100 -> "Namnet ska vara 1–100 tecken."
        value.description.length > 500 -> "Beskrivningen får vara högst 500 tecken."
        value.metricType !in metrics -> "Välj ett mål."
        value.targetValue <= 0 || !value.targetValue.isFinite() -> "Målet måste vara större än noll."
        value.metricType == "ACTIVITY_COUNT" && value.targetValue % 1 != 0.0 -> "Antal pass måste vara ett heltal."
        value.activityTypes.isEmpty() || !activityTypes.containsAll(value.activityTypes) -> "Välj minst en aktivitetstyp."
        value.endDate.isBefore(value.startDate) -> "Slutdatum måste vara samma dag eller senare än startdatum."
        java.time.temporal.ChronoUnit.DAYS.between(value.startDate, value.endDate) > 365 -> "Perioden får vara högst 366 dagar."
        runCatching { ZoneId.of(value.timeZone) }.isFailure -> "Ogiltig tidszon."
        value.scope !in listOf("PRIVATE", "FRIENDS", "GROUP") -> "Välj utmaningstyp."
        (value.scope == "GROUP") != (value.groupId != null) -> "Välj en grupp."
        else -> null
    }
}
