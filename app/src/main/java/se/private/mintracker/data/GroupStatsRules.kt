package se.oxelv.mintracker.data

import java.text.NumberFormat
import java.util.Locale

object GroupStatsRules {
    private val swedish = Locale("sv", "SE")

    fun sort(stats: List<WeeklyGroupStat>): List<WeeklyGroupStat> =
        stats.sortedWith(compareByDescending<WeeklyGroupStat> { it.distanceMeters ?: 0.0 }.thenBy { it.displayName })

    fun withInactiveMembers(stats: List<WeeklyGroupStat>, members: List<GroupMemberView>): List<WeeklyGroupStat> {
        val existing = stats.mapTo(mutableSetOf()) { it.userId }
        val inactive = members.filter { existing.add(it.member.userId) }.map { member ->
            WeeklyGroupStat(member.member.userId, member.profile?.displayName ?: "Privat profil", member.profile?.username, 0.0, 0, 0)
        }
        return sort(stats + inactive)
    }

    fun distance(stat: WeeklyGroupStat): String {
        val meters = stat.distanceMeters ?: 0.0
        if (meters == 0.0) return "0 km"
        return "${NumberFormat.getNumberInstance(swedish).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }.format(meters / 1000.0)} km"
    }

    fun activities(stat: WeeklyGroupStat): String = "${stat.activityCount ?: 0} pass"

    fun duration(stat: WeeklyGroupStat): String {
        val minutes = ((stat.durationSeconds ?: 0L).coerceAtLeast(0L) / 60L)
        val hours = minutes / 60L
        return if (hours == 0L) "$minutes min" else "$hours h ${minutes % 60L} min"
    }

    fun isInviteCodeConflict(message: String): Boolean {
        val value = message.lowercase()
        return ("23505" in value || "duplicate key" in value || "unique constraint" in value) &&
            ("invite_code" in value || "groups_invite_code" in value)
    }
}
