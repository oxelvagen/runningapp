package se.oxelv.mintracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.util.Locale

@Serializable data class NotificationCursor(@SerialName("created_at") val createdAt: String, val id: String)
@Serializable data class NotificationPage(val items: List<TrackerNotification> = emptyList(),
    @SerialName("unread_count") val unreadCount: Long = 0,
    @SerialName("next_cursor") val nextCursor: NotificationCursor? = null)
@Serializable data class TrackerNotification(val id: String,
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("event_type") val eventType: String,
    @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
    val payload: NotificationPayload = NotificationPayload())
@Serializable data class NotificationPayload(
    @SerialName("display_name") val name: String? = null,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("is_npc") val isNpc: Boolean = false,
    @SerialName("activity_type") val activityType: String? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    @SerialName("dog_name") val dogName: String? = null,
    @SerialName("challenge_title") val challengeTitle: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    val title: String? = null, val body: String? = null)
@Serializable data class NotificationTarget(val available: Boolean,
    @SerialName("unread_count") val unreadCount: Long,
    @SerialName("target_type") val type: String? = null,
    @SerialName("target_id") val id: String? = null,
    @SerialName("actor_id") val actorId: String? = null)
@Serializable data class NotificationPreferences(val categories: Map<String, Boolean>, val revision: Long = 0)
@Serializable data class SharedNotificationActivity(val id: String,
    @SerialName("actor_id") val actorId: String,
    @SerialName("display_name") val name: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("is_npc") val isNpc: Boolean = false,
    @SerialName("activity_type") val kind: String,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("duration_seconds") val durationSeconds: Long,
    @SerialName("dog_name") val dogName: String? = null)

object NotificationText {
    val categories = linkedMapOf("activities" to "Vänners träningspass", "records" to "Personliga rekord",
        "new_challenges" to "Nya utmaningar", "completed_challenges" to "Avklarade utmaningar",
        "cheers" to "Hejarop", "friends" to "Vänförfrågningar", "groups" to "Grupphändelser")
    fun badge(count: Long): String? = when { count <= 0 -> null; count > 99 -> "99+"; else -> count.toString() }
    fun km(meters: Double) = String.format(Locale.forLanguageTag("sv-SE"), "%.1f km", meters / 1000)
    fun relative(createdAt: String, now: Instant = Instant.now()): String {
        val seconds = runCatching { Duration.between(Instant.parse(createdAt), now).seconds.coerceAtLeast(0) }.getOrNull() ?: return "Tid saknas"
        return when { seconds < 60 -> "Just nu"; seconds < 3600 -> "${seconds / 60} min sedan"
            seconds < 86400 -> "${seconds / 3600} tim sedan"; seconds < 604800 -> "${seconds / 86400} d sedan"
            else -> "${seconds / 604800} v sedan" }
    }
    fun description(n: TrackerNotification): String = with(n.payload) {
        when(n.eventType) {
            "ACTIVITY_COMPLETED" -> when(activityType) {
                "DOG_WALK" -> "gick ${km(distanceMeters ?: 0.0)} med ${dogName?.let { "hunden $it" } ?: "sin hund"}."
                "DOG_RUN" -> "sprang ${km(distanceMeters ?: 0.0)} med ${dogName?.let { "hunden $it" } ?: "sin hund"}."
                "WALK" -> "genomförde en promenad på ${km(distanceMeters ?: 0.0)}."
                else -> "genomförde ett löppass på ${km(distanceMeters ?: 0.0)}."
            }
            "PERSONAL_RECORD" -> "slog sitt personliga rekord i längsta löprunda!"
            "CHALLENGE_CREATED" -> "skapade utmaningen ${challengeTitle ?: "en ny utmaning"}."
            "CHALLENGE_COMPLETED" -> "klarade utmaningen ${challengeTitle ?: title ?: "en utmaning"}!"
            "CHEER" -> "gav dig ett hejarop för ${if(n.targetType == "CHALLENGE") "din utmaning" else "ditt träningspass"}!"
            "FRIEND_REQUEST" -> "vill bli vän med dig."
            "FRIEND_ACCEPTED" -> "accepterade din vänförfrågan."
            "GROUP_JOINED" -> "Du har blivit medlem i gruppen ${groupName ?: "din grupp"}."
            "GROUP_MEMBER_JOINED" -> "gick med i gruppen ${groupName ?: "din grupp"}."
            else -> listOfNotNull(title, body).joinToString("\n").ifBlank { "Du har en ny notis." }
        }
    }
}
