package se.oxelv.mintracker.data

import androidx.room.withTransaction
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.oxelv.mintracker.tracking.Session
import java.util.UUID

class ActivityRepository(private val db: TrackerDatabase) {
    /** Room is the first durable write; cloud work is scheduled only after this returns. */
    suspend fun saveLocal(session: Session, userId: String, endedAt: Long, note: String? = null): Long = withContext(Dispatchers.IO) {
        if (rememberedUserId(supabase.auth.sessionStatus.value) != userId) throw AccountChangedException()
        val savedNote = ActivityNote.normalize(note)
        require(session.ownerUserId == userId) { "Passet tillhör ett annat konto." }
        val kind = ActivityKind.fromStored(session.kind) ?: error("Okänd aktivitetstyp.")
        require(kind.requiresDog == (session.dogId != null)) { "Välj hund för ett hundpass." }
        if (session.dogId != null) require(db.dogDao().byId(session.dogId, userId) != null) { "Hunden tillhör inte ditt konto." }
        db.withTransaction {
            db.dao().byCloudId(session.sessionId, userId)?.let { existing ->
                db.activeTrainingDao().delete(session.sessionId, userId)
                return@withTransaction existing.id
            }
            val id = db.dao().insert(ActivityEntity(
                kind = kind.name,
                startedAt = session.startedAt,
                activeMs = session.activeMs,
                distanceM = session.distanceM,
                ascentM = session.ascentM,
                descentM = session.descentM,
                steps = session.steps,
                userId = userId,
                cloudId = session.sessionId,
                endedAt = endedAt,
                syncStatus = SyncStatus.PENDING,
                dogId = session.dogId,
                note = savedNote
            ))
            db.dao().insertPoints(session.points.mapIndexed { index, point ->
                PointEntity(id, index, point.latitude, point.longitude, point.timestamp, point.accuracy, point.altitude, point.segment)
            })
            db.dao().insertSplits(session.splits.map { split ->
                SplitEntity(id, split.kilometer, split.durationMs, split.latitude, split.longitude)
            })
            db.activeTrainingDao().delete(session.sessionId, userId)
            id
        }
    }

    suspend fun updateNote(id: Long, userId: String, note: String?) = withContext(Dispatchers.IO) {
        requireCurrentUser(userId)
        val savedNote = ActivityNote.normalize(note)
        val activity = db.dao().activity(id)
        require(activity?.userId == userId) { "Passet tillhör ett annat konto." }
        require(activity.syncStatus != SyncStatus.RESTORING) { "Passet återställs fortfarande. Försök igen." }
        if (activity.note != savedNote) db.dao().editNote(id, userId, savedNote)
    }

    /** Removes the Room row and its children atomically; the tombstone survives a restart. */
    suspend fun deleteLocal(id: Long, userId: String) = withContext(Dispatchers.IO) {
        requireCurrentUser(userId)
        db.withTransaction {
            val activity = db.dao().ownedActivity(id, userId)
                ?: throw IllegalArgumentException("Passet finns inte på ditt konto.")
            activity.cloudId?.let { cloudId ->
                db.dao().insertDeletion(ActivityDeletionEntity(cloudId, userId, System.currentTimeMillis()))
            }
            check(db.dao().deleteActivity(id, userId) == 1) { "Passet kunde inte raderas lokalt." }
        }
    }

    /** Claims only pre-auth rows; rows already owned by another user are never reassigned. */
    suspend fun prepareLegacy(userId: String) = withContext(Dispatchers.IO) {
        requireCurrentUser(userId)
        db.withTransaction {
            db.dao().claimLegacyActivities(userId)
            db.dao().withoutCloudId(userId).forEach { id ->
                db.dao().assignCloudId(id, userId, UUID.randomUUID().toString())
            }
        }
    }

