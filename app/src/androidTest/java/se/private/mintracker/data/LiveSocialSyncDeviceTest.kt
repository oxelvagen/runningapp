package se.oxelv.mintracker.data

import android.net.ConnectivityManager
import android.content.Intent
import android.view.MotionEvent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.oxelv.mintracker.tracking.Session
import se.oxelv.mintracker.MainActivity
import se.oxelv.mintracker.tracking.TrackPoint
import se.oxelv.mintracker.tracking.KmSplit
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Opt-in integration test. Credentials are provisioned to debug app storage, never compiled into an APK. */
@RunWith(AndroidJUnit4::class)
class LiveSocialSyncDeviceTest {
    @Serializable private data class TestAccount(val email: String, val password: String)

    @Test fun offlineRoomQueueSyncsThroughRealWorkerAndAccountSwitchRemainsIsolated() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val credentials = File(context.filesDir, "live-social-test-accounts.json")
        assumeTrue("Opt-in test requires temporary test-account credentials", credentials.exists())
        val accounts = try { Json.decodeFromString<List<TestAccount>>(credentials.readText()) } finally { credentials.delete() }
        require(accounts.size == 2 && accounts.all { it.email.endsWith("@mintracker.example.test") })
        val db = TrackerDatabaseProvider.get(context)
        val auth = AuthRepository()
        var owner: String? = null
        var activityId: Long? = null
        var challengeId: String? = null
        var cloudId: String? = null
        var initialPrivacy: SocialPrivacy? = null
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        } }
        suspend fun online() {
            shell("svc wifi enable"); shell("svc data enable")
            withTimeout(45_000) { while (context.getSystemService(ConnectivityManager::class.java).activeNetwork == null) delay(250) }
            delay(1500)
        }
        try {
            auth.signIn(accounts[0].email, accounts[0].password)
            owner = checkNotNull(supabase.auth.currentUserOrNull()?.id)
            val social = SocialFeedRepository(owner)
            initialPrivacy = social.privacy()
            social.setPrivacy(initialPrivacy, false, false)
            val day = LocalDate.now(ZoneId.of("Europe/Stockholm")).toString()
            challengeId = supabase.postgrest.rpc("create_challenge", buildJsonObject {
                put("p_title", "Disposable Android offline audit ${UUID.randomUUID()}")
                put("p_description", "Temporary device integration test")
                put("p_metric_type", "DISTANCE"); put("p_target_value", 10000)
                putJsonArray("p_activity_types") { add("RUN") }
                put("p_start_date", day); put("p_end_date", day); put("p_time_zone", "Europe/Stockholm")
                put("p_scope", "PRIVATE"); put("p_group_id", null as String?)
            }).decodeAs<String>()
            shell("svc wifi disable"); shell("svc data disable")
            withTimeout(15_000) { while (context.getSystemService(ConnectivityManager::class.java).activeNetwork != null) delay(250) }
            val now = System.currentTimeMillis()
            val repository = ActivityRepository(db)
            activityId = repository.saveLocal(Session(ownerUserId = owner, startedAt = now - 1_800_000, activeMs = 1_800_000,
                distanceM = 3000.0, ascentM = 50.0, steps = 3500,
                points = listOf(TrackPoint(59.0, 18.0, now - 1_800_000, 3f, null)),
                splits = listOf(KmSplit(1, 600_000, 59.0, 18.0))), owner, now - 1, "Private Android offline note")
            cloudId = db.dao().ownedActivity(activityId, owner)!!.cloudId!!
            val before = SocialChanges.revisions.value[owner] ?: 0
            ActivitySyncWorker.enqueue(context, owner)
            delay(2500)
            assertEquals(SyncStatus.PENDING, db.dao().ownedActivity(activityId, owner)!!.syncStatus)
            assertTrue(db.dao().pending(owner).any { it.id == activityId })
            online()
            withTimeout(120_000) { while (db.dao().ownedActivity(activityId, owner)?.syncStatus != SyncStatus.SYNCED) delay(500) }
            val remote = supabase.from("activities").select { filter { eq("id", cloudId) } }.decodeSingle<CloudActivity>()
            assertEquals("Private Android offline note", remote.note)
            assertEquals(3000.0, remote.distanceMeters, 0.001)
            assertEquals("PRIVATE", social.sharing(cloudId).visibility)
            assertTrue((SocialChanges.revisions.value[owner] ?: 0) > before)
            val rank = ChallengeRepository().leaderboard(challengeId).first { it.userId == owner }
            assertEquals(3000.0, rank.progressValue, 0.001)
            assertEquals(30.0, rank.progressPercent, 0.001)
            auth.signIn(accounts[1].email, accounts[1].password)
            val other = checkNotNull(supabase.auth.currentUserOrNull()?.id)
            assertNotEquals(owner, other)
            assertTrue(db.dao().activities(other).first().none { it.cloudId == cloudId })
            assertTrue(supabase.from("activities").select { filter { eq("id", cloudId) } }.decodeList<CloudActivity>().isEmpty())
            try { social.privacy(); fail("Stale account repository must be rejected") } catch (_: AccountChangedException) { }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                instrumentation.waitForIdleSync()
                delay(1500)
                // Capture the Socialt destination for human visual review on the fixed test emulator.
                // Repository/worker assertions above and below are the automatic correctness checks.
                val decor = activity.window.decorView
                val at = SystemClock.uptimeMillis()
                val x = decor.width * .7f; val y = decor.height * .945f
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                    val event = MotionEvent.obtain(at, at + index * 50L, action, x, y, 0)
                    instrumentation.sendPointerSync(event); event.recycle()
                }
                delay(2000)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull(screenshot)
                File(context.cacheDir, "social-feed-device.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            } finally {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "social-feed-diagnostic.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                instrumentation.runOnMainSync { activity.finish() }
            }
            auth.signIn(accounts[0].email, accounts[0].password)
            repository.deleteLocal(activityId, owner)
            ActivitySyncWorker.enqueue(context, owner)
            withTimeout(120_000) { while (db.dao().hasDeletion(cloudId, owner)) delay(500) }
            assertTrue(supabase.from("activities").select { filter { eq("id", cloudId) } }.decodeList<CloudActivity>().isEmpty())
            assertEquals(0.0, ChallengeRepository().leaderboard(challengeId).first { it.userId == owner }.progressValue, 0.001)
        } finally {
            online()
            auth.signIn(accounts[0].email, accounts[0].password)
            owner?.let { uid ->
                ActivitySyncWorker.cancel(context, uid)
                cloudId?.let { id ->
                    supabase.from("activities").delete { filter { eq("id", id); eq("user_id", uid) } }
                    db.dao().finishDeletion(id, uid)
                }
                activityId?.let { db.dao().deleteActivity(it, uid) }
                initialPrivacy?.let { old -> val social = SocialFeedRepository(uid); social.setPrivacy(social.privacy(), old.autoShare, old.showStatistics) }
            }
            challengeId?.let { ChallengeRepository().delete(it) }
            auth.signOut()
        }
    }
}
