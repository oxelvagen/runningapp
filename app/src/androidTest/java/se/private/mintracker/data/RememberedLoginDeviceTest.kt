package se.oxelv.mintracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Instant

/** Each phase runs in a new Android process, with the real SDK and canonical encrypted store. */
@RunWith(AndroidJUnit4::class)
@OptIn(kotlin.time.ExperimentalTime::class)
class RememberedLoginDeviceTest {
    @Serializable private data class Account(val email: String, val password: String)
    @Test fun sessionSurvivesColdStart() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("session_phase")
        assumeTrue("Opt-in cold-start test", phase != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(context.filesDir, "remembered-login-owner.txt")
        when (phase) {
            "login" -> {
                val file = File(context.filesDir, "live-social-test-accounts.json")
                val account = try { Json.decodeFromString<List<Account>>(file.readText()).first() } finally { file.delete() }
                require(account.email.endsWith("@mintracker.example.test"))
                AuthRepository().signIn(account.email, account.password)
                val owner = checkNotNull(supabase.auth.currentUserOrNull()?.id)
                marker.writeText(owner)
                assertTrue("Login must persist a session", SecureSessions.store.hasSession)
                assertEquals(owner, SecureSessionManager(context, "secure_auth", false).loadSession()?.user?.id)
                assertNull("An explicitly unauthenticated state must never fall back to stored credentials",
                    rememberedUserId(io.github.jan.supabase.auth.status.SessionStatus.NotAuthenticated()))
            }
            "invalid_password" -> {
                supabase.auth.awaitInitialization()
                val user = checkNotNull(supabase.auth.currentUserOrNull())
                require(user.email?.endsWith("@mintracker.example.test") == true)
                var rejected = false
                try { AuthRepository().signIn(checkNotNull(user.email), "deliberately-wrong-test-password-0f1ab") }
                catch (_: Exception) { rejected = true }
                assertTrue(rejected)
                assertTrue(SecureSessions.store.hasSession)
                assertEquals(marker.readText(), rememberedUserId(supabase.auth.sessionStatus.value))
                assertEquals(marker.readText(), SecureSessions.store.owner)
            }
            "restore", "restore_offline" -> {
                withTimeout(30_000) { supabase.auth.awaitInitialization() }
                assertTrue("Cold start must retain encrypted session", SecureSessions.store.hasSession)
                assertEquals("Cold start must keep the same account in the app", marker.readText(), rememberedUserId(supabase.auth.sessionStatus.value))
                if (phase == "restore") assertEquals(marker.readText(), supabase.auth.currentUserOrNull()?.id)
            }
            "expire" -> {
                supabase.auth.awaitInitialization()
                val session = checkNotNull(supabase.auth.currentSessionOrNull())
                supabase.auth.stopAutoRefreshForCurrentSession()
                SecureSessions.store.saveSession(session.copy(expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 120_000)))
            }
            "logout" -> {
                supabase.auth.awaitInitialization(); AuthRepository().signOut()
                assertNull(rememberedUserId(supabase.auth.sessionStatus.value))
                assertFalse(SecureSessions.store.hasSession)
                marker.delete()
            }
            "logged_out" -> {
                supabase.auth.awaitInitialization()
                assertNull(rememberedUserId(supabase.auth.sessionStatus.value))
                assertFalse(SecureSessions.store.hasSession)
            }
            else -> error("Unknown test phase")
        }
    }
}