    suspend fun sync(userId: String) = withContext(Dispatchers.IO) {
        requireCurrentUser(userId)
        prepareLegacy(userId)
        var uploadFailure: Exception? = null
        for (deletion in db.dao().pendingDeletions(userId)) {
            requireCurrentUser(userId)
            try {
                supabase.from("activities").delete {
                    filter { eq("id", deletion.cloudId); eq("user_id", userId) }
                }
                requireCurrentUser(userId)
                val remains = supabase.from("activities").select(Columns.list("id")) {
                    filter { eq("id", deletion.cloudId); eq("user_id", userId) }
                }.decodeSingleOrNull<CloudActivityIdentity>()
                check(remains == null) { "Passet finns fortfarande i molnet." }
                requireCurrentUser(userId)
                db.dao().finishDeletion(deletion.cloudId, userId)
                SocialChanges.changed(userId)
            } catch (error: AccountChangedException) {
                throw error
            } catch (error: Exception) {
                uploadFailure = error
            }
        }
        DogRepository(db).sync(userId)
        for (activity in db.dao().pending(userId)) {
            requireCurrentUser(userId)
            if (activity.cloudId?.let { db.dao().hasDeletion(it, userId) } == true) continue
            if (db.dao().ownedActivity(activity.id, userId) == null) continue
            try {
                upload(activity, userId)
                val state = readCloudNote(checkNotNull(activity.cloudId), userId)
                db.dao().completeSync(activity.id, userId, activity.localRevision, fromTimestamptz(state.updatedAt))
                SocialChanges.changed(userId)
            } catch (error: AccountChangedException) {
                throw error
            } catch (error: Exception) {
                db.dao().failSync(activity.id, userId, activity.localRevision)
                uploadFailure = error
            }
        }
        download(userId)
        uploadFailure?.let { throw it }
    }

