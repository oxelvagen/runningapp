package se.oxelv.mintracker.data

import org.junit.Assert.*
import org.junit.Test

class GroupStatsRulesTest {
    private fun row(name: String, meters: Double?, count: Long? = null, seconds: Long? = null) =
        WeeklyGroupStat(name, name, null, meters, count, seconds)

    @Test fun sortsByDistanceDescendingAndKeepsInactiveMembers() {
        val sorted = GroupStatsRules.sort(listOf(row("Mona", 12000.0), row("Ingen", null), row("Andreas", 18400.0)))
        assertEquals(listOf("Andreas", "Mona", "Ingen"), sorted.map { it.displayName })
    }

    @Test fun formatsWeeklyValuesAndZeroActivity() {
        val active = row("Andreas", 18400.0, 3, 6420)
        assertEquals("18,4 km", GroupStatsRules.distance(active))
        assertEquals("3 pass", GroupStatsRules.activities(active))
        assertEquals("1 h 47 min", GroupStatsRules.duration(active))
        val inactive = row("Ingen", null)
        assertEquals("0 km", GroupStatsRules.distance(inactive))
        assertEquals("0 pass", GroupStatsRules.activities(inactive))
        assertEquals("0 min", GroupStatsRules.duration(inactive))
    }

    @Test fun onlyInviteCodeUniquenessTriggersRetry() {
        assertTrue(GroupStatsRules.isInviteCodeConflict("23505 duplicate key on groups_invite_code_key"))
        assertFalse(GroupStatsRules.isInviteCodeConflict("23505 duplicate key on groups_pkey"))
        assertFalse(GroupStatsRules.isInviteCodeConflict("42501 permission denied for groups"))
    }

    @Test fun addsMembersWithoutActivitiesAndDoesNotDuplicateActiveMembers() {
        val members = listOf(
            GroupMemberView(GroupMember("g", "active", "OWNER"), Profile("active", "Andreas")),
            GroupMemberView(GroupMember("g", "inactive", "MEMBER"), Profile("inactive", "Mona"))
        )
        val merged = GroupStatsRules.withInactiveMembers(listOf(WeeklyGroupStat("active", "Andreas", null, 5000.0, 1, 1800)), members)
        assertEquals(2, merged.size)
        assertEquals(listOf("active", "inactive"), merged.map { it.userId })
        assertEquals("0 pass", GroupStatsRules.activities(merged.last()))
    }
}
