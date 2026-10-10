package se.oxelv.mintracker.data

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.oxelv.mintracker.MainActivity
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LiveNotificationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Serializable private data class Account(val email: String, val password: String)
    private fun waitFor(matcher: SemanticsMatcher) { compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun realNotificationUiAndAccountIsolation() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.filesDir, "live-social-test-accounts.json")
        assumeTrue("Opt-in real account test", file.exists())
        val accounts = try { Json.decodeFromString<List<Account>>(file.readText()) } finally { file.delete() }
        require(accounts.size == 2 && accounts.all { it.email.endsWith("@mintracker.example.test") })
        val auth = AuthRepository(); val friends = FriendRepository(ProfileRepository())
        var first: String? = null; var second: String? = null; var friendshipCreated = false
        val cloudId = UUID.randomUUID().toString()
        var initialPreferences: NotificationPreferences? = null
        var activity: android.app.Activity? = null
        var failure: Throwable? = null
        suspend fun login(index: Int) {
            supabase.auth.awaitInitialization()
            auth.signOut()
            auth.signIn(accounts[index].email, accounts[index].password)
        }
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        } }
        fun screenshot(name: String) { instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        } }
        try {
            login(1); second = checkNotNull(supabase.auth.currentUserOrNull()?.id)
            val receiver = NotificationCenterRepository(second)
            initialPreferences = receiver.preferences()
            receiver.setPreferences(initialPreferences.copy(categories = initialPreferences.categories + ("activities" to true)))
            login(0); first = checkNotNull(supabase.auth.currentUserOrNull()?.id)
            if (friends.relation(second).kind != FriendRelationKind.FRIENDS) {
                friends.send(second); friendshipCreated = true
                login(1)
                friends.accept(checkNotNull(friends.relation(first).requestId))
                login(0)
            }
            val now = Instant.now()
            val pass = CloudActivity(cloudId, first, "RUN", now.minusSeconds(1800).toString(), now.minusSeconds(1).toString(), 1799, 5200.0)
            SocialFeedRepository(first).sync(pass)
            val social = SocialFeedRepository(first)
            social.setSharing(cloudId, social.sharing(cloudId), true, false)
            login(1)
            val repo = NotificationCenterRepository(second)
            val page = repo.page()
            val notice = page.items.first { it.targetId == cloudId && it.eventType == "ACTIVITY_COMPLETED" }
            assertTrue(repo.state.value.unread > 0)
            assertEquals(cloudId,repo.activity(cloudId).id)
            activity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val bell = hasContentDescription("Notiser", substring = true) or hasContentDescription("Öppna notiser")
            waitFor(bell); compose.onNode(bell).performClick()
            waitFor(hasText("Notiser", substring = false))
            waitFor(hasText("genomförde ett löppass på 5,2 km.", substring = true))
            screenshot("notification-center-live.png")
            compose.onNodeWithContentDescription("Notisinställningar").performClick()
            waitFor(hasText("Notisinställningar")); compose.onNodeWithText("Personliga rekord").assertIsDisplayed()
            screenshot("notification-settings-live.png")
            compose.onNodeWithContentDescription("Tillbaka").performClick()
            val passRow = hasText("genomförde ett löppass på 5,2 km.", substring = true) and isEnabled()
            waitFor(passRow)
            compose.onNode(passRow).performClick()
            waitFor(hasText("Träningspass"))
            try { waitFor(hasText("5,2 km")) } catch(e: Throwable) { screenshot("notification-failure.png"); throw e }
            compose.onNodeWithText("5,2 km").assertIsDisplayed()
            screenshot("notification-activity-live.png")
            assertNotNull(repo.page().items.first { it.id == notice.id }.readAt)
            compose.onNodeWithContentDescription("Till notiser").performClick()
            waitFor(hasText("Notiser", substring = false))
            shell("svc wifi disable"); shell("svc data disable"); delay(2000)
            compose.onNodeWithContentDescription("Uppdatera notiser").performClick()
            waitFor(hasText("Visar tidigare hämtade notiser.", substring = true))
            compose.onNode(hasText("genomförde ett löppass på 5,2 km.", substring = true)).assertIsDisplayed()
            screenshot("notification-offline-live.png")
            shell("svc wifi enable"); shell("svc data enable"); delay(2500)
            instrumentation.runOnMainSync { activity?.finish() }; activity = null
            val oldCache = repo.state.value.cached
            login(0)
            try { repo.page(); fail("Old account repository must reject requests") } catch (_: AccountChangedException) { }
            val other = NotificationCenterRepository(first)
            assertNull(other.state.value.cached)
            assertFalse(other.page().items.any { it.id == notice.id })
            assertEquals(second,oldCache?.items?.first()?.recipientId)
        } catch (e: Throwable) { screenshot("notification-failure.png"); failure = e; throw e
        } finally { try {
            shell("svc wifi enable"); shell("svc data enable")
            instrumentation.runOnMainSync { activity?.finish() }
            if(first != null) {
                login(0)
                supabase.from("activities").delete { filter { eq("id",cloudId); eq("user_id",first) } }
                if(friendshipCreated && second != null) friends.remove(second)
            }
            if(initialPreferences != null && second != null) {
                login(1)
                val repo = NotificationCenterRepository(second)
                repo.setPreferences(initialPreferences.copy(revision = repo.preferences().revision))
            }
            auth.signOut()
        } catch (cleanup: Throwable) { if(failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }
}
