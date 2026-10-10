package se.oxelv.mintracker.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FriendUiRulesTest {
    private val outgoing = FriendRequest("out", "me", "other", "PENDING")
    private val incoming = FriendRequest("in", "other", "me", "PENDING")

    @Test fun profileRelationStates() {
        assertEquals(FriendRelationKind.SELF, FriendUiRules.relation("me", "me", false, emptyList()).kind)
        assertEquals(FriendRelationKind.NONE, FriendUiRules.relation("me", "other", false, emptyList()).kind)
        assertEquals(FriendRelationKind.OUTGOING_PENDING, FriendUiRules.relation("me", "other", false, listOf(outgoing)).kind)
        assertEquals(FriendRelationKind.INCOMING_PENDING, FriendUiRules.relation("me", "other", false, listOf(incoming)).kind)
        assertEquals(FriendRelationKind.FRIENDS, FriendUiRules.relation("me", "other", true, listOf(outgoing)).kind)
    }

    @Test fun requestTerminalStatesDoNotImplyFriendship() {
        for (status in listOf("ACCEPTED", "DECLINED", "CANCELLED", "EXPIRED")) {
            val request = outgoing.copy(status = status)
            assertEquals(FriendRelationKind.NONE, FriendUiRules.relation("me", "other", false, listOf(request)).kind)
        }
    }

    @Test fun inboxAndSentCountOnlyPendingInCorrectDirection() {
        val requests = listOf(incoming, outgoing, incoming.copy(id = "old", status = "DECLINED"), outgoing.copy(id = "cancel", status = "CANCELLED"))
        assertEquals(listOf("in"), FriendUiRules.incoming(requests, "me").map { it.id })
        assertEquals(listOf("out"), FriendUiRules.outgoing(requests, "me").map { it.id })
    }

    @Test fun accountSwitchDiscardsOldResult() {
        assertTrue(FriendUiRules.canApplyResponse("me", "me"))
        assertFalse(FriendUiRules.canApplyResponse("me", "other"))
        assertFalse(FriendUiRules.canApplyResponse("me", null))
    }

    @Test fun acceptDeclineCancelAndRemoveFollowCurrentServerState() {
        assertEquals(FriendRelationKind.FRIENDS, FriendUiRules.relation("me", "other", true, listOf(incoming.copy(status = "ACCEPTED"))).kind)
        assertEquals(FriendRelationKind.NONE, FriendUiRules.relation("me", "other", false, listOf(incoming.copy(status = "DECLINED"))).kind)
        assertEquals(FriendRelationKind.NONE, FriendUiRules.relation("me", "other", false, listOf(outgoing.copy(status = "CANCELLED"))).kind)
        assertEquals(FriendRelationKind.NONE, FriendUiRules.relation("me", "other", false, listOf(incoming.copy(status = "ACCEPTED"))).kind)
    }

    @Test fun expiredPendingIsNotActionableEvenIfStatusWasNotUpdatedYet() {
        val stale = incoming.copy(expiresAt = "2020-01-01T00:00:00Z")
        assertFalse(FriendUiRules.isActivePending(stale, Instant.parse("2026-01-01T00:00:00Z")))
        assertTrue(FriendUiRules.incoming(listOf(stale), "me").isEmpty())
        assertEquals(FriendRelationKind.NONE, FriendUiRules.relation("me", "other", false, listOf(stale)).kind)
    }
}
