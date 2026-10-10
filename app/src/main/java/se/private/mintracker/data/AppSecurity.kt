package se.oxelv.mintracker.data

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SecurityState(val locked: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val offerFor: String? = null, val revision: Int = 0)

object AppSecurity {
    private val mutable = MutableStateFlow(SecurityState(locked = SecureSessions.store.biometricEnabled))
    val state = mutable.asStateFlow()
    private var stoppedAt: Long? = null
    private class PromptCancelled : Exception()
    fun supported(context: Context) = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    private fun preferences(context: Context) = context.getSharedPreferences("security_choices", Context.MODE_PRIVATE)
    fun passwordLogin(context: Context, userId: String) {
        mutable.value = SecurityState(offerFor = userId.takeIf {
            supported(context) && !preferences(context).getBoolean("asked:$it", false)
        })
    }
    fun decline(context: Context, userId: String) {
        preferences(context).edit().putBoolean("asked:$userId", true).apply()
        mutable.value = mutable.value.copy(offerFor = null)
    }
    fun stopped() { stoppedAt = SystemClock.elapsedRealtime() }
    fun resumed(trainingInProgress: Boolean) {
        val elapsed = stoppedAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        stoppedAt = null
        if (elapsed >= 60_000 && SecureSessions.store.biometricEnabled && !trainingInProgress)
            mutable.value = mutable.value.copy(locked = true, offerFor = null)
    }
    suspend fun usePassword() {
        supabase.auth.awaitInitialization()
        supabase.auth.clearSession()
        // Keep the login form mounted until the password request finishes.
        // Removing a Compose host here would cancel its in-flight sign-in coroutine.
        mutable.value = mutable.value.copy(offerFor = null, revision = mutable.value.revision + 1)
    }
    fun loggedOut() { mutable.value = SecurityState() }

    private suspend fun authenticate(activity: FragmentActivity, cipher: Cipher): Cipher = suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) {
                    val authenticatedCipher = result.cryptoObject?.cipher
                    if (authenticatedCipher != null) continuation.resume(authenticatedCipher)
                    else continuation.resumeWithException(IllegalStateException("Missing crypto result"))
                }
            }
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                if (continuation.isActive) continuation.resumeWithException(PromptCancelled())
            }
        })
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Min Tracker")
            .setSubtitle("Bekräfta med fingeravtryck eller säker ansiktsigenkänning")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Använd lösenord").build(), BiometricPrompt.CryptoObject(cipher))
    }

    suspend fun change(activity: FragmentActivity, enable: Boolean) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, offerFor = null)
        val store = SecureSessions.store
        try {
            check(supported(activity))
            val owner = checkNotNull(supabase.auth.currentUserOrNull()?.id)
            check(store.owner == owner)
            // Verify the server session before changing local protection.
            check(supabase.auth.retrieveUserForCurrentSession().id == owner)
            if (enable) store.enable(authenticate(activity, store.enableCipher()), owner)
            else {
                store.unlock(authenticate(activity, store.unlockCipher()), owner)
                store.disable(owner)
            }
            decline(activity, owner)
            mutable.value = mutable.value.copy(revision = mutable.value.revision + 1)
        } catch (_: PromptCancelled) {
            mutable.value = mutable.value.copy(error = "Biometrin avbröts eller är inte tillgänglig. Du kan använda lösenord.")
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = "Säkerhetsinställningen kunde inte ändras. Logga in med lösenord och försök igen.")
        } finally { mutable.value = mutable.value.copy(busy = false) }
    }

    suspend fun unlock(activity: FragmentActivity) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        val store = SecureSessions.store
        var decrypted = false
        try {
            check(supported(activity))
            val owner = checkNotNull(store.owner)
            val session = store.unlock(authenticate(activity, store.unlockCipher()), owner)
            decrypted = true
            supabase.auth.awaitInitialization()
            supabase.auth.importSession(session, autoRefresh = false)
            // A local CryptoObject never bypasses server revocation or expiration.
            supabase.auth.refreshCurrentSession()
            check(supabase.auth.retrieveUserForCurrentSession().id == owner)
            check(store.owner == owner)
            supabase.auth.startAutoRefreshForCurrentSession()
            mutable.value = SecurityState()
        } catch (_: PromptCancelled) {
            mutable.value = mutable.value.copy(error = "Biometrin avbröts. Du kan logga in med lösenord.")
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            // Invalidated keys and rejected refresh tokens require ordinary login.
            // Keep no imported Auth session alive after a failed server check.
            if (decrypted || !supported(activity)) supabase.auth.clearSession() else store.clear()
            mutable.value = SecurityState(error = "Snabb inloggning kunde inte användas. Logga in med e-post och lösenord.")
        } finally { mutable.value = mutable.value.copy(busy = false) }
    }
}
