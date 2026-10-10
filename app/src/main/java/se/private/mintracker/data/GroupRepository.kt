package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GroupRepository(private val profiles: ProfileRepository) {
    private fun uid(): String = supabase.auth.currentUserOrNull()?.id ?: error("Du behöver logga in igen.")

    suspend fun myGroups(): List<SocialGroup> {
        val userId = uid()
        val memberships = supabase.from("group_members").select { filter { eq("user_id", userId) } }
            .decodeList<GroupMember>()
        if (memberships.isEmpty()) return emptyList()
        return supabase.from("groups").select { filter { isIn("id", memberships.map { it.groupId }) } }
            .decodeList<SocialGroup>()
    }

    suspend fun create(name: String, type: String): SocialGroup {
        uid()
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Ange ett gruppnamn." }
        require(type == "GROUP" || type == "FAMILY") { "Välj grupp eller familj." }
        var lastError: Exception? = null
        repeat(5) {
            val params = buildJsonObject {
                put("p_name", cleanName)
                put("p_group_type", type)
                put("p_invite_code", SocialRules.inviteCode())
            }
            try {
                val response = supabase.postgrest.rpc("create_group", params)
                val groupId = Json.decodeFromString<String>(response.data)
                return detail(groupId) ?: error("Gruppen skapades men kunde inte läsas. Kontrollera gruppens RLS-regler.")
            } catch (error: Exception) {
                lastError = error
                if (!GroupStatsRules.isInviteCodeConflict(error.message.orEmpty())) throw error
            }
        }
        throw IllegalStateException("Kunde inte skapa en unik inbjudningskod. Försök igen.", lastError)
    }

    suspend fun weeklyStats(groupId: String): List<WeeklyGroupStat> {
        uid()
        return GroupStatsRules.sort(supabase.postgrest.rpc("get_group_weekly_stats", buildJsonObject { put("p_group_id", groupId) })
            .decodeList<WeeklyGroupStat>())
    }

    suspend fun preview(code: String): GroupPreview? {
        val actor = uid()
        val normalized = SocialRules.normalizeCode(code)
        require(normalized.length == 6) { "Ange en kod med sex tecken." }
        val result = supabase.postgrest.rpc("preview_group_by_invite_code", buildJsonObject { put("p_invite_code", normalized) })
        check(uid() == actor) { "Inloggat konto har ändrats." }
        return GroupPreviewParser.parse(result.data, normalized)
    }

    suspend fun join(code: String) {
        val actor = uid()
        supabase.postgrest.rpc("join_group_by_invite_code", buildJsonObject { put("p_invite_code", SocialRules.normalizeCode(code)) })
        check(uid() == actor) { "Inloggat konto har ändrats." }
    }

    suspend fun detail(groupId: String): SocialGroup? {
        uid()
        return supabase.from("groups").select { filter { eq("id", groupId) } }
            .decodeSingleOrNull<SocialGroup>()
    }

    suspend fun members(groupId: String): List<GroupMember> {
        uid()
        return supabase.from("group_members").select { filter { eq("group_id", groupId) } }
            .decodeList<GroupMember>()
    }

    suspend fun memberViews(groupId: String): List<GroupMemberView> {
        val members = members(groupId)
        val profilesById = profiles.visibleProfiles(members.map { it.userId })
        return members.map { GroupMemberView(it, profilesById[it.userId]) }
    }

    suspend fun leave(groupId: String) {
        val actor = uid()
        val role = members(groupId).firstOrNull { it.userId == actor }?.role
        require(GroupActionRules.canLeave(role)) {
            if (role == "OWNER") "Överför ägarskapet eller radera gruppen innan du lämnar den."
            else "Du är inte längre medlem i gruppen."
        }
        supabase.postgrest.rpc("leave_group", buildJsonObject { put("p_group_id", groupId) })
        check(uid() == actor) { "Inloggat konto har ändrats." }
    }

    suspend fun removeMember(groupId: String, targetId: String) {
        val actor = uid()
        require(actor != targetId) { "Du kan inte ta bort dig själv som medlem." }
        supabase.postgrest.rpc("remove_group_member", buildJsonObject { put("p_group_id", groupId); put("p_user_id", targetId) })
        check(uid() == actor) { "Inloggat konto har ändrats." }
    }

    suspend fun transferOwnership(groupId: String, targetId: String) {
        val actor = uid()
        require(actor != targetId) { "Välj en annan medlem som ny ägare." }
        supabase.postgrest.rpc("transfer_group_ownership", buildJsonObject { put("p_group_id", groupId); put("p_new_owner_id", targetId) })
        check(uid() == actor) { "Inloggat konto har ändrats." }
    }

    suspend fun delete(groupId: String) {
        val actor = uid()
        require(members(groupId).any { it.userId == actor && it.role == "OWNER" }) { "Bara gruppens ägare kan radera gruppen." }
        supabase.postgrest.rpc("delete_group", buildJsonObject { put("p_group_id", groupId) })
        check(uid() == actor) { "Inloggat konto har ändrats." }
    }
}
