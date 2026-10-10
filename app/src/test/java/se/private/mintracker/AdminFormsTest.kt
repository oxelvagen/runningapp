package se.oxelv.mintracker

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.data.ChallengeRules

class AdminFormsTest {
    @Test fun formEncodesTypedSettingsAndSwedishDecimal() {
        val fields = listOf(AdminField("multiplier", "Nivå", "number"), AdminField("enabled", "På", "boolean"), AdminField("days", "Dagar", "integers"))
        val payload = adminFormPayload(fields, mapOf("multiplier" to "0,5", "enabled" to "false", "days" to "1,3,7"))
        assertEquals(0.5, payload.getValue("multiplier").jsonPrimitive.double, 0.0)
        assertFalse(payload.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(listOf(1,3,7), payload.getValue("days").jsonArray.map { it.jsonPrimitive.int })
    }
    @Test(expected = IllegalArgumentException::class) fun invalidNumericSettingsCannotSubmit() {
        adminFormPayload(listOf(AdminField("multiplier", "Nivå", "number")), mapOf("multiplier" to "oops"))
    }
    @Test fun fiveKilometerSpeedDisplaysAsPace() {
        assertEquals("5:00 min/km", ChallengeRules.format("FASTEST_5K", 1000.0/300))
        assertEquals("— min/km", ChallengeRules.format("FASTEST_5K", 0.0))
        assertEquals("CANCELLED", ChallengeRules.state("2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", status = "CANCELLED"))
    }
}
