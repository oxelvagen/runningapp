package se.oxelv.mintracker.data

import org.junit.Assert.*
import org.junit.Test

class SocialRulesTest {
    @Test fun usernameNormalizationAndValidation() {
        assertEquals("andreas_7", SocialRules.username("  AnDrEaS_7  "))
        assertNull(SocialRules.usernameError("  Andreas.7 "))
        assertNotNull(SocialRules.usernameError("ab"))
        assertNotNull(SocialRules.usernameError("a".repeat(31)))
        assertNotNull(SocialRules.usernameError("name space"))
        assertNotNull(SocialRules.usernameError("åäö"))
        assertNull(SocialRules.usernameError("  "))
    }

    @Test fun bioLimit() {
        assertNull(SocialRules.bioError("a".repeat(300)))
        assertNotNull(SocialRules.bioError("a".repeat(301)))
    }

    @Test fun inviteCodeIsUnambiguousAndNormalized() {
        repeat(100) {
            assertTrue(SocialRules.inviteCode().matches(Regex("[A-HJ-NP-Z2-9]{6}")))
        }
        assertEquals("ABC234", SocialRules.normalizeCode(" abc234 "))
    }

    @Test fun rolesAndDuplicateMembership() {
        val owner = GroupMember("group", "owner", "OWNER")
        val member = GroupMember("group", "member", "MEMBER")
        assertEquals("OWNER", owner.role)
        assertEquals("MEMBER", member.role)
        assertFalse(SocialRules.canJoin(listOf("owner", "member"), "member"))
        assertTrue(SocialRules.canJoin(listOf("owner"), "member"))
    }
}
