package se.oxelv.mintracker.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index(value = ["cloudId"], unique = true)]) data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val startedAt: Long,
    val activeMs: Long,
    val distanceM: Double,
    val ascentM: Double? = null,
    val descentM: Double? = null,
    val steps: Int? = null,
    val userId: String? = null,
    val cloudId: String? = null,
    val endedAt: Long? = null,
    @ColumnInfo(defaultValue = "'PENDING'") val syncStatus: String = SyncStatus.PENDING,
    val dogId: String? = null,
    val note: String? = null,
    @ColumnInfo(defaultValue = "0") val localRevision: Long = 0,
    @ColumnInfo(defaultValue = "0") val noteOnlyPending: Boolean = false,
    val cloudUpdatedAt: Long? = null
)

object SyncStatus {
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
    const val ERROR = "ERROR"
    const val RESTORING = "RESTORING"
}

@Entity(primaryKeys = ["activityId", "sequence"], foreignKeys = [ForeignKey(entity = ActivityEntity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE)])
data class PointEntity(val activityId: Long, val sequence: Int, val latitude: Double, val longitude: Double, val timestamp: Long, val accuracy: Float, val altitude: Double?, @ColumnInfo(defaultValue = "0") val segment: Int = 0)

@Entity(primaryKeys = ["activityId", "kilometer"], foreignKeys = [ForeignKey(entity = ActivityEntity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE)])
data class SplitEntity(val activityId: Long, val kilometer: Int, val durationMs: Long, val latitude: Double?, val longitude: Double?)

@Entity(primaryKeys = ["cloudId", "userId"], indices = [Index(value = ["userId"])])
data class ActivityDeletionEntity(val cloudId: String, val userId: String, val requestedAt: Long)

