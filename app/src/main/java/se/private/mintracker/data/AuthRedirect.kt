package se.oxelv.mintracker.data

import android.net.Uri

/** Must match the Android intent filter and Supabase Authentication redirect allow list. */
object AuthRedirect {
    const val URL = "mintracker://auth/callback"

    sealed interface Callback {
        data object Session : Callback
        data object Recovery : Callback
        data class Failure(val message: String) : Callback
        data object Unrelated : Callback
    }

    fun classify(uri: Uri?): Callback {
        if (uri == null || uri.scheme != "mintracker" || uri.host != "auth" || uri.path != "/callback")
            return Callback.Unrelated
        val fragment = uri.fragment.orEmpty()
        val fragmentParams = Uri.parse("https://callback.invalid/?$fragment")
        val error = uri.getQueryParameter("error_code") ?: fragmentParams.getQueryParameter("error_code")
            ?: uri.getQueryParameter("error") ?: fragmentParams.getQueryParameter("error")
        if (error != null) return Callback.Failure(when (error.lowercase()) {
            "otp_expired", "expired_token", "invalid_grant" ->
                "Länken har gått ut eller redan använts. Begär en ny länk eller logga in om du redan verifierat kontot."
            "access_denied" -> "Verifieringen avbröts. Öppna länken från e-postmeddelandet igen."
            "already_confirmed" -> "E-postadressen är redan verifierad. Du kan logga in."
            else -> "E-postadressen kunde inte verifieras. Kontrollera att du öppnat den senaste länken eller prova att logga in."
        })
        return if (fragmentParams.getQueryParameter("access_token") != null &&
            fragmentParams.getQueryParameter("refresh_token") != null) {
            if (fragmentParams.getQueryParameter("type") == "recovery") Callback.Recovery else Callback.Session
        }
        else Callback.Failure("Verifieringslänken är ogiltig eller ofullständig. Öppna länken från e-postmeddelandet igen.")
    }
}
