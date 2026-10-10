package se.oxelv.mintracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import se.oxelv.mintracker.data.ChallengeDetail
import se.oxelv.mintracker.data.ChallengeRank
import se.oxelv.mintracker.data.ChallengeRules

@RunWith(AndroidJUnit4::class)
class ChallengePayloadTest {
    @Test fun decodesActualRpcFieldNamesAndEmptyProgress() {
        val detail = Json.decodeFromString<ChallengeDetail>("""{
          "id":"c", "creator_id":"u", "group_id":null, "title":"50 km i oktober",
          "description":null, "metric_type":"DISTANCE", "target_value":50000,
          "scope":"PRIVATE", "time_zone":"Europe/Stockholm",
          "start_at":"2026-09-30T22:00:00+00:00", "end_at":"2026-10-31T23:00:00+00:00",
          "created_at":"2026-09-01T00:00:00+00:00", "activity_types":["RUN","DOG_RUN"], "joined":true
        }""")
        assertEquals("ACTIVE", ChallengeRules.state(detail.startAt, detail.endAt, java.time.Instant.parse("2026-10-10T00:00:00Z")))
        assertEquals(2, detail.activityTypes.size)
        val ranks = Json.decodeFromString<List<ChallengeRank>>("""[{"user_id":"u","display_name":"Anna","username":"anna","avatar_path":null,"progress_value":0,"target_value":50000,"progress_percent":0,"completed":false,"rank":1}]""")
        assertEquals(0.0, ranks.first().progressValue, 0.0)
        assertFalse(ranks.first().completed)
    }
}
