package se.oxelv.mintracker.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityNoteTest {
    @Test fun blankNotesAreRemovedAndLengthIsBounded() {
        assertEquals(null, ActivityNote.normalize("  \n  "))
        assertEquals("Ett bra pass", ActivityNote.normalize(" Ett bra pass "))
        assertEquals(500, ActivityNote.normalize("a".repeat(500))?.length)
        var rejected = false
        try { ActivityNote.normalize("a".repeat(501)) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun cloudActivityRestoresOptionalNoteWithoutSendingUpdatedAtOnUpload() {
        val old = """{"id":"a","user_id":"u","activity_type":"RUN","started_at":"2026-10-03T12:30:00Z","ended_at":"2026-10-03T13:00:00Z","duration_seconds":1800,"distance_meters":5000.0}"""
        assertEquals(null, Json.decodeFromString<CloudActivity>(old).note)
        val current = Json.decodeFromString<CloudActivity>(old.dropLast(1) + ",\"note\":\"Skogstur\",\"updated_at\":\"2026-10-04T13:00:00Z\"}")
        assertEquals("Skogstur", current.note)
        assertEquals("2026-10-04T13:00:00Z", current.updatedAt)
        val upload = Json.encodeToString(current.copy(updatedAt = null))
        assertFalse(upload.contains("updated_at"))
    }

    @Test fun remoteNoteNeverReplacesPendingOrFailedLocalEdit() {
        val base = ActivityEntity(kind = "DOG_RUN", startedAt = 1, activeMs = 1000, distanceM = 100.0,
            userId = "owner-a", cloudId = "same-activity-id", dogId = "dog-a", note = "Ny lokal text",
            cloudUpdatedAt = 1000)
        assertFalse(ActivityNote.mayAcceptRemote(base.copy(syncStatus = SyncStatus.PENDING), 2000))
        assertFalse(ActivityNote.mayAcceptRemote(base.copy(syncStatus = SyncStatus.ERROR), 2000))
        assertFalse(ActivityNote.mayAcceptRemote(base.copy(syncStatus = SyncStatus.SYNCED), 999))
        assertTrue(ActivityNote.mayAcceptRemote(base.copy(syncStatus = SyncStatus.SYNCED), 2000))
        assertEquals("same-activity-id", base.copy(note = null).cloudId)
        assertEquals("dog-a", base.copy(note = null).dogId)
        assertEquals("DOG_RUN", base.copy(note = null).kind)
        assertEquals("DOG_WALK", base.copy(kind = "DOG_WALK", note = null).kind)
    }
}
