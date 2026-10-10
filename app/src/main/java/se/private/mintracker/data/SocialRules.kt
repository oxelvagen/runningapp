package se.oxelv.mintracker.data

import java.security.SecureRandom
import java.util.Locale

object SocialRules {
    private const val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun username(value: String): String? = value.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }

    fun usernameError(value: String): String? {
        val normalized = username(value) ?: return null
        return when {
            normalized.length !in 3..30 -> "Användarnamnet måste vara 3–30 tecken."
            !normalized.matches(Regex("[a-z0-9._]+")) -> "Använd bara bokstäver, siffror, punkt och understreck."
            else -> null
        }
    }

    fun bioError(value: String): String? = if (value.length > 300) "Biografin får vara högst 300 tecken." else null

    fun inviteCode(): String = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }

    fun normalizeCode(value: String): String = value.trim().uppercase(Locale.ROOT)

    fun canJoin(memberIds: Collection<String>, userId: String): Boolean = userId !in memberIds
}
