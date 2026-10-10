package se.oxelv.mintracker.data

import kotlinx.serialization.json.Json

/** Supabase RPC JSON contracts: detail is an object; set-returning RPCs are arrays. */
internal object ChallengeRpcPayloads {
    fun detail(raw: String): ChallengeDetail = Json.decodeFromString(raw)
    fun summaries(raw: String): List<ChallengeSummary> = Json.decodeFromString(raw)
    fun leaderboard(raw: String): List<ChallengeRank> = Json.decodeFromString(raw)
    fun invitations(raw: String): List<ChallengeInvitation> = Json.decodeFromString(raw)
    fun createdId(raw: String): String = Json.decodeFromString(raw)
}