    private suspend fun upload(activity: ActivityEntity, userId: String) {
        val cloudId = checkNotNull(activity.cloudId)
        if (activity.noteOnlyPending) {
            requireCurrentUser(userId)
            supabase.from("activities").update({ set("note", activity.note) }) {
                filter { eq("id", cloudId); eq("user_id", userId) }
            }
            requireCurrentUser(userId)
            return
        }
        val durationSeconds = (activity.activeMs / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val hasPace = activity.distanceM > 0 && activity.activeMs > 0
        val cloud = CloudActivity(
            id = cloudId,
            userId = userId,
            activityType = ActivityKind.canonical(activity.kind),
            startedAt = toTimestamptz(activity.startedAt),
            endedAt = toTimestamptz(activity.endedAt ?: activity.startedAt + activity.activeMs),
            durationSeconds = durationSeconds,
            distanceMeters = activity.distanceM,
            avgPaceSecondsPerKm = if (hasPace) activity.activeMs / 1000.0 / (activity.distanceM / 1000.0) else null,
            avgSpeedKmh = if (hasPace) activity.distanceM / activity.activeMs * 3600.0 else null,
            elevationGainMeters = activity.ascentM,
            elevationLossMeters = activity.descentM,
            steps = activity.steps,
            dogId = activity.dogId,
            note = activity.note
        )
        requireCurrentUser(userId)
        SocialFeedRepository(userId).sync(cloud)
        if (db.dao().hasDeletion(cloudId, userId)) return
        val points = db.dao().points(activity.id).map { point ->
            CloudPoint(cloudId, userId, point.sequence, point.latitude, point.longitude, point.altitude, toTimestamptz(point.timestamp))
        }
        for (batch in points.chunked(250)) {
            requireCurrentUser(userId)
            supabase.from("activity_points").upsert(batch) {
                onConflict = "activity_id,sequence_number"
            }
        }
        val splits = db.dao().splits(activity.id).map { it.toCloudSplit(cloudId, userId) }
        for (batch in splits.chunked(250)) {
            requireCurrentUser(userId)
            supabase.from("activity_splits").upsert(batch) {
                onConflict = "activity_id,split_number"
            }
        }
        requireCurrentUser(userId)
    }

    private suspend fun download(userId: String) {
        var offset = 0L
        while (true) {
            requireCurrentUser(userId)
            val page = supabase.from("activities").select(
                columns = Columns.list("id,user_id,activity_type,started_at,ended_at,duration_seconds,distance_meters,avg_pace_seconds_per_km,avg_speed_kmh,elevation_gain_meters,elevation_loss_meters,steps,dog_id,note,updated_at")
            ) {
                filter { eq("user_id", userId) }
                order("id", Order.ASCENDING)
                range(offset..offset + 199)
            }.decodeList<CloudActivity>()
            for (remote in page) {
                check(remote.userId == userId) { "En aktivitet tillhör ett annat konto." }
                importActivity(remote, userId)
            }
            if (page.size < 200) break
            offset += page.size
        }
    }

    internal suspend fun importActivity(remote: CloudActivity, userId: String) {
        if (db.dao().hasDeletion(remote.id, userId)) return
        var local = db.dao().byCloudId(remote.id, userId)
        if (local == null) {
            requireCurrentUser(userId)
            val id = db.withTransaction {
                if (db.dao().hasDeletion(remote.id, userId)) return@withTransaction null
                db.dao().insert(ActivityEntity(
                kind = ActivityKind.canonical(remote.activityType),
                startedAt = fromTimestamptz(remote.startedAt),
                activeMs = remote.durationSeconds.toLong() * 1000,
                distanceM = remote.distanceMeters,
                ascentM = remote.elevationGainMeters,
                descentM = remote.elevationLossMeters,
                steps = remote.steps,
                userId = userId,
                cloudId = remote.id,
                endedAt = fromTimestamptz(remote.endedAt),
                syncStatus = SyncStatus.RESTORING,
                dogId = remote.dogId,
                note = remote.note,
                cloudUpdatedAt = remote.updatedAt?.let(::fromTimestamptz)
                ))
            }
            local = id?.let { db.dao().activity(it) }
        }
        if (local == null || (local.syncStatus != SyncStatus.SYNCED && local.syncStatus != SyncStatus.RESTORING)) return
        remote.updatedAt?.let {
            val remoteTime = fromTimestamptz(it)
            if (ActivityNote.mayAcceptRemote(local, remoteTime)) db.dao().acceptRemoteNote(local.id, userId, remote.note, remoteTime)
        }
        if (remote.dogId != null) check(db.dogDao().byId(remote.dogId, userId) != null) { "Hundprofilen saknas. Återställning försöks igen." }
        db.dao().updateRestoredDog(local.id, userId, remote.dogId, ActivityKind.canonical(remote.activityType))
        db.dao().fillMissingMetrics(local.id, userId, remote.elevationGainMeters, remote.elevationLossMeters, remote.steps)

        var offset = 0L
        while (true) {
            requireCurrentUser(userId)
            val points = supabase.from("activity_points").select(
                columns = Columns.list("activity_id,user_id,sequence_number,latitude,longitude,altitude,recorded_at")
            ) {
                filter { eq("activity_id", remote.id); eq("user_id", userId) }
                order("sequence_number", Order.ASCENDING)
                range(offset..offset + 499)
            }.decodeList<CloudPoint>()
            check(points.all { it.userId == userId && it.activityId == remote.id }) { "GPS-punkter tillhör ett annat konto." }
            db.dao().insertMissingPoints(points.map { point ->
                PointEntity(local.id, point.sequenceNumber, point.latitude, point.longitude,
                    fromTimestamptz(point.recordedAt), 0f, point.altitude)
            })
            if (points.size < 500) break
            offset += points.size
        }

        offset = 0L
        while (true) {
            requireCurrentUser(userId)
            val splits = supabase.from("activity_splits").select(
                columns = Columns.list("activity_id,user_id,split_number,distance_meters,duration_seconds,pace_seconds_per_km")
            ) {
                filter { eq("activity_id", remote.id); eq("user_id", userId) }
                order("split_number", Order.ASCENDING)
                range(offset..offset + 499)
            }.decodeList<CloudSplit>()
            check(splits.all { it.userId == userId && it.activityId == remote.id }) { "Kilometertider tillhör ett annat konto." }
            db.dao().insertMissingSplits(splits.map { it.toLocalSplit(local.id) })
            if (splits.size < 500) break
            offset += splits.size
        }
        if (local.syncStatus == SyncStatus.RESTORING) {
            db.dao().setSyncStatus(local.id, userId, SyncStatus.SYNCED)
        }
    }

    private suspend fun readCloudNote(cloudId: String, userId: String): CloudNoteState {
        requireCurrentUser(userId)
        return supabase.from("activities").select(Columns.list("note,updated_at")) {
            filter { eq("id", cloudId); eq("user_id", userId) }
        }.decodeSingle<CloudNoteState>()
    }

    private fun requireCurrentUser(userId: String) {
        if (supabase.auth.currentUserOrNull()?.id != userId) throw AccountChangedException()
    }
}

class AccountChangedException : IllegalStateException("Inloggat konto har ändrats.")
