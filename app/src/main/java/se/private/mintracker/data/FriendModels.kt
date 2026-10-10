package se.oxelv.mintracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FriendRequest(
    val id: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("receiver_id") val receiverId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("responded_at") val respondedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

@Serializable
data class Friendship(
    @SerialName("user_low_id") val userLowId: String,
    @SerialName("user_high_id") val userHighId: String,
    @SerialName("created_at") val createdAt: String? = null
) {
    fun otherUser(myId: String): String? = when (myId) {
        userLowId -> userHighId
        userHighId -> userLowId
        else -> null
    }
}

enum class FriendRelationKind { SELF, NONE, OUTGOING_PENDING, INCOMING_PENDING, FRIENDS }

data class FriendRelation(val kind: FriendRelationKind, val requestId: String? = null)

enum class SocialInboxKind { FRIEND_REQUEST, GROUP_INVITATION, CHALLENGE_INVITATION }

data class SocialInboxItem(
    val kind: SocialInboxKind,
    val requestId: String,
    val person: Profile?,
    val personId: String,
    val createdAt: String?,
    val incoming: Boolean
)

data class FriendListItem(val userId: String, val profile: Profile?)
