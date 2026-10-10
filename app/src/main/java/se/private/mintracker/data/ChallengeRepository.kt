package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.SerializationException

/** All challenge reads and writes go through server RPCs; no table access is granted. */
class ChallengeRepository {
    private fun actor(): String = supabase.auth.currentUserOrNull()?.id ?: error("Du behöver logga in igen.")
    private fun ensureActor(id: String) { check(actor() == id) { "Inloggat konto har ändrats." } }

    suspend fun manageableGroupIds(): Set<String> {
        val id = actor()
        val memberships = supabase.from("group_members").select { filter { eq("user_id", id) } }.decodeList<GroupMember>()
        ensureActor(id)
        return memberships.filter { it.role == "OWNER" || it.role == "ADMIN" }.map { it.groupId }.toSet()
    }

    suspend fun mine(): List<ChallengeSummary> {
        val id = actor()
        val result = versionedRead("get_my_challenges")
        ensureActor(id)
        return ChallengeRpcPayloads.summaries(result.data)
    }

    suspend fun detail(challengeId: String): ChallengeDetail {
        val id = actor()
        val result = versionedRead("get_challenge_detail", buildJsonObject { put("p_challenge_id", challengeId) })
        ensureActor(id)
        return ChallengeRpcPayloads.detail(result.data)
    }

    suspend fun leaderboard(challengeId: String): List<ChallengeRank> {
        val id = actor()
        val result = supabase.postgrest.rpc("get_challenge_leaderboard", buildJsonObject { put("p_challenge_id", challengeId) })
        ensureActor(id)
        return ChallengeRpcPayloads.leaderboard(result.data)
    }

    private suspend fun versionedRead(name: String, params: kotlinx.serialization.json.JsonObject = buildJsonObject {}) = try {
        supabase.postgrest.rpc("${name}_v2", params)
    } catch (error: Exception) {
        // Allow the APK to be installed before the additive backend migration.
        if ("PGRST202" !in error.message.orEmpty()) throw error
        supabase.postgrest.rpc(name, params)
    }

    suspend fun invitations(sent: Boolean): List<ChallengeInvitation> {
        val id = actor()
        val result = supabase.postgrest.rpc("get_challenge_invitations", buildJsonObject { put("p_sent", sent) })
        ensureActor(id)
        return ChallengeRpcPayloads.invitations(result.data)
    }

    suspend fun create(value: NewChallenge): String {
        val id = actor()
        ChallengeRules.validate(value)?.let { throw IllegalArgumentException(it) }
        val params = buildJsonObject {
            put("p_title", value.title.trim())
            put("p_description", value.description.trim().ifBlank { null })
            put("p_metric_type", value.metricType)
            put("p_target_value", ChallengeRules.toBase(value.metricType, value.targetValue))
            putJsonArray("p_activity_types") { value.activityTypes.sorted().forEach { add(it) } }
            put("p_start_date", value.startDate.toString())
            put("p_end_date", value.endDate.toString())
            put("p_time_zone", value.timeZone)
            put("p_scope", value.scope)
            put("p_group_id", value.groupId)
        }
        val result = supabase.postgrest.rpc("create_challenge", params)
        ensureActor(id)
        return ChallengeRpcPayloads.createdId(result.data)
    }

    suspend fun invite(challengeId: String, friendId: String) = mutate("invite_to_challenge") {
        put("p_challenge_id", challengeId); put("p_receiver_id", friendId)
    }
    suspend fun accept(invitationId: String) = mutate("accept_challenge_invitation") { put("p_invitation_id", invitationId) }
    suspend fun decline(invitationId: String) = mutate("decline_challenge_invitation") { put("p_invitation_id", invitationId) }
    suspend fun cancel(invitationId: String) = mutate("cancel_challenge_invitation") { put("p_invitation_id", invitationId) }
    suspend fun join(challengeId: String) = mutate("join_group_challenge") { put("p_challenge_id", challengeId) }
    suspend fun leave(challengeId: String) = mutate("leave_challenge") { put("p_challenge_id", challengeId) }
    suspend fun delete(challengeId: String) = mutate("delete_challenge") { put("p_challenge_id", challengeId) }

    private suspend fun mutate(name: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val id = actor()
        supabase.postgrest.rpc(name, buildJsonObject(fields))
        ensureActor(id)
    }
}

fun challengeErrorMessage(error: Exception): String {
    if (error is SerializationException) return "Utmaningens data kunde inte läsas. Uppdatera sidan och försök igen."
    if (error is IllegalArgumentException) return error.message ?: "Kontrollera uppgifterna."
    val message = error.message.orEmpty().lowercase()
    return when {
        "network" in message || "timeout" in message || "connect" in message -> "Ingen anslutning. Kontrollera internet och försök igen."
        "jwt" in message || "401" in message -> "Inloggningen har gått ut. Logga in igen."
        "42501" in message || "403" in message -> "Utmaningen eller inbjudan är inte längre tillgänglig för dig. Uppdatera sidan."
        "23505" in message -> "Den här personen är redan inbjuden eller deltar redan."
        else -> "Det gick inte att slutföra åtgärden. Försök igen."
    }
}
