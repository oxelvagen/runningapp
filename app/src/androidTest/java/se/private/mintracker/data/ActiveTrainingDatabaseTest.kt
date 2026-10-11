package se.oxelv.mintracker.data

import android.location.Location
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.tracking.*
import java.util.UUID

class ActiveTrainingDatabaseTest {
    @Test fun migrationFromVersionEightPreservesOldWorkoutAndPoints() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "training-migration-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, TrackerDatabase::class.java, name).build()
        try {
            val id = db.dao().insert(ActivityEntity(kind = "RUN", startedAt = 1, activeMs = 2000, distanceM = 10.0))
            db.dao().insertPoints(listOf(PointEntity(id, 0, 59.0, 18.0, 1, 3f, null)))
            db.close()
            val raw = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0)
            raw.use { sql ->
                sql.execSQL("DROP TABLE ActiveTrainingEntity")
                sql.execSQL("CREATE TABLE old_points (activityId INTEGER NOT NULL, sequence INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, timestamp INTEGER NOT NULL, accuracy REAL NOT NULL, altitude REAL, PRIMARY KEY(activityId,sequence), FOREIGN KEY(activityId) REFERENCES ActivityEntity(id) ON DELETE CASCADE)")
                sql.execSQL("INSERT INTO old_points SELECT activityId,sequence,latitude,longitude,timestamp,accuracy,altitude FROM PointEntity")
                sql.execSQL("DROP TABLE PointEntity")
                sql.execSQL("ALTER TABLE old_points RENAME TO PointEntity")
                sql.execSQL("PRAGMA user_version=8")
            }
            db = Room.databaseBuilder(context, TrackerDatabase::class.java, name).addMigrations(MIGRATION_8_9).build()
            assertEquals(10.0, checkNotNull(db.dao().activity(id)).distanceM, 0.001)
            assertEquals(1, db.dao().points(id).size)
            assertEquals(0, db.dao().points(id).single().segment)
            assertNull(ActiveTrainingStore(db).load("other"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun checkpointSurvivesDatabaseReopenAndNeverLeaksToAnotherAccount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "training-${UUID.randomUUID()}.db"
        val owner = UUID.randomUUID().toString()
        var db = Room.databaseBuilder(context, TrackerDatabase::class.java, name).build()
        val session = Session(ownerUserId = owner, status = "COUNTDOWN", countdownUntil = 9000,
            running = true, checkpointAt = 1000, points = listOf(TrackPoint(59.0, 18.0, 1000, 3f, null)))
        try {
            ActiveTrainingStore(db).checkpoint(session)
            db.close(); db = Room.databaseBuilder(context, TrackerDatabase::class.java, name).build()
            val store = ActiveTrainingStore(db)
            assertEquals(session, store.load(owner))
            assertNull(store.load(UUID.randomUUID().toString()))
            store.checkpoint(session.copy(status = "STOPPING", running = false, endedAt = 10000))
            db.close(); db = Room.databaseBuilder(context, TrackerDatabase::class.java, name).build()
            assertEquals("STOPPING", ActiveTrainingStore(db).load(owner)?.status)
            assertEquals(session.sessionId, ActiveTrainingStore(db).load(owner)?.sessionId)
            ActiveTrainingStore(db).discard(session)
            assertNull(ActiveTrainingStore(db).load(owner))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun restoringAndGpsGapNeverInventDistanceOrDuplicatePoints() {
        val now = System.currentTimeMillis()
        val session = Session(ownerUserId = "owner", running = true, startedAt = now - 60000,
            activeMs = 20000, distanceM = 100.0, points = listOf(TrackPoint(59.0, 18.0, now - 60000, 3f, null)))
        val accumulator = TrackAccumulator("RUN", session.startedAt, "owner", restored = session)
        fun point(lat: Double, time: Long) = Location("test").apply { latitude = lat; longitude = 18.0; this.time = time; accuracy = 3f }
        accumulator.tick(); accumulator.add(point(59.1, now))
        assertEquals(session.sessionId, TrackingState.session.value.sessionId)
        assertEquals(100.0, TrackingState.session.value.distanceM, 0.001)
        assertTrue(TrackingState.session.value.activeMs < 21000)
        accumulator.add(point(59.1, now))
        assertEquals(2, TrackingState.session.value.points.size)
        accumulator.add(point(59.1001, now + 3000))
        assertTrue(TrackingState.session.value.distanceM > 100)
        val before = TrackingState.session.value.distanceM
        accumulator.add(point(59.2, now + 60000))
        assertEquals(before, TrackingState.session.value.distanceM, 0.001)
        assertEquals(3, routeSegments(TrackingState.session.value.points).size)
        accumulator.pause(); val pausedDistance = TrackingState.session.value.distanceM
        accumulator.add(point(59.3, now + 63000))
        assertEquals(pausedDistance, TrackingState.session.value.distanceM, 0.001)
        accumulator.resume(); accumulator.add(point(59.4, now + 66000))
        assertEquals(pausedDistance, TrackingState.session.value.distanceM, 0.001)
        TrackingState.session.value = Session()
    }
}
