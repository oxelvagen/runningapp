package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.github.jan.supabase.postgrest.query.Order

class ProfileRepository {
    suspend fun getOrCreate(): Profile {
        val user = supabase.auth.currentUserOrNull() ?: error("Du behöver logga in igen.")
        find(user.id)?.let { return it }

        val name = user.userMetadata?.get("display_name")?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: user.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: "Användare"
        try {
            supabase.from("profiles").insert(NewProfile(user.id, name))
        } catch (error: Exception) {
            // Another client may have created the row after the SELECT.
            find(user.id)?.let { return it }
            throw error
        }
        return find(user.id) ?: error("Profilen skapades men kunde inte läsas. Kontrollera RLS-reglerna.")
    }

    suspend fun updateDisplayName(displayName: String): Profile {
        val user = supabase.auth.currentUserOrNull() ?: error("Du behöver logga in igen.")
        val name = displayName.trim()
        require(name.isNotEmpty()) { "Ange ett visningsnamn." }
        supabase.from("profiles").update({ set("display_name", name) }) {
            filter { eq("id", user.id) }
        }
        val updated = find(user.id) ?: error("Det nya namnet kunde inte läsas. Försök igen.")
        check(updated.displayName == name) { "Det nya namnet sparades inte. Kontrollera RLS-reglerna." }
        return updated
    }

    suspend fun updateSocialProfile(displayName: String, username: String, bio: String, isPublic: Boolean): Profile {
        val user = supabase.auth.currentUserOrNull() ?: error("Du behöver logga in igen.")
        val name = displayName.trim()
        require(name.isNotEmpty()) { "Ange ett visningsnamn." }
        SocialRules.usernameError(username)?.let { throw IllegalArgumentException(it) }
        SocialRules.bioError(bio)?.let { throw IllegalArgumentException(it) }
        val normalized = SocialRules.username(username)
        supabase.from("profiles").update({
            set("display_name", name)
            set("username", normalized)
            set("bio", bio.trim().takeIf { it.isNotEmpty() })
            set("is_public", isPublic)
        }) { filter { eq("id", user.id) } }
        return find(user.id) ?: error("Profilen kunde inte läsas efter uppdatering.")
    }

    suspend fun updateAvatarPath(userId: String, oldPath: String?, newPath: String?): Profile {
        require(supabase.auth.currentUserOrNull()?.id == userId) { "Inloggat konto har ändrats." }
        check(find(userId)?.avatarPath == oldPath) { "Profilbilden ändrades på en annan enhet. Försök igen." }
        supabase.from("profiles").update({
            set("avatar_path", newPath)
            if (newPath == null) set("avatar_url", null as String?)
        }) {
            filter {
                eq("id", userId)
                if (oldPath != null) eq("avatar_path", oldPath)
            }
        }
        val updated = find(userId) ?: error("Profilen kunde inte läsas efter bildändringen.")
        check(updated.avatarPath == newPath) { "Profilbilden ändrades på en annan enhet. Försök igen." }
        return updated
    }

    suspend fun publicProfile(id: String): Profile? {
        val actor = supabase.auth.currentUserOrNull()?.id ?: throw AccountChangedException()
        val result = supabase.from("profiles").select { filter { eq("id", id) } }.decodeSingleOrNull<Profile>()
        if (supabase.auth.currentUserOrNull()?.id != actor) throw AccountChangedException()
        return result
    }

    suspend fun searchPublic(query: String, page: Int, pageSize: Int = 25): List<Profile> {
        val safeQuery = query.trim().replace("%", "").replace("_", "").replace(",", "")
        return supabase.from("profiles").select {
            filter {
                eq("is_public", true)
                if (safeQuery.isNotEmpty()) or {
                    ilike("display_name", "%$safeQuery%")
                    ilike("username", "%$safeQuery%")
                }
            }
            order("display_name", Order.ASCENDING)
            range(page * pageSize.toLong(), (page + 1) * pageSize.toLong() - 1)
        }.decodeList<Profile>()
    }

    suspend fun visibleProfiles(ids: List<String>): Map<String, Profile> {
        if (ids.isEmpty()) return emptyMap()
        return supabase.from("profiles").select { filter { isIn("id", ids) } }
            .decodeList<Profile>().associateBy { it.id }
    }

    private suspend fun find(userId: String): Profile? = supabase.from("profiles")
        .select { filter { eq("id", userId) } }
        .decodeSingleOrNull<Profile>()
}
