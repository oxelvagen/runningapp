package se.oxelv.mintracker.data

import org.junit.Assert.*
import org.junit.Test

class GroupActionRulesTest {
    private val members = listOf(GroupMember("group", "owner", "OWNER"), GroupMember("group", "mona", "MEMBER"))

    @Test fun memberCanLeaveButOwnerCannot() {
        assertTrue(GroupActionRules.canLeave("MEMBER"))
        assertTrue(GroupActionRules.canLeave("ADMIN"))
        assertFalse(GroupActionRules.canLeave("OWNER"))
    }

    @Test fun onlyOwnerCanRemoveAnotherMemberOrTransferToExistingMember() {
        assertTrue(GroupActionRules.canRemove("owner", "OWNER", "mona", members))
        assertFalse(GroupActionRules.canRemove("owner", "OWNER", "owner", members))
        assertFalse(GroupActionRules.canRemove("mona", "MEMBER", "owner", members))
        assertTrue(GroupActionRules.canTransfer("owner", "OWNER", "mona", members))
        assertFalse(GroupActionRules.canTransfer("owner", "OWNER", "stranger", members))
        assertFalse(GroupActionRules.canTransfer("owner", "OWNER", "owner", members))
    }

    @Test fun previewNormalizesCodeAndAlreadyMemberDisablesJoin() {
        val preview = GroupPreviewParser.parse("[{\"group_id\":\"g\",\"group_name\":\"Familjen\",\"group_type\":\"FAMILY\",\"member_count\":2,\"already_member\":true}]", SocialRules.normalizeCode(" ab23cd "))
        assertEquals("AB23CD", preview?.inviteCode)
        assertEquals(2, preview?.memberCount)
        assertFalse(GroupActionRules.canJoin(preview!!))
        assertNull(GroupPreviewParser.parse("[]", "AB23CD"))
    }

    @Test fun incompletePreviewCannotLookLikeSuccessfulJoinOption() {
        assertThrows(IllegalStateException::class.java) {
            GroupPreviewParser.parse("{\"name\":\"Familjen\",\"group_type\":\"FAMILY\",\"member_count\":2}", "AB23CD")
        }
    }

    @Test fun oldAccountResponseCannotUpdateNewAccountsUi() {
        assertTrue(GroupActionRules.canApplyResponse("user-a", "user-a"))
        assertFalse(GroupActionRules.canApplyResponse("user-a", "user-b"))
        assertFalse(GroupActionRules.canApplyResponse("user-a", null))
    }
}
