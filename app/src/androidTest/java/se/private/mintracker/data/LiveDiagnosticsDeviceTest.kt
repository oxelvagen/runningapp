package se.oxelv.mintracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveDiagnosticsDeviceTest {
    @Test fun realOfflineReportingAndCrashSpool() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("diagnostic_phase")
        assumeTrue("Opt-in diagnostic integration test", phase != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        supabase.auth.awaitInitialization()
        val user = checkNotNull(supabase.auth.currentUserOrNull())
        require(user.email?.endsWith("@mintracker.example.test") == true)
        val marker = File(context.filesDir, "diagnostic-test-ids.json")
        suspend fun until(condition: suspend () -> Boolean) { withTimeout(45_000) { while (!condition()) delay(300) } }
        when (phase) {
            "queue" -> {
                Diagnostics.setEnabled(false)
                Diagnostics.retain(user.id, Diagnostics.payload(DiagnosticCode.PROCESS_INTERRUPTED))
                until { Diagnostics.queue.batch(user.id).isEmpty() }
                Diagnostics.report(DiagnosticCode.PROFILE_IMAGE_UPLOAD_FAILED, IllegalStateException("SECRET email@example.com https://private GPS=59"))
                delay(500); assertTrue(Diagnostics.queue.batch(user.id).isEmpty())
                Diagnostics.setEnabled(true)
                repeat(3) { Diagnostics.report(DiagnosticCode.PROFILE_IMAGE_UPLOAD_FAILED, IllegalStateException("SECRET email@example.com https://private GPS=59")) }
                until { Diagnostics.queue.batch(user.id).any { Json.parseToJsonElement(it.payload).jsonObject.text("code") == "PROFILE_IMAGE_UPLOAD_FAILED" } }
                delay(500)
                val reports = Diagnostics.queue.batch(user.id).filter { Json.parseToJsonElement(it.payload).jsonObject.text("code") == "PROFILE_IMAGE_UPLOAD_FAILED" }
                assertEquals(1, reports.size)
                assertFalse(reports.first().payload.contains("SECRET")); assertFalse(reports.first().payload.contains("email@example.com"))
                marker.writeText(buildJsonObject { put("owner", user.id); putJsonArray("ids") { reports.forEach { add(it.id) } } }.toString())
            }
            "upload" -> {
                val saved = Json.parseToJsonElement(marker.readText()).jsonObject
                assertEquals(user.id, saved.text("owner"))
                DiagnosticUploadWorker.enqueue(context, user.id)
                until { Diagnostics.queue.batch(user.id).isEmpty() }
                var denied = false
                try { supabase.postgrest.rpc("admin_system_health", buildJsonObject {}) }
                catch (_: Exception) { denied = true }
                assertTrue("Ordinary user must not read admin diagnostics", denied)
            }
            "crash" -> {
                // Deliberately crash only the opted-in test process; no production crash switch exists.
                Thread { throw IllegalStateException("CONTROLLED TEST PRIVATE PAYLOAD MUST NOT BE REPORTED") }.start()
                delay(20_000)
                error("Android should have terminated this process")
            }
            "recover" -> {
                until { !File(context.filesDir, "diagnostic-crash.json").exists() }
                DiagnosticUploadWorker.enqueue(context, user.id)
                until { Diagnostics.queue.batch(user.id).isEmpty() }
                assertTrue(SecureSessions.store.hasSession)
            }
            else -> error("Unknown diagnostic phase")
        }
    }
}
