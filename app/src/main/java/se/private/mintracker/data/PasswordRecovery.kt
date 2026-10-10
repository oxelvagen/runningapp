package se.oxelv.mintracker.data

import android.net.Uri
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PasswordRules {
    // Project policy is also enforced by Auth on the server; see docs/auth-security.md.
    const val description = "Minst 6 tecken. Välj gärna ett långt och unikt lösenord."
    fun validate(password: String, confirmation: String): String? = when {
        password.length < 6 -> "Lösenordet måste innehålla minst 6 tecken."
        password != confirmation -> "Lösenorden stämmer inte överens."
        else -> null
    }
}

data class RecoveryState(val visible: Boolean = false, val busy: Boolean = false, val ready: Boolean = false,
    val completed: Boolean = false, val message: String? = null)

/** Recovery credentials never enter the regular client, disk, logs, or saved UI state. */
object PasswordRecovery {
    private val client = createSupabaseClient(SUPABASE_PROJECT_URL, SUPABASE_PUBLISHABLE_KEY) {
        install(Auth) { autoLoadFromStorage = false; autoSaveToStorage = false; alwaysAutoRefresh = false; sessionManager = MemorySessionManager() }
    }
    private val mutable = MutableStateFlow(RecoveryState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var owner: String? = null
    fun showLoading() { mutable.value = RecoveryState(visible = true, busy = true) }
    suspend fun open(uri: Uri) = mutex.withLock {
        mutable.value = RecoveryState(visible = true, busy = true)
        owner = null
        client.auth.clearSession()
        try {
            require(AuthRedirect.classify(uri) == AuthRedirect.Callback.Recovery)
            val params = Uri.parse("https://callback.invalid/?${uri.fragment.orEmpty()}")
            val access = requireNotNull(params.getQueryParameter("access_token"))
            val refresh = requireNotNull(params.getQueryParameter("refresh_token"))
            client.auth.importAuthToken(access, refresh, retrieveUser = true, autoRefresh = false)
            owner = client.auth.retrieveUserForCurrentSession().id
            mutable.value = RecoveryState(visible = true, ready = true)
        } catch (error: CancellationException) { client.auth.clearSession(); throw error
        } catch (_: Exception) {
            client.auth.clearSession()
            mutable.value = RecoveryState(visible = true, message = "Länken är ogiltig, har gått ut eller redan använts. Begär en ny återställningslänk.")
        }
    }
    suspend fun save(password: String, confirmation: String) = mutex.withLock {
        val validation = PasswordRules.validate(password, confirmation)
        if (validation != null) { mutable.value = mutable.value.copy(message = validation); return@withLock }
        if (!mutable.value.ready || mutable.value.busy) return@withLock
        mutable.value = mutable.value.copy(busy = true, message = null)
        try {
            check(client.auth.retrieveUserForCurrentSession().id == owner)
            client.auth.updateUser { this.password = password }
            // Logout just this recovery session; don't revoke an unrelated ongoing training session.
            runCatching { client.auth.signOut() }
            client.auth.clearSession(); owner = null
            mutable.value = RecoveryState(visible = true, completed = true, message = "Lösenordet har sparats. Logga in med ditt nya lösenord.")
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            val expired = listOf("expired", "invalid jwt", "not authenticated", "session_not_found").any { it in error.message.orEmpty().lowercase() }
            if (expired) { client.auth.clearSession(); owner = null }
            mutable.value = mutable.value.copy(busy = false, ready = !expired,
                message = if (expired) "Länken har gått ut. Begär en ny återställningslänk." else authErrorMessage(error))
        }
    }
    suspend fun dismiss() = mutex.withLock {
        client.auth.clearSession(); owner = null; mutable.value = RecoveryState()
    }
}
