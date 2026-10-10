package se.oxelv.mintracker.data

object ActivityNote {
    const val MAX_LENGTH = 500

    fun normalize(value: String?): String? {
        val note = value?.trim().orEmpty()
        require(note.length <= MAX_LENGTH) { "Anteckningen får innehålla högst 500 tecken." }
        return note.ifEmpty { null }
    }

    fun mayAcceptRemote(local: ActivityEntity, remoteUpdatedAt: Long): Boolean =
        local.syncStatus in setOf(SyncStatus.SYNCED, SyncStatus.RESTORING) &&
            (local.cloudUpdatedAt == null || remoteUpdatedAt > local.cloudUpdatedAt)
}
