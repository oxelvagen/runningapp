package se.oxelv.mintracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.oxelv.mintracker.data.AdminRepository
import se.oxelv.mintracker.data.text

@RunWith(AndroidJUnit4::class)
class SystemHealthUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun administratorCanOpenErrorGroupAndChangeStatus() {
        var status = "NEW"
        fun group() = buildJsonObject {
            put("fingerprint", "a".repeat(32)); put("code", "PROFILE_IMAGE_UPLOAD_FAILED")
            put("category", "STORAGE"); put("severity", "ERROR"); put("status", status); put("occurrences", 2)
            put("version_name", "1.0.6"); put("device_model", "Test phone"); put("last_seen", "2026-10-11")
        }
        compose.setContent { MaterialTheme {
            SystemHealthScreen(AdminRepository(), 0, request = { name, params ->
                when (name) {
                    "admin_system_health" -> buildJsonObject {
                        put("errors_24h", 2); put("crashes_24h", 0); put("affected_users_24h", 1); put("critical_24h", 0)
                        putJsonArray("groups") { add(group()) }
                    }
                    "admin_system_health_detail" -> buildJsonObject { put("group", group()); putJsonArray("reports") {}; putJsonArray("versions") {} }
                    "admin_system_health_status" -> { status = params.text("p_status"); JsonNull }
                    else -> error("Unexpected RPC")
                }
            })
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Fel: 2", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("PROFILE_IMAGE_UPLOAD_FAILED").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Utreds", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Utreds").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Status: Utreds").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("INVESTIGATING", status)
        compose.onNodeWithText("‹ Alla grupper").performClick()
        compose.onNodeWithText("Sök felkod").performScrollTo().assertIsDisplayed()
    }
}
