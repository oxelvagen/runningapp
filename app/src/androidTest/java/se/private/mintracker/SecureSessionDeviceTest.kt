package se.oxelv.mintracker

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.auth.MemorySessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import se.oxelv.mintracker.data.SecureSessionManager
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@OptIn(kotlin.time.ExperimentalTime::class)
class SecureSessionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun session(owner: String, token: String = "test-access-token") = UserSession(
        accessToken = token, refreshToken = "test-refresh-token", tokenType = "bearer", expiresIn = 3600,
        user = UserInfo(id = owner, aud = "authenticated"))

    @Test fun encryptedSessionSurvivesManagerRestartAndRotation() = runBlocking {
        val name = "auth_test_${UUID.randomUUID()}"
        val manager = SecureSessionManager(context, name, false)
        try {
            manager.saveSession(session("A"))
            val disk = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(disk.contains("test-access-token"))
            assertFalse(disk.contains("test-refresh-token"))
            val restarted = SecureSessionManager(context, name, false)
            assertEquals("A", restarted.loadSession()?.user?.id)
            restarted.saveSession(session("A", "rotated-token"))
            assertEquals("rotated-token", SecureSessionManager(context, name, false).loadSession()?.accessToken)
        } finally { manager.clear() }
    }

    @Test fun logoutAndAccountSwitchCannotRestorePreviousOwner() = runBlocking {
        val name = "auth_test_${UUID.randomUUID()}"
        val manager = SecureSessionManager(context, name, false)
        try {
            manager.saveSession(session("A"))
            manager.deleteSession()
            assertNull(SecureSessionManager(context, name, false).loadSession())
            manager.saveSession(session("B"))
            assertEquals("B", SecureSessionManager(context, name, false).loadSession()?.user?.id)
            manager.saveSession(session("C"))
            assertEquals("C", SecureSessionManager(context, name, false).loadSession()?.user?.id)
            assertFalse(manager.biometricEnabled)
        } finally { manager.clear() }
    }

    @Test fun tamperedOwnerFailsClosed() = runBlocking {
        val name = "auth_test_${UUID.randomUUID()}"
        val manager = SecureSessionManager(context, name, false)
        try {
            manager.saveSession(session("A"))
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("owner", "B").commit()
            assertNull(SecureSessionManager(context, name, false).loadSession())
            assertFalse(manager.hasSession)
        } finally { manager.clear() }
    }

    @Test fun legacySessionMigratesOnceAndIsRemovedFromOldStore() = runBlocking {
        val name = "auth_test_${UUID.randomUUID()}"
        val legacy = MemorySessionManager()
        legacy.saveSession(session("A"))
        val manager = SecureSessionManager(context, name, true) { legacy }
        try {
            assertEquals("A", manager.loadSession()?.user?.id)
            assertNull(legacy.loadSession())
            assertEquals("A", SecureSessionManager(context, name, false).loadSession()?.user?.id)
            manager.deleteSession()
            legacy.saveSession(session("old-stale-account"))
            assertNull(manager.loadSession())
        } finally { manager.clear() }
    }
}
