package se.oxelv.mintracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SocialGroup(
    val id: String,
    val name: String,
    @SerialName("group_type") val groupType: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class GroupMember(
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String,
    val role: String,
    @SerialName("joined_at") val joinedAt: String? = null
)

@Serializable
data class WeeklyGroupStat(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val username: String? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    @SerialName("activity_count") val activityCount: Long? = null,
    @SerialName("duration_seconds") val durationSeconds: Long? = null
)

data class GroupMemberView(val member: GroupMember, val profile: Profile?)

data class GroupPreview(
    val id: String?,
    val name: String,
    val groupType: String,
    val memberCount: Int,
    val alreadyMember: Boolean,
    val inviteCode: String
)
