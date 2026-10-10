package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable data class SocialPrivacy(
    @SerialName("auto_share_new_activities") val autoShare: Boolean = false,
    @SerialName("show_profile_statistics") val showStatistics: Boolean = false,
    val revision: Long = 0
)
@Serializable data class ActivitySharing(val visibility: String = "PRIVATE",
    @SerialName("share_dog_name") val shareDogName: Boolean = false, val revision: Long = 0)
@Serializable data class SocialCursor(@SerialName("occurred_at") val occurredAt: String, val id: String)
@Serializable data class SocialFeedPage(val items: List<SocialEvent> = emptyList(),
    @SerialName("next_cursor") val nextCursor: SocialCursor? = null)
@Serializable data class SocialEvent(
    val id: String, @SerialName("event_type") val eventType: String,
    @SerialName("actor_id") val actorId: String, @SerialName("display_name") val displayName: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("activity_type") val activityType: String? = null,
    @SerialName("dog_name") val dogName: String? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Long? = null,
    @SerialName("pace_seconds_per_km") val paceSecondsPerKm: Double? = null,
    @SerialName("record_type") val recordType: String? = null,
    @SerialName("challenge_id") val challengeId: String? = null,
    @SerialName("challenge_title") val challengeTitle: String? = null,
    @SerialName("cheer_count") val cheerCount: Long = 0,
    @SerialName("cheered_by_me") val cheeredByMe: Boolean = false
)
@Serializable data class SocialCheer(@SerialName("cheer_count") val count: Long,
    @SerialName("cheered_by_me") val mine: Boolean)
@Serializable data class PublicStatistics(
    val status: String,
    @SerialName("activity_count") val count: Long = 0,
    @SerialName("distance_meters") val distanceMeters: Double = 0.0,
    @SerialName("duration_seconds") val durationSeconds: Long = 0,
    @SerialName("run_count") val runCount: Long = 0,
    @SerialName("walk_count") val walkCount: Long = 0,
    @SerialName("distance_last_7_days") val last7: Double = 0.0,
    @SerialName("distance_last_30_days") val last30: Double = 0.0,
    @SerialName("run_pace_seconds_per_km") val runPace: Double? = null,
    @SerialName("longest_run_meters") val longestRun: Double = 0.0,
    @SerialName("completed_challenge_count") val completedChallenges: Long = 0,
    val weeks: List<PublicStatisticsWeek> = emptyList()
)
@Serializable data class PublicStatisticsWeek(@SerialName("week_start") val weekStart: String,
    @SerialName("distance_meters") val distanceMeters: Double, @SerialName("activity_count") val count: Long)

/** Only revision counters are retained globally; social content lives inside the account's composition. */
object SocialChanges {
    private val mutable = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revisions = mutable.asStateFlow()
    fun changed(userId: String) { mutable.update { it + (userId to ((it[userId] ?: 0) + 1)) } }
}

class SocialFeedRepository(private val userId: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun ensureActor() { if (supabase.auth.currentUserOrNull()?.id != userId) throw AccountChangedException() }
    private suspend inline fun <reified T> read(name: String, params: JsonObject = buildJsonObject {}): T {
        ensureActor()
        val result = supabase.postgrest.rpc(name, params)
        ensureActor()
        return json.decodeFromString<T>(result.data)
    }
    suspend fun privacy(): SocialPrivacy = read("get_social_privacy")
    suspend fun setPrivacy(value: SocialPrivacy, autoShare: Boolean, statistics: Boolean): SocialPrivacy {
        val result: SocialPrivacy = read("set_social_privacy", buildJsonObject {
            put("p_auto_share", autoShare); put("p_show_statistics", statistics); put("p_expected_revision", value.revision)
        })
        SocialChanges.changed(userId)
        return result
    }
    suspend fun sharing(id: String): ActivitySharing = read("get_activity_sharing", buildJsonObject { put("p_activity_id", id) })
    suspend fun setSharing(id: String, value: ActivitySharing, friends: Boolean, dogName: Boolean): ActivitySharing {
        val result: ActivitySharing = read("set_activity_sharing", buildJsonObject {
            put("p_activity_id", id); put("p_visibility", if (friends) "FRIENDS" else "PRIVATE")
            put("p_share_dog_name", dogName); put("p_expected_revision", value.revision)
        })
        SocialChanges.changed(userId)
        return result
    }
    suspend fun sync(activity: CloudActivity) {
        ensureActor()
        supabase.postgrest.rpc("sync_social_activity", buildJsonObject { put("p_activity", json.encodeToJsonElement(activity)) })
        ensureActor()
    }
    suspend fun feed(cursor: SocialCursor? = null): SocialFeedPage = read("get_social_feed", buildJsonObject {
        put("p_limit", 20)
        cursor?.let { put("p_before_at", it.occurredAt); put("p_before_id", it.id) }
    })
    suspend fun cheer(id: String, cheered: Boolean): SocialCheer = read("set_social_cheer", buildJsonObject {
        put("p_event_id", id); put("p_cheered", cheered)
    })
    suspend fun statistics(id: String): PublicStatistics = read("get_public_profile_statistics", buildJsonObject { put("p_profile_id", id) })
}
