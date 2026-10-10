package se.oxelv.mintracker.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

class AuthRepository {
    suspend fun signIn(email: String, password: String) {
        if (SecureSessions.store.biometricEnabled) AppSecurity.usePassword()
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    /** True means Supabase is waiting for email confirmation. */
    suspend fun signUp(displayName: String, email: String, password: String): Boolean {
        if (SecureSessions.store.biometricEnabled) AppSecurity.usePassword()
        supabase.auth.signUpWith(Email, redirectUrl = AuthRedirect.URL) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject { put("display_name", displayName.trim()) }
        }
        val needsConfirmation = supabase.auth.currentSessionOrNull() == null
        if (!needsConfirmation) AppSecurity.loggedOut()
        return needsConfirmation
    }

    suspend fun resetPassword(email: String) = supabase.auth.resetPasswordForEmail(email.trim(), redirectUrl = AuthRedirect.URL)

    suspend fun signOut() {
        try { supabase.auth.signOut() }
        finally { supabase.auth.clearSession(); AppSecurity.loggedOut() }
    }
}

fun authErrorMessage(error: Exception): String {
    val detail = error.message.orEmpty().lowercase()
    return when {
        error is IOException || "network" in detail || "connect" in detail || "timeout" in detail ->
            "Ingen anslutning. Kontrollera internet och försök igen."
        "email not confirmed" in detail || "email_not_confirmed" in detail ->
            "Bekräfta din e-post via länken vi skickade innan du loggar in."
        "invalid login credentials" in detail || "invalid_credentials" in detail ->
            "Fel e-postadress eller lösenord."
        "user already registered" in detail || "user_already_exists" in detail ->
            "Det finns redan ett konto med den e-postadressen."
        "weak password" in detail || "weak_password" in detail || "password should be" in detail ->
            "Lösenordet är för svagt. Välj ett längre lösenord."
        "same_password" in detail -> "Välj ett nytt lösenord som skiljer sig från det tidigare."
        "email rate limit" in detail || "over_email_send_rate_limit" in detail ->
            "För många e-postmeddelanden har skickats. Försök igen senare."
        "rate limit" in detail || "too many requests" in detail ->
            "För många försök. Vänta en stund och försök igen."
        "invalid email" in detail || "email_address_invalid" in detail ->
            "Ange en giltig e-postadress."
        else -> "Det gick inte att slutföra begäran. Försök igen."
    }
}
