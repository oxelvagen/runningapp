package se.oxelv.mintracker.data

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.MainActivity
import se.oxelv.mintracker.tracking.TrackingService
import se.oxelv.mintracker.tracking.TrackingState
import java.util.UUID
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import se.oxelv.mintracker.tracking.Session

class TrainingDurabilityDeviceTest {
    @Test fun pauseAndStopFlushLastBufferedFixWithoutRecordingPausedMovement() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        shell("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("appops set ${context.packageName} android:mock_location allow")
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val owner = UUID.randomUUID().toString()
        val store = ActiveTrainingStore(TrackerDatabaseProvider.get(context))
        suspend fun waitFor(status: String): Session {
            repeat(150) {
                val state = store.load(owner)
                if (state?.status == status) return state
                Thread.sleep(100)
            }
            error("Timed out waiting for durable $status")
        }
        fun inject(lat: Double): Long {
            val fix = Location("gps").apply {
                latitude = lat; longitude = 18.0; accuracy = 3f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            Tasks.await(fused.setMockLocation(fix))
            return fix.time
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                Tasks.await(fused.setMockMode(true))
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.START).putExtra(TrackingService.EXTRA_KIND, "RUN")
                    .putExtra(TrackingService.EXTRA_USER_ID, owner))
                waitFor("ACTIVE")
                var last = 0L
                repeat(5) { index -> last = inject(59.0 + index * .0001); if (index < 4) Thread.sleep(1800) }
                context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.PAUSE))
                val paused = waitFor("PAUSED")
                assertEquals("Flush must retain last measured fix before pause", last, paused.points.last().timestamp)
                Thread.sleep(1800)
                val duringPause = inject(59.5)
                context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.RESUME))
                waitFor("ACTIVE")
                repeat(5) { index -> last = inject(59.01 + index * .0001); if (index < 4) Thread.sleep(1800) }
                context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.STOP))
                val stopped = waitFor("STOPPING")
                assertEquals("Flush must retain last measured fix before stop", last, stopped.points.last().timestamp)
                assertFalse(stopped.points.any { point -> point.timestamp == duringPause })
                assertEquals(2, se.oxelv.mintracker.tracking.routeSegments(stopped.points).size)
                assertEquals(stopped.points.size, stopped.points.map { point -> point.timestamp }.distinct().size)
                assertTrue("Paused movement must not add a kilometre", stopped.distanceM < 120)
            } finally {
                context.stopService(Intent(context, TrackingService::class.java)); Thread.sleep(400)
                Tasks.await(fused.setMockMode(false))
                store.load(owner)?.let { saved -> store.discard(saved) }
                TrackingState.session.value = Session()
            }
        }
    }
    @Test fun serviceWritesRecoverableSessionBeforeActivityCanDisappear() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION").close()
        val owner = UUID.randomUUID().toString()
        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                .setAction(TrackingService.START).putExtra(TrackingService.EXTRA_KIND, "RUN")
                .putExtra(TrackingService.EXTRA_USER_ID, owner))
            try {
                repeat(50) { if (!TrackingState.session.value.running) Thread.sleep(100) }
                assertTrue("Service must start", TrackingState.session.value.running)
                Thread.sleep(1200)
                val db = TrackerDatabaseProvider.get(context)
                db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='ActiveTrainingEntity'").use { cursor ->
                    assertTrue("An ongoing workout must exist on disk before process death", cursor.moveToFirst())
                }
                db.openHelper.readableDatabase.query("SELECT userId FROM ActiveTrainingEntity WHERE userId = ?", arrayOf(owner)).use { cursor ->
                    assertTrue("Workout owner and state must survive process memory loss", cursor.moveToFirst())
                }
                val other = UUID.randomUUID().toString()
                instrumentation.runOnMainSync {
                    TrackingState.session.value = Session(ownerUserId = other)
                    context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.STOP))
                }
                val store = ActiveTrainingStore(db)
                repeat(30) { if (store.load(owner)?.status != "STOPPING") Thread.sleep(100) }
                assertEquals("UI cache must never overwrite the service-owned session", "STOPPING", store.load(owner)?.status)
                assertNull(store.load(other))
            } finally {
                context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.STOP))
                Thread.sleep(300)
                ActiveTrainingStore(TrackerDatabaseProvider.get(context)).load(owner)?.let {
                    ActiveTrainingStore(TrackerDatabaseProvider.get(context)).discard(it)
                }
                TrackingState.session.value = Session()
            }
        }
    }
    @Test fun countdownAndGpsContinueWithScreenLockedAndActivityRecreated() = lockedGpsAndRestore(false)
    @Test fun carSpeedGpsContinuesWithScreenLockedDuringCountdown() = lockedGpsAndRestore(true)
    private fun lockedGpsAndRestore(carSpeed: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        shell("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("appops set ${context.packageName} android:mock_location allow")
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val owner = UUID.randomUUID().toString()
        val store = ActiveTrainingStore(TrackerDatabaseProvider.get(context))
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            try {
                Tasks.await(fused.setMockMode(true))
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.START).putExtra(TrackingService.EXTRA_KIND, "RUN")
                    .putExtra(TrackingService.EXTRA_USER_ID, owner).putExtra(TrackingService.EXTRA_COUNTDOWN, 4))
                repeat(50) { if (store.load(owner) == null) Thread.sleep(100) }
                val pending = checkNotNull(store.load(owner))
                assertEquals("COUNTDOWN", pending.status)
                activity.recreate()
                shell("input keyevent 223")
                Thread.sleep(5000)
                assertEquals("ACTIVE", TrackingState.session.value.status)
                assertEquals(pending.sessionId, TrackingState.session.value.sessionId)
                repeat(5) { index ->
                    Tasks.await(fused.setMockLocation(Location("gps").apply {
                        latitude = 59.0 + index * (if (carSpeed) 0.0004 else 0.0001); longitude = 18.0; accuracy = 3f
                        time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }))
                    Thread.sleep(1800)
                }
                Thread.sleep(6500)
                val saved = checkNotNull(store.load(owner))
                assertTrue("GPS must continue behind the lock screen", saved.points.size >= 3)
                assertTrue(saved.distanceM > 10)
                if (carSpeed) {
                    assertTrue("Road speed fixes must contribute distance while locked", saved.distanceM > 100)
                    assertTrue("Road speed must not be capped as running", saved.speedKmh > 60)
                    assertEquals("Continuous locked-screen reception must remain one segment", 1,
                        se.oxelv.mintracker.tracking.routeSegments(saved.points).size)
                }
                assertTrue(saved.activeMs >= 10000)
                context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.PAUSE))
                Thread.sleep(500)
                assertEquals("PAUSED", store.load(owner)?.status)
                val paused = checkNotNull(store.load(owner))
                context.stopService(Intent(context, TrackingService::class.java))
                Thread.sleep(500)
                TrackingState.session.value = Session()
                shell("input keyevent 224"); shell("wm dismiss-keyguard")
                activity.recreate()
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.RESTORE).putExtra(TrackingService.EXTRA_USER_ID, owner))
                Thread.sleep(1000)
                assertEquals(paused.sessionId, TrackingState.session.value.sessionId)
                assertEquals(paused.points, TrackingState.session.value.points)
                assertEquals(paused.distanceM, TrackingState.session.value.distanceM, 0.001)
                assertTrue(TrackingState.session.value.paused)
                context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.STOP))
                Thread.sleep(500)
                assertEquals("STOPPING", store.load(owner)?.status)
                assertEquals(paused.sessionId, store.load(owner)?.sessionId)
            } finally {
                shell("input keyevent 224"); shell("wm dismiss-keyguard")
                Tasks.await(fused.setMockMode(false))
                context.stopService(Intent(context, TrackingService::class.java)); Thread.sleep(300)
                store.load(owner)?.let { store.discard(it) }
                TrackingState.session.value = Session()
                shell("appops set ${context.packageName} android:mock_location default")
            }
        }
    }
}
