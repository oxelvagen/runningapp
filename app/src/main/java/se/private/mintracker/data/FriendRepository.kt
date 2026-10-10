package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FriendRepository(private val profiles: ProfileRepository) {
    private fun uid(): String = supabase.auth.currentUserOrNull()?.id ?: error("Du behöver logga in igen.")

    suspend fun relation(otherId: String): FriendRelation {
        val myId = uid()
        if (myId == otherId) return FriendRelation(FriendRelationKind.SELF)
        val low = minOf(myId, otherId)
        val high = maxOf(myId, otherId)
        val friendship = supabase.from("friendships").select {
            filter { eq("user_low_id", low); eq("user_high_id", high) }
        }.decodeSingleOrNull<Friendship>()
        val outgoing = supabase.from("friend_requests").select {
            filter { eq("sender_id", myId); eq("receiver_id", otherId); eq("status", "PENDING") }
        }.decodeList<FriendRequest>()
        val incoming = supabase.from("friend_requests").select {
            filter { eq("sender_id", otherId); eq("receiver_id", myId); eq("status", "PENDING") }
        }.decodeList<FriendRequest>()
        check(uid() == myId) { "Inloggat konto har ändrats." }
        return FriendUiRules.relation(myId, otherId, friendship != null, outgoing + incoming)
    }

    suspend fun inbox(): List<SocialInboxItem> {
        val myId = uid()
        val requests = allPending("receiver_id", myId)
        val profilesById = profiles.visibleProfiles(requests.map { it.senderId })
        check(uid() == myId) { "Inloggat konto har ändrats." }
        return FriendUiRules.incoming(requests, myId).map { request ->
            SocialInboxItem(SocialInboxKind.FRIEND_REQUEST, request.id, profilesById[request.senderId], request.senderId, request.createdAt, true)
        }
    }

    suspend fun sent(): List<SocialInboxItem> {
        val myId = uid()
        val requests = allPending("sender_id", myId)
        val profilesById = profiles.visibleProfiles(requests.map { it.receiverId })
        check(uid() == myId) { "Inloggat konto har ändrats." }
        return FriendUiRules.outgoing(requests, myId).map { request ->
            SocialInboxItem(SocialInboxKind.FRIEND_REQUEST, request.id, profilesById[request.receiverId], request.receiverId, request.createdAt, false)
        }
    }

    suspend fun friends(): List<FriendListItem> {
        val myId = uid()
        val rows = supabase.from("friendships").select {
            filter { or { eq("user_low_id", myId); eq("user_high_id", myId) } }
        }.decodeList<Friendship>()
        val otherIds = rows.mapNotNull { it.otherUser(myId) }.distinct()
        val profilesById = profiles.visibleProfiles(otherIds)
        check(uid() == myId) { "Inloggat konto har ändrats." }
        return otherIds.map { FriendListItem(it, profilesById[it]) }
            .sortedBy { it.profile?.displayName?.lowercase() ?: "" }
    }

    private suspend fun allPending(actorColumn: String, actorId: String): List<FriendRequest> {
        val results = mutableListOf<FriendRequest>()
        var offset = 0L
        while (true) {
            check(uid() == actorId) { "Inloggat konto har ändrats." }
            val page = supabase.from("friend_requests").select {
                filter { eq(actorColumn, actorId); eq("status", "PENDING") }
                order("created_at", Order.DESCENDING)
                range(offset..offset + 99)
            }.decodeList<FriendRequest>()
            results += page
            if (page.size < 100) break
            offset += page.size
        }
        return results
    }

    suspend fun send(receiverId: String) {
        val myId = uid()
        require(receiverId != myId) { "Du kan inte lägga till dig själv som vän." }
        supabase.postgrest.rpc("send_friend_request", buildJsonObject { put("p_receiver_id", receiverId) })
        check(uid() == myId) { "Inloggat konto har ändrats." }
    }

    suspend fun accept(requestId: String) = mutateRequest("accept_friend_request", requestId)
    suspend fun decline(requestId: String) = mutateRequest("decline_friend_request", requestId)
    suspend fun cancel(requestId: String) = mutateRequest("cancel_friend_request", requestId)

    private suspend fun mutateRequest(function: String, requestId: String) {
        val myId = uid()
        supabase.postgrest.rpc(function, buildJsonObject { put("p_request_id", requestId) })
        check(uid() == myId) { "Inloggat konto har ändrats." }
    }

    suspend fun remove(friendId: String) {
        val myId = uid()
        require(friendId != myId) { "Du kan inte ta bort dig själv som vän." }
        supabase.postgrest.rpc("remove_friend", buildJsonObject { put("p_friend_id", friendId) })
        check(uid() == myId) { "Inloggat konto har ändrats." }
    }
}
