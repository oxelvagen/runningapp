package se.oxelv.mintracker.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ChallengeRulesTest {
    private val form = NewChallenge("50 km", "", "DISTANCE", 50.0, setOf("RUN", "DOG_RUN"),
        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "Europe/Stockholm", "FRIENDS", null)

    @Test fun metricUnitsAndOverTargetProgress() {
        assertEquals(50000.0, ChallengeRules.toBase("DISTANCE", 50.0), 0.0)
        assertEquals(36000.0, ChallengeRules.toBase("DURATION", 10.0), 0.0)
        assertEquals(12.0, ChallengeRules.toBase("ACTIVITY_COUNT", 12.0), 0.0)
        assertEquals(2000.0, ChallengeRules.toBase("ELEVATION_GAIN", 2000.0), 0.0)
        assertEquals(126.8, ChallengeRules.percent(63400.0, 50000.0), 0.01)
        assertTrue(ChallengeRules.format("DISTANCE", 63400.0).contains("63,4"))
    }

    @Test fun validAndInvalidCreationFields() {
        assertNull(ChallengeRules.validate(form))
        assertNotNull(ChallengeRules.validate(form.copy(activityTypes = emptySet())))
        assertNotNull(ChallengeRules.validate(form.copy(activityTypes = setOf("BIKE"))))
        assertNotNull(ChallengeRules.validate(form.copy(endDate = LocalDate.of(2026, 9, 30))))
        assertNotNull(ChallengeRules.validate(form.copy(metricType = "ACTIVITY_COUNT", targetValue = 1.5)))
        assertNotNull(ChallengeRules.validate(form.copy(scope = "GROUP", groupId = null)))
    }

    @Test fun stateUsesExactInstantBoundaries() {
        val start = "2026-09-30T22:00:00Z" // 1 October in Stockholm
        val end = "2026-10-31T23:00:00Z" // 1 November in Stockholm
        assertEquals("UPCOMING", ChallengeRules.state(start, end, Instant.parse("2026-09-30T21:59:59Z")))
        assertEquals("ACTIVE", ChallengeRules.state(start, end, Instant.parse(start)))
        assertEquals("COMPLETED", ChallengeRules.state(start, end, Instant.parse(end)))
    }
}
