package se.oxelv.mintracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityKindTest {
    @Test fun legacySwedishKindsMapToStableCodes() {
        assertEquals("RUN", ActivityKind.canonical("Löpning"))
        assertEquals("WALK", ActivityKind.canonical("Promenad"))
        assertEquals("RUN", ActivityKind.canonical("RUN"))
        assertEquals("WALK", ActivityKind.canonical("WALK"))
    }

    @Test fun onlyDogKindsRequireDog() {
        assertFalse(ActivityKind.RUN.requiresDog)
        assertFalse(ActivityKind.WALK.requiresDog)
        assertTrue(ActivityKind.DOG_RUN.requiresDog)
        assertTrue(ActivityKind.DOG_WALK.requiresDog)
    }

    @Test fun unknownCloudKindIsNotSilentlyReclassified() {
        assertEquals("UNKNOWN", ActivityKind.canonical("UNKNOWN"))
        assertEquals("UNKNOWN", ActivityKind.label("UNKNOWN"))
    }
}
