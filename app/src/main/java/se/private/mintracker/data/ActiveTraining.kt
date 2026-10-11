package se.oxelv.mintracker.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import se.oxelv.mintracker.tracking.Session

/** Private, local checkpoint. Never included in activity sync before explicit Save. */
@Entity(indices = [Index(value = ["userId"], unique = true)])
data class ActiveTrainingEntity(@PrimaryKey val id: String, val userId: String, val status: String,
    val updatedAt: Long, val snapshot: String)

@Dao interface ActiveTrainingDao {
    @Query("SELECT * FROM ActiveTrainingEntity WHERE userId = :owner LIMIT 1") suspend fun owned(owner: String): ActiveTrainingEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: ActiveTrainingEntity)
    @Query("DELETE FROM ActiveTrainingEntity WHERE id = :id AND userId = :owner") suspend fun delete(id: String, owner: String)
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE PointEntity ADD COLUMN segment INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS ActiveTrainingEntity (id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, status TEXT NOT NULL, updatedAt INTEGER NOT NULL, snapshot TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ActiveTrainingEntity_userId ON ActiveTrainingEntity(userId)")
    }
}

class ActiveTrainingStore(private val db: TrackerDatabase) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    suspend fun load(owner: String): Session? = withContext(Dispatchers.IO) {
        db.activeTrainingDao().owned(owner)?.let { row ->
            json.decodeFromString(Session.serializer(), row.snapshot).also {
                check(it.ownerUserId == owner && it.sessionId == row.id && it.status == row.status)
            }
        }
    }
    suspend fun checkpoint(session: Session) = withContext(Dispatchers.IO) {
        val owner = checkNotNull(session.ownerUserId)
        check(session.sessionId.isNotBlank())
        db.activeTrainingDao().put(ActiveTrainingEntity(session.sessionId, owner, session.status,
            session.checkpointAt, json.encodeToString(Session.serializer(), session)))
    }
    suspend fun discard(session: Session) = withContext(Dispatchers.IO) {
        db.activeTrainingDao().delete(session.sessionId, checkNotNull(session.ownerUserId))
    }
}
