package se.oxelv.mintracker.data

object GroupActionRules {
    fun canLeave(role: String?): Boolean = role == "MEMBER" || role == "ADMIN"
    fun canManage(role: String?): Boolean = role == "OWNER"
    fun canRemove(actorId: String, role: String?, targetId: String, members: List<GroupMember>): Boolean =
        canManage(role) && actorId != targetId && members.any { it.userId == targetId }
    fun canTransfer(actorId: String, role: String?, targetId: String, members: List<GroupMember>): Boolean =
        canManage(role) && actorId != targetId && members.any { it.userId == targetId }
    fun canJoin(preview: GroupPreview): Boolean = !preview.alreadyMember
    fun canApplyResponse(requestUserId: String?, currentUserId: String?): Boolean =
        requestUserId != null && requestUserId == currentUserId
}
