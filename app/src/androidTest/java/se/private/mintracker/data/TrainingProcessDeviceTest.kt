package se.oxelv.mintracker.data

import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import se.oxelv.mintracker.MainActivity
import se.oxelv.mintracker.tracking.*
import java.io.File

/** Opt-in: the PowerShell runner kills the process between these phases. */
class TrainingProcessDeviceTest {
    @Test fun durableWorkoutSurvivesRealProcessDeathAndSavesExactlyOnce() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("training_phase")
        assumeTrue("Requires a dedicated test account and the process runner", phase != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stage = File(context.filesDir, "training-test-stage.txt")
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        fun assertActiveScreen() {
            fun hasStop(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.text?.toString() == "Avsluta") return true
                return (0 until node.childCount).any { hasStop(node.getChild(it)) }
            }
            val deadline = SystemClock.elapsedRealtime() + 15000
            var found = false
            while (!found && SystemClock.elapsedRealtime() < deadline) {
                found = hasStop(instrumentation.uiAutomation.rootInActiveWindow)
                if (!found) Thread.sleep(200)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "training-active-test.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val texts = mutableListOf<String>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if (node == null) return
                node.text?.toString()?.takeUnless { '@' in it }?.let { texts += it }
                repeat(node.childCount) { visit(node.getChild(it)) }
            }
            visit(instrumentation.uiAutomation.rootInActiveWindow)
            File(context.filesDir, "training-ui-probe.txt").writeText(texts.joinToString("\n"))
            assertTrue("The restored workout must display its Stop control, not a splash or home screen", found)
        }
        stage.writeText("await_auth")
        withTimeout(30_000) { supabase.auth.awaitInitialization() }
        val owner = checkNotNull(rememberedUserId(supabase.auth.sessionStatus.value))
        val db = TrackerDatabaseProvider.get(context)
        val store = ActiveTrainingStore(db)
        val marker = File(context.filesDir, "training-process-id.txt")
        if (phase == "repair_fixture") {
            require(supabase.auth.currentUserOrNull()?.email?.endsWith("@mintracker.example.test") == true)
            store.load(owner)?.let { old ->
                require(marker.exists() && marker.readText() == old.sessionId || old.points.isNotEmpty() &&
                    old.points.all { it.latitude in 59.0..59.01 && it.longitude == 18.0 }) { "Refusing to remove non-fixture training" }
                context.stopService(Intent(context, TrackingService::class.java)); Thread.sleep(300)
                store.discard(old)
            }
            marker.delete(); stage.delete(); return@runBlocking
        }
        shell("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        stage.writeText("launch_activity")
        // ActivityScenario's idle wait is unsuitable for a restored activity with a live timer/map.
        // Launch the real Activity and let Android enforce the visible-FGS requirement.
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Thread.sleep(2000)
        run {
            stage.writeText("activity_ready")
            if (phase == "prepare") {
                check(store.load(owner) == null) { "Test account must have no unfinished training" }
                shell("appops set ${context.packageName} android:mock_location allow")
                val fused = LocationServices.getFusedLocationProviderClient(context)
                Tasks.await(fused.setMockMode(true))
                try {
                    ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                        .setAction(TrackingService.START).putExtra(TrackingService.EXTRA_KIND, "RUN")
                        .putExtra(TrackingService.EXTRA_USER_ID, owner))
                    Thread.sleep(1500)
                    repeat(4) { index ->
                        Tasks.await(fused.setMockLocation(Location("gps").apply {
                            latitude = 59.0 + index * 0.0001; longitude = 18.0; accuracy = 3f
                            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        })); Thread.sleep(1800)
                    }
                    Thread.sleep(6500)
                    val checkpoint = checkNotNull(store.load(owner))
                    assertTrue(checkpoint.points.size >= 3)
                    marker.writeText(checkpoint.sessionId)
                    assertActiveScreen()
                } finally { Tasks.await(fused.setMockMode(false)) }
            } else if (phase == "restore_save") {
                val original = checkNotNull(store.load(owner))
                stage.writeText("checkpoint_loaded:${original.status}:${original.interruptions}")
                assertEquals(marker.readText(), original.sessionId)
                assertTrue(original.points.size >= 3)
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.RESTORE).putExtra(TrackingService.EXTRA_USER_ID, owner))
                Thread.sleep(1500)
                stage.writeText("service_restored:old=${original.status}:now=${TrackingState.session.value.status}:interruptions=${TrackingState.session.value.interruptions}:running=${TrackingState.serviceRunning.value}")
                assertEquals(original.sessionId, TrackingState.session.value.sessionId)
                assertTrue(TrackingState.session.value.points.size >= original.points.size)
                assertTrue("Restoration must register the gap: ${stage.readText()}; ${TrackingState.session.value.recordingError}", TrackingState.session.value.interruptions >= 1)
                assertActiveScreen()
                context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.STOP))
                Thread.sleep(800)
                val stopped = checkNotNull(store.load(owner))
                stage.writeText("service_stopped")
                assertEquals("STOPPING", stopped.status)
                val repository = ActivityRepository(db)
                val first = repository.saveLocal(stopped, owner, stopped.endedAt)
                stage.writeText("saved_once")
                val second = repository.saveLocal(stopped, owner, stopped.endedAt)
                assertEquals(first, second)
                assertEquals(original.sessionId, db.dao().activity(first)?.cloudId)
                assertEquals(stopped.points.size, db.dao().points(first).size)
                assertNull(store.load(owner))
                // Keep this fixture offline/local. No synthetic GPS is published.
                db.dao().deleteActivity(first, owner)
                marker.delete(); TrackingState.session.value = Session()
                shell("appops set ${context.packageName} android:mock_location default")
            } else error("Unknown phase")
        }
        stage.delete()
    }
}