@Dao interface ActivityDao {
    @Query("UPDATE ActivityEntity SET userId = :userId WHERE userId IS NULL") suspend fun claimLegacyActivities(userId: String)
    @Query("SELECT id FROM ActivityEntity WHERE userId = :userId AND cloudId IS NULL") suspend fun withoutCloudId(userId: String): List<Long>
    @Query("UPDATE ActivityEntity SET cloudId = :cloudId WHERE id = :id AND userId = :userId AND cloudId IS NULL") suspend fun assignCloudId(id: Long, userId: String, cloudId: String)
    @Query("SELECT * FROM ActivityEntity WHERE userId = :userId AND syncStatus IN ('PENDING', 'ERROR') ORDER BY startedAt") suspend fun pending(userId: String): List<ActivityEntity>
    @Query("UPDATE ActivityEntity SET syncStatus = :status WHERE id = :id AND userId = :userId") suspend fun setSyncStatus(id: Long, userId: String, status: String)
    @Query("""UPDATE ActivityEntity SET note = :note, localRevision = localRevision + 1,
        noteOnlyPending = CASE WHEN syncStatus = 'SYNCED' THEN 1 ELSE noteOnlyPending END,
        syncStatus = 'PENDING' WHERE id = :id AND userId = :userId AND syncStatus != 'RESTORING'""")
    suspend fun editNote(id: Long, userId: String, note: String?): Int
    @Query("""UPDATE ActivityEntity SET syncStatus = 'SYNCED', noteOnlyPending = 0, cloudUpdatedAt = :cloudUpdatedAt
        WHERE id = :id AND userId = :userId AND localRevision = :revision AND syncStatus IN ('PENDING', 'ERROR')""")
    suspend fun completeSync(id: Long, userId: String, revision: Long, cloudUpdatedAt: Long?): Int
    @Query("""UPDATE ActivityEntity SET syncStatus = 'ERROR'
        WHERE id = :id AND userId = :userId AND localRevision = :revision AND syncStatus IN ('PENDING', 'ERROR')""")
    suspend fun failSync(id: Long, userId: String, revision: Long): Int
    @Query("""UPDATE ActivityEntity SET note = :note, cloudUpdatedAt = :cloudUpdatedAt
        WHERE id = :id AND userId = :userId AND syncStatus IN ('SYNCED', 'RESTORING')
        AND (cloudUpdatedAt IS NULL OR cloudUpdatedAt < :cloudUpdatedAt)""")
    suspend fun acceptRemoteNote(id: Long, userId: String, note: String?, cloudUpdatedAt: Long): Int
    @Query("""UPDATE ActivityEntity SET
        ascentM = COALESCE(ascentM, :ascentM),
        descentM = COALESCE(descentM, :descentM),
        steps = COALESCE(steps, :steps)
        WHERE id = :id AND userId = :userId""")
    suspend fun fillMissingMetrics(id: Long, userId: String, ascentM: Double?, descentM: Double?, steps: Int?)
    @Query("SELECT * FROM ActivityEntity WHERE cloudId = :cloudId AND userId = :userId LIMIT 1") suspend fun byCloudId(cloudId: String, userId: String): ActivityEntity?
    @Query("SELECT * FROM ActivityEntity WHERE userId = :userId ORDER BY startedAt DESC") fun activities(userId: String): Flow<List<ActivityEntity>>
    @Query("SELECT * FROM ActivityEntity WHERE id = :id") suspend fun activity(id: Long): ActivityEntity?
    @Query("SELECT * FROM ActivityEntity WHERE id = :id AND userId = :userId LIMIT 1") suspend fun ownedActivity(id: Long, userId: String): ActivityEntity?
    @Query("DELETE FROM ActivityEntity WHERE id = :id AND userId = :userId") suspend fun deleteActivity(id: Long, userId: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDeletion(deletion: ActivityDeletionEntity)
    @Query("SELECT * FROM ActivityDeletionEntity WHERE userId = :userId ORDER BY requestedAt") suspend fun pendingDeletions(userId: String): List<ActivityDeletionEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM ActivityDeletionEntity WHERE cloudId = :cloudId AND userId = :userId)") suspend fun hasDeletion(cloudId: String, userId: String): Boolean
    @Query("DELETE FROM ActivityDeletionEntity WHERE cloudId = :cloudId AND userId = :userId") suspend fun finishDeletion(cloudId: String, userId: String): Int
    @Query("UPDATE ActivityEntity SET dogId = :dogId, kind = :kind WHERE id = :id AND userId = :userId AND syncStatus IN ('SYNCED', 'RESTORING')")
    suspend fun updateRestoredDog(id: Long, userId: String, dogId: String?, kind: String)
    @Query("SELECT * FROM PointEntity WHERE activityId = :id ORDER BY sequence") suspend fun points(id: Long): List<PointEntity>
    @Query("SELECT * FROM SplitEntity WHERE activityId = :id ORDER BY kilometer") suspend fun splits(id: Long): List<SplitEntity>
    @Insert suspend fun insert(activity: ActivityEntity): Long
    @Insert suspend fun insertPoints(points: List<PointEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMissingPoints(points: List<PointEntity>)
    @Insert suspend fun insertSplits(splits: List<SplitEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMissingSplits(splits: List<SplitEntity>)
}

@Entity(indices = [Index(value = ["ownerId", "archivedAt"])])
data class DogEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val name: String,
    val breed: String? = null,
    val birthDate: String? = null,
    val avatarPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null,
    @ColumnInfo(defaultValue = "'PENDING'") val syncStatus: String = SyncStatus.PENDING
)

@Dao interface DogDao {
    @Query("SELECT * FROM DogEntity WHERE ownerId = :ownerId ORDER BY archivedAt IS NOT NULL, name COLLATE NOCASE") fun dogs(ownerId: String): Flow<List<DogEntity>>
    @Query("SELECT * FROM DogEntity WHERE id = :id AND ownerId = :ownerId LIMIT 1") suspend fun byId(id: String, ownerId: String): DogEntity?
    @Query("SELECT * FROM DogEntity WHERE ownerId = :ownerId AND syncStatus IN ('PENDING', 'ERROR') ORDER BY createdAt") suspend fun pending(ownerId: String): List<DogEntity>
    @Query("SELECT * FROM DogEntity WHERE ownerId = :ownerId AND syncStatus = 'SYNCED'") suspend fun synced(ownerId: String): List<DogEntity>
    @Query("UPDATE DogEntity SET name = :name, breed = :breed, birthDate = :birthDate, updatedAt = :updatedAt, syncStatus = 'PENDING' WHERE id = :id AND ownerId = :ownerId AND archivedAt IS NULL") suspend fun edit(id: String, ownerId: String, name: String, breed: String?, birthDate: String?, updatedAt: Long): Int
    @Query("UPDATE DogEntity SET avatarPath = :path WHERE id = :id AND ownerId = :ownerId AND archivedAt IS NULL")
    suspend fun setAvatarPath(id: String, ownerId: String, path: String?): Int
    @Query("UPDATE DogEntity SET archivedAt = :archivedAt, updatedAt = :archivedAt, syncStatus = 'PENDING' WHERE id = :id AND ownerId = :ownerId AND archivedAt IS NULL") suspend fun archive(id: String, ownerId: String, archivedAt: Long): Int
    @Query("UPDATE DogEntity SET syncStatus = :status WHERE id = :id AND ownerId = :ownerId AND updatedAt = :expectedUpdatedAt") suspend fun setStatus(id: String, ownerId: String, expectedUpdatedAt: Long, status: String): Int
    @Query("UPDATE DogEntity SET name = :name, breed = :breed, birthDate = :birthDate, avatarPath = :avatarPath, archivedAt = :archivedAt, updatedAt = :updatedAt, syncStatus = 'SYNCED' WHERE id = :id AND ownerId = :ownerId AND updatedAt = :expectedUpdatedAt AND archivedAt IS NULL")
    suspend fun acceptRemoteArchive(id: String, ownerId: String, expectedUpdatedAt: Long, name: String, breed: String?, birthDate: String?, avatarPath: String?, archivedAt: Long, updatedAt: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfAbsent(dog: DogEntity): Long
    @Query("UPDATE DogEntity SET name = :name, breed = :breed, birthDate = :birthDate, avatarPath = :avatarPath, archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id = :id AND ownerId = :ownerId AND syncStatus = 'SYNCED'") suspend fun updateSynced(id: String, ownerId: String, name: String, breed: String?, birthDate: String?, avatarPath: String?, archivedAt: Long?, updatedAt: Long)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN ascentM REAL")
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN descentM REAL")
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN steps INTEGER")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN userId TEXT")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN cloudId TEXT")
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN endedAt INTEGER")
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
        db.execSQL("CREATE UNIQUE INDEX index_ActivityEntity_cloudId ON ActivityEntity(cloudId)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE SplitEntity_new (
            activityId INTEGER NOT NULL,
            kilometer INTEGER NOT NULL,
            durationMs INTEGER NOT NULL,
            latitude REAL,
            longitude REAL,
            PRIMARY KEY(activityId, kilometer),
            FOREIGN KEY(activityId) REFERENCES ActivityEntity(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("INSERT INTO SplitEntity_new SELECT activityId, kilometer, durationMs, latitude, longitude FROM SplitEntity")
        db.execSQL("DROP TABLE SplitEntity")
        db.execSQL("ALTER TABLE SplitEntity_new RENAME TO SplitEntity")
        // Previously synced rows need one more upload for elevation, steps, and splits.
        db.execSQL("UPDATE ActivityEntity SET syncStatus = 'PENDING' WHERE syncStatus = 'SYNCED'")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN dogId TEXT")
        db.execSQL("UPDATE ActivityEntity SET kind = 'RUN' WHERE kind = 'Löpning'")
        db.execSQL("UPDATE ActivityEntity SET kind = 'WALK' WHERE kind = 'Promenad'")
        db.execSQL("""CREATE TABLE IF NOT EXISTS DogEntity (
            id TEXT NOT NULL PRIMARY KEY,
            ownerId TEXT NOT NULL,
            name TEXT NOT NULL,
            breed TEXT,
            birthDate TEXT,
            avatarPath TEXT,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            archivedAt INTEGER,
            syncStatus TEXT NOT NULL DEFAULT 'PENDING'
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_DogEntity_ownerId_archivedAt ON DogEntity(ownerId, archivedAt)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN note TEXT")
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN localRevision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN noteOnlyPending INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ActivityEntity ADD COLUMN cloudUpdatedAt INTEGER")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS ActivityDeletionEntity (
            cloudId TEXT NOT NULL,
            userId TEXT NOT NULL,
            requestedAt INTEGER NOT NULL,
            PRIMARY KEY(cloudId, userId)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ActivityDeletionEntity_userId ON ActivityDeletionEntity(userId)")
    }
}

@Database(entities = [ActivityEntity::class, PointEntity::class, SplitEntity::class, DogEntity::class, ActivityDeletionEntity::class, ActiveTrainingEntity::class], version = 9, exportSchema = false)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun activeTrainingDao(): ActiveTrainingDao
    abstract fun dao(): ActivityDao
    abstract fun dogDao(): DogDao
}

