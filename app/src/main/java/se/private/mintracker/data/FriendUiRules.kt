package se.oxelv.mintracker.data

import java.time.Instant
import java.time.OffsetDateTime

object FriendUiRules {
    fun isActivePending(request: FriendRequest, now: Instant = Instant.now()): Boolean =
        request.status == "PENDING" && (request.expiresAt == null || runCatching { OffsetDateTime.parse(request.expiresAt).toInstant().isAfter(now) }.getOrDefault(false))

    fun relation(myId: String, otherId: String, isFriend: Boolean, requests: List<FriendRequest>): FriendRelation {
        if (myId == otherId) return FriendRelation(FriendRelationKind.SELF)
        if (isFriend) return FriendRelation(FriendRelationKind.FRIENDS)
        val pending = requests.firstOrNull { isActivePending(it) &&
            ((it.senderId == myId && it.receiverId == otherId) || (it.senderId == otherId && it.receiverId == myId)) }
            ?: return FriendRelation(FriendRelationKind.NONE)
        return if (pending.senderId == myId) FriendRelation(FriendRelationKind.OUTGOING_PENDING, pending.id)
        else FriendRelation(FriendRelationKind.INCOMING_PENDING, pending.id)
    }

    fun incoming(requests: List<FriendRequest>, myId: String): List<FriendRequest> =
        requests.filter { isActivePending(it) && it.receiverId == myId }

    fun outgoing(requests: List<FriendRequest>, myId: String): List<FriendRequest> =
        requests.filter { isActivePending(it) && it.senderId == myId }

    fun canApplyResponse(requestUserId: String?, currentUserId: String?): Boolean =
        requestUserId != null && requestUserId == currentUserId
}
