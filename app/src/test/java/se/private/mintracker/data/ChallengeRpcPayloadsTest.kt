package se.oxelv.mintracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeRpcPayloadsTest {
    @Test fun jsonbDetailIsOneObjectNotAnArray() {
        val raw = """{
          "id":"bb508d03-c0fb-49dc-a8ff-111111111111",
          "creator_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "group_id":null,"title":"50 km i oktober","description":null,
          "metric_type":"DISTANCE","target_value":50000,"scope":"PRIVATE",
          "time_zone":"Europe/Stockholm","start_at":"2026-09-30T22:00:00+00:00",
          "end_at":"2026-10-31T23:00:00+00:00","created_at":"2026-09-01T00:00:00+00:00",
          "activity_types":["RUN","DOG_RUN"],"joined":true
        }"""
        assertEquals("50 km i oktober", ChallengeRpcPayloads.detail(raw).title)
    }

    @Test fun setReturningRpcsAreArraysAndCreateReturnsUuidString() {
        val list = """[{"id":"c","title":"50 km","metric_type":"DISTANCE","target_value":50000,"scope":"PRIVATE","start_at":"2026-10-01T00:00:00Z","end_at":"2026-11-01T00:00:00Z","participant_count":1,"joined":true}]"""
        val board = """[{"user_id":"u","display_name":"Anna","username":"anna","avatar_path":null,"progress_value":1000,"target_value":50000,"progress_percent":2,"completed":false,"rank":1}]"""
        val invitations = """[{"id":"i","challenge_id":"c","challenge_title":"50 km","sender_id":"u","receiver_id":"v","status":"PENDING","created_at":"2026-10-01T00:00:00Z","expires_at":"2026-10-31T00:00:00Z","display_name":"Anna","username":"anna","avatar_path":null}]"""
        assertEquals("50 km", ChallengeRpcPayloads.summaries(list).single().title)
        assertEquals(1000.0, ChallengeRpcPayloads.leaderboard(board).single().progressValue, 0.0)
        assertEquals("i", ChallengeRpcPayloads.invitations(invitations).single().id)
        assertTrue(ChallengeRpcPayloads.summaries("[]").isEmpty())
        assertTrue(ChallengeRpcPayloads.leaderboard("[]").isEmpty())
        assertTrue(ChallengeRpcPayloads.invitations("[]").isEmpty())
        assertEquals("bb508d03-c0fb-49dc-a8ff-111111111111", ChallengeRpcPayloads.createdId("\"bb508d03-c0fb-49dc-a8ff-111111111111\""))
    }
}
