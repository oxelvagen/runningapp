package se.oxelv.mintracker.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ActivityDeletionDatabaseTest {
    @Test fun neverUploadedLocalWalkCanBeDeletedWithoutCloudTombstone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val owner = UUID.randomUUID().toString()
            val dogId = UUID.randomUUID().toString()
            db.dogDao().insertIfAbsent(DogEntity(dogId, owner, "Luna", createdAt = 1, updatedAt = 1))
            val id = db.dao().insert(ActivityEntity(kind = "DOG_WALK", startedAt = 1, activeMs = 1000,
                distanceM = 20.0, userId = owner, dogId = dogId, note = "Kort promenad"))
            db.dao().insertPoints(listOf(PointEntity(id, 0, 59.0, 18.0, 1, 2f, null)))
            db.dao().insertSplits(listOf(SplitEntity(id, 1, 1000, null, null)))
            assertEquals(1, db.dao().deleteActivity(id, owner))
            assertNull(db.dao().activity(id))
            assertTrue(db.dao().points(id).isEmpty())
            assertTrue(db.dao().splits(id).isEmpty())
            assertTrue(db.dao().pendingDeletions(owner).isEmpty())
            assertNotNull(db.dogDao().byId(dogId, owner))
        } finally { db.close() }
    }

    @Test fun migrationAddsTombstonesWithoutChangingOldActivities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "delete-migration-test-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE ActivityEntity (id INTEGER NOT NULL PRIMARY KEY, kind TEXT NOT NULL, note TEXT)")
                        db.execSQL("INSERT INTO ActivityEntity VALUES (42, 'DOG_WALK', 'Gammal anteckning')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        try {
            val sql = helper.writableDatabase
            MIGRATION_7_8.migrate(sql)
            sql.query("SELECT kind, note FROM ActivityEntity WHERE id = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("DOG_WALK", cursor.getString(0))
                assertEquals("Gammal anteckning", cursor.getString(1))
            }
            sql.execSQL("INSERT INTO ActivityDeletionEntity VALUES ('cloud-id', 'owner-id', 123)")
            sql.query("SELECT count(*) FROM ActivityDeletionEntity").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun deletingDogRunRemovesChildrenAndStatsButKeepsDogAndDurableTombstone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "activity-delete-test-${UUID.randomUUID()}.db"
        val owner = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val cloudId = UUID.randomUUID().toString()
        val dogId = UUID.randomUUID().toString()
        var db = Room.databaseBuilder(context, TrackerDatabase::class.java, databaseName).build()
        try {
            db.dogDao().insertIfAbsent(DogEntity(dogId, owner, "Luna", createdAt = 1, updatedAt = 1, syncStatus = SyncStatus.SYNCED))
            val activityId = db.dao().insert(ActivityEntity(kind = "DOG_RUN", startedAt = 1, activeMs = 60_000,
                distanceM = 1000.0, userId = owner, cloudId = cloudId, dogId = dogId,
                note = "Runda med Luna", syncStatus = SyncStatus.SYNCED))
            db.dao().insertPoints(listOf(PointEntity(activityId, 0, 59.0, 18.0, 1, 2f, 10.0)))
            db.dao().insertSplits(listOf(SplitEntity(activityId, 1, 60_000, null, null)))

            assertNull(db.dao().ownedActivity(activityId, other))
            assertEquals(0, db.dao().deleteActivity(activityId, other))
            db.withTransaction {
                db.dao().insertDeletion(ActivityDeletionEntity(cloudId, owner, 2))
                assertEquals(1, db.dao().deleteActivity(activityId, owner))
            }
            assertTrue(db.dao().activities(owner).first().isEmpty())
            assertEquals(0, ActivitySummary.totals(db.dao().activities(owner).first()).count)
            val statistics = Statistics.calculate(db.dao().activities(owner).first(),
                listOfNotNull(db.dogDao().byId(dogId, owner)), StatisticsPeriod.ALL)
            assertEquals(0, statistics.totals.count)
            assertTrue(statistics.dogs.isEmpty())
            assertNull(statistics.longest)
            assertTrue(db.dao().points(activityId).isEmpty())
            assertTrue(db.dao().splits(activityId).isEmpty())
            assertNotNull(db.dogDao().byId(dogId, owner))
            assertFalse(db.dao().hasDeletion(cloudId, other))
            db.close()

            db = Room.databaseBuilder(context, TrackerDatabase::class.java, databaseName).build()
            assertTrue(db.dao().hasDeletion(cloudId, owner))
            assertEquals(1, db.dao().pendingDeletions(owner).size)
            assertTrue(db.dao().pendingDeletions(other).isEmpty())
            ActivityRepository(db).importActivity(CloudActivity(
                id = cloudId, userId = owner, activityType = "DOG_RUN",
                startedAt = "2026-01-01T00:00:00Z", endedAt = "2026-01-01T00:01:00Z",
                durationSeconds = 60, distanceMeters = 1000.0, dogId = dogId,
                note = "Gammal molnanteckning"
            ), owner)
            assertNull(db.dao().byCloudId(cloudId, owner))
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }
}
