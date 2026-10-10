package se.oxelv.mintracker

import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.data.*
import kotlinx.serialization.json.Json
import java.time.Instant

class NotificationTextTest {
    private fun item(type: String, payload: NotificationPayload = NotificationPayload()) = TrackerNotification(
        "id", "recipient", "actor", type, "ACTIVITY", "pass", "2026-10-10T12:00:00Z", payload = payload)
    @Test fun badgeBoundaries() {
        assertNull(NotificationText.badge(0)); assertNull(NotificationText.badge(-1))
        assertEquals("1", NotificationText.badge(1)); assertEquals("99", NotificationText.badge(99)); assertEquals("99+", NotificationText.badge(100))
    }
    @Test fun relativeTimesHandleFutureAndInvalidInput() {
        val now = Instant.parse("2026-10-10T12:00:00Z")
        assertEquals("Just nu", NotificationText.relative(now.plusSeconds(30).toString(), now))
        assertEquals("5 min sedan", NotificationText.relative(now.minusSeconds(300).toString(), now))
        assertEquals("2 tim sedan", NotificationText.relative(now.minusSeconds(7200).toString(), now))
        assertEquals("3 d sedan", NotificationText.relative(now.minusSeconds(259200).toString(), now))
        assertEquals("Tid saknas", NotificationText.relative("invalid", now))
    }
    @Test fun privateDogNameIsNotInvented() {
        assertEquals("gick 3,8 km med sin hund.", NotificationText.description(item("ACTIVITY_COMPLETED", NotificationPayload(activityType = "DOG_WALK", distanceMeters = 3800.0))))
        assertEquals("gick 3,8 km med hunden Bella.", NotificationText.description(item("ACTIVITY_COMPLETED", NotificationPayload(activityType = "DOG_WALK", distanceMeters = 3800.0, dogName = "Bella"))))
    }
    @Test fun activityAndChallengeCheerHaveDifferentText() {
        val cheer = item("CHEER")
        assertTrue(NotificationText.description(cheer).contains("träningspass"))
        assertTrue(NotificationText.description(cheer.copy(targetType = "CHALLENGE")).contains("utmaning"))
    }
    @Test fun criticalSystemIsNotAnOptionalCategory() {
        assertEquals(7, NotificationText.categories.size)
        assertFalse(NotificationText.categories.containsKey("system"))
        assertEquals("Underhåll\nPå söndag", NotificationText.description(item("SYSTEM_MESSAGE", NotificationPayload(title = "Underhåll", body = "På söndag"))))
    }
    @Test fun nullableActorAndUnknownPayloadFieldsDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val page = json.decodeFromString<NotificationPage>("""{"items":[{"id":"n","recipient_id":"a","actor_id":null,"event_type":"SYSTEM_MESSAGE","target_type":"SYSTEM","target_id":"n","created_at":"2026-10-10T12:00:00Z","read_at":null,"payload":{"title":"Hello","legacy_id":"old","is_npc":false}}],"unread_count":1,"next_cursor":null}""")
        assertNull(page.items.single().actorId); assertEquals(1L,page.unreadCount); assertEquals("Hello",page.items.single().payload.title)
    }
}
