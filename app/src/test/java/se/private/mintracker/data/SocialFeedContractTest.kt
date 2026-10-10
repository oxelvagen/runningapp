package se.oxelv.mintracker.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SocialFeedContractTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Test fun challengeFeedDecodesWithoutExposingActivityMetrics() {
        val page = json.decodeFromString<SocialFeedPage>("""{"items":[{"id":"e","event_type":"CHALLENGE_COMPLETED","actor_id":"a","display_name":"Anna","occurred_at":"2026-10-10T10:00:00+00:00","activity_type":null,"distance_meters":null,"duration_seconds":null,"challenge_id":"c","challenge_title":"Oktober","cheer_count":2,"cheered_by_me":true}],"next_cursor":{"occurred_at":"2026-10-10T10:00:00+00:00","id":"e"}}""")
        assertEquals("e", page.nextCursor?.id)
        assertNull(page.items.single().distanceMeters)
        assertEquals("Klarade utmaningen Oktober", SocialFeedText.description(page.items.single()))
        assertTrue(page.items.single().cheeredByMe)
    }
    @Test fun syncPayloadMatchesRpcAllowlistWithDefaultEncoding() {
        val activity = CloudActivity("a", "u", "RUN", "2026-10-10T09:00:00Z", "2026-10-10T09:30:00Z", 1800, 3000.0, note = "Privat anteckning")
        val payload = json.encodeToJsonElement(activity).jsonObject
        assertFalse(payload.containsKey("updated_at"))
        assertFalse(payload.containsKey("latitude"))
        assertFalse(payload.containsKey("visibility"))
        assertTrue(payload.containsKey("note"))
    }
    @Test fun publicStatisticsPrivateResponseDoesNotRequireAnyMetrics() {
        val private = json.decodeFromString<PublicStatistics>("""{"status":"PRIVATE"}""")
        assertEquals("PRIVATE", private.status)
        assertTrue(private.weeks.isEmpty())
        assertNull(private.runPace)
        val visible = json.decodeFromString<PublicStatistics>("""{"status":"VISIBLE","run_pace_seconds_per_km":360.5,"weeks":[{"week_start":"2026-10-05","distance_meters":3000,"activity_count":1}]}""")
        assertEquals(360.5, visible.runPace!!, 0.001)
        assertEquals(3000.0, visible.weeks.single().distanceMeters, 0.001)
    }
    @Test fun structuredActivityProducesSwedishTextWithOnlyOptedInDogName() {
        val event = SocialEvent("e", "ACTIVITY_COMPLETED", "a", "Anna", occurredAt = "2026-10-10T10:00:00Z", activityType = "DOG_WALK")
        assertEquals("Tog en hundpromenad", SocialFeedText.description(event))
        assertEquals("Tog en hundpromenad · Fido", SocialFeedText.description(event.copy(dogName = "Fido")))
    }
}
