package se.oxelv.mintracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.roundToLong

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class CloudActivity(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("activity_type") val activityType: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("avg_pace_seconds_per_km") val avgPaceSecondsPerKm: Double? = null,
    @SerialName("avg_speed_kmh") val avgSpeedKmh: Double? = null,
    @SerialName("elevation_gain_meters") val elevationGainMeters: Double? = null,
    @SerialName("elevation_loss_meters") val elevationLossMeters: Double? = null,
    val steps: Int? = null,
    @SerialName("dog_id") val dogId: String? = null,
    val note: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class CloudNoteState(val note: String? = null, @SerialName("updated_at") val updatedAt: String)

@Serializable
data class CloudActivityIdentity(val id: String)

@Serializable
data class CloudPoint(
    @SerialName("activity_id") val activityId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("sequence_number") val sequenceNumber: Int,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    @SerialName("recorded_at") val recordedAt: String
)

@Serializable
data class CloudSplit(
    @SerialName("activity_id") val activityId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("split_number") val splitNumber: Int,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("pace_seconds_per_km") val paceSecondsPerKm: Double? = null
)

internal fun SplitEntity.toCloudSplit(activityId: String, userId: String): CloudSplit {
    val seconds = durationMs / 1000.0
    return CloudSplit(activityId, userId, kilometer, 1000.0, seconds, seconds)
}

internal fun CloudSplit.toLocalSplit(localActivityId: Long): SplitEntity =
    SplitEntity(localActivityId, splitNumber, (durationSeconds * 1000).roundToLong(), null, null)

internal fun toTimestamptz(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()
internal fun fromTimestamptz(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()
